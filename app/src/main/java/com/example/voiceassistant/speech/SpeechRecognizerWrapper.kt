package com.example.voiceassistant.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpeechRecognizerWrapper(
    private val context: Context
) {
    companion object {
        private const val TAG = "SpeechRecognizerWrapper"
        private const val SILENCE_TIMEOUT_MS = 3000L
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [STT] $msg\n") }
        } catch (_: Exception) {}
    }

    // Android built-in recognizer
    private var recognizer: SpeechRecognizer? = null
    private var isOnlineRecognizing = false
    private var onResultCallback: ((String) -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    // Vosk engine (lazy init)
    private var voskRecognizer: Any? = null // org.vosk.Recognizer
    private var voskAvailable = false

    /**
     * Initialize the online recognizer.
     */
    fun initOnline(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean {
        this.onResultCallback = onResult
        this.onPartialResultCallback = onPartial
        this.onErrorCallback = onError

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Online speech recognition not available")
            onError("Speech recognition not available on this device")
            return false
        }

        try {
            // Try iFlytek engine explicitly for Chinese phone compatibility
            recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context, android.content.ComponentName(
                    "com.iflytek.vflynote",
                    "com.iflytek.vflynote.service.SpeechService"
                ))
            } catch (e: Exception) {
                debugLog("iFlytek recognizer failed, trying default")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer?.setRecognitionListener(createListener())
            Log.i(TAG, "Online recognizer initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            return false
        }
    }

    /**
     * Start listening for speech (online mode).
     */
    fun startOnlineListening() {
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
        }
        isOnlineRecognizing = true
        rec.startListening(intent)
        Log.i(TAG, "Online listening started")
    }

    /**
     * Stop online listening.
     */
    fun stopOnlineListening() {
        if (isOnlineRecognizing) {
            recognizer?.stopListening()
            isOnlineRecognizing = false
            Log.i(TAG, "Online listening stopped")
        }
    }

    /**
     * Initialize Vosk offline recognizer.
     * Model should be at app/src/main/assets/vosk-model-small-cn-0.22/
     *
     * Note: Vosk model (~1.5GB) must be downloaded separately and placed
     * in the assets folder before building.
     */
    fun initVosk(modelPath: String = "vosk-model-small-cn-0.22"): Boolean {
        return try {
            // Vosk is loaded via JNI; model must be symlinked or copied from assets
            // For now, the model is expected at context.filesDir/modelPath
            val voskClass = Class.forName("org.vosk.Recognizer")
            val constructor = voskClass.getConstructor(Int::class.java)
            val sampleRate = 16000
            voskRecognizer = constructor.newInstance(sampleRate)
            voskAvailable = true
            Log.i(TAG, "Vosk recognizer initialized from: $modelPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Vosk", e)
            voskAvailable = false
            false
        }
    }

    /**
     * Feed raw PCM audio to Vosk for offline recognition.
     *
     * @param pcm 16-bit PCM, mono, 16kHz
     * @return recognized partial text, or null
     */
    fun feedVoskAudio(pcm: ShortArray): String? {
        if (!voskAvailable || voskRecognizer == null) return null
        return try {
            val recognizerClass = voskRecognizer!!.javaClass
            val acceptMethod = recognizerClass.getMethod("acceptWaveForm", ShortArray::class.java, Int::class.java)
            val result = acceptMethod.invoke(voskRecognizer, pcm, pcm.size) as? Boolean ?: false

            if (result) {
                val getResultMethod = recognizerClass.getMethod("getResult")
                val json = getResultMethod.invoke(voskRecognizer) as? String
                json?.let { parseVoskResult(it) }
            } else {
                val getPartialMethod = recognizerClass.getMethod("getPartialResult")
                val partialJson = getPartialMethod.invoke(voskRecognizer) as? String
                partialJson?.let { parseVoskResult(it, partial = true) } ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk feed error", e)
            null
        }
    }

    /**
     * Check if Vosk is available.
     */
    fun isVoskAvailable(): Boolean = voskAvailable

    /**
     * Release all recognizer resources.
     */
    fun release() {
        stopOnlineListening()
        try {
            recognizer?.destroy()
            recognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying online recognizer", e)
        }

        try {
            voskRecognizer?.javaClass?.getMethod("close")?.invoke(voskRecognizer)
            voskRecognizer = null
            voskAvailable = false
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk recognizer", e)
        }
        Log.i(TAG, "Released")
    }

    private fun parseVoskResult(jsonStr: String, partial: Boolean = false): String {
        return try {
            // Vosk returns: {"text": "你好"} or {"partial": "你"}
            val textKey = if (partial) "partial" else "text"
            // Simple JSON extraction without Gson dependency for this module
            val regex = Regex(""""$textKey"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            regex.find(jsonStr)?.groupValues?.getOrNull(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Volume level indicator; could show a VU meter
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Raw audio buffer
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isOnlineRecognizing = false
            }

            override fun onError(error: Int) {
                isOnlineRecognizing = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error: $error"
                }
                Log.w(TAG, "Recognition error: $errorMsg")
                onErrorCallback?.invoke(errorMsg)
            }

            override fun onResults(results: Bundle?) {
                isOnlineRecognizing = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.i(TAG, "Final result: '$text'")
                if (text.isNotBlank()) {
                    onResultCallback?.invoke(text)
                } else {
                    onErrorCallback?.invoke("No speech recognized")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    Log.d(TAG, "Partial: '$text'")
                    onPartialResultCallback?.invoke(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Event: $eventType")
            }
        }
    }
}
