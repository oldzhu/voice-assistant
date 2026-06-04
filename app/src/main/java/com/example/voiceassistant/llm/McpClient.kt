package com.example.voiceassistant.llm

import android.util.Log
import com.example.voiceassistant.llm.transport.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP (Model Context Protocol) client.
 *
 * Handles JSON-RPC 2.0 protocol over an [McpTransport]:
 * - initialize → negotiate protocol version
 * - tools/list → discover available tools
 * - tools/call → execute a tool
 *
 * Usage:
 *   val transport = StdioMcpTransport("/path/to/server")
 *   val client = McpClient("myserver", transport)
 *   client.connect()
 *   val tools = client.listTools()
 *   // tools: List<McpToolDef>
 */
class McpClient(
    val serverName: String,
    private val transport: McpTransport,
    private val timeoutMs: Long = 30_000
) {
    companion object {
        private const val TAG = "McpClient"
        private const val PROTOCOL_VERSION = "2024-11-05"
    }

    private val gson = Gson()
    private var requestId = 0
    private var connected = false

    /** Connect and perform MCP handshake. */
    suspend fun connect(): McpInitResult = withContext(Dispatchers.IO) {
        transport.connect()

        // Step 1: initialize
        val initParams = mapOf<String, Any?>(
            "protocolVersion" to PROTOCOL_VERSION,
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf(
                "name" to "pighead",
                "version" to "1.4"
            )
        )
        val initResponse = sendRequest("initialize", initParams)
        val initResult = initResponse["result"] as? Map<*, *>
            ?: throw IllegalStateException("MCP initialize failed: $initResponse")

        val serverInfo = initResult["serverInfo"] as? Map<*, *>
        val serverName = serverInfo?.get("name") as? String ?: "unknown"
        val serverVersion = serverInfo?.get("version") as? String ?: "?"
        Log.i(TAG, "[$serverName] MCP server initialized: $serverName v$serverVersion")

        // Step 2: send initialized notification
        sendNotification("notifications/initialized", null)

        connected = true
        McpInitResult(
            serverName = serverName,
            serverVersion = serverVersion,
            protocolVersion = initResult["protocolVersion"] as? String ?: PROTOCOL_VERSION
        )
    }

    /** Discover tools from the MCP server. */
    suspend fun listTools(): List<McpToolDef> = withContext(Dispatchers.IO) {
        if (!connected) throw IllegalStateException("MCP client not connected")
        val response = sendRequest("tools/list", emptyMap<String, Any?>())
        val result = response["result"] as? Map<*, *>
            ?: throw IllegalStateException("MCP tools/list failed: $response")

        @Suppress("UNCHECKED_CAST")
        val toolsRaw = result["tools"] as? List<Map<String, Any?>>
            ?: emptyList()

        toolsRaw.map { raw ->
            val inputSchemaRaw = raw["inputSchema"] as? Map<String, Any?>
            val inputSchema = if (inputSchemaRaw != null) {
                @Suppress("UNCHECKED_CAST")
                val propsRaw = inputSchemaRaw["properties"] as? Map<String, Map<String, Any?>>
                val props = propsRaw?.mapValues { (_, v) ->
                    McpPropertyDef(
                        type = v["type"] as? String,
                        description = v["description"] as? String
                    )
                }
                @Suppress("UNCHECKED_CAST")
                val required = inputSchemaRaw["required"] as? List<String>
                McpInputSchema(
                    type = inputSchemaRaw["type"] as? String ?: "object",
                    properties = props,
                    required = required
                )
            } else null

            McpToolDef(
                name = raw["name"] as? String ?: "unknown",
                description = raw["description"] as? String,
                inputSchema = inputSchema
            )
        }
    }

    /** Call a tool on the MCP server. */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        if (!connected) throw IllegalStateException("MCP client not connected")
        val params = mapOf<String, Any?>(
            "name" to name,
            "arguments" to arguments
        )
        val response = sendRequest("tools/call", params)
        val result = response["result"] as? Map<*, *>
        if (result == null) {
            val error = response["error"] as? Map<*, *>
            val msg = error?.get("message") as? String ?: "unknown MCP error"
            throw IllegalStateException("MCP tool call failed: $msg")
        }

        // Extract content from result
        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as? List<Map<String, Any?>>
        val textParts = content?.mapNotNull { it["text"] as? String } ?: emptyList()
        textParts.joinToString("\n").ifEmpty { result.toString() }
    }

    /** Disconnect from the MCP server. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        connected = false
        transport.disconnect()
    }

    // --- Internal helpers ---

    private suspend fun sendRequest(method: String, params: Map<String, Any?>?): Map<String, Any?> {
        val id = ++requestId
        val request = mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
            "params" to (params ?: emptyMap<String, Any?>())
        )
        val json = gson.toJson(request)
        Log.d(TAG, "[$serverName] → $method id=$id")
        val raw = transport.send(json)
        Log.d(TAG, "[$serverName] ← ${raw.take(200)}")

        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(raw, Map::class.java) as Map<String, Any?>
    }

    private suspend fun sendNotification(method: String, params: Map<String, Any?>?) {
        val request = mapOf(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to (params ?: emptyMap<String, Any?>())
        )
        val json = gson.toJson(request)
        Log.d(TAG, "[$serverName] → $method (notification)")
        transport.send(json)
    }
}

/** Result of a successful MCP initialize handshake. */
data class McpInitResult(
    val serverName: String,
    val serverVersion: String,
    val protocolVersion: String
)
