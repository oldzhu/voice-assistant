package com.example.voiceassistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TTS engine wrapping Android TextToSpeech.
 */
class TtsEngine(
    private val context: Context
) : TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "TtsEngine"
        private const val UTTERANCE_ID = "voice_assistant_tts"
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [TTS] $msg\n") }
        } catch (_: Exception) {}
    }

    private var tts: TextToSpeech? = null
    private val speakQueue = ConcurrentLinkedQueue<String>()
    private val isSpeaking = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)
    private var onStartCallback: (() -> Unit)? = null
    private var onDoneCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    /**
     * Initialize TTS with Chinese voice.
     */
    fun init(
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        this.onStartCallback = onStart
        this.onDoneCallback = onDone
        this.onErrorCallback = onError
        // Try iFlytek engine explicitly — Chinese phones use this
        val iflytekEngine = "com.iflytek.vflynote"
        debugLog("Creating TTS with engine: $iflytekEngine")
        tts = TextToSpeech(context, this, iflytekEngine)
    }

    override fun onInit(status: Int) {
        debugLog("TTS onInit: status=$status")
        if (status == TextToSpeech.SUCCESS) {
            val engines = tts?.engines
            debugLog("Available engines: ${engines?.joinToString()}")
            val defaultEngine = tts?.defaultEngine
            debugLog("Default engine: $defaultEngine")
            
            val result = tts?.setLanguage(Locale.CHINESE)
            debugLog("setLanguage CHINESE: $result (LANG_MISSING_DATA=${TextToSpeech.LANG_MISSING_DATA}, LANG_NOT_SUPPORTED=${TextToSpeech.LANG_NOT_SUPPORTED})")
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Chinese TTS not fully supported, trying Simplified Chinese")
                tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            }

            // Configure audio attributes for Bluetooth headset output
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
            }

            // Speech rate: 1.0 = normal, 0.8 = slightly slower (easier to understand)
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started: $utteranceId")
                    onStartCallback?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS done: $utteranceId")
                    isSpeaking.set(false)
                    processQueue()
                    onDoneCallback?.invoke()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error: $utteranceId")
                    isSpeaking.set(false)
                    onErrorCallback?.invoke("TTS playback error")
                    processQueue()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e(TAG, "TTS error: $utteranceId, code=$errorCode")
                    isSpeaking.set(false)
                    onErrorCallback?.invoke("TTS error code: $errorCode")
                    processQueue()
                }
            })

            isInitialized.set(true)
            Log.i(TAG, "TTS initialized with Chinese")
        } else {
            Log.e(TAG, "TTS initialization failed: $status")
            onErrorCallback?.invoke("TTS init failed: $status")
        }
    }

    /**
     * Queue text to be spoken. Non-blocking.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        speakQueue.offer(text)
        if (!isSpeaking.get()) {
            processQueue()
        }
    }

    /**
     * Stop current speech and clear queue.
     */
    fun stop() {
        speakQueue.clear()
        tts?.stop()
        isSpeaking.set(false)
    }

    /**
     * Release TTS resources.
     */
    fun release() {
        stop()
        try {
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
        isInitialized.set(false)
        Log.i(TAG, "Released")
    }

    fun isReady(): Boolean = isInitialized.get()
    fun isCurrentlySpeaking(): Boolean = isSpeaking.get()

    /**
     * Process the speak queue.
     */
    private fun processQueue() {
        val text = speakQueue.poll() ?: return
        isSpeaking.set(true)

        // For large texts, limit to a reasonable length to avoid TTS overflow
        val speakableText = if (text.length > 4000) text.take(4000) else text

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(speakableText, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            @Suppress("DEPRECATION")
            tts?.speak(speakableText, TextToSpeech.QUEUE_FLUSH, null)
        }

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak error")
            isSpeaking.set(false)
            onErrorCallback?.invoke("TTS speak failed")
            processQueue()
        } else {
            Log.d(TAG, "Speaking: ${speakableText.take(50)}...")
        }
    }
}
