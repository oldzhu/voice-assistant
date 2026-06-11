package com.example.voiceassistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.voiceassistant.config.ConfigManager
import com.example.voiceassistant.databinding.ActivityMainBinding
import com.example.voiceassistant.tools.ScreenCaptureManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private lateinit var binding: ActivityMainBinding
    private var voiceService: VoiceService? = null
    private var serviceBound = false
    private var configManager: ConfigManager? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceService = (service as VoiceService.LocalBinder).getService()
            serviceBound = true
            voiceService?.setStateChangeListener { state, text ->
                runOnUiThread { updateUI(state, text) }
            }
            // Load saved conversation history
            val saved = voiceService?.loadConversationLog() ?: ""
            if (saved.isNotBlank()) {
                runOnUiThread {
                    binding.tvConversation.text = saved.trimEnd()
                    binding.svConversation.post { binding.svConversation.fullScroll(View.FOCUS_DOWN) }
                }
            }
            runOnUiThread { updateUI(voiceService?.getCurrentState() ?: VoiceService.State.STOPPED) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null; serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.all { it.value }) startVoiceService()
        else Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ScreenCaptureManager.onActivityResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configManager = ConfigManager(this)

        // Register for screen capture
        ScreenCaptureManager.activity = this
        ScreenCaptureManager.launchIntent = { intent ->
            screenCaptureLauncher.launch(intent)
        }

        setupUI()
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        super.onDestroy()
    }

    private fun setupUI() {
        // Mic button → start/stop service
        binding.fabMic.setOnClickListener {
            if (serviceBound && voiceService != null) {
                stopVoiceService()
            } else {
                checkPermissionsAndStart()
            }
        }

        binding.toggleBackend.apply {
            isChecked = configManager?.backendType == ConfigManager.BACKEND_CLOUD
            // Set initial label (onCheckedChangeListener won't fire for programmatic set)
            binding.tvBackendLabel.text = if (isChecked)
                getString(R.string.label_backend_cloud) else getString(R.string.label_backend_local)
            setOnCheckedChangeListener { _, checked ->
                configManager?.backendType = if (checked) ConfigManager.BACKEND_CLOUD else ConfigManager.BACKEND_LOCAL
                voiceService?.switchBackend(checked)
                binding.tvBackendLabel.text = if (checked)
                    getString(R.string.label_backend_cloud) else getString(R.string.label_backend_local)
            }
        }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnClear.setOnClickListener { confirmClearHistory() }
        binding.btnHistory.setOnClickListener { showHistoryDialog() }
        binding.tvConversation.movementMethod = ScrollingMovementMethod()
        updateUI(VoiceService.State.STOPPED)
    }

    private fun checkPermissionsAndStart() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { permissionLauncher.launch(missing.toTypedArray()); return }
        startVoiceService()
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java).apply {
            // Forward any extras from the launch intent (e.g., test mode)
            this@MainActivity.intent?.extras?.let { putExtras(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        binding.fabMic.setImageResource(android.R.drawable.ic_media_pause)
        Toast.makeText(this, "猪头助手已启动，直接说话就行 🐷", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceService() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        stopService(Intent(this, VoiceService::class.java))
        voiceService = null
        binding.fabMic.setImageResource(R.drawable.ic_mic)
        binding.tvStatus.text = "已停止"
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI(state: VoiceService.State, text: String? = null) {
        binding.tvStatus.text = when (state) {
            VoiceService.State.INITIALIZING -> "⏳ 初始化中…"
            VoiceService.State.LISTENING -> "🎤 聆听中…"
            VoiceService.State.THINKING -> "🧠 思考中…"
            VoiceService.State.SPEAKING -> "🔊 回复中…"
            VoiceService.State.STOPPED -> "已停止"
            VoiceService.State.DORMANT -> "💤 休眠中…"
        }

        if (text != null && text.isNotBlank()) {
            val prefix = when (state) {
                VoiceService.State.THINKING -> "👤 "
                VoiceService.State.SPEAKING -> "🐷 "
                else -> ""
            }
            val current = binding.tvConversation.text.toString()
            binding.tvConversation.text = if (current == getString(R.string.sample_text)) "$prefix$text"
            else "$current\n\n$prefix$text"
            binding.svConversation.post { binding.svConversation.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showSettingsDialog() {
        val cm = configManager ?: return

        // ---- Helper: make a clickable settings row ----
        fun makeRow(label: String, value: String, onClick: () -> Unit): android.widget.LinearLayout {
            return android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 2, 0, 2)
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = "$label  "
                    textSize = 14f; setTextColor(0xFF555555.toInt())
                })
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = value; textSize = 14f
                    setTextColor(0xFF333333.toInt())
                    isClickable = true; isFocusable = true
                    setOnClickListener { onClick() }
                })
            }
        }

        // ---- Helper: section divider ----
        fun makeDivider(): android.view.View = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 8; bottomMargin = 6 }
            setBackgroundColor(0x22000000)
        }

        // ---- Helper: section title ----
        fun makeSectionTitle(text: String): android.widget.TextView =
            android.widget.TextView(this).apply {
                this.text = text; textSize = 15f
                setTextColor(0xFF333333.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 12, 0, 4)
            }

        val root = android.widget.ScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 600.dpToPx()
            )
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        root.addView(layout)

        // ── Speech rate ──────────────────────────────────
        val rateLabel = android.widget.TextView(this).apply {
            text = "语速: ${String.format("%.1f", cm.speechRate)}x"
            textSize = 16f; setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, 4)
        }
        layout.addView(rateLabel)

        val seekBar = android.widget.SeekBar(this).apply {
            max = 20
            progress = ((cm.speechRate - 0.5f) * 10).toInt()
            setPadding(0, 0, 0, 8)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val rate = 0.5f + p * 0.1f
                    rateLabel.text = "语速: ${String.format("%.1f", rate)}x"
                    if (fromUser) voiceService?.setSpeechRate(rate)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        layout.addView(seekBar)
        layout.addView(makeDivider())

        // ── Barge-in mode ────────────────────────────────
        val bargeLabel = android.widget.TextView(this).apply {
            text = "打断模式" + when (cm.bargeInMode) {
                "on" -> "：允许打断"
                "keyword" -> "：关键词打断"
                else -> "：不打断"
            }
            textSize = 15f; setTextColor(0xFF333333.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 4)
        }
        layout.addView(bargeLabel)

        val bargeGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        for ((mode, label) in listOf("off" to "不打断", "on" to "允许打断", "keyword" to "关键词")) {
            bargeGroup.addView(android.widget.RadioButton(this).apply {
                text = label; id = mode.hashCode()
                isChecked = cm.bargeInMode == mode
                textSize = 14f
            })
        }
        bargeGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                "on".hashCode() -> "on"
                "keyword".hashCode() -> "keyword"
                else -> "off"
            }
            bargeLabel.text = "打断模式" + when (mode) {
                "on" -> "：允许打断"
                "keyword" -> "：关键词打断"
                else -> "：不打断"
            }
            voiceService?.setBargeInMode(mode)
        }
        layout.addView(bargeGroup)
        layout.addView(makeDivider())

        // ── LLM Settings ─────────────────────────────────
        layout.addView(makeSectionTitle("🤖 LLM 设置"))

        layout.addView(makeRow("API Key：",
            if (cm.apiKey.isNotBlank()) "****${cm.apiKey.takeLast(4)}" else "未设置"
        ) {
            showInputDialog("API Key", cm.apiKey) { cm.apiKey = it }
        })

        layout.addView(makeRow("Base URL：", cm.baseUrl) {
            showInputDialog("Base URL", cm.baseUrl) { cm.baseUrl = it }
        })

        layout.addView(makeRow("Model：", cm.model) {
            showInputDialog("Model", cm.model) { cm.model = it }
        })

        layout.addView(makeDivider())

        // ── Vision Settings ──────────────────────────────
        layout.addView(makeSectionTitle("👁 视觉设置"))

        // Vision provider radio
        val visProviderLabel = android.widget.TextView(this).apply {
            text = "识别方式：" + when (cm.visionProvider) {
                ConfigManager.VISION_REMOTE -> "云端"
                ConfigManager.VISION_AUTO -> "自动"
                else -> "本地 OCR"
            }
            textSize = 14f; setTextColor(0xFF555555.toInt())
            setPadding(0, 4, 0, 4)
        }
        layout.addView(visProviderLabel)

        val visProviderGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
        for ((mode, label) in listOf(
            ConfigManager.VISION_LOCAL to "本地",
            ConfigManager.VISION_REMOTE to "云端",
            ConfigManager.VISION_AUTO to "自动"
        )) {
            visProviderGroup.addView(android.widget.RadioButton(this).apply {
                text = label; id = mode.hashCode()
                isChecked = cm.visionProvider == mode
                textSize = 13f
            })
        }
        visProviderGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                ConfigManager.VISION_REMOTE.hashCode() -> ConfigManager.VISION_REMOTE
                ConfigManager.VISION_AUTO.hashCode() -> ConfigManager.VISION_AUTO
                else -> ConfigManager.VISION_LOCAL
            }
            cm.visionProvider = mode
            visProviderLabel.text = "识别方式：" + when (mode) {
                ConfigManager.VISION_REMOTE -> "云端"
                ConfigManager.VISION_AUTO -> "自动"
                else -> "本地 OCR"
            }
        }
        layout.addView(visProviderGroup)

        layout.addView(makeRow("Vision API Key：",
            if (cm.visionApiKey.isNotBlank()) "****${cm.visionApiKey.takeLast(4)}" else "同 LLM Key"
        ) {
            showInputDialog("Vision API Key", cm.visionApiKey) { cm.visionApiKey = it }
        })

        layout.addView(makeRow("Vision URL：",
            cm.visionBaseUrl.ifBlank { "同 LLM URL" }
        ) {
            showInputDialog("Vision Base URL", cm.visionBaseUrl) { cm.visionBaseUrl = it }
        })

        layout.addView(makeRow("Vision Model：",
            cm.visionModel.ifBlank { "未配置" }
        ) {
            showInputDialog("Vision Model", cm.visionModel) { cm.visionModel = it }
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("设置")
            .setView(root)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun showInputDialog(title: String, value: String, onSave: (String) -> Unit) {
        val input = android.widget.EditText(this).apply { setText(value); setSingleLine() }
        MaterialAlertDialogBuilder(this)
            .setTitle(title).setView(input)
            .setPositiveButton("保存") { _, _ ->
                onSave(input.text.toString().trim())
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmClearHistory() {
        val vs = voiceService ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("清除对话历史")
            .setMessage("确定要清除所有对话记录吗？建议先备份。")
            .setPositiveButton("清除") { _, _ ->
                vs.clearConversation()
                binding.tvConversation.text = getString(R.string.sample_text)
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistoryDialog() {
        val vs = voiceService ?: return
        val backups = vs.listBackups()

        val items = mutableListOf("📦 备份当前对话")
        items.addAll(backups.map { formatBackupName(it) })

        MaterialAlertDialogBuilder(this)
            .setTitle("对话历史")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    // Backup
                    val name = vs.backupConversation()
                    if (name != null) {
                        Toast.makeText(this, "已备份: ${formatBackupName(name)}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "没有对话可备份", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Show restore/delete options for this backup
                    val backupName = backups[which - 1]
                    MaterialAlertDialogBuilder(this)
                        .setTitle(formatBackupName(backupName))
                        .setItems(arrayOf("📖 恢复", "🗑 删除")) { _, action ->
                            when (action) {
                                0 -> {
                                    // Restore
                                    val content = vs.restoreConversation(backupName)
                                    if (content.isNotBlank()) {
                                        binding.tvConversation.text = content.trimEnd()
                                        binding.svConversation.post { binding.svConversation.fullScroll(View.FOCUS_DOWN) }
                                        Toast.makeText(this, "已恢复", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this, "恢复失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                1 -> {
                                    // Delete
                                    MaterialAlertDialogBuilder(this)
                                        .setTitle("删除备份")
                                        .setMessage("确定删除 ${formatBackupName(backupName)}？")
                                        .setPositiveButton("删除") { _, _ ->
                                            if (backupName.delete()) {
                                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        .setNegativeButton("取消", null)
                                        .show()
                                }
                            }
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun formatBackupName(file: File): String {
        // "backup_20260602_143000.txt" → "2026-06-02 14:30:00"
        val filename = file.name
        return try {
            val name = filename.removePrefix("backup_").removeSuffix(".txt")
            val date = name.substring(0, 8)
            val time = name.substring(9, 15)
            "${date.substring(0,4)}-${date.substring(4,6)}-${date.substring(6,8)} ${time.substring(0,2)}:${time.substring(2,4)}:${time.substring(4,6)}"
        } catch (_: Exception) {
            filename
        }
    }
}
