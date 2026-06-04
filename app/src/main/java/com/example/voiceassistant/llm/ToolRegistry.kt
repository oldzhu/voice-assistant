package com.example.voiceassistant.llm

/**
 * Central registry for [Tool] instances.
 *
 * Each tool is registered by its [Tool.name] (the function name the LLM sees).
 * The registry handles lookup, execution, and serialization to the
 * function-definitions array required by the OpenAI-compatible API.
 *
 * Usage:
 *   val registry = ToolRegistry()
 *   registry.register(SetSpeechRateTool { rate -> ... })
 *   val result = registry.execute("set_speech_rate", mapOf("rate" to 1.5))
 *   val defs = registry.getFunctionDefs()  // for API request
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    /** Register a tool. Overwrites any existing tool with the same name. */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /** Remove a tool by function name. */
    fun unregister(name: String) {
        tools.remove(name)
    }

    /** All currently registered tools. */
    fun getAll(): List<Tool> = tools.values.toList()

    /**
     * Serialize all registered tools to the format expected by the
     * OpenAI-compatible API's "tools" field.
     *
     * Each entry: { "type": "function", "function": { "name": ..., "description": ..., "parameters": {...} } }
     */
    fun getFunctionDefs(): List<Map<String, Any?>> =
        tools.values.map { it.toFunctionDef() }

    /**
     * Execute a tool by function name with parsed arguments.
     *
     * @param name The function name (Tool.name) to execute.
     * @param args Arguments from the LLM's function_call, as a Map of param_name -> value.
     * @return The tool's output string, or an error message if the tool is not found
     *         or execution throws.
     */
    suspend fun execute(name: String, args: Map<String, Any?>): String {
        val tool = tools[name]
            ?: return "错误：未知工具 '$name'。可用工具：${tools.keys}"

        return try {
            tool.execute(args)
        } catch (e: Exception) {
            "执行 '$name' 出错：${e.message}"
        }
    }

    /** Check if a tool is registered. */
    fun has(name: String): Boolean = tools.containsKey(name)

    /** Number of registered tools. */
    fun size(): Int = tools.size
}
