package com.example.voiceassistant.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sherpa-ONNX offline streaming speech recognition engine.
 * Uses OnlineRecognizer (Paraformer) with built-in endpoint detection.
 *
 * Model: sherpa-onnx-streaming-paraformer-bilingual-zh-en
 *   - Bilingual Chinese + English
 *   - Paraformer architecture, int8 quantized
 *   - Streaming (real-time incremental results)
 *   - Loaded via AssetManager
 *
 * NOTE: Zipformer transducer model (bilingual-zh-en-2023-02-20) fails with
 * "protobuf parsing failed" on sherpa-onnx-jni v1.13.2. Paraformer works.
 */
class SherpaAsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-streaming-paraformer-bilingual-zh-en"
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [ASR] $msg\n") }
        } catch (_: Exception) {}
    }

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var recordingJob: Job? = null

    // Switchable audio source: VOICE_COMMUNICATION (user mode, AEC on) vs VOICE_RECOGNITION (test mode, permissive)
    @Volatile var audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    private var onResultCallback: ((String) -> Unit)? = null
    private var onPartialCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    @Volatile private var isRunning = false

    fun init(
        onResult: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean {
        this.onResultCallback = onResult
        this.onPartialCallback = onPartial
        this.onErrorCallback = onError

        return try {
            val modelDir = MODEL_DIR  // asset-relative

            debugLog("Loading ASR from assets: $modelDir")

            val asrConfig = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                ),
                modelConfig = OnlineModelConfig(
                    paraformer = OnlineParaformerModelConfig(
                        encoder = "$modelDir/encoder.int8.onnx",
                        decoder = "$modelDir/decoder.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 1,
                    debug = true,
                    provider = "cpu",
                    modelType = "zipformer",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
            )

            recognizer = OnlineRecognizer(context.assets, asrConfig)
            debugLog("OnlineRecognizer created (paraformer bilingual zh-en, int8)")

            true
        } catch (e: Exception) {
            debugLog("Sherpa init failed: ${e.message}")
            Log.e(TAG, "Init failed", e)
            onError("语音引擎初始化失败: ${e.message}")
            false
        }
    }

    fun startListening() {
        if (isRunning) return
        if (recognizer == null) {
            debugLog("startListening: engine not initialized")
            return
        }

        isRunning = true
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        try {
            val src = audioSource
            debugLog("AudioRecord source: ${if (src == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"}")
            audioRecord = AudioRecord(
                src,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                debugLog("AudioRecord init failed")
                onErrorCallback?.invoke("麦克风初始化失败")
                isRunning = false
                return
            }

            audioRecord?.startRecording()
            debugLog("AudioRecord started, buffer=$bufferSize")

            // AEC: only for VOICE_COMMUNICATION mode (user mode). Skip in test mode.
            if (src == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                        aec?.enabled = true
                        debugLog("AEC enabled")
                    } else {
                        debugLog("AEC not available on this device")
                    }
                } catch (e: Exception) {
                    debugLog("AEC setup failed: ${e.message}")
                }
            } else {
                debugLog("AEC skipped (permissive/test mode)")
            }

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                streamingRecognitionLoop(bufferSize)
            }
            debugLog("Streaming ASR listening started")
        } catch (e: Exception) {
            debugLog("startListening failed: ${e.message}")
            onErrorCallback?.invoke("启动录音失败: ${e.message}")
            isRunning = false
        }
    }

    private suspend fun streamingRecognitionLoop(bufferSize: Int) {
        val rec = recognizer ?: return
        val shortBuffer = ShortArray(bufferSize / 2)
        var stream: OnlineStream? = null
        var lastPartialText = ""
        var hasSpeech = false

        try {
            stream = rec.createStream()
        } catch (e: Exception) {
            debugLog("Failed to create stream: ${e.message}")
            isRunning = false
            return
        }

        while (isRunning) {
            val nread = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
            if (nread <= 0) continue

            val floatSamples = FloatArray(nread) { shortBuffer[it] / 32768f }

            try {
                stream.acceptWaveform(floatSamples, SAMPLE_RATE)

                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }

                val result = rec.getResult(stream)
                if (result.text != lastPartialText) {
                    lastPartialText = result.text
                    if (lastPartialText.isNotBlank()) {
                        hasSpeech = true
                        withContext(Dispatchers.Main) {
                            onPartialCallback?.invoke(lastPartialText)
                        }
                    }
                }

                // Only consider endpoint after actual speech detected
                if (hasSpeech && rec.isEndpoint(stream)) {
                    debugLog("Endpoint detected, finalizing...")
                    stream.inputFinished()
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    val finalResult = rec.getResult(stream)
                    debugLog("Final result: '${finalResult.text}'")
                    if (finalResult.text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            onResultCallback?.invoke(finalResult.text)
                        }
                    }
                    rec.reset(stream)
                    lastPartialText = ""
                    hasSpeech = false
                }
            } catch (e: Exception) {
                debugLog("Recognition error: ${e.message}")
                try { rec.reset(stream) } catch (_: Exception) {}
                lastPartialText = ""
            }
        }

        try { stream.release() } catch (_: Exception) {}
    }

    fun stop() {
        isRunning = false
        recordingJob?.cancel()
        try {
            aec?.enabled = false
            aec?.release()
            aec = null
        } catch (_: Exception) {}
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            debugLog("stop error: ${e.message}")
        }
        debugLog("Streaming ASR stopped")
    }

    fun release() {
        stop()
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        debugLog("Streaming ASR released")
    }

    fun isActive(): Boolean = isRunning
}
