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
        history: List<LLMBackend.ChatMessage>
    ): Result<String> {
        // Build initial message list as Maps — supports tool-role messages
        // that don't fit the simple ChatMessage model.
        val messages = mutableListOf<Map<String, Any?>>()

        // System prompt — tells LLM it has tools and how to use them
        messages.add(mapOf(
            "role" to "system",
            "content" to buildSystemPrompt()
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
            Log.d(TAG, "Turn $turn: messages=${messages.size}, tools=$toolCount")

            val result = llmBackend.chatWithTools(messages, toolRegistry.getFunctionDefs())
            if (result.isFailure) {
                Log.e(TAG, "Turn $turn: LLM call failed: ${result.exceptionOrNull()?.message}")
                return Result.failure(result.exceptionOrNull() ?: Exception("LLM call failed"))
            }

            val toolResult = result.getOrNull() ?: continue

            // --- Case 1: Text response → we're done ---
            if (toolResult.textResponse != null) {
                Log.d(TAG, "Turn $turn: text response (${toolResult.textResponse.length} chars)")
                return Result.success(toolResult.textResponse)
            }

            // --- Case 2: Function call → execute tool ---
            val (funcName, funcArgs) = toolResult.functionCall
                ?: run {
                    Log.w(TAG, "Turn $turn: no text and no function_call — breaking")
                    return Result.success("我暂时无法处理这个请求")
                }

            Log.i(TAG, "Turn $turn: LLM calls → $funcName($funcArgs)")

            // Execute the tool
            val toolOutput = toolRegistry.execute(funcName, funcArgs)
            Log.i(TAG, "Turn $turn: tool output → ${toolOutput.take(100)}")

            // Add assistant message with tool_call (tells LLM what it asked for)
            val toolCallId = "call_${funcName}_$turn"
            messages.add(mapOf(
                "role" to "assistant",
                "content" to null,
                "tool_calls" to listOf(mapOf(
                    "id" to toolCallId,
                    "type" to "function",
                    "function" to mapOf(
                        "name" to funcName,
                        "arguments" to gson.toJson(funcArgs)
                    )
                ))
            ))

            // Add tool result message (the tool's output)
            messages.add(mapOf(
                "role" to "tool",
                "tool_call_id" to toolCallId,
                "content" to toolOutput
            ))
        }

        // Max turns exceeded — give a graceful fallback
        Log.w(TAG, "Max turns ($maxTurns) exceeded")
        return Result.success("处理稍微复杂了点，请换个方式再说一遍")
    }

    /**
     * System prompt that teaches the LLM about its tool-calling capability.
     * Written in Chinese because the user interacts in Chinese.
     */
    private fun buildSystemPrompt(): String = buildString {
        append("你是猪头助手，一个友好的中文语音助手。")
        append("你可以调用工具来执行操作（如调整设置、搜索信息等）。")
        append("当用户要求执行某个操作时，请直接调用对应的工具函数，不要用文字描述你将要做什么。")
        append("工具执行完毕后，用口语化的中文简短总结结果，控制在2-3句话以内。")
        append("如果用户只是聊天而不是要求操作，正常回复即可，不要调用工具。")
        append("重要：用户的输入来自语音识别，可能对中英混杂词汇识别不准，请根据上下文自动纠正。")
    }
}
