package com.example.voiceassistant.tools

import android.util.Log
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter

/**
 * Tool: tap_screen — tap on screen by coordinates or by finding text on screen.
 *
 * Two modes:
 * 1. By coordinates: tap_screen(x=540, y=960)
 * 2. By text match: tap_screen(text="搜索") — finds element containing text and taps its center
 */
class ScreenTapTool : Tool {
    override val name = "tap_screen"
    override val description = "点击屏幕上的指定位置或元素。" +
        "可以传入坐标(x,y)精确点击，或传入text参数根据文字匹配点击。" +
        "当用户说「点击XX」「点XX」「按XX按钮」时调用。优先使用text匹配而非坐标。"
    override val parameters = mapOf(
        "x" to ToolParameter("number", "点击的X坐标（像素）。与text二选一", required = false),
        "y" to ToolParameter("number", "点击的Y坐标（像素）。与text二选一", required = false),
        "text" to ToolParameter("string", "要点击的元素文字（模糊匹配）。与坐标二选一，优先使用此方式", required = false)
    )

    companion object { private const val TAG = "ScreenTap" }

    override suspend fun execute(args: Map<String, Any?>): String {
        if (!GestureManager.isAvailable) return "错误：无障碍服务未开启，无法点击。请到设置→无障碍→猪头助手中开启。"

        val text = (args["text"] as? String)?.trim()

        // Mode 1: text match
        if (!text.isNullOrBlank()) {
            val el = GestureManager.findElement(text)
            if (el != null) {
                val x = el.centerX().toFloat()
                val y = el.centerY().toFloat()
                Log.i(TAG, "Tap by text '$text' → (${el.centerX()},${el.centerY()}) — ${el.summary()}")
                return GestureManager.tap(x, y).fold(
                    onSuccess = { "已点击「${el.text.ifBlank { el.contentDesc }}」" },
                    onFailure = { "点击失败：${it.message}" }
                )
            }
            return "未找到包含「$text」的可点击元素。试试用 get_screen_elements 查看当前屏幕有哪些元素。"
        }

        // Mode 2: coordinates
        val x = (args["x"] as? Number)?.toFloat()
        val y = (args["y"] as? Number)?.toFloat()
        if (x == null || y == null) return "错误：请提供坐标(x,y)或要点击的文字(text)。"

        return GestureManager.tap(x, y).fold(
            onSuccess = { "已点击(${x.toInt()},${y.toInt()})" },
            onFailure = { "点击失败：${it.message}" }
        )
    }
}

/**
 * Tool: type_text — enter text into the currently focused input field.
 */
class TypeTextTool : Tool {
    override val name = "type_text"
    override val description = "在当前焦点输入框中输入文字。需要先点击输入框使其获得焦点。" +
        "当用户说「输入XX」「搜索XX」「打字XX」时调用。"
    override val parameters = mapOf(
        "text" to ToolParameter("string", "要输入的文字内容", required = true)
    )

    companion object { private const val TAG = "TypeText" }

    override suspend fun execute(args: Map<String, Any?>): String {
        if (!GestureManager.isAvailable) return "错误：无障碍服务未开启，无法输入文字。"

        val text = (args["text"] as? String)?.trim() ?: return "错误：请提供要输入的文字"
        return GestureManager.typeText(text).fold(
            onSuccess = { "已输入「${text.take(30)}${if (text.length > 30) "..." else ""}」" },
            onFailure = { "输入失败：${it.message}" }
        )
    }
}

/**
 * Tool: swipe_screen — scroll in a direction or from point to point.
 */
class SwipeScreenTool : Tool {
    override val name = "swipe_screen"
    override val description = "屏幕滑动操作。可用方向：up(向上滑/上翻)、down(向下滑/下翻)、left(左滑)、right(右滑)。" +
        "也可指定起点和终点坐标精确滑动。当用户说「上滑」「翻页」「往下拉」「滑到底部」等时调用。"
    override val parameters = mapOf(
        "direction" to ToolParameter("string", "滑动方向：up, down, left, right。与坐标二选一",
            required = false, enum = listOf("up", "down", "left", "right")),
        "x1" to ToolParameter("number", "起点X坐标（精确模式）", required = false),
        "y1" to ToolParameter("number", "起点Y坐标（精确模式）", required = false),
        "x2" to ToolParameter("number", "终点X坐标（精确模式）", required = false),
        "y2" to ToolParameter("number", "终点Y坐标（精确模式）", required = false)
    )

    companion object { private const val TAG = "SwipeScreen" }

    override suspend fun execute(args: Map<String, Any?>): String {
        if (!GestureManager.isAvailable) return "错误：无障碍服务未开启，无法滑动。"

        // Mode 1: direction
        val direction = (args["direction"] as? String)?.trim()?.lowercase()
        if (!direction.isNullOrBlank()) {
            return GestureManager.swipeDirection(direction).fold(
                onSuccess = { "已向${mapDirection(direction)}滑动" },
                onFailure = { "滑动失败：${it.message}" }
            )
        }

        // Mode 2: explicit coordinates
        val x1 = (args["x1"] as? Number)?.toFloat()
        val y1 = (args["y1"] as? Number)?.toFloat()
        val x2 = (args["x2"] as? Number)?.toFloat()
        val y2 = (args["y2"] as? Number)?.toFloat()

        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return "错误：请指定方向(up/down/left/right)或提供起点终点坐标(x1,y1,x2,y2)"
        }

        return GestureManager.swipe(x1, y1, x2, y2).fold(
            onSuccess = { "已从(${x1.toInt()},${y1.toInt()})滑到(${x2.toInt()},${y2.toInt()})" },
            onFailure = { "滑动失败：${it.message}" }
        )
    }

    private fun mapDirection(d: String): String = when (d) {
        "up" -> "上"
        "down" -> "下"
        "left" -> "左"
        "right" -> "右"
        else -> d
    }
}
