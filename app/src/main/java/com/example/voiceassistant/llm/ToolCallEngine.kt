package com.example.voiceassistant.llm

import android.util.Log
import com.google.gson.Gson

/**
 * Orchestrates the LLM ↔ tool execution loop.
 *
 * Flow:
 * 1. Send user message + history + tool defs to LLM via [CloudLLMBackend.chatWithTools].
 * 2. If LLM returns text → done, return text to caller.
 * 3. If LLM returns function_call → execute tool via [ToolRegistry.execute],
 *    feed result back to LLM, repeat.
 * 4. Loop bounded by [maxTurns] to prevent infinite chains.
 *
 * Usage:
 *   val engine = ToolCallEngine(backend, registry)
 *   val result = engine.chat("把语速调到1.5倍", history)
 *   // result: Result.success("已将语速调到1.5倍")
 */
class ToolCallEngine(
    private val llmBackend: CloudLLMBackend,
    private val toolRegistry: ToolRegistry,
    private val maxTurns: Int = 5
) {
    companion object {
        private const val TAG = "ToolCallEngine"
    }

    private val gson = Gson()

    /**
     * Process a user message with tool calling support.
     *
     * Builds the full message list (system prompt + history + user message),
     * then enters the tool-calling loop. Each LLM response is inspected:
     * text → return immediately; function_call → execute tool → continue.
     *
     * @param userMessage The user's input text (already transcribed by ASR).
     * @param history Previous chat messages (user + assistant pairs).
     * @return Final text response from LLM after all tool calls are resolved.
     */
    suspend fun chat(
        userMessage: String,
        history: List<LLMBackend.ChatMessage>,
        memoryContext: String = ""
    ): Result<String> {
        // Build initial message list as Maps — supports tool-role messages
        // that don't fit the simple ChatMessage model.
        val messages = mutableListOf<Map<String, Any?>>()

        // System prompt — tells LLM it has tools and how to use them
        messages.add(mapOf(
            "role" to "system",
            "content" to buildSystemPrompt(memoryContext)
        ))

        // Conversation history (last 10 messages to manage context)
        val recentHistory = history.takeLast(10)
        for (msg in recentHistory) {
            messages.add(mapOf("role" to msg.role, "content" to msg.content))
        }

        // Current user message
        messages.add(mapOf("role" to "user", "content" to userMessage))

        // --- Tool calling loop ---
        var turn = 0
        while (turn < maxTurns) {
            turn++
            val toolCount = toolRegistry.getAll().size
            Log.i(TAG, "Turn $turn: messages=${messages.size}, tools=$toolCount")

            val result = llmBackend.chatWithTools(messages, toolRegistry.getFunctionDefs())
            if (result.isFailure) {
                Log.e(TAG, "Turn $turn: LLM call failed: ${result.exceptionOrNull()?.message}")
                return Result.failure(result.exceptionOrNull() ?: Exception("LLM call failed"))
            }

            val toolResult = result.getOrNull() ?: continue

            // --- Case 1: Text response → we're done ---
            if (toolResult.textResponse != null) {
                Log.i(TAG, "Turn $turn: text response (${toolResult.textResponse.length} chars)")
                return Result.success(toolResult.textResponse)
            }

            // --- Case 2: Function call(s) → execute all tools ---
            // LLM may emit multiple tool_calls in one response (e.g., DeepSeek).
            // Execute ALL of them before feeding results back, to avoid orphaned tool_call_ids.
            val allCalls = toolResult.functionCalls
                ?: toolResult.functionCall?.let { listOf(it) }
                ?: run {
                    Log.w(TAG, "Turn $turn: no text and no function_call — breaking")
                    return Result.success("我暂时无法处理这个请求")
                }

            Log.i(TAG, "Turn $turn: LLM calls → ${allCalls.map { it.first }.joinToString()}")

            // Add the raw assistant message first (contains all tool_calls)
            val rawMsg = toolResult.rawAssistantMessage
            if (rawMsg != null) {
                messages.add(rawMsg)
            }

            // Execute all tools, collect outputs
            val toolOutputs = mutableListOf<Pair<String, String>>() // (tool_call_id, output)
            for ((funcName, funcArgs) in allCalls) {
                val output = toolRegistry.execute(funcName, funcArgs)
                Log.i(TAG, "Turn $turn: $funcName → ${output.take(80)}")

                // Timeout check
                if (output.contains("超时") || output.contains("Timed out") ||
                    output.contains("Unable to resolve host") || output.contains("connect timed out")) {
                    Log.w(TAG, "Turn $turn: $funcName timeout → breaking")
                    // Still add the error as tool output so the LLM sees it
                    toolOutputs.add(Pair("call_${funcName}_$turn", output))
                    break
                }

                // Extract tool_call_id from raw message
                @Suppress("UNCHECKED_CAST")
                val tcs = rawMsg?.get("tool_calls") as? List<Map<String, Any?>>
                val callId = tcs?.find {
                    @Suppress("UNCHECKED_CAST")
                    (it?.get("function") as? Map<String, Any?>)?.get("name") == funcName
                }?.get("id") as? String ?: "call_${funcName}_$turn"

                toolOutputs.add(Pair(callId, output))
            }

            // Add all tool result messages
            for ((callId, output) in toolOutputs) {
                messages.add(mapOf(
                    "role" to "tool",
                    "tool_call_id" to callId,
                    "content" to output
                ))
            }

            // If any tool hit a timeout, break the loop
            if (toolOutputs.any { it.second.contains("超时") }) {
                return Result.success("网络不太好，搜索超时了，请稍后再试")
            }
        }

        // Max turns exceeded — give a graceful fallback
        Log.w(TAG, "Max turns ($maxTurns) exceeded")
        return Result.success("处理稍微复杂了点，请换个方式再说一遍")
    }

    /**
     * System prompt that teaches the LLM about its tool-calling capability.
     * Written in Chinese because the user interacts in Chinese.
     */
    private fun buildSystemPrompt(memoryContext: String = ""): String = buildString {
        append("你是猪头助手，一个友好的中文语音助手。")
        append("你可以调用工具来执行操作（调整设置、搜索信息、获取天气、新闻、位置，以及记忆和配置管理等）。")
        append("当用户要求执行某个操作时，请直接调用对应的工具函数，不要用文字描述你将要做什么。")
        append("工具执行完毕后，用口语化的中文简短总结结果，控制在2-3句话以内。")
        append("如果用户只是聊天而不是要求操作，正常回复即可，不要调用工具。")
        append("重要：用户的输入来自语音识别，可能对中英混杂词汇识别不准，请根据上下文自动纠正。")
        append("重要：你的回复会被语音朗读（TTS），禁止使用任何Markdown格式（**加粗**、*斜体*、`代码`、[链接](url)、列表符号、代码块等），只用纯文本自然口语表达。")
        append("当用户询问天气但没有指定城市时，先调用 get_location 获取位置，再用获取到的城市名调用 get_weather。")
        append("当用户询问新闻、资讯、最新消息时，调用 get_news 获取头条。如果用户想看特定类别（如科技、体育、财经），传入 category 参数。")
        append("当用户说「读一下XX」「念XX」「给我读XX」时，调用 read_article(query=\"XX\") 获取文章内容。获取到的文本会由语音引擎朗读给用户，所以你只需要输出工具的返回内容即可，不要额外总结。")
        append("但如果用户只是说出古诗名（如「静夜思」）、常见成语、简短名句，你可以直接背诵内容，不需要调用工具。read_article 用于搜索你不熟悉的长文、文章、新闻。")
        append("不要调用不相关的工具。聊天、问候、闲聊时直接文本回复，不要调用任何工具。")
        append("重要：如果用户请求需要多步骤操作，可以调用 skill_ 开头的技能工具。技能会自动完成所有步骤并报告进度，你只需要等待最终结果。")

        // Self-improvement: L2 — configuration
        append("你可以通过 update_config 工具记住用户偏好。")
        append("当用户说'以后回答简短点'时，调用 update_config(key='response_style', value='concise')。")
        append("当用户分享个人信息（如'我叫XX'、'我住在XX'）时，同时调用 update_config 保存，并调用 remember 记录。")

        // Self-improvement: L4 — memory
        append("当用户说'记住XX'或分享重要信息时，调用 remember 保存。")
        append("重要：即使用户没有明确说'记住'，只要用户在对话中透露了新的个人信息（如名字、城市、爱好、工作等），你也应该主动调用 remember 保存。")
        append("当用户问'你都知道我什么'或'还记得XX吗'时，调用 what_do_you_know。")

        // Inject memory context (auto-populated from past conversations)
        if (memoryContext.isNotBlank()) {
            append("\n")
            append(memoryContext)
        }
    }
}
