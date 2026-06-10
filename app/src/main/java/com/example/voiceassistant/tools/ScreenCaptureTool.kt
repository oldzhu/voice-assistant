package com.example.voiceassistant.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.voiceassistant.llm.CloudLLMBackend
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import java.io.ByteArrayOutputStream

/**
 * Tool: capture_screen — capture the device screen and describe its content.
 *
 * Flow:
 * 1. Request screen capture via MediaProjection (shows system permission dialog)
 * 2. After user grants, capture the screen as a bitmap
 * 3. Convert to JPEG base64
 * 4. Send to vision LLM with OCR-optimized prompt
 * 5. Return extracted text + visual description
 *
 * Designed for voice interaction:
 * - "截屏识别" → capture and extract text
 * - "read what's on screen" → capture and describe
 * - "capture screen" → full capture with description
 */
class ScreenCaptureTool(
    private val context: () -> Context,
    private val llmBackend: () -> CloudLLMBackend?
) : Tool {
    override val name = "capture_screen"
    override val description = "截取当前屏幕并用视觉AI提取文字和描述内容。" +
        "当用户说「截屏」「识别屏幕」「屏幕上的文字是什么」「read the screen」时调用。" +
        "注意：第一次使用会弹出系统权限对话框，用户需要允许。"
    override val parameters = mapOf(
        "mode" to ToolParameter("string",
            "模式：text(只提取文字), describe(只描述画面), full(提取文字+描述)。默认 full",
            required = false,
            enum = listOf("text", "describe", "full")
        )
    )

    companion object {
        private const val TAG = "ScreenCaptureTool"
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val mode = (args["mode"] as? String)?.trim() ?: "full"
        val ctx = context()
        val backend = llmBackend() ?: return "错误：LLM 后端未初始化"

        // 1. Capture screen
        Log.i(TAG, "Requesting screen capture (mode=$mode)...")
        val bitmap: Bitmap = ScreenCaptureManager.requestCapture(ctx)
            ?: return "屏幕截取未完成。你可能取消了权限请求，或截取超时。"

        Log.i(TAG, "Screen captured: ${bitmap.width}x${bitmap.height}")

        // 2. Convert to JPEG base64 (compress to ~500KB for API limits)
        val base64: String = try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val bytes = stream.toByteArray()
            bitmap.recycle()
            if (bytes.isEmpty()) return "错误：截图数据为空"
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            bitmap.recycle()
            return "错误：图片编码失败：${e.message}"
        }

        Log.i(TAG, "Image base64: ${base64.length} chars")

        // 3. Build vision prompt based on mode
        val prompt = when (mode) {
            "text" -> "请提取这张屏幕截图中的所有文字内容。按从上到下、从左到右的顺序列出。忽略图标和按钮，只提取文本。用中文回复。"
            "describe" -> "请用中文简洁描述这张屏幕截图的内容和布局，2-3句话。"
            else -> "请用中文描述这张屏幕截图：(1)先提取所有可见文字，(2)再简述整体内容和布局。"
        }

        // 4. Send to vision API
        return try {
            val result = backend.describeImage(prompt, base64)
            result.getOrElse { ex -> "屏幕识别失败：${ex.message}" }
        } catch (e: Exception) {
            "屏幕识别出错：${e.message}"
        }
    }
}
