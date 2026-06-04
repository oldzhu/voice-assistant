package com.example.voiceassistant.llm.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP-based MCP transport — connects to a remote MCP server via HTTP POST.
 *
 * Uses OkHttp (already in project deps). Supports custom headers for auth.
 *
 * Usage:
 *   val transport = HttpMcpTransport("https://mcp.example.com/mcp", mapOf("Authorization" to "Bearer xxx"))
 *   transport.connect()
 *   val response = transport.send(requestJson)
 *   transport.disconnect()
 */
class HttpMcpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val timeoutSec: Long = 120
) : McpTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /** HTTP transport — no explicit connect, just verify URL is reachable. */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        // No persistent connection for HTTP — each send() is a new request
        // Just validate the URL is non-empty
        if (url.isBlank()) throw IllegalArgumentException("MCP HTTP URL cannot be blank")
    }

    override suspend fun send(requestJson: String): String = withContext(Dispatchers.IO) {
        val body = requestJson.toRequestBody(jsonMediaType)

        val reqBuilder = Request.Builder()
            .url(url)
            .post(body)
        // Add custom headers
        for ((key, value) in headers) {
            reqBuilder.addHeader(key, value)
        }

        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("MCP HTTP error ${response.code}: ${response.message}")
        }
        response.body?.string() ?: throw IllegalStateException("Empty response from MCP server")
    }

    override suspend fun disconnect() {
        // No persistent connection to close
        client.dispatcher.executorService.shutdown()
    }
}
