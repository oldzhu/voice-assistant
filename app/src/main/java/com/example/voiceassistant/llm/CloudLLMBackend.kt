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
        .readTimeout(90, TimeUnit.SECONDS)
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
        val functionCall: Pair<String, Map<String, Any?>>?, // (function_name, args) — first call only (legacy)
        val functionCalls: List<Pair<String, Map<String, Any?>>>?, // all function calls (when LLM emits multiple)
        val rawAssistantMessage: Map<String, Any?>? = null
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
            Log.i(TAG, "ToolChat request: ${jsonBody.take(300)}...")

            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "ToolChat error ${response.code}: $body")
                return@withContext Result.failure(Exception("API ${response.code}: ${body.take(200)}"))
            }

            // Parse as raw Map to handle both text and tool_calls
            @Suppress("UNCHECKED_CAST")
            val json = gson.fromJson(body, Map::class.java) as Map<String, Any?>
            val choices = json["choices"] as? List<Map<String, Any?>> ?: emptyList()
            val choice = choices.firstOrNull()

            if (choice == null) {
                return@withContext Result.success(ToolChatResult("", null, null))
            }

            @Suppress("UNCHECKED_CAST")
            val message = choice["message"] as? Map<String, Any?>
            val finishReason = choice["finish_reason"] as? String ?: "stop"

            if (finishReason == "tool_calls") {
                @Suppress("UNCHECKED_CAST")
                val toolCalls = message?.get("tool_calls") as? List<Map<String, Any?>> ?: emptyList()

                // Extract ALL function calls (LLM may emit multiple)
                val allCalls = mutableListOf<Pair<String, Map<String, Any?>>>()
                for (tc in toolCalls) {
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
                        allCalls.add(Pair(funcName, args))
                    }
                }

                val firstCall = allCalls.firstOrNull()
                if (firstCall != null) {
                    Log.i(TAG, "ToolChat: ${allCalls.size} function_call(s) → ${allCalls.map { it.first }.joinToString()}")
                    Result.success(ToolChatResult(
                        textResponse = null,
                        functionCall = firstCall,
                        functionCalls = allCalls,
                        rawAssistantMessage = message
                    ))
                } else {
                    Result.success(ToolChatResult(null, null, null))
                }
            } else {
                val content = message?.get("content") as? String ?: ""
                Log.d(TAG, "ToolChat: text response (${content.length} chars)")
                Result.success(ToolChatResult(content.trim(), null, null))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ToolChat failed", e)
            Result.failure(e)
        }
    }

    // ==================================================================
    // Vision — describe image
    // ==================================================================

    /**
     * Send an image to the vision model for description.
     *
     * @param prompt The text prompt (e.g., "Describe this image in Chinese").
     * @param imageBase64 Base64-encoded JPEG image (without data URI prefix).
     * @return The model's description of the image.
     */
    suspend fun describeImage(prompt: String, imageBase64: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val content = listOf(
                mapOf("type" to "text", "text" to prompt),
                mapOf("type" to "image_url", "image_url" to mapOf(
                    "url" to "data:image/jpeg;base64,$imageBase64"
                ))
            )
            val messages = listOf(
                mapOf("role" to "user", "content" to content)
            )
            val body = mapOf(
                "model" to model,
                "messages" to messages,
                "max_tokens" to 300,
                "temperature" to 0.7
            )
            val jsonBody = gson.toJson(body)

            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Vision API error: ${response.code} — $respBody")
                return@withContext Result.failure(Exception("Vision API error ${response.code}"))
            }

            val chatResponse = gson.fromJson(respBody, ChatResponse::class.java)
            val description = chatResponse.choices?.firstOrNull()?.message?.content ?: "无法识别图片内容"
            Log.i(TAG, "Vision response: ${description.take(100)}...")
            Result.success(description.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Vision failed", e)
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
