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
 * Cloud LLM backend via OpenAI-compatible API (DeepSeek, OpenAI, etc.)
 *
 * Supports both streaming (SSE) and non-streaming modes.
 * Default: non-streaming for simpler integration with TTS.
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

    // --- Data classes for JSON serialization ---

    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean = false,
        @SerializedName("max_tokens") val maxTokens: Int = 500,
        val temperature: Double = 0.7
    )

    data class Message(
        val role: String,
        val content: String
    )

    data class ChatResponse(
        val id: String?,
        val choices: List<Choice>?
    )

    data class Choice(
        val index: Int?,
        val message: Message?
    )
}
