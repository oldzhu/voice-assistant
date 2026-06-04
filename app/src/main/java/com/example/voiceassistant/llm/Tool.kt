package com.example.voiceassistant.llm

/**
 * JSON Schema property definition for a tool parameter.
 *
 * Used by [Tool] to describe the parameters the LLM should provide
 * when calling the function. Serialized into OpenAI-compatible
 * function definition JSON by [Tool.toFunctionDef].
 */
data class ToolParameter(
    val type: String,           // "string" | "number" | "boolean" | "integer"
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null  // for constrained string params like mode selection
)

/**
 * A tool (function) callable by the LLM.
 *
 * Implement this interface for each new tool you want the assistant to use.
 * The LLM sees [name], [description], and [parameters] as the function schema
 * in every API request that includes tools.
 *
 * When the LLM calls the tool, [execute] receives arguments as a Map<String, Any?>
 * with types matching the parameter schema (String, Number, Boolean).
 *
 * Usage:
 *   class SetSpeechRateTool(private val onSet: (Float) -> Unit) : Tool {
 *       override val name = "set_speech_rate"
 *       override val description = "调整语音播报语速"
 *       override val parameters = mapOf("rate" to ToolParameter("number", "语速0.5-2.5"))
 *       override suspend fun execute(args: Map<String, Any?>) = ...
 *   }
 */
interface Tool {
    /** Unique function name exposed to the LLM (e.g., "set_speech_rate"). */
    val name: String

    /**
     * Human-readable description the LLM uses to decide when to call this tool.
     * Write this in Chinese for a Chinese-speaking assistant — the LLM will
     * understand it and route user requests accordingly.
     */
    val description: String

    /**
     * Parameter schema. Key = parameter name, value = its type definition.
     * Parameters marked [ToolParameter.required] = true will appear in the
     * JSON Schema "required" array.
     */
    val parameters: Map<String, ToolParameter>

    /**
     * Execute the tool with arguments supplied by the LLM.
     *
     * @param args Parsed arguments from the function_call.
     *             Keys match [parameters]. Values are deserialized by Gson:
     *             "number" → Double, "integer" → Double, "boolean" → Boolean,
     *             "string" → String.
     * @return Result string to feed back to the LLM as a tool-role message.
     */
    suspend fun execute(args: Map<String, Any?>): String

    /**
     * Serialize to an OpenAI-compatible function definition map.
     *
     * Output format:
     *   { "type": "function",
     *     "function": { "name": ..., "description": ..., "parameters": { ... } } }
     *
     * This is called by [ToolRegistry.getFunctionDefs] when building the
     * API request body. Do not override unless you need a non-standard schema.
     */
    fun toFunctionDef(): Map<String, Any?> {
        val props = mutableMapOf<String, Map<String, Any?>>()
        val required = mutableListOf<String>()
        for ((key, param) in parameters) {
            val propDef = mutableMapOf<String, Any?>(
                "type" to param.type,
                "description" to param.description
            )
            param.enum?.let { propDef["enum"] = it }
            props[key] = propDef
            if (param.required) required.add(key)
        }
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to props,
                    "required" to required
                )
            )
        )
    }
}
