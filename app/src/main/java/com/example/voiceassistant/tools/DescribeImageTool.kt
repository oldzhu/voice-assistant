package com.example.voiceassistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import com.example.voiceassistant.llm.VisionProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Tool: describe_photo — take a photo and describe it via VisionProvider.
 *
 * Flow:
 * 1. Launch camera intent to capture a photo
 * 2. Wait for the photo file to be written by the camera app
 * 3. Read the image, convert to base64
 * 4. Send to VisionProvider (remote cloud VLM or local Tesseract OCR)
 * 5. Return the description as TTS-ready text
 */
class DescribeImageTool(
    private val context: () -> Context,
    private val visionProvider: () -> VisionProvider?
) : Tool {
    override val name = "describe_photo"
    override val description = "拍照并用视觉AI描述照片内容。当用户说「看看这是什么」「描述一下」「拍张照看看」「我面前是什么」时调用。"
    override val parameters = mapOf(
        "prompt" to ToolParameter("string",
            "描述提示语，如'用中文详细描述这张图片的内容'",
            required = false)
    )

    companion object {
        private const val TAG = "DescribeImageTool"
        private const val PHOTO_TIMEOUT_MS = 30000L
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val prompt = (args["prompt"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: "用中文简洁描述这张图片的内容，2-3句话即可"

        val ctx = context()
        val provider = visionProvider() ?: return "错误：视觉模块未初始化"

        // 1. Create temp file for the photo
        val photoDir = File(ctx.filesDir, "photos").also { it.mkdirs() }
        val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photoFile)
        } catch (e: Exception) {
            return "错误：无法创建照片文件：${e.message}"
        }

        // 2. Launch camera intent
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            Log.i(TAG, "Camera intent launched, waiting for photo at ${photoFile.absolutePath}")
        } catch (e: Exception) {
            return "错误：无法启动相机：${e.message}"
        }

        // 3. Wait for photo file to be written
        val fileReady = withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
            while (!photoFile.exists() || photoFile.length() == 0L) {
                delay(500)
                Log.d(TAG, "Waiting for photo... exists=${photoFile.exists()} size=${photoFile.length()}")
            }
            delay(1000)  // let camera finish writing
            true
        }

        if (fileReady != true) {
            return "错误：等待照片超时（${PHOTO_TIMEOUT_MS / 1000}秒）。请确认你已拍摄照片。"
        }

        Log.i(TAG, "Photo captured: ${photoFile.length()} bytes")

        // 4. Read and convert to base64
        val base64: String = try {
            val bytes = photoFile.readBytes()
            if (bytes.isEmpty()) return "错误：照片文件为空"
            if (bytes.size > 10 * 1024 * 1024) return "错误：照片太大（${bytes.size / 1024}KB），请拍小一点"
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            return "错误：读取照片失败：${e.message}"
        }

        Log.i(TAG, "Image base64: ${base64.length} chars (${"%.1f".format(base64.length / 1024.0)}KB)")

        // 5. Send to VisionProvider
        return try {
            val result = provider.describe(prompt, base64)
            result.getOrElse { ex -> "图片识别失败：${ex.message}" }
        } catch (e: Exception) {
            "图片识别出错：${e.message}"
        } finally {
            try { photoFile.delete() } catch (_: Exception) {}
        }
    }
}
