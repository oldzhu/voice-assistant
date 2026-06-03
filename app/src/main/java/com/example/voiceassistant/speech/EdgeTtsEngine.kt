package com.example.voiceassistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Microsoft Edge TTS engine — free cloud-based Chinese speech synthesis.
 * Uses Edge's public speech API (no API key required).
 */
class EdgeTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "EdgeTtsEngine"
        private const val EDGE_TTS_URL =
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val VOICE_NAME = "zh-CN-XiaoxiaoNeural" // Female, natural Chinese
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [TTS] $msg\n") }
        } catch (_: Exception) {}
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null

    private var onStartCallback: (() -> Unit)? = null
    private var onDoneCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

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
     * Synthesize speech and play it.
     * Runs on a background thread, callbacks on main thread.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return

        debugLog("TTS request: '${text.take(50)}...'")

        val ssml = buildSsml(text, VOICE_NAME)

        try {
            val audioData = withContext(Dispatchers.IO) {
                synthesize(ssml)
            }

            if (audioData == null) {
                debugLog("TTS: no audio data returned")
                onErrorCallback?.invoke("语音合成返回空")
                return
            }

            debugLog("TTS: received ${audioData.size} bytes of audio")

            // Save to temp file for playback
            val tempFile = File(context.cacheDir, "tts_output.mp3")
            tempFile.writeBytes(audioData)
            audioFile = tempFile

            playAudio(tempFile)

        } catch (e: Exception) {
            debugLog("TTS error: ${e.message}")
            Log.e(TAG, "TTS failed", e)
            onErrorCallback?.invoke("语音合成失败: ${e.message}")
        }
    }

    /**
     * Stop current playback.
     */
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    fun release() {
        stop()
        audioFile?.delete()
        audioFile = null
    }

    /**
     * Call Edge TTS HTTP API. Returns MP3 bytes.
     */
    private fun synthesize(ssml: String): ByteArray? {
        val body = ssml.toRequestBody("application/ssml+xml".toMediaType())

        val request = Request.Builder()
            .url(EDGE_TTS_URL)
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("X-Microsoft-OutputFormat",
                "audio-16khz-32kbitrate-mono-mp3")
            .header("Content-Type", "application/ssml+xml")
            .header("Origin", "https://www.bing.com")
            .header("Referer", "https://www.bing.com/")
            .post(body)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            debugLog("Edge TTS HTTP ${response.code}: ${response.message}")
            debugLog("Response body: ${response.body?.string()?.take(200)}")
            return null
        }

        return response.body?.bytes()
    }

    /**
     * Play MP3 audio file using MediaPlayer.
     */
    private fun playAudio(file: File) {
        stop() // Stop any previous playback

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    debugLog("MediaPlayer prepared, starting playback")
                    onStartCallback?.invoke()
                    start()
                }
                setOnCompletionListener {
                    debugLog("MediaPlayer playback complete")
                    onDoneCallback?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    debugLog("MediaPlayer error: what=$what extra=$extra")
                    onErrorCallback?.invoke("播放错误: $what/$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            debugLog("MediaPlayer setup failed: ${e.message}")
            Log.e(TAG, "MediaPlayer error", e)
            onErrorCallback?.invoke("播放器错误: ${e.message}")
        }
    }

    /**
     * Build SSML with the given voice and text.
     */
    private fun buildSsml(text: String, voiceName: String): String {
        // Escape XML special characters
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        return """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="https://www.w3.org/2001/mstts" xml:lang="zh-CN">
            |  <voice name="$voiceName">
            |    <prosody rate="1.0" pitch="0%">
            |      $escaped
            |    </prosody>
            |  </voice>
            |</speak>""".trimMargin()
    }
}
