package com.example.voiceassistant.tools

import com.example.voiceassistant.llm.LLMBackend
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * A tool whose implementation is a prompt template executed by the LLM.
 *
 * When the user says "我需要汇率转换功能", the LLM calls create_tool
 * with a prompt_template like "Convert {amount} {from} to {to}. Return ONLY the number."
 * When the new tool is called, DynamicTool fills the template with args
 * and calls the LLM (without tool calling to prevent recursion).
 */
class DynamicTool(
    override val name: String,
    override val description: String,
    override val parameters: Map<String, ToolParameter>,
    private val promptTemplate: String,
    private val llmBackend: () -> LLMBackend?
) : Tool {

    override suspend fun execute(args: Map<String, Any?>): String {
        val llm = llmBackend() ?: return "错误：LLM 后端未初始化"

        // Fill template with provided args
        var filled = promptTemplate
        for ((key, value) in args) {
            val strValue = value?.toString() ?: ""
            filled = filled.replace("{$key}", strValue)
        }

        // Also replace unfilled placeholders with empty string
        // Use a simple loop instead of regex to avoid ICU regex escaping issues on Android
        while (filled.contains("{")) {
            val start = filled.indexOf("{")
            val end = filled.indexOf("}", start)
            if (end < 0) break
            filled = filled.substring(0, start) + filled.substring(end + 1)
        }

        if (filled.isBlank()) return "错误：prompt 模板为空"

        return try {
            val result = llm.chat(filled, emptyList())
            result.getOrElse { ex -> "执行失败：${ex.message}" }
        } catch (e: Exception) {
            "执行出错：${e.message}"
        }
    }
}

// ══════════════════════════════════════════════════════════
// L3: CreateToolTool — LLM generates its own tools
// ══════════════════════════════════════════════════════════

/**
 * Tool: create_tool — let the LLM create custom tools backed by prompt templates.
 *
 * When the user says "我需要汇率转换", the LLM can:
 * 1. Call create_tool with name="convert_currency", parameters_json={"amount":"string",...},
 *    prompt_template="Convert {amount} ..."
 * 2. The DynamicTool is created and registered
 * 3. The LLM can now call convert_currency directly
 *
 * Generated tools are session-only (lost on app restart).
 */
class CreateToolTool(
    private val toolRegistry: () -> com.example.voiceassistant.llm.ToolRegistry,
    private val llmBackend: () -> LLMBackend?,
    private val generatedToolDir: () -> java.io.File
) : Tool {
    override val name = "create_tool"
    override val description = "为自己创建新工具。当用户说'帮我加一个XX功能'、'我需要XX工具'、" +
        "'能不能做一个XX'时调用。用 prompt_template 描述如何用 LLM 实现该功能，" +
        "参数用 {param_name} 占位符标记。"
    override val parameters = mapOf(
        "tool_name" to ToolParameter("string", "新工具的名称，英文小写+下划线，如 convert_currency"),
        "tool_description" to ToolParameter("string", "一句话描述这个工具做什么及其触发条件"),
        "parameters_json" to ToolParameter("string",
            "JSON 格式的参数定义。key=参数名, value=参数类型(如\"string\")。例如: {\"amount\":\"string\",\"from\":\"string\",\"to\":\"string\"}",
            required = false),
        "prompt_template" to ToolParameter("string",
            "执行模板，用 {参数名} 作为占位符。例如: '将{amount}从{from}转换为{to}，只返回数字结果。'")
    )

    private val gson = Gson()

    override suspend fun execute(args: Map<String, Any?>): String {
        val toolName = (args["tool_name"] as? String)?.trim()
            ?: return "错误：缺少 tool_name 参数"
        val toolDesc = (args["tool_description"] as? String)?.trim()
            ?: return "错误：缺少 tool_description 参数"
        val paramsJson = (args["parameters_json"] as? String)?.trim() ?: "{}"
        val promptTemplate = (args["prompt_template"] as? String)?.trim()
            ?: return "错误：缺少 prompt_template 参数"

        // Validate name format
        if (!toolName.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            return "错误：工具名 '$toolName' 格式无效，只允许小写字母、数字、下划线，且必须以字母开头"
        }

        // Parse parameters JSON
        val parameters: Map<String, ToolParameter>
        try {
            val rawParams: Map<String, String> = gson.fromJson(
                paramsJson,
                object : TypeToken<Map<String, String>>() {}.type
            ) ?: emptyMap()
            parameters = rawParams.mapValues { (_, type) -> ToolParameter(type, "") }
        } catch (e: Exception) {
            return "错误：解析 parameters_json 失败：${e.message}"
        }

        // Check for duplicate
        val registry = toolRegistry()
        if (registry.has(toolName)) {
            return "工具 '$toolName' 已存在，请用其他名称"
        }

        // Create and register the dynamic tool
        val dynamicTool = DynamicTool(
            name = toolName,
            description = toolDesc,
            parameters = parameters,
            promptTemplate = promptTemplate,
            llmBackend = llmBackend
        )
        registry.register(dynamicTool)

        // Persist to disk for future sessions (optional)
        try {
            val toolDef = mapOf(
                "name" to toolName,
                "description" to toolDesc,
                "parameters" to parameters.mapValues { it.value.type },
                "prompt_template" to promptTemplate
            )
            val file = java.io.File(generatedToolDir(), "${toolName}.json")
            file.writeText(gson.toJson(toolDef))
        } catch (_: Exception) { /* persistence is best-effort */ }

        val paramList = if (parameters.isNotEmpty()) {
            "，参数：${parameters.keys.joinToString(", ")}"
        } else ""

        return "✅ 工具 '$toolName' 已创建$paramList。触发条件：$toolDesc"
    }

    /**
     * Restore tools from disk that were generated in previous sessions.
     */
    fun restoreFromDisk() {
        val dir = generatedToolDir()
        if (!dir.exists()) return

        val registry = toolRegistry()
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json: Map<String, Any?> = gson.fromJson(
                    file.readText(),
                    object : TypeToken<Map<String, Any?>>() {}.type
                )
                val name = json["name"] as? String ?: return@forEach
                val desc = json["description"] as? String ?: return@forEach
                val template = json["prompt_template"] as? String ?: return@forEach

                @Suppress("UNCHECKED_CAST")
                val rawParams = json["parameters"] as? Map<String, String> ?: emptyMap()
                val parameters = rawParams.mapValues { (_, type) -> ToolParameter(type, "") }

                val tool = DynamicTool(name, desc, parameters, template, llmBackend)
                registry.register(tool)
            } catch (_: Exception) {
                // Skip corrupted files
            }
        }
    }
}
