package com.example.voiceassistant.tools

import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// ==================================================================
// Shared HTTP client — reused across all external tools
// ==================================================================

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private val gson = Gson()

// ==================================================================
// WebSearchTool — DuckDuckGo Instant Answer API
// ==================================================================

/**
 * Tool: web_search — search the web via DuckDuckGo Instant Answer API.
 *
 * No API key required. Returns abstract + related topics.
 * LLM prompt: "帮我搜索深圳天气" → LLM calls web_search(query="深圳天气")
 */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "搜索网页信息。当用户问'搜索XX'、'查一下XX'、'XX是什么'等需要联网查找的问题时调用。"
    override val parameters = mapOf(
        "query" to ToolParameter(
            type = "string",
            description = "搜索关键词，用中文或英文",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext "错误：缺少搜索关键词"

        try {
            val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder().url(url).header("User-Agent", "VoiceAssistant/1.0").build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext "搜索无结果"

            @Suppress("UNCHECKED_CAST")
            val json = gson.fromJson(body, Map::class.java) as Map<String, Any?>

            val abstract = json["AbstractText"] as? String ?: ""
            val abstractUrl = json["AbstractURL"] as? String ?: ""

            // Related topics
            @Suppress("UNCHECKED_CAST")
            val relatedTopics = json["RelatedTopics"] as? List<Map<String, Any?>> ?: emptyList()
            val topics = relatedTopics.take(3).mapNotNull { it["Text"] as? String }

            buildString {
                if (abstract.isNotBlank()) {
                    append(abstract.take(500))
                } else {
                    append("未找到直接答案。")
                }
                if (topics.isNotEmpty()) {
                    append("\n相关内容：")
                    topics.forEach { append("\n- ${it.take(200)}") }
                }
                if (abstractUrl.isNotBlank()) {
                    append("\n来源：$abstractUrl")
                }
            }.take(2000) // Truncate for TTS-friendliness
        } catch (e: Exception) {
            "搜索失败：${e.message?.take(100) ?: "未知错误"}"
        }
    }
}

// ==================================================================
// WebFetchTool — fetch and extract text from any URL
// ==================================================================

/**
 * Tool: web_fetch — download a webpage and extract readable text.
 *
 * Strips HTML tags via regex (simple, no Jsoup dependency).
 * LLM prompt: "打开这个网页看看" → LLM calls web_fetch(url="...")
 */
class WebFetchTool : Tool {
    override val name = "web_fetch"
    override val description = "获取网页内容并提取文字。当用户说'打开这个链接'、'看看这个网页'、'阅读XX'时调用。"
    override val parameters = mapOf(
        "url" to ToolParameter(
            type = "string",
            description = "要获取的网页完整URL（以http://或https://开头）",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext "错误：缺少URL参数"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext "错误：URL必须以http://或https://开头"
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 VoiceAssistant/1.0")
                .build()
            val response = httpClient.newCall(request).execute()
            val rawHtml = response.body?.string() ?: return@withContext "网页内容为空"

            // SAFETY: Truncate HTML to 200KB before regex processing.
            // Large news pages can be several MB — regex on full HTML causes
            // catastrophic backtracking (ReDoS) → OOM crash / ANR.
            val html = if (rawHtml.length > 200_000) rawHtml.take(200_000) else rawHtml

            // Extract title (small, safe regex)
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                .find(html.take(2000))?.groupValues?.get(1)?.trim() ?: ""

            // Strip tags sequentially (no .* across whole document — O(n) per pass)
            val stripped = html
                // Remove script + style blocks (lazy quantifier, bounded)
                .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE)), " ")
                .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE)), " ")
                // Remove all HTML tags
                .replace(Regex("<[^>]+>"), " ")
                // Decode entities
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                // Collapse whitespace
                .replace(Regex("\\s+"), " ")
                .trim()

            buildString {
                if (title.isNotBlank()) {
                    append("📄 $title\n\n")
                }
                append(stripped.take(1500))
                if (stripped.length > 1500) append("...")
            }.take(2000)
        } catch (e: Exception) {
            "获取网页失败：${e.message?.take(100) ?: "未知错误"}"
        }
    }
}

// ==================================================================
// WeatherTool — wttr.in weather API
// ==================================================================

/**
 * Tool: get_weather — look up current weather via wttr.in.
 *
 * No API key required. Returns temp, condition, humidity, wind.
 * LLM prompt: "今天天气怎么样" → LLM calls get_weather(city="Beijing")
 */
class WeatherTool : Tool {
    override val name = "get_weather"
    override val description = "查询天气。当用户问'天气怎么样'、'XX天气'、'今天热不热'等时调用。"
    override val parameters = mapOf(
        "city" to ToolParameter(
            type = "string",
            description = "城市名称，中文或英文。如'深圳'、'北京'、'Shanghai'",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val city = args["city"] as? String ?: return@withContext "错误：缺少城市名称"

        try {
            val url = "https://wttr.in/${java.net.URLEncoder.encode(city, "UTF-8")}?format=j1"
            val request = Request.Builder().url(url).header("User-Agent", "VoiceAssistant/1.0").build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext "天气数据获取失败"

            @Suppress("UNCHECKED_CAST")
            val json = gson.fromJson(body, Map::class.java) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val currentCondition = json["current_condition"] as? List<Map<String, Any?>>
            val current = currentCondition?.firstOrNull()

            if (current == null) return@withContext "未找到${city}的天气数据"

            val tempC = current["temp_C"] as? String ?: "?"
            val feelsLike = current["FeelsLikeC"] as? String ?: "?"
            val humidity = current["humidity"] as? String ?: "?"
            val weatherDesc = (current["weatherDesc"] as? List<Map<String, Any?>>)
                ?.firstOrNull()?.get("value") as? String ?: "未知"
            val windSpeed = current["windspeedKmph"] as? String ?: "?"
            val windDir = current["winddir16Point"] as? String ?: ""

            // Get city name from nearest_area
            @Suppress("UNCHECKED_CAST")
            val nearestArea = json["nearest_area"] as? List<Map<String, Any?>>
            val areaName = (nearestArea?.firstOrNull()
                ?.get("areaName") as? List<Map<String, Any?>>)
                ?.firstOrNull()?.get("value") as? String ?: city

            buildString {
                append("${areaName}天气：${weatherDesc}，${tempC}°C（体感${feelsLike}°C）")
                append("，湿度${humidity}%")
                if (windSpeed != "0" && windSpeed != "?") {
                    append("，${windDir}风${windSpeed}km/h")
                }
            }
        } catch (e: Exception) {
            "查询天气失败：${e.message?.take(100) ?: "未知错误"}"
        }
    }
}
