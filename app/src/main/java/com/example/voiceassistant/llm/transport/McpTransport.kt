package com.example.voiceassistant.llm.transport

import com.google.gson.annotations.SerializedName

// ==================================================================
// MCP Transport interface — abstracts communication channel
// ==================================================================

/**
 * A transport for JSON-RPC communication with an MCP server.
 *
 * Two implementations:
 * - [StdioMcpTransport] — spawns a local process, communicates via stdin/stdout
 * - [HttpMcpTransport] — connects to a remote HTTP MCP endpoint
 */
interface McpTransport {
    /** Connect to the MCP server. */
    suspend fun connect()

    /** Send a raw JSON-RPC request string, return the raw JSON response. */
    suspend fun send(requestJson: String): String

    /** Disconnect and clean up. */
    suspend fun disconnect()
}

// ==================================================================
// JSON-RPC 2.0 message types
// ==================================================================

/** Outgoing JSON-RPC request. */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: Map<String, Any?>? = null
)

/** Incoming JSON-RPC response (success). */
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int,
    val result: Map<String, Any?>? = null
)

/** Incoming JSON-RPC error response. */
data class JsonRpcError(
    val jsonrpc: String = "2.0",
    val id: Int,
    val error: RpcErrorDetail
)

data class RpcErrorDetail(
    val code: Int,
    val message: String
)

// ==================================================================
// MCP protocol-specific data types
// ==================================================================

/** MCP initialize request params. */
data class McpInitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: Map<String, Any?> = emptyMap(),
    val clientInfo: McpClientInfo
)

data class McpClientInfo(
    val name: String,
    val version: String
)

/** MCP tool definition returned by tools/list. */
data class McpToolDef(
    val name: String,
    val description: String? = null,
    val inputSchema: McpInputSchema? = null
)

/** JSON Schema for tool input parameters. */
data class McpInputSchema(
    val type: String = "object",
    val properties: Map<String, McpPropertyDef>? = null,
    val required: List<String>? = null
)

data class McpPropertyDef(
    val type: String? = null,
    val description: String? = null
)

/** MCP tool call request params. */
data class McpCallToolParams(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap()
)

/** MCP tool call result. Called 'result' but the field is 'content' per spec. */
data class McpCallToolResult(
    val content: List<McpContentItem> = emptyList(),
    val isError: Boolean = false
)

data class McpContentItem(
    val type: String = "text",
    val text: String = ""
)
