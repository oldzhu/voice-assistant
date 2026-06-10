package com.example.voiceassistant.tools

import com.example.voiceassistant.config.ConfigManager
import com.example.voiceassistant.config.MemoryManager
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

// ══════════════════════════════════════════════════════════
// L2: UpdateConfigTool — LLM-writable key-value preferences
// ══════════════════════════════════════════════════════════

/**
 * Tool: update_config — let the LLM modify runtime configuration.
 *
 * Known keys that affect assistant behavior:
 *   response_style   → "concise" | "detailed" | "friendly"
 *   default_news_category → "tech" | "sports" | "finance" | "domestic"
 *   user_city        → city name for weather default
 *   user_name        → how to address the user
 *
 * LLM prompt: "以后回答简短一点" → update_config(key="response_style", value="concise")
 */
class UpdateConfigTool(private val config: () -> ConfigManager) : Tool {
    override val name = "update_config"
    override val description = "修改助理的配置或记忆用户偏好。如用户说'以后回答简短点'就设 response_style=concise，" +
        "用户说'我住在XX'就设 user_city=XX。常用键：response_style, default_news_category, user_city, user_name。"
    override val parameters = mapOf(
        "key" to ToolParameter("string", "配置项名称，如 response_style、user_city、user_name"),
        "value" to ToolParameter("string", "配置值，如 concise、深圳、小王")
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val key = args["key"] as? String ?: return "错误：缺少 key 参数"
        val value = args["value"] as? String ?: return "错误：缺少 value 参数"
        if (key.isBlank() || value.isBlank()) return "错误：key 或 value 不能为空"

        config().setUserPreference(key.trim(), value.trim())
        return "已更新：$key = $value"
    }
}

// ══════════════════════════════════════════════════════════
// L4: RememberTool — persist facts the user shares
// ══════════════════════════════════════════════════════════

/**
 * Tool: remember — save a fact/note about the user.
 *
 * Uses MemoryManager for deduplication and access tracking.
 * LLM should call this automatically when it learns new user facts,
 * not only when the user explicitly says "remember X".
 */
class RememberTool(private val memoryManager: () -> com.example.voiceassistant.config.MemoryManager) : Tool {
    override val name = "remember"
    override val description = "记住用户分享的重要信息。当用户说'记住XX'、'我叫XX'、'我喜欢XX'、透露了新的个人信息或偏好时自动调用。"
    override val parameters = mapOf(
        "fact" to ToolParameter("string", "要记住的事实，一句话描述"),
        "category" to ToolParameter("string", "类别：personal(个人信息), preference(偏好), context(上下文)", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val fact = args["fact"] as? String ?: return "错误：缺少 fact 参数"
        val category = args["category"] as? String ?: "general"

        val added = memoryManager().remember(fact.trim(), category.trim())
        return if (added) "已记住：$fact" else "已知信息已更新：$fact"
    }
}

// ══════════════════════════════════════════════════════════
// L4: RecallTool — retrieve stored memories
// ══════════════════════════════════════════════════════════

/**
 * Tool: what_do_you_know — recall facts the assistant has stored.
 *
 * Uses MemoryManager for structured query with access tracking.
 * If query is empty, returns all memories. If query is provided,
 * does fuzzy matching (substring) across facts.
 */
class RecallTool(private val memoryManager: () -> com.example.voiceassistant.config.MemoryManager) : Tool {
    override val name = "what_do_you_know"
    override val description = "回忆之前记住的信息。当用户问'你都知道我什么'、'还记得XX吗'时调用。"
    override val parameters = mapOf(
        "query" to ToolParameter("string", "搜索关键词，留空返回全部记忆", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val query = args["query"] as? String ?: ""
        return memoryManager().formatResults(query)
    }
}

// ══════════════════════════════════════════════════════════
// Personality: SetPersonalityTool — change assistant character
// ══════════════════════════════════════════════════════════

/**
 * Tool: set_personality — change the assistant's personality.
 *
 * Users can say "你以后说话幽默一点" or LLM can decide to change tone.
 * Modifies ConfigManager personality settings.
 */
class SetPersonalityTool(private val config: () -> ConfigManager) : Tool {
    override val name = "set_personality"
    override val description = "修改助手的性格。用户说'你以后幽默一点'、'换个语气'、" +
        "'你的口头禅改成XX'时调用。支持修改名称、语气、口头禅。"
    override val parameters = mapOf(
        "name" to ToolParameter("string", "新的名字，如'小助手'", required = false),
        "tone" to ToolParameter("string", "语气风格：friendly(友好), professional(专业), funny(幽默), concise(简洁)", required = false),
        "catchphrase" to ToolParameter("string", "口头禅，如'嘿嘿'。设为空字符串取消口头禅", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val cfg = config()
        val changes = mutableListOf<String>()

        (args["name"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let {
            cfg.assistantName = it
            changes.add("名字改为「$it」")
        }
        (args["tone"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it in listOf("friendly", "professional", "funny", "concise")) {
                cfg.tone = it
                val toneName = mapOf(
                    "friendly" to "友好", "professional" to "专业",
                    "funny" to "幽默", "concise" to "简洁"
                )[it] ?: it
                changes.add("语气改为「$toneName」")
            }
        }
        args["catchphrase"]?.toString()?.trim()?.let {
            cfg.catchphrase = it
            if (it.isBlank()) changes.add("口头禅已取消")
            else changes.add("口头禅改为「$it」")
        }

        return if (changes.isEmpty()) "没有需要修改的内容。可用选项：name, tone, catchphrase"
        else "✅ " + changes.joinToString("，")
    }
}
