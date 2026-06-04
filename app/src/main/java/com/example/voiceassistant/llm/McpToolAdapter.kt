package com.example.voiceassistant.llm

import com.example.voiceassistant.llm.transport.McpInputSchema
import com.example.voiceassistant.llm.transport.McpPropertyDef
import com.example.voiceassistant.llm.transport.McpToolDef

/**
 * Adapts an MCP tool definition into our [Tool] interface.
 *
 * Tool name is prefixed with `mcp_{serverName}_` to avoid collisions.
 *
 * Usage:
 *   val adapter = McpToolAdapter("time", mcpToolDef, mcpClient)
 *   toolRegistry.register(adapter)
 */
class McpToolAdapter(
    serverName: String,
    private val mcpTool: McpToolDef,
    private val mcpClient: McpClient
) : Tool {

    override val name = "mcp_${serverName}_${mcpTool.name.replace("-", "_").replace(".", "_")}"

    override val description: String
        get() = mcpTool.description ?: "MCP tool: ${mcpTool.name}"

    override val parameters: Map<String, ToolParameter>
        get() {
            val schema = mcpTool.inputSchema ?: return emptyMap()
            val props = schema.properties ?: return emptyMap()
            val required = schema.required?.toSet() ?: emptySet()

            return props.mapValues { (paramName, propDef) ->
                ToolParameter(
                    type = propDef.type ?: "string",
                    description = propDef.description ?: paramName,
                    required = paramName in required
                )
            }
        }

    override suspend fun execute(args: Map<String, Any?>): String {
        return try {
            mcpClient.callTool(mcpTool.name, args)
        } catch (e: Exception) {
            "MCP tool '${mcpTool.name}' failed: ${e.message?.take(200) ?: "unknown error"}"
        }
    }
}
