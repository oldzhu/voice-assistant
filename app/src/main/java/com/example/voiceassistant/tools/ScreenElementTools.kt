package com.example.voiceassistant.tools

import android.util.Log
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter

/**
 * Tool: get_screen_elements — list interactive UI elements on the current screen.
 *
 * Returns a list of clickable/editable/scrollable elements with text, coordinates, and tags.
 * The LLM uses this to decide: what to tap, where to type, whether results are loaded.
 */
class ScreenElementsTool : Tool {
    override val name = "get_screen_elements"
    override val description = "获取当前屏幕上所有可交互元素的列表（按钮、输入框、可点击文字等）。" +
        "返回每个元素的文字、坐标、类型标签。在操作app前应调用此工具了解当前屏幕布局，确定下一步操作目标。" +
        "当需要了解「当前屏幕有什么」「有哪些按钮」「搜索结果出来了吗」时调用。"
    override val parameters = mapOf<String, ToolParameter>()  // no params needed

    companion object { private const val TAG = "ScreenElements" }

    override suspend fun execute(args: Map<String, Any?>): String {
        if (!GestureManager.isAvailable) return "错误：无障碍服务未开启，无法读取屏幕元素。"

        val result = GestureManager.getInteractiveElements(maxResults = 30)
        return result.fold(
            onSuccess = { elements ->
                if (elements.isEmpty()) {
                    "屏幕上未检测到可交互元素。可能页面正在加载中，试试 wait_for_element 等待加载完成。"
                } else {
                    buildString {
                        appendLine("当前屏幕共${elements.size}个可交互元素：")
                        elements.forEachIndexed { i, el ->
                            appendLine("  ${i + 1}. ${el.summary()}")
                            // For clickable elements with text, add bounds detail
                            if (el.isClickable && el.text.isNotBlank()) {
                                appendLine("     坐标(${el.centerX()},${el.centerY()}) ${el.bounds.width()}x${el.bounds.height()}")
                            }
                        }
                    }
                }
            },
            onFailure = { "读取屏幕元素失败：${it.message}" }
        )
    }
}

/**
 * Tool: wait_for_element — wait until specific text/element appears on screen.
 *
 * Used when navigating between screens (app loading, search results, page transitions).
 * Blocks for up to [timeout] seconds then returns success or timeout.
 */
class WaitForElementTool : Tool {
    override val name = "wait_for_element"
    override val description = "等待屏幕上出现包含指定文字的元素。用于等待页面加载完成。" +
        "例如打开app后等「搜索」出现、搜索后等「结果」出现、提交后等「成功」出现。" +
        "当执行操作后需要等待页面跳转/加载时调用。"
    override val parameters = mapOf(
        "text" to ToolParameter("string", "要等待出现的文字（模糊匹配）", required = true),
        "timeout" to ToolParameter("number", "超时秒数，默认5秒", required = false)
    )

    companion object { private const val TAG = "WaitElement" }

    override suspend fun execute(args: Map<String, Any?>): String {
        if (!GestureManager.isAvailable) return "错误：无障碍服务未开启。"

        val text = (args["text"] as? String)?.trim() ?: return "错误：请提供要等待的文字"
        val timeoutSec = ((args["timeout"] as? Number)?.toDouble() ?: 5.0)
            .coerceIn(1.0, 15.0)  // Cap at 15s
        val timeoutMs = (timeoutSec * 1000).toLong()

        Log.i(TAG, "Waiting for '$text' (timeout=${timeoutSec}s)...")
        val result = GestureManager.waitForElement(text, timeoutMs)
        return result.fold(
            onSuccess = { el ->
                "已找到「${el.text.ifBlank { el.contentDesc }}」在坐标(${el.centerX()},${el.centerY()})，" +
                    "${if (el.isClickable) "可点击" else ""}${if (el.isEditable) "可输入" else ""}"
            },
            onFailure = { "等待超时：${timeoutSec.toInt()}秒内未在屏幕上找到「$text」。可能是页面未加载或应用不支持无障碍读取。" }
        )
    }
}
