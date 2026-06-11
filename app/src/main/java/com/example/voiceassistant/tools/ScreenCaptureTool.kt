package com.example.voiceassistant.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import com.example.voiceassistant.llm.VisionProvider
import java.io.ByteArrayOutputStream

/**
 * Tool: capture_screen — capture the device screen and describe its content.
 *
 * Flow:
 * 1. Request screen capture via MediaProjection (shows system permission dialog)
 * 2. After user grants, capture the screen as a bitmap
 * 3. Convert to JPEG base64
 * 4. Send to VisionProvider (remote cloud VLM or local Tesseract OCR)
 * 5. Return extracted text + visual description
 *
 * Designed for voice interaction while browsing other apps:
 * - "帮我总结这一页" → capture and summarize
 * - "提取这页的文字" → capture and extract text
 * - "这页在说什么" → capture and describe content
 */
class ScreenCaptureTool(
    private val context: () -> Context,
    private val visionProvider: () -> VisionProvider?
) : Tool {
    override val name = "capture_screen"
    override val description = "截取当前屏幕并用视觉AI提取文字和描述内容。" +
        "当用户说「截屏」「识别屏幕」「帮我看看这页」「总结这页」「提取文字」「屏幕上的文字是什么」「read the screen」时调用。" +
        "特别适用于用户正在浏览其他app时，想了解当前页面内容的场景。比如用户在看浏览器/微信/新闻等，" +
        "说「帮我总结这一页」就可以截屏识别。注意：第一次使用会弹出系统权限对话框。"
    override val parameters = mapOf(
        "mode" to ToolParameter("string",
            "模式：text(只提取文字), describe(只描述画面), full(提取文字+描述)。默认 full。注意：当用户说「总结」时用describe，" +
            "说「提取文字」时用text，说「看看这页」时用full",
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
        val provider = visionProvider() ?: return "错误：视觉模块未初始化"

        // 1. Capture screen — try accessibility first (silent), fallback to MediaProjection (needs permission dialog)
        Log.i(TAG, "Requesting screen capture (mode=$mode)...")

        var bitmap: Bitmap? = null
        var usedMethod: String = "none"

        // Method A: AccessibilityService.takeScreenshot() — silent, no dialog
        if (AccessibilityCaptureManager.isAvailable) {
            Log.i(TAG, "Trying accessibility screenshot...")
            bitmap = AccessibilityCaptureManager.requestCapture()
            if (bitmap != null) usedMethod = "accessibility"
        }

        // Method B: MediaProjection — shows permission dialog
        if (bitmap == null) {
            Log.i(TAG, "Falling back to MediaProjection...")
            bitmap = ScreenCaptureManager.requestCapture(ctx)
            if (bitmap != null) usedMethod = "media_projection"
        }

        if (bitmap == null) {
            return if (AccessibilityCaptureManager.isAvailable) {
                "屏幕截取失败。请检查无障碍服务是否正常运行。"
            } else {
                "屏幕截取未完成。\n\n💡 提示：首次使用需要在系统「设置 → 无障碍 → 猪头助手」中开启无障碍服务，之后就可以静默截屏了，不用再弹权限框！"
            }
        }

        Log.i(TAG, "Screen captured ($usedMethod): ${bitmap.width}x${bitmap.height}")

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
            "describe" -> "请用中文简洁总结这张屏幕截图的主要内容，2-4句话。包括：这是什么页面/应用、主要内容是什么。"
            else -> "请用中文描述这张屏幕截图：(1)先提取所有可见文字，(2)再简述整体内容和布局。"
        }

        // 4. Send to VisionProvider
        return try {
            val result = provider.describe(prompt, base64)
            result.getOrElse { ex -> "屏幕识别失败：${ex.message}" }
        } catch (e: Exception) {
            "屏幕识别出错：${e.message}"
        }
    }
}
