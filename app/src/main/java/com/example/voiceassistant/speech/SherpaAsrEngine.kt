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
 * Sherpa-ONNX offline speech recognition engine.
 * Uses VAD for speech detection + Zipformer CTC for recognition.
 */
class SherpaAsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaAsrEngine"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "sherpa-onnx-zipformer-ctc-small-zh-int8-2025-07-16"
        private const val VAD_MODEL = "silero_vad.onnx"
    }

    private fun debugLog(msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(context.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} [ASR] $msg\n") }
        } catch (_: Exception) {}
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var recordingJob: Job? = null

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
            val vadModelPath = VAD_MODEL  // asset-relative

            debugLog("Loading ASR model from assets: $modelDir")

            // Create ASR recognizer
            val asrConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                ),
                modelConfig = OfflineModelConfig(
                    zipformerCtc = OfflineZipformerCtcModelConfig(
                        model = "$modelDir/model.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    debug = true,
                    provider = "cpu",
                    numThreads = 1,
                ),
            )

            recognizer = OfflineRecognizer(context.assets, asrConfig)
            debugLog("ASR recognizer created")

            // Create VAD
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModelPath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.3f,
                    maxSpeechDuration = 15f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )

            vad = Vad(context.assets, vadConfig)
            debugLog("VAD created")

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
        if (recognizer == null || vad == null) {
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
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
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

            // Acoustic Echo Cancellation — prevent TTS feeding back into ASR
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

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                processAudioLoop(bufferSize)
            }
            debugLog("Sherpa ASR listening started")
        } catch (e: Exception) {
            debugLog("startListening failed: ${e.message}")
            onErrorCallback?.invoke("启动录音失败: ${e.message}")
            isRunning = false
        }
    }

    private suspend fun processAudioLoop(bufferSize: Int) {
        val rec = recognizer ?: return
        val v = vad ?: return
        val shortBuffer = ShortArray(bufferSize / 2)

        while (isRunning) {
            val nread = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
            if (nread <= 0) continue

            // Convert short[] to float[]
            val floatSamples = FloatArray(nread) { shortBuffer[it] / 32768f }

            // Feed to VAD
            v.acceptWaveform(floatSamples)

            // Check for speech segments
            while (!v.empty() && isRunning) {
                val segment = v.front()
                v.pop()

                if (segment.samples.isNotEmpty()) {
                    val text = recognizeSegment(rec, segment.samples)
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            onResultCallback?.invoke(text)
                        }
                    }
                }
            }
        }
    }

    private fun recognizeSegment(rec: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            stream.release()
            result.text
        } catch (e: Exception) {
            debugLog("recognizeSegment error: ${e.message}")
            ""
        }
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
        vad?.reset()
        debugLog("Sherpa ASR stopped")
    }

    fun release() {
        stop()
        try { recognizer?.release() } catch (_: Exception) {}
        try { vad?.release() } catch (_: Exception) {}
        recognizer = null
        vad = null
        debugLog("Sherpa ASR released")
    }

    fun isActive(): Boolean = isRunning

    /**
     * Extract model directory from assets to internal storage.
     */
    private fun prepareModel(modelName: String): String? {
        val targetDir = File(context.filesDir, modelName)
        if (targetDir.exists() && targetDir.isDirectory && targetDir.list()?.isNotEmpty() == true) {
            debugLog("Model already at: ${targetDir.absolutePath}")
            return targetDir.absolutePath
        }

        return try {
            extractAssetDir(modelName, targetDir)
            targetDir.absolutePath
        } catch (e: Exception) {
            debugLog("Failed to extract model $modelName: ${e.message}")
            null
        }
    }

    /**
     * Extract a single file from assets.
     */
    private fun prepareFile(fileName: String): String? {
        val target = File(context.filesDir, fileName)
        if (target.exists() && target.length() > 0) {
            return target.absolutePath
        }

        return try {
            context.assets.open(fileName).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target.absolutePath
        } catch (e: Exception) {
            debugLog("Failed to extract file $fileName: ${e.message}")
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
}
