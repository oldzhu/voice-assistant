package com.example.voiceassistant.tools

import com.example.voiceassistant.config.ConfigManager
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
 * Facts are stored in filesDir/assistant_memory.json, one per line.
 * LLM prompt: "我老婆叫小王" → remember(fact="用户老婆叫小王", category="personal")
 */
class RememberTool(private val memoryFile: () -> File) : Tool {
    override val name = "remember"
    override val description = "记住用户分享的重要信息。当用户说'记住XX'、'我叫XX'、'我喜欢XX'等分享个人信息时调用。"
    override val parameters = mapOf(
        "fact" to ToolParameter("string", "要记住的事实，一句话描述"),
        "category" to ToolParameter("string", "类别：personal(个人信息), preference(偏好), context(上下文)", required = false)
    )

    private data class MemoryEntry(
        val fact: String,
        val category: String = "general",
        val timestamp: Long = System.currentTimeMillis()
    )

    private val gson = Gson()

    override suspend fun execute(args: Map<String, Any?>): String {
        val fact = args["fact"] as? String ?: return "错误：缺少 fact 参数"
        val category = args["category"] as? String ?: "general"

        try {
            val entry = MemoryEntry(fact.trim(), category.trim())
            val line = gson.toJson(entry) + "\n"
            memoryFile().appendText(line)
            return "已记住：$fact"
        } catch (e: Exception) {
            return "记忆保存失败：${e.message}"
        }
    }
}

// ══════════════════════════════════════════════════════════
// L4: RecallTool — retrieve stored memories
// ══════════════════════════════════════════════════════════

/**
 * Tool: what_do_you_know — recall facts the assistant has stored.
 *
 * If query is empty, returns all memories. If query is provided,
 * does fuzzy matching (substring) across facts.
 *
 * LLM prompt: "还记得我喜欢什么吗" → what_do_you_know(query="喜欢")
 */
class RecallTool(private val memoryFile: () -> File) : Tool {
    override val name = "what_do_you_know"
    override val description = "回忆之前记住的信息。当用户问'你都知道我什么'、'还记得XX吗'时调用。"
    override val parameters = mapOf(
        "query" to ToolParameter("string", "搜索关键词，留空返回全部记忆", required = false)
    )

    private val gson = Gson()

    override suspend fun execute(args: Map<String, Any?>): String {
        val query = args["query"] as? String ?: ""

        return try {
            val file = memoryFile()
            if (!file.exists()) return "我还没有记住任何信息。"

            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return "我还没有记住任何信息。"

            // Parse memory entries
            val entries = lines.mapNotNull { line ->
                try {
                    gson.fromJson(line, Map::class.java) as? Map<String, Any?>
                } catch (_: Exception) { null }
            }

            if (entries.isEmpty()) return "记忆数据损坏。"

            // Filter by query if provided
            val filtered = if (query.isBlank()) {
                entries
            } else {
                entries.filter { entry ->
                    val fact = entry["fact"] as? String ?: ""
                    fact.contains(query, ignoreCase = true)
                }
            }

            if (filtered.isEmpty()) {
                return "没有找到关于'$query'的记忆。当前共有 ${entries.size} 条记忆。"
            }

            val sb = StringBuilder()
            sb.append("我的记忆（${filtered.size}条）：\n")
            for ((i, entry) in filtered.withIndex()) {
                val fact = entry["fact"] as? String ?: continue
                val cat = entry["category"] as? String ?: "general"
                sb.append("${i + 1}. [$cat] $fact\n")
            }
            sb.toString().trimEnd().take(1500)
        } catch (e: Exception) {
            "回忆失败：${e.message}"
        }
    }
}
