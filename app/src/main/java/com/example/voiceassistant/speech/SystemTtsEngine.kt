package com.example.voiceassistant.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * System TTS engine using Android's built-in TextToSpeech API.
 * Uses the phone's default TTS engine (Yuemeng/Realme).
 * MUST be initialized on the main thread.
 */
class SystemTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SystemTtsEngine"
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [SysTTS] $msg\n") }
        } catch (_: Exception) {}
    }

    private var tts: TextToSpeech? = null
    private var initDone = false

    @Volatile private var onStartCallback: (() -> Unit)? = null
    @Volatile private var onDoneCallback: (() -> Unit)? = null
    @Volatile private var onErrorCallback: ((String) -> Unit)? = null

    fun setCallbacks(
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        this.onStartCallback = onStart
        this.onDoneCallback = onDone
        this.onErrorCallback = onError
    }

    /**
     * Initialize TTS. Must be called from the MAIN thread.
     */
    suspend fun init(speechRate: Float = 1.3f): Boolean = withContext(Dispatchers.Main) {
        try {
            val result = withTimeoutOrNull(10000L) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    // Try with explicit Yuemeng engine first, then fall back to default
                    tts = TextToSpeech(context, { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val langResult = tts?.setLanguage(Locale.CHINESE)
                            tts?.setSpeechRate(speechRate)
                            debugLog("System TTS OK, setLanguage(CHINESE)=$langResult, rate=$speechRate")
                            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                debugLog("CHINESE not available, trying default: ${Locale.getDefault()}")
                                tts?.setLanguage(Locale.getDefault())
                            }
                            try {
                                val voices = tts?.voices
                                debugLog("Voices: ${voices?.size ?: 0}")
                                voices?.take(3)?.forEach { v ->
                                    debugLog("  ${v.name} ${v.locale} q=${v.quality}")
                                }
                            } catch (e: Exception) {
                                debugLog("Voice query: ${e.message}")
                            }
                            try {
                                debugLog("Default engine: ${tts?.defaultEngine}")
                            } catch (_: Exception) {}
                            initDone = true
                        } else {
                            debugLog("System TTS FAILED, status=$status")
                            initDone = true
                        }
                        if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS)
                    }, "com.yuemeng.speechsuite")
                }
            }
            if (result == true) {
                true
            } else if (result == false) {
                // Yuemeng failed, try without explicit engine
                debugLog("Retrying with default engine...")
                val retryResult = withTimeoutOrNull(5000L) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        tts = TextToSpeech(context) { status ->
                            if (status == TextToSpeech.SUCCESS) {
                                tts?.setLanguage(Locale.CHINESE)
                                tts?.setSpeechRate(speechRate)
                                debugLog("Default engine TTS OK")
                                initDone = true
                            } else {
                                debugLog("Default engine TTS FAILED, status=$status")
                                initDone = true
                            }
                            if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS)
                        }
                    }
                }
                retryResult ?: false
            } else {
                // null = timed out
                debugLog("System TTS init timed out (10s)")
                false
            }
        } catch (e: Exception) {
            debugLog("System TTS exception: ${e.message}")
            false
        }
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!initDone) {
            debugLog("Not initialized, waiting...")
            delay(500)
        }
        val engine = tts ?: run {
            debugLog("TTS null")
            onErrorCallback?.invoke("系统TTS不可用")
            return
        }

        debugLog("Speaking: '$text'")
        val utteranceId = UUID.randomUUID().toString()

        var done = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uttId: String?) {
                onStartCallback?.invoke()
            }
            override fun onDone(uttId: String?) {
                debugLog("Done")
                done = true
                onDoneCallback?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(uttId: String?) {
                debugLog("Error")
                done = true
                onErrorCallback?.invoke("TTS播放失败")
            }
        })

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        debugLog("speak() returned $result")

        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            while (!done && System.currentTimeMillis() - start < 30000) {
                delay(200)
            }
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.5f))
        debugLog("Speech rate set to $rate")
    }

    fun release() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
