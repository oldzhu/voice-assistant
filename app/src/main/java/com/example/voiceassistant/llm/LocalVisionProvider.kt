package com.example.voiceassistant.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Local/offline vision provider using Tesseract OCR.
 *
 * Flow:
 * 1. Decode base64 image to Bitmap
 * 2. Run Tesseract OCR (chi_sim + eng) to extract text
 * 3. If the prompt asks for description/summary (not pure extraction),
 *    send OCR text to the LLM for natural language composition
 * 4. If LLM backend unavailable, return raw OCR results
 *
 * Image pixels never leave the device.
 *
 * Tesseract traineddata is bundled in assets/tessdata/chi_sim.traineddata
 * and copied to internal storage on first use (~16MB).
 */
class LocalVisionProvider(
    private val context: Context,
    private val llmBackend: () -> LLMBackend?
) : VisionProvider {

    companion object {
        private const val TAG = "LocalVision"
        private const val TESS_LANG = "chi_sim+eng"

        /** Phrases that suggest summarization (not pure extraction). */
        private val SUMMARY_KEYWORDS = listOf(
            "总结", "summary", "summarize", "描述", "describe",
            "内容是什么", "说什么", "是什么", "怎么回事", "简述"
        )
    }

    private val tessApi: TessBaseAPI by lazy { initTesseract() }

    private fun initTesseract(): TessBaseAPI {
        val tessDataDir = ensureTrainedData()
        val api = TessBaseAPI()
        val ok = api.init(tessDataDir, TESS_LANG, TessBaseAPI.OEM_LSTM_ONLY)
        if (!ok) {
            Log.e(TAG, "Tesseract init FAILED for language=$TESS_LANG path=$tessDataDir")
        } else {
            Log.i(TAG, "Tesseract init OK (language=$TESS_LANG)")
        }
        return api
    }

    /**
     * Copy chi_sim.traineddata from APK assets → internal storage.
     * Returns the parent directory of the tessdata folder.
     */
    private fun ensureTrainedData(): String {
        val tessDir = File(context.filesDir, "tesseract")
        val tessDataDir = File(tessDir, "tessdata")
        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        val trainedData = File(tessDataDir, "chi_sim.traineddata")
        if (!trainedData.exists()) {
            try {
                context.assets.open("tessdata/chi_sim.traineddata").use { input ->
                    trainedData.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Traineddata extracted: ${trainedData.length()} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract chi_sim.traineddata from assets", e)
            }
        }

        return tessDir.absolutePath
    }

    override suspend fun describe(prompt: String, imageBase64: String): Result<String> {
        // 1. Decode base64 to Bitmap
        val bitmap: Bitmap
        try {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            if (bytes.isEmpty()) return Result.failure(Exception("Empty image data"))
            bitmap = BitmapFactory.decodeStream(ByteArrayInputStream(bytes))
                ?: return Result.failure(Exception("Failed to decode image"))
        } catch (e: Exception) {
            Log.e(TAG, "Base64 decode failed", e)
            return Result.failure(e)
        }

        // 2. Run OCR
        val ocrText: String
        try {
            val api = tessApi
            api.setImage(bitmap)
            ocrText = api.utF8Text?.trim() ?: ""
            api.clear()
            bitmap.recycle()
            Log.i(TAG, "OCR done: ${ocrText.length} chars → \"${ocrText.take(80)}...\"")
        } catch (e: Exception) {
            bitmap.recycle()
            Log.e(TAG, "OCR failed", e)
            return Result.failure(e)
        }

        // 3. If no text found at all
        if (ocrText.isBlank()) {
            return Result.success("此图片中没有识别到文字内容。")
        }

        // 4. Decide: pure extraction or needs LLM composition?
        val needsComposition = SUMMARY_KEYWORDS.any { prompt.contains(it) }

        if (!needsComposition) {
            // Pure text extraction — return OCR result directly
            return Result.success(ocrText)
        }

        // 5. Send OCR text to LLM for natural language description
        val llm = llmBackend()
        if (llm == null) {
            Log.w(TAG, "LLM backend unavailable, returning raw OCR")
            return Result.success(ocrText)
        }

        val compositionPrompt = buildString {
            append("以下是从图片中通过OCR提取的文字内容。请根据这些文字，用2-3句中文简洁描述图片的内容：")
            append("\n\n--- OCR文字 ---\n")
            append(ocrText.take(2000))  // cap to avoid token waste
            append("\n--- 结束 ---")
            append("\n\n注意：如果OCR文字杂乱无章，直接说'这张图片的主要内容是...'然后用一句话概括。")
        }

        return try {
            val result = llm.chat(compositionPrompt, emptyList())
            result.map { it.trim() }
        } catch (e: Exception) {
            Log.e(TAG, "LLM composition failed, returning raw OCR", e)
            Result.success(ocrText)
        }
    }

    /** Check if Tesseract is ready (traineddata available). */
    fun isReady(): Boolean {
        return try {
            ensureTrainedData()
            tessApi  // force init
            true
        } catch (e: Exception) {
            Log.w(TAG, "Tesseract not ready: ${e.message}")
            false
        }
    }
}
