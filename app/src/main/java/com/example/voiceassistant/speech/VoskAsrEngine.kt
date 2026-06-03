package com.example.voiceassistant.speech

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vosk offline speech recognition engine.
 * Uses AudioRecord internally via Vosk's SpeechService.
 */
class VoskAsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskAsrEngine"
        private const val SAMPLE_RATE = 16000.0f
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [ASR] $msg\n") }
        } catch (_: Exception) {}
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    private var onResultCallback: ((String) -> Unit)? = null
    private var onPartialCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var isRunning = false

    /**
     * Initialize Vosk with the Chinese model.
     * Model must be at app/src/main/assets/vosk-model-small-cn-0.22/
     * We copy it to internal storage on first run.
     */
    fun init(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean {
        this.onResultCallback = onResult
        this.onPartialCallback = onPartial
        this.onErrorCallback = onError

        return try {
            val modelDir = prepareModel()
            if (modelDir == null) {
                debugLog("Failed to prepare Vosk model")
                onError("语音模型未就绪")
                return false
            }

            debugLog("Loading Vosk model from: $modelDir")
            model = Model(modelDir)
            recognizer = Recognizer(model, SAMPLE_RATE)
            debugLog("Vosk model loaded successfully")
            true
        } catch (e: Exception) {
            debugLog("Vosk init failed: ${e.message}")
            Log.e(TAG, "Vosk init failed", e)
            onError("语音引擎初始化失败: ${e.message}")
            false
        }
    }

    /**
     * Start listening continuously.
     */
    fun startListening() {
        if (isRunning) return
        val rec = recognizer ?: run {
            debugLog("startListening: recognizer is null")
            return
        }

        try {
            speechService = SpeechService(rec, SAMPLE_RATE)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    val text = parseResult(hypothesis, partial = true)
                    if (text.isNotBlank()) {
                        onPartialCallback?.invoke(text)
                    }
                }

                override fun onResult(hypothesis: String) {
                    val text = parseResult(hypothesis)
                    debugLog("Vosk final: '$text'")
                    if (text.isNotBlank()) {
                        onResultCallback?.invoke(text)
                    }
                }

                override fun onFinalResult(hypothesis: String) {
                    val text = parseResult(hypothesis)
                    debugLog("Vosk finalResult: '$text'")
                    if (text.isNotBlank()) {
                        onResultCallback?.invoke(text)
                    }
                }

                override fun onError(exception: Exception) {
                    debugLog("Vosk error: ${exception.message}")
                    onErrorCallback?.invoke(exception.message ?: "识别错误")
                }

                override fun onTimeout() {
                    debugLog("Vosk timeout")
                    // Restart listening
                    onErrorCallback?.invoke("timeout")
                }
            })
            isRunning = true
            debugLog("Vosk listening started")
        } catch (e: Exception) {
            debugLog("startListening failed: ${e.message}")
            onErrorCallback?.invoke("启动录音失败: ${e.message}")
        }
    }

    /**
     * Stop listening and release audio resources.
     */
    fun stop() {
        isRunning = false
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            debugLog("Vosk stopped")
        } catch (e: Exception) {
            debugLog("Vosk stop error: ${e.message}")
        }
    }

    /**
     * Pause recognition (keeps model loaded, stops mic).
     */
    fun pause() {
        try {
            speechService?.setPause(true)
            debugLog("Vosk paused")
        } catch (e: Exception) {
            debugLog("Vosk pause error: ${e.message}")
        }
    }

    /**
     * Resume recognition.
     */
    fun resume() {
        try {
            speechService?.setPause(false)
            debugLog("Vosk resumed")
        } catch (e: Exception) {
            debugLog("Vosk resume error: ${e.message}")
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        try {
            recognizer?.close()
            recognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing recognizer", e)
        }
        try {
            model?.close()
            model = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing model", e)
        }
        debugLog("Vosk released")
    }

    fun isActive(): Boolean = isRunning

    /**
     * Copy model from assets to internal storage if needed.
     * Returns the path to the model directory, or null on failure.
     */
    private fun prepareModel(): String? {
        val modelName = "vosk-model-small-cn-0.22"
        val targetDir = File(context.filesDir, modelName)

        // Check if already extracted
        if (targetDir.exists() && targetDir.isDirectory) {
            val files = targetDir.list()
            if (files != null && files.isNotEmpty()) {
                debugLog("Vosk model already at: ${targetDir.absolutePath}")
                return targetDir.absolutePath
            }
        }

        // Extract from assets
        try {
            val assets = context.assets
            val modelAssetsPath = modelName

            // Check if model is in assets
            val assetFiles = assets.list(modelAssetsPath)
            if (assetFiles == null || assetFiles.isEmpty()) {
                debugLog("Vosk model NOT FOUND in assets/$modelAssetsPath")
                debugLog("Available assets: ${assets.list("")?.joinToString()}")
                return null
            }

            debugLog("Extracting Vosk model from assets (${assetFiles.size} files)...")
            targetDir.mkdirs()
            extractAssetDir(assets, modelAssetsPath, targetDir)
            debugLog("Vosk model extracted to: ${targetDir.absolutePath}")
            return targetDir.absolutePath
        } catch (e: Exception) {
            debugLog("Failed to extract Vosk model: ${e.message}")
            Log.e(TAG, "Failed to extract model", e)
            return null
        }
    }

    private fun extractAssetDir(
        assets: android.content.res.AssetManager,
        assetPath: String,
        targetDir: File
    ) {
        val files = assets.list(assetPath) ?: return
        for (file in files) {
            val childPath = "$assetPath/$file"
            val childFiles = assets.list(childPath)
            if (childFiles != null && childFiles.isNotEmpty()) {
                // It's a directory
                val childDir = File(targetDir, file)
                childDir.mkdirs()
                extractAssetDir(assets, childPath, childDir)
            } else {
                // It's a file
                val outFile = File(targetDir, file)
                assets.open(childPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    /**
     * Parse Vosk JSON result: {"text": "你好世界"} or {"partial": "你好"}
     */
    private fun parseResult(json: String, partial: Boolean = false): String {
        return try {
            val obj = JSONObject(json)
            val key = if (partial) "partial" else "text"
            obj.optString(key, "")
        } catch (e: Exception) {
            ""
        }
    }
}
