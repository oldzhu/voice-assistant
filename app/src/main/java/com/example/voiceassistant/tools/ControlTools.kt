package com.example.voiceassistant.tools

import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter

/**
 * Tool: set_speech_rate — adjusts TTS playback speed via voice command.
 *
 * LLM prompt: "把语速调到1.5倍" → LLM calls set_speech_rate(rate=1.5)
 * TTS response: "语速已调到1.5倍（较快）"
 */
class SetSpeechRateTool(
    private val onSetRate: (Float) -> Unit
) : Tool {
    override val name = "set_speech_rate"
    override val description = "调整语音播报语速。1.0是正常速度，小于1是慢速，大于1是快速。范围0.5到2.5。"
    override val parameters = mapOf(
        "rate" to ToolParameter(
            type = "number",
            description = "语速，范围0.5-2.5，1.0是正常速度",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val rate = (args["rate"] as? Number)?.toFloat()
            ?: return "错误：缺少语速参数"
        val clamped = rate.coerceIn(0.5f, 2.5f)
        onSetRate(clamped)
        val label = when {
            clamped < 0.8f -> "慢速"
            clamped < 1.2f -> "正常"
            clamped < 1.8f -> "较快"
            else -> "快速"
        }
        return "语速已调到${clamped}倍（${label}）"
    }
}

/**
 * Tool: stop_listening — pauses ASR and stops active listening.
 *
 * LLM prompt: "别听了" / "暂停" → LLM calls stop_listening()
 */
class StopListeningTool(
    private val onStop: () -> Unit
) : Tool {
    override val name = "stop_listening"
    override val description = "停止语音监听，暂停识别。当用户说'别听了'、'暂停'、'停下'等时调用。"
    override val parameters = emptyMap<String, ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): String {
        onStop()
        return "已停止监听。说'开始监听'或点按按钮可以恢复。"
    }
}

/**
 * Tool: start_listening — resumes ASR after stop.
 *
 * LLM prompt: "开始听" / "继续" → LLM calls start_listening()
 */
class StartListeningTool(
    private val onStart: () -> Unit
) : Tool {
    override val name = "start_listening"
    override val description = "开始语音监听，恢复识别。当用户说'开始听'、'继续'、'打开监听'等时调用。"
    override val parameters = emptyMap<String, ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): String {
        onStart()
        return "已开始监听，请说话。"
    }
}

/**
 * Tool: set_barge_in_mode — changes the interrupt/barge-in strategy.
 *
 * LLM prompt: "切换成不打断模式" → LLM calls set_barge_in_mode(mode="off")
 */
class SetBargeInModeTool(
    private val onSetMode: (String) -> Unit
) : Tool {
    override val name = "set_barge_in_mode"
    override val description = "设置语音打断模式。off=不打断(助手说话时暂停识别), on=允许打断, keyword=关键词打断。"
    override val parameters = mapOf(
        "mode" to ToolParameter(
            type = "string",
            description = "打断模式",
            enum = listOf("off", "on", "keyword")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val mode = args["mode"] as? String ?: return "错误：缺少模式参数"
        onSetMode(mode)
        val label = when (mode) {
            "off" -> "不打断"
            "on" -> "允许打断"
            "keyword" -> "关键词打断"
            else -> mode
        }
        return "打断模式已切换为：${label}"
    }
}

/**
 * Tool: clear_history — clears conversation history.
 *
 * LLM prompt: "忘记之前的对话" / "清空记录" → LLM calls clear_history()
 */
class ClearHistoryTool(
    private val onClear: () -> Unit
) : Tool {
    override val name = "clear_history"
    override val description = "清空对话历史记录。当用户说'清空记录'、'忘记之前的对话'、'重新开始'等时调用。"
    override val parameters = emptyMap<String, ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): String {
        onClear()
        return "已清空对话记录，我们重新开始。"
    }
}
