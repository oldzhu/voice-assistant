package com.example.voiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.example.voiceassistant.config.ConfigManager
import com.example.voiceassistant.llm.CloudLLMBackend
import com.example.voiceassistant.llm.LLMBackend
import com.example.voiceassistant.llm.LocalLLMBackend
import com.example.voiceassistant.llm.ToolRegistry
import com.example.voiceassistant.llm.ToolCallEngine
import com.example.voiceassistant.tools.SetSpeechRateTool
import com.example.voiceassistant.tools.StopListeningTool
import com.example.voiceassistant.tools.StartListeningTool
import com.example.voiceassistant.tools.SetBargeInModeTool
import com.example.voiceassistant.tools.ClearHistoryTool
import com.example.voiceassistant.tools.WebSearchTool
import com.example.voiceassistant.tools.WebFetchTool
import com.example.voiceassistant.tools.WeatherTool
import com.example.voiceassistant.llm.transport.StdioMcpTransport
import com.example.voiceassistant.llm.transport.HttpMcpTransport
import com.example.voiceassistant.llm.McpClient
import com.example.voiceassistant.llm.McpToolAdapter
import com.example.voiceassistant.config.McpServerConfig
import com.example.voiceassistant.speech.SherpaAsrEngine
import com.example.voiceassistant.speech.SherpaTtsEngine
import com.example.voiceassistant.speech.SystemTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_assistant_channel"
        private const val HISTORY_MAX_SIZE = 20
        private const val PAUSE_BEFORE_LISTEN_MS = 1500L
        private const val LISTEN_RESTART_DELAY_MS = 2000L
        const val ACTION_TEST_TTS = "com.example.voiceassistant.TEST_TTS"
        const val TEST_TEXT = "我是猪头您的手机个人语音助手"
        private const val MAX_TEST_ATTEMPTS = 3
        // Wake phrases that resume from DORMANT state
        private val WAKE_PHRASES = listOf("开始听", "开始监听", "继续", "回来", "猪头回来", "猪头")
        // Sleep phrases that enter DORMANT directly (bypass LLM for reliability)
        private val SLEEP_PHRASES = listOf("别听了", "休息", "睡觉", "暂停", "停下", "停止", "睡了")
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} $msg\n") }
        } catch (_: Exception) {}
    }

    enum class State { STOPPED, INITIALIZING, LISTENING, THINKING, SPEAKING, DORMANT }

    private val binder = LocalBinder()
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var audioManager: AudioManager
    private var asrEngine: SherpaAsrEngine? = null
    private var ttsEngine: SherpaTtsEngine? = null
    private var sysTtsEngine: SystemTtsEngine? = null
    private var useSystemTts = false
    private var llmBackend: LLMBackend? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    @Volatile private var state: State = State.STOPPED
    @Volatile private var initialized = false
    private val conversationHistory = mutableListOf<LLMBackend.ChatMessage>()
    private var stateChangeListener: ((State, String?) -> Unit)? = null

    // Barge-in config
    private var bargeInMode = "off"  // "off" | "on" | "keyword"
    private var bargeInKeyword = "猪头"
    private var bargeInKeywordDetected = false  // flag for mode C

    // Tool calling
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolCallEngine: ToolCallEngine

    private val conversationLogFile by lazy { File(filesDir, "conversation.txt") }
    private val backupDir by lazy { File(filesDir, "backups").also { it.mkdirs() } }

    // Auto-test fields
    private var testMode = false
    private var testAttempt = 0

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    override fun onCreate() {
        super.onCreate()
        debugLog("===== onCreate =====")
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voiceassistant:wakelock")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        // Heavy model init on background thread
        lifecycleScope.launch(Dispatchers.IO) {
            initEngines()
        }
    }

    private suspend fun initEngines() {
        // Enable full-duplex audio path for hardware echo cancellation
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        debugLog("AudioManager mode: MODE_IN_COMMUNICATION")

        // Set up system TTS engine
        sysTtsEngine = SystemTtsEngine(this@VoiceService).apply {
            setCallbacks(
                onStart = {
                    bargeInKeywordDetected = false
                    // Don't override DORMANT — farewell message keeps dormant state
                    if (state != State.DORMANT) {
                        updateState(State.SPEAKING)
                    }
                },
                onDone = {
                    bargeInKeywordDetected = false
                    // If DORMANT (farewell message), stay dormant but keep ASR alive for wake words
                    if (state == State.DORMANT) {
                        debugLog("TTS done but staying DORMANT — restarting ASR for wake words")
                        asrEngine?.startListening()
                        return@setCallbacks
                    }
                    lifecycleScope.launch {
                        delay(PAUSE_BEFORE_LISTEN_MS)
                        startListening()
                    }
                },
                onError = { err ->
                    debugLog("System TTS error: $err")
                    updateState(State.LISTENING)
                    lifecycleScope.launch {
                        delay(LISTEN_RESTART_DELAY_MS)
                        startListening()
                    }
                }
            )
        }

        // Try system TTS first (suspendCancellableCoroutine, no deadlock)
        val config = ConfigManager(this@VoiceService)
        val rate = config.speechRate
        bargeInMode = config.bargeInMode
        bargeInKeyword = config.bargeInKeyword
        debugLog("Barge-in mode: $bargeInMode, keyword: $bargeInKeyword")
        val sysOk = sysTtsEngine?.init(rate) ?: false
        if (sysOk) {
            useSystemTts = true
            debugLog("System TTS ready, using phone's built-in engine")
        } else {
            debugLog("System TTS not available, falling back to sherpa-onnx")
            initSherpaTts()
        }

        // Init ASR
        debugLog("Initializing Sherpa ASR...")
        asrEngine = SherpaAsrEngine(this@VoiceService).apply {
            val ok = init(
                onResult = { text ->
                    debugLog("ASR result: '$text'")
                    if (state == State.SPEAKING) {
                        when {
                            bargeInMode == "on" -> {
                                // Mode B: any speech interrupts TTS
                                debugLog("Barge-in (voice) detected, interrupting TTS")
                                bargeInKeywordDetected = false
                                stopTts()
                                onSpeechRecognized(text)
                            }
                            bargeInMode == "keyword" && bargeInKeywordDetected -> {
                                // Mode C: keyword was detected, process this utterance
                                debugLog("Barge-in (keyword) processing: '$text'")
                                bargeInKeywordDetected = false
                                // TTS already stopped in onPartial
                                onSpeechRecognized(text)
                            }
                            else -> {
                                // Mode C without keyword, or mode A (shouldn't reach here) — ignore as echo
                                debugLog("Ignoring speech during TTS (mode=$bargeInMode, keywordDet=$bargeInKeywordDetected)")
                            }
                        }
                    } else {
                        onSpeechRecognized(text)
                    }
                },
                onPartial = { partial ->
                    // Mode C: keyword-triggered barge-in — detect in real-time
                    if (state == State.SPEAKING && bargeInMode == "keyword" && partial.contains(bargeInKeyword)) {
                        debugLog("Keyword '$bargeInKeyword' detected in partial, interrupting TTS")
                        bargeInKeywordDetected = true
                        stopTts()
                    }
                },
                onError = { err ->
                    debugLog("ASR error: $err")
                    if (state != State.STOPPED) {
                        lifecycleScope.launch {
                            delay(LISTEN_RESTART_DELAY_MS)
                            startListening()
                        }
                    }
                }
            )
            if (!ok) debugLog("Sherpa ASR init FAILED")
            else debugLog("Sherpa ASR init OK")
        }

        // Init LLM backend
        debugLog("Initializing LLM backend...")
        llmBackend = createLLMBackend()
        debugLog("LLM backend ready")

        // Initialize tool calling system
        val cloudBackend = llmBackend as? CloudLLMBackend
        if (cloudBackend != null) {
            toolRegistry = ToolRegistry().apply {
                register(SetSpeechRateTool { rate ->
                    val cfg = ConfigManager(this@VoiceService)
                    cfg.speechRate = rate
                    sysTtsEngine?.setSpeechRate(rate)
                })
                register(StopListeningTool {
                    // Don't stop ASR — just go dormant (listens for wake phrase only)
                    updateState(State.DORMANT)
                })
                register(StartListeningTool {
                    lifecycleScope.launch { startListening() }
                })
                register(SetBargeInModeTool { mode ->
                    setBargeInMode(mode)
                })
                register(ClearHistoryTool {
                    conversationHistory.clear()
                })
                // External tools
                register(WebSearchTool())
                register(WebFetchTool())
                register(WeatherTool())
            }
            toolCallEngine = ToolCallEngine(cloudBackend, toolRegistry)
            debugLog("Tools registered: ${toolRegistry.getAll().map { it.name }}")

            // Connect MCP servers and register their tools
            lifecycleScope.launch { connectMcpServers(cloudBackend) }
        } else {
            debugLog("Local backend does not support function calling; tools disabled")
        }

        initialized = true
        debugLog("Engines initialized, state=$state")

        if (testMode) {
            updateState(State.INITIALIZING)
            lifecycleScope.launch {
                delay(500)
                runTtsTest()
            }
        } else {
            updateState(State.LISTENING)
            startListening()
        }
    }

    private fun initSherpaTts() {
        ttsEngine = SherpaTtsEngine(this@VoiceService).apply {
            setCallbacks(
                onStart = {
                    asrEngine?.stop()
                    updateState(State.SPEAKING)
                },
                onDone = {
                    lifecycleScope.launch {
                        delay(PAUSE_BEFORE_LISTEN_MS)
                        startListening()
                    }
                },
                onError = { err ->
                    debugLog("TTS error in callback: $err")
                    updateState(State.LISTENING)
                    lifecycleScope.launch {
                        delay(LISTEN_RESTART_DELAY_MS)
                        startListening()
                    }
                }
            )
            val ok = init()
            if (!ok) debugLog("Sherpa TTS init FAILED")
            else debugLog("Sherpa TTS init OK")
        }
    }

    private fun createLLMBackend(): LLMBackend {
        val config = ConfigManager(this)
        return if (config.backendType == ConfigManager.BACKEND_LOCAL) {
            LocalLLMBackend(config.baseUrl, config.model)
        } else {
            CloudLLMBackend(config.apiKey, config.baseUrl, config.model)
        }
    }

    private fun runTtsTest() {
        if (!testMode) return
        testAttempt++
        debugLog("========== TTS TEST #$testAttempt ==========")
        debugLog("TEST speaking test text...")
        lifecycleScope.launch {
            ttsEngine?.speakForTest(TEST_TEXT)
        }
    }

    private fun onSpeechRecognized(text: String) {
        if (text.isBlank()) return
        if (testMode) {
            onTestAsrResult(text)
            return
        }
        // Sleep phrases: go dormant directly without LLM (more reliable than tool calling)
        if (state == State.LISTENING) {
            val sleepMatch = SLEEP_PHRASES.any { text.contains(it) }
            if (sleepMatch) {
                debugLog("Sleep phrase detected: '$text' → entering DORMANT")
                updateState(State.DORMANT)
                // Play farewell while staying DORMANT — onDone won't startListening
                lifecycleScope.launch { speakTts("好的，我休息了，随时呼我") }
                return
            }
        }
        // Dormant mode: only wake phrases pass through, everything else silently ignored
        if (state == State.DORMANT) {
            val matched = WAKE_PHRASES.any { text.contains(it) }
            if (matched) {
                debugLog("Wake phrase detected in dormant: '$text'")
                asrEngine?.stop() // Prevent ASR from hearing the greeting TTS
                lifecycleScope.launch {
                    updateState(State.SPEAKING, "我回来了，有啥要聊的？")
                    speakTts("我回来了，有啥要聊的？")
                    // startListening() will be called from TTS onDone
                }
            } else {
                debugLog("Dormant — ignoring: '$text'")
            }
            return
        }
        updateState(State.THINKING, text)
        lifecycleScope.launch {
            processQuery(text)
        }
    }

    private fun onTestAsrResult(recognized: String) {
        debugLog("TEST recognized: '$recognized'")
        val similarity = textSimilarity(TEST_TEXT, recognized)
        debugLog("TEST similarity: $similarity%")
        if (similarity >= 60) {
            debugLog("========== TEST PASSED ($similarity%) ==========")
            testMode = false
            lifecycleScope.launch { delay(1000); startListening() }
        } else if (testAttempt < MAX_TEST_ATTEMPTS) {
            debugLog("========== TEST FAILED ($similarity%), retrying... ==========")
            lifecycleScope.launch { delay(1500); runTtsTest() }
        } else {
            debugLog("========== TEST FAILED after $MAX_TEST_ATTEMPTS attempts ==========")
            testMode = false
            lifecycleScope.launch { delay(1000); startListening() }
        }
    }

    private fun textSimilarity(a: String, b: String): Int {
        val clean = { s: String -> s.replace(Regex("[^\\u4e00-\\u9fff]"), "") }
        val ca = clean(a); val cb = clean(b)
        if (ca.isEmpty() || cb.isEmpty()) return 0
        val maxLen = maxOf(ca.length, cb.length)
        val dist = levenshtein(ca, cb)
        return ((maxLen - dist).toDouble() / maxLen * 100).toInt()
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n)
            dp[i][j] = minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1] + if (a[i-1]==b[j-1]) 0 else 1)
        return dp[m][n]
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debugLog("===== onStartCommand ===== initialized=$initialized")
        if (intent?.action == ACTION_TEST_TTS || intent?.getStringExtra("action") == "TEST_TTS") {
            testMode = true; testAttempt = 0; debugLog("TEST MODE enabled")
        }
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        wakeLock?.acquire(24 * 60 * 60 * 1000L)
        if (initialized) {
            state = State.LISTENING
            startListening()
        } else {
            debugLog("Waiting for engine init...")
            updateState(State.INITIALIZING)
        }
        return START_STICKY
    }

    private suspend fun speakTts(text: String) {
        // Mode A (off): stop ASR to prevent self-loop
        // Mode B (on) / C (keyword): keep ASR running for barge-in
        if (bargeInMode == "off") {
            asrEngine?.stop()
        }
        if (useSystemTts) sysTtsEngine?.speak(text)
        else ttsEngine?.speak(text)
    }

    private fun stopTts() {
        if (useSystemTts) sysTtsEngine?.stop()
        else ttsEngine?.stop()
    }

    private fun releaseTts() {
        if (useSystemTts) sysTtsEngine?.release()
        else ttsEngine?.release()
    }

    override fun onDestroy() {
        updateState(State.STOPPED)
        asrEngine?.stop(); asrEngine?.release()
        stopTts(); releaseTts()
        try { wakeLock?.release() } catch (_: Exception) {}
        audioManager.mode = AudioManager.MODE_NORMAL
        debugLog("AudioManager mode: MODE_NORMAL (restored)")
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun updateState(newState: State, message: String? = null) {
        val old = state; state = newState
        debugLog("State: $old → $newState")
        if (newState != old) stateChangeListener?.invoke(newState, message)
    }

    private fun startListening() {
        if (state == State.STOPPED) return
        updateState(State.LISTENING)
        asrEngine?.startListening()
    }

    private suspend fun processQuery(text: String) {
        val engine = if (::toolCallEngine.isInitialized) toolCallEngine else null
        val backend = llmBackend ?: run {
            val msg = "请先在设置里填入API密钥"
            debugLog("LLM backend null, TTS: $msg")
            updateState(State.SPEAKING, msg)
            speakTts(msg)
            return
        }
        conversationHistory.add(LLMBackend.ChatMessage("user", text))
        saveConversationLine("👤 用户", text)
        updateState(State.THINKING)

        try {
            // Use tool-calling engine if available, fall back to plain chat
            val result = if (engine != null) {
                withTimeoutOrNull(30000L) {
                    engine.chat(text, conversationHistory)
                }
            } else {
                withTimeoutOrNull(15000L) {
                    backend.chat(text, conversationHistory)
                }
            }
            val response = when {
                result == null -> "回复超时了"
                result.isSuccess -> result.getOrNull() ?: "没听清楚，再说一次？"
                else -> {
                    debugLog("LLM error: ${result.exceptionOrNull()?.message}")
                    "出了点问题，再试一次"
                }
            }
            conversationHistory.add(LLMBackend.ChatMessage("assistant", response))
            if (conversationHistory.size > HISTORY_MAX_SIZE) conversationHistory.removeAt(0)
            saveConversationLine("🐷 猪头", response)
            // If tool execution put us in DORMANT (stop_listening), still speak the farewell
            // Keep DORMANT state so TTS onDone stays dormant after farewell
            if (state != State.DORMANT) {
                updateState(State.SPEAKING, response)
            }
            speakTts(response)
        } catch (e: Exception) {
            debugLog("LLM error: ${e.message}")
            val msg = when {
                e.message?.contains("timeout", true) == true -> "网络超时，再试一次"
                e.message?.contains("401", true) == true -> "API密钥不对，检查设置"
                e.message?.contains("429", true) == true -> "请求太频繁，等一下"
                else -> "出了点问题，再试一次"
            }
            updateState(State.SPEAKING, msg)
            speakTts(msg)
        }
    }

    private fun saveConversationLine(speaker: String, text: String) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            conversationLogFile.appendText("[${sdf.format(Date())}] $speaker: $text\n")
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "语音助手", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "猪头助手前台服务"
                setSound(null, null)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stateText = when (state) {
            State.STOPPED -> "已停止"
            State.INITIALIZING -> "初始化中..."
            State.LISTENING -> "正在听..."
            State.THINKING -> "思考中..."
            State.SPEAKING -> "说话中..."
            State.DORMANT -> "休眠中（说'开始听'唤醒）"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("猪头助手")
            .setContentText(stateText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun setStateChangeListener(listener: (State, String?) -> Unit) {
        stateChangeListener = listener
    }

    fun pauseConversation() {
        debugLog("pauseConversation called")
        asrEngine?.stop()
        stopTts()
        updateState(State.STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun getCurrentState(): State = state

    fun loadConversationLog(): String {
        return try { conversationLogFile.readText() } catch (_: Exception) { "" }
    }

    fun switchBackend(isCloud: Boolean) {
        lifecycleScope.launch {
            llmBackend = createLLMBackend()
            debugLog("Switched backend to ${if (isCloud) "cloud" else "local"}")
        }
    }

    fun clearConversation() {
        conversationHistory.clear()
        try { conversationLogFile.writeText("") } catch (_: Exception) {}
        debugLog("Conversation cleared")
    }

    fun setSpeechRate(rate: Float) {
        ConfigManager(this).speechRate = rate
        sysTtsEngine?.setSpeechRate(rate)
    }

    fun setBargeInMode(mode: String) {
        bargeInMode = mode
        ConfigManager(this).bargeInMode = mode
        debugLog("Barge-in mode changed to: $mode")
    }

    fun getBargeInMode(): String = bargeInMode

    fun setBargeInKeyword(keyword: String) {
        bargeInKeyword = keyword
        ConfigManager(this).bargeInKeyword = keyword
        debugLog("Barge-in keyword changed to: $keyword")
    }

    fun getBargeInKeyword(): String = bargeInKeyword

    fun listBackups(): List<File> {
        return backupDir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** Connect to configured MCP servers and register their tools. */
    private suspend fun connectMcpServers(cloudBackend: CloudLLMBackend) {
        val config = ConfigManager(this@VoiceService)
        val servers = config.mcpServers
        if (servers.isEmpty()) {
            debugLog("No MCP servers configured")
            return
        }

        for (server in servers) {
            try {
                val transport = if (server.isStdio) {
                    StdioMcpTransport(
                        command = server.command ?: continue,
                        args = server.args
                    )
                } else if (server.isHttp) {
                    HttpMcpTransport(
                        url = server.url ?: continue,
                        headers = server.headers,
                        timeoutSec = server.timeout / 1000
                    )
                } else {
                    debugLog("MCP '${server.name}': unknown transport '${server.transport}'")
                    continue
                }

                val client = McpClient(server.name, transport)
                val initResult = client.connect()
                debugLog("MCP '${server.name}' connected: ${initResult.serverName} v${initResult.serverVersion}")

                val tools = client.listTools()
                debugLog("MCP '${server.name}': ${tools.size} tools discovered")

                for (tool in tools) {
                    val adapter = McpToolAdapter(server.name, tool, client)
                    toolRegistry.register(adapter)
                    debugLog("MCP '${server.name}': registered ${adapter.name}")
                }

                // Update toolCallEngine with new tools
                toolCallEngine = ToolCallEngine(cloudBackend, toolRegistry)
            } catch (e: Exception) {
                debugLog("MCP '${server.name}' connection failed: ${e.message}")
            }
        }

        val allTools = toolRegistry.getAll().map { it.name }
        debugLog("All tools (after MCP): $allTools")
    }

    fun backupConversation(name: String) {
        try {
            val safe = name.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_\\-]"), "_")
            val backup = File(backupDir, "backup_${safe}.txt")
            conversationLogFile.copyTo(backup, overwrite = true)
            debugLog("Backed up to ${backup.name}")
        } catch (e: Exception) {
            debugLog("Backup failed: ${e.message}")
        }
    }

    /** Auto-generate timestamped backup, returns the backup File */
    fun backupConversation(): File? {
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val backup = File(backupDir, "backup_${sdf.format(Date())}.txt")
            conversationLogFile.copyTo(backup, overwrite = true)
            debugLog("Backed up to ${backup.name}")
            backup
        } catch (e: Exception) {
            debugLog("Backup failed: ${e.message}")
            null
        }
    }

    fun restoreConversation(file: File): String {
        return try {
            val content = file.readText()
            conversationLogFile.writeText(content)
            debugLog("Restored from ${file.name}")
            content
        } catch (e: Exception) {
            debugLog("Restore failed: ${e.message}")
            ""
        }
    }
}
