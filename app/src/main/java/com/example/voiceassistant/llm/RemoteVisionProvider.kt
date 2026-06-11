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
 * Cloud-based vision provider.
 *
 * Sends image as base64 to an OpenAI-compatible vision endpoint.
 * Extracted from CloudLLMBackend.describeImage() for clean separation.
 *
 * Requires a vision-capable model (e.g., gpt-4o, qwen-vl-max).
 * deepseek-chat is text-only — do NOT use it here.
 */
class RemoteVisionProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val visionModel: String
) : VisionProvider {

    companion object {
        private const val TAG = "RemoteVision"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun describe(prompt: String, imageBase64: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (visionModel.isBlank()) {
                Log.w(TAG, "Vision called but no vision_model configured")
                return@withContext Result.success(
                    "视觉识别功能未配置。请在设置中添加 vision_model（如 gpt-4o、qwen-vl-max 等支持图片输入的模型）。"
                )
            }

            try {
                val content = listOf(
                    mapOf("type" to "text", "text" to prompt),
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$imageBase64")
                    )
                )
                val body = mapOf(
                    "model" to visionModel,
                    "messages" to listOf(mapOf("role" to "user", "content" to content)),
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
                    return@withContext Result.failure(
                        Exception("Vision API error ${response.code}")
                    )
                }

                val chatResponse = gson.fromJson(respBody, ChatResponse::class.java)
                val description =
                    chatResponse.choices?.firstOrNull()?.message?.content ?: "无法识别图片内容"
                Log.i(TAG, "Vision response: ${description.take(100)}...")
                Result.success(description.trim())
            } catch (e: Exception) {
                Log.e(TAG, "Vision failed", e)
                Result.failure(e)
            }
        }

    // ——— JSON data classes ———

    data class ChatResponse(
        val choices: List<Choice>?
    )

    data class Choice(
        val message: Message?
    )

    data class Message(
        @SerializedName("content") val content: String?
    )
}
