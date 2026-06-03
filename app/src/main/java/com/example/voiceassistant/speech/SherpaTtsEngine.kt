package com.example.voiceassistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sherpa-ONNX offline text-to-speech engine.
 * Uses VITS model with streaming AudioTrack playback.
 */
class SherpaTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaTtsEngine"
        private const val MODEL_DIR = "sherpa-onnx-vits-zh-ll"
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [TTS] $msg\n") }
        } catch (_: Exception) {}
    }

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    private var onStartCallback: (() -> Unit)? = null
    private var onDoneCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    @Volatile private var stopped = false

    fun setCallbacks(
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        this.onStartCallback = onStart
        this.onDoneCallback = onDone
        this.onErrorCallback = onError
    }

    fun init(): Boolean {
        return try {
            val modelDir = MODEL_DIR

            // Try file-based loading first (extract to internal storage)
            val localPath = prepareModel(modelDir)
            if (localPath != null) {
                try {
                    debugLog("Loading TTS model from filesystem: $localPath")
                    val config = getOfflineTtsConfig(
                        modelDir = localPath,
                        modelName = "model.onnx",
                        acousticModelName = "",
                        vocoder = "",
                        voices = "",
                        lexicon = "lexicon.txt",
                        dataDir = localPath,
                        dictDir = "",
                        ruleFsts = "",
                        ruleFars = "",
                        numThreads = 1,
                    )
                    tts = OfflineTts(null, config)
                    if (tts != null) {
                        debugLog("File-based TTS loaded OK")
                    }
                } catch (e: Exception) {
                    debugLog("File-based loading failed: ${e.message}")
                    tts = null
                }
            }

            // Fall back to AssetManager if file loading failed or not attempted
            if (tts == null) {
                debugLog("Loading TTS from AssetManager: $modelDir")
                val assetConfig = getOfflineTtsConfig(
                    modelDir = modelDir,
                    modelName = "model.onnx",
                    acousticModelName = "",
                    vocoder = "",
                    voices = "",
                    lexicon = "lexicon.txt",
                    dataDir = modelDir,
                    dictDir = "",
                    ruleFsts = "",
                    ruleFars = "",
                    numThreads = 1,
                )
                tts = OfflineTts(context.assets, assetConfig)
            }
            val sr = tts!!.sampleRate()
            val numSpeakers = tts!!.numSpeakers()
            debugLog("TTS loaded: sampleRate=$sr, speakers=$numSpeakers")

            // Init AudioTrack
            initAudioTrack(sr)

            true
        } catch (e: Exception) {
            debugLog("TTS init failed: ${e.message}")
            Log.e(TAG, "Init failed", e)
            onErrorCallback?.invoke("TTS初始化失败: ${e.message}")
            false
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(
            attr, format, bufLength,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track?.play()
    }

    /**
     * Generate and play speech. Runs on background thread.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: run {
            debugLog("speak: TTS not initialized")
            onErrorCallback?.invoke("TTS未初始化")
            return
        }

        // Melo model is pinyin-based — pass raw Chinese text directly.
        // The model's internal lexicon handles Chinese→pinyin conversion.
        val ttsInput = text
        debugLog("TTS input: '$ttsInput'")

        onStartCallback?.invoke()  // Notify that TTS is starting

        stopped = false
        withContext(Dispatchers.IO) {
            try {
                // Use generate() instead of generateWithCallback() to avoid
                // JNI/Kotlin compiler boxing mismatch on callback signature.
                val audio = engine.generate(
                    text = ttsInput,
                    sid = 4,
                    speed = 1.0f
                )

                // Diagnostic: check raw samples
                val rawSamples = audio.samples
                val absMax = rawSamples.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                val rms = kotlin.math.sqrt(rawSamples.map { it * it }.average())
                debugLog("Audio stats: samples=${rawSamples.size}, absMax=$absMax, rms=$rms")

                // Save WAV for diagnostic (write our own, not sherpa-onnx's buggy save)
                try {
                    val outDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val wavFile = java.io.File(outDir, "tts_test.wav")
                    saveWav(wavFile.absolutePath, rawSamples, engine.sampleRate())
                    debugLog("Saved test WAV: ${wavFile.absolutePath} (${wavFile.length()} bytes)")
                } catch (e: Exception) {
                    debugLog("Failed to save WAV: ${e.message}")
                }

                if (!stopped && track != null) {
                    // Ensure AudioTrack is in play state (may have been stopped)
                    track?.play()
                    val samples = audio.samples
                    val sampleRate = engine.sampleRate()
                    debugLog("Writing ${samples.size} samples, sr=$sampleRate")

                    // Convert float samples [-1,1] to 16-bit PCM with gain boost
                    val gain = 3.0f  // boost melo model's low output
                    val shortSamples = ShortArray(samples.size) { i ->
                        (samples[i] * 32767f * gain).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    val absMaxShort = shortSamples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                    debugLog("PCM gain=${gain}x, peak=$absMaxShort/32767")
                    track?.write(shortSamples, 0, shortSamples.size, AudioTrack.WRITE_BLOCKING)
                    // Wait for playback to finish: duration = samples / sampleRate
                    val durationMs = (samples.size.toLong() * 1000 / sampleRate)
                    delay(durationMs + 200)  // +200ms buffer
                }

                if (!stopped) {
                    track?.stop()
                    withContext(Dispatchers.Main) {
                        onDoneCallback?.invoke()
                    }
                }
                debugLog("TTS done: ${audio.samples.size} samples")
            } catch (e: Exception) {
                debugLog("TTS generation error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onErrorCallback?.invoke("语音合成失败: ${e.message}")
                }
            }
        }
    }

    /**
     * Generate and play speech WITHOUT triggering callbacks.
     * For automated testing — ASR stays active during playback.
     */
    suspend fun speakForTest(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: return

        stopped = false
        withContext(Dispatchers.IO) {
            try {
                val audio = engine.generate(text = text, sid = 4, speed = 1.0f)
                if (!stopped && track != null) {
                    track?.play()
                    val gain = 3.0f
                    val shortSamples = ShortArray(audio.samples.size) { i ->
                        (audio.samples[i] * 32767f * gain).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    track?.write(shortSamples, 0, shortSamples.size, AudioTrack.WRITE_BLOCKING)
                    val durationMs = (audio.samples.size.toLong() * 1000 / engine.sampleRate())
                    delay(durationMs + 200)
                }
                if (!stopped) track?.stop()
                // Save test WAV
                try {
                    val outDir = context.getExternalFilesDir(null) ?: context.filesDir
                    saveWav(File(outDir, "tts_test.wav").absolutePath, audio.samples, engine.sampleRate())
                } catch (_: Exception) {}
                debugLog("TEST speak done: ${audio.samples.size} samples")
            } catch (e: Exception) {
                debugLog("TEST speak error: ${e.message}")
            }
        }
    }

    fun stop() {
        stopped = true
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    fun release() {
        stop()
        try { track?.release() } catch (_: Exception) {}
        try { tts?.release() } catch (_: Exception) {}
        track = null
        tts = null
        debugLog("Sherpa TTS released")
    }

    /**
     * Extract TTS model from assets to internal storage.
     */
    private fun prepareModel(modelName: String): String? {
        val targetDir = File(context.filesDir, modelName)

        // Always re-extract to ensure clean files
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
            debugLog("Deleted old model cache")
        }

        return try {
            extractAssetDir(modelName, targetDir)
            targetDir.absolutePath
        } catch (e: Exception) {
            debugLog("Failed to extract TTS model: ${e.message}")
            null
        }
    }

    private fun extractAssetDir(assetPath: String, targetDir: File) {
        val assets = context.assets
        val entries = assets.list(assetPath) ?: return
        targetDir.mkdirs()

        for (entry in entries) {
            val childPath = "$assetPath/$entry"
            val childEntries = assets.list(childPath)

            if (childEntries != null && childEntries.isNotEmpty()) {
                extractAssetDir(childPath, File(targetDir, entry))
            } else {
                val outFile = File(targetDir, entry)
                assets.open(childPath).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    /**
     * Write float PCM samples to a WAV file (16-bit).
     */
    private fun saveWav(filename: String, samples: FloatArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val fileSize = 36 + dataSize
        val buf = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(fileSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)          // chunk size
        buf.putShort(1)         // PCM
        buf.putShort(1)         // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2) // byte rate
        buf.putShort(2)         // block align
        buf.putShort(16)        // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (s in samples) {
            buf.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        java.io.File(filename).writeBytes(buf.array())
    }
}
