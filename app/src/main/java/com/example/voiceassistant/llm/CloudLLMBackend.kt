package com.example.voiceassistant.llm

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Cloud LLM backend via OpenAI-compatible API (DeepSeek, OpenAI, etc.).
 *
 * Supports:
 * - Standard chat (non-streaming) via [chat]
 * - Function calling via [chatWithTools]
 */
class CloudLLMBackend(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-chat",
    private val systemPrompt: String = "你是猪头助手，一个友好的中文语音助手。请用简洁、口语化的中文回复。每次回复控制在2-3句话以内，方便语音播报。" +
        "重要：用户的输入来自语音识别，可能对中英混杂词汇（如\"linux内核\"、\"python代码\"、\"api接口\"等）识别不准。请根据上下文自动纠正可能的语音识别错误。"
) : LLMBackend {

    companion object {
        private const val TAG = "CloudLLMBackend"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ==================================================================
    // Standard chat (existing, unchanged API)
    // ==================================================================

    override suspend fun chat(
        userMessage: String,
        history: List<LLMBackend.ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = buildMessages(userMessage, history)
            val requestBody = ChatRequest(
                model = model,
                messages = messages,
                stream = false,
                maxTokens = 500,
                temperature = 0.7
            )

            val jsonBody = gson.toJson(requestBody)
            Log.d(TAG, "Request: ${jsonBody.take(200)}...")

            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "API error: ${response.code} — $body")
                return@withContext Result.failure(Exception("API error ${response.code}: $body"))
            }

            val chatResponse = gson.fromJson(body, ChatResponse::class.java)
            val content = chatResponse.choices?.firstOrNull()?.message?.content ?: ""
            Log.i(TAG, "Response: ${content.take(100)}...")
            Result.success(content.trim())

        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            Result.failure(e)
        }
    }

    // ==================================================================
    // Function calling
    // ==================================================================

    /**
     * Result of a tool-enabled chat call.
     *
     * - If [textResponse] is non-null: LLM produced a final text answer; done.
     * - If [functionCall] is non-null: LLM wants to call a tool.
     */
    data class ToolChatResult(
        val textResponse: String?,
        val functionCall: Pair<String, Map<String, Any?>>? // (function_name, args)
    )

    /**
     * Send a chat request with tool definitions.
     *
     * @param messages Full message list as Maps (supports tool-role messages
     *                 that don't fit the simple ChatMessage model).
     * @param functionDefs Tool definitions from [Tool.toFunctionDef].
     * @return Either a text response or a function_call to execute.
     */
    suspend fun chatWithTools(
        messages: List<Map<String, Any?>>,
        functionDefs: List<Map<String, Any?>>
    ): Result<ToolChatResult> = withContext(Dispatchers.IO) {
        try {
            val requestBody = mapOf<String, Any?>(
                "model" to model,
                "messages" to messages,
                "stream" to false,
                "max_tokens" to 500,
                "temperature" to 0.7,
                "tools" to functionDefs,
                "tool_choice" to "auto"
            )

            val jsonBody = gson.toJson(requestBody)
            Log.d(TAG, "ToolChat request: ${jsonBody.take(300)}...")

            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "ToolChat error: ${response.code} — $body")
                return@withContext Result.failure(Exception("API error ${response.code}"))
            }

            // Parse as raw Map to handle both text and tool_calls
            @Suppress("UNCHECKED_CAST")
            val json = gson.fromJson(body, Map::class.java) as Map<String, Any?>
            val choices = json["choices"] as? List<Map<String, Any?>> ?: emptyList()
            val choice = choices.firstOrNull()

            if (choice == null) {
                return@withContext Result.success(ToolChatResult("", null))
            }

            @Suppress("UNCHECKED_CAST")
            val message = choice["message"] as? Map<String, Any?>
            val finishReason = choice["finish_reason"] as? String ?: "stop"

            if (finishReason == "tool_calls") {
                @Suppress("UNCHECKED_CAST")
                val toolCalls = message?.get("tool_calls") as? List<Map<String, Any?>>
                val tc = toolCalls?.firstOrNull()
                @Suppress("UNCHECKED_CAST")
                val func = tc?.get("function") as? Map<String, Any?>
                if (func != null) {
                    val funcName = func["name"] as? String ?: ""
                    val argsJson = func["arguments"] as? String ?: "{}"
                    val args: Map<String, Any?> = try {
                        @Suppress("UNCHECKED_CAST")
                        (gson.fromJson(argsJson, Map::class.java) as? Map<String, Any?>)
                            ?: emptyMap()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse tool args: $argsJson", e)
                        emptyMap()
                    }
                    Log.d(TAG, "ToolChat: function_call → $funcName($args)")
                    Result.success(ToolChatResult(
                        textResponse = null,
                        functionCall = Pair(funcName, args)
                    ))
                } else {
                    Result.success(ToolChatResult(null, null))
                }
            } else {
                val content = message?.get("content") as? String ?: ""
                Log.d(TAG, "ToolChat: text response (${content.length} chars)")
                Result.success(ToolChatResult(content.trim(), null))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ToolChat failed", e)
            Result.failure(e)
        }
    }

    // ==================================================================
    // Message construction
    // ==================================================================

    private fun buildMessages(
        userMessage: String,
        history: List<LLMBackend.ChatMessage>
    ): List<Message> {
        val messages = mutableListOf<Message>()
        messages.add(Message(role = "system", content = systemPrompt))

        // Add history, keeping last 10 messages to manage context window
        val recentHistory = history.takeLast(10)
        for (msg in recentHistory) {
            messages.add(Message(role = msg.role, content = msg.content))
        }

        messages.add(Message(role = "user", content = userMessage))
        return messages
    }

    // ==================================================================
    // Data classes for JSON serialization
    // ==================================================================

    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean = false,
        @SerializedName("max_tokens") val maxTokens: Int = 500,
        val temperature: Double = 0.7
    )

    data class Message(
        @SerializedName("role") val role: String,
        @SerializedName("content") val content: String?
    )

    data class ChatResponse(
        @SerializedName("id") val id: String?,
        @SerializedName("choices") val choices: List<Choice>?
    )

    data class Choice(
        @SerializedName("index") val index: Int?,
        @SerializedName("message") val message: Message?,
        @SerializedName("finish_reason") val finishReason: String?
    )
}
