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
 * Local LLM backend via Ollama API.
 *
 * Connects to an Ollama instance running on the same network.
 * Default: http://<host>:11434
 */
class LocalLLMBackend(
    private val baseUrl: String = "http://192.168.1.100:11434",
    private val model: String = "qwen2.5:7b",
    private val systemPrompt: String = "你是猪头助手，一个友好的中文语音助手。请用简洁、口语化的中文回复。每次回复控制在2-3句话以内，方便语音播报。"
) : LLMBackend {

    companion object {
        private const val TAG = "LocalLLMBackend"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun chat(
        userMessage: String,
        history: List<LLMBackend.ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = buildMessages(userMessage, history)
            val requestBody = OllamaChatRequest(
                model = model,
                messages = messages,
                stream = false
            )

            val jsonBody = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Ollama error: ${response.code} — $body")
                return@withContext Result.failure(Exception("Ollama error ${response.code}: $body"))
            }

            val chatResponse = gson.fromJson(body, OllamaChatResponse::class.java)
            val content = chatResponse.message?.content ?: ""
            Log.i(TAG, "Ollama response: ${content.take(100)}...")
            Result.success(content.trim())

        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "Cannot connect to Ollama at $baseUrl — is the server running?")
            Result.failure(Exception("无法连接本地 Ollama 服务，请确认 $baseUrl 是否在运行"))
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            Result.failure(e)
        }
    }

    /**
     * Check if the Ollama server is reachable.
     */
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun buildMessages(
        userMessage: String,
        history: List<LLMBackend.ChatMessage>
    ): List<OllamaMessage> {
        val messages = mutableListOf<OllamaMessage>()
        messages.add(OllamaMessage(role = "system", content = systemPrompt))

        val recentHistory = history.takeLast(10)
        for (msg in recentHistory) {
            messages.add(OllamaMessage(role = msg.role, content = msg.content))
        }

        messages.add(OllamaMessage(role = "user", content = userMessage))
        return messages
    }

    // --- Data classes ---

    data class OllamaChatRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean = false
    )

    data class OllamaMessage(
        val role: String,
        val content: String
    )

    data class OllamaChatResponse(
        val model: String?,
        @SerializedName("created_at") val createdAt: String?,
        val message: OllamaMessage?,
        @SerializedName("done_reason") val doneReason: String?
    )
}
