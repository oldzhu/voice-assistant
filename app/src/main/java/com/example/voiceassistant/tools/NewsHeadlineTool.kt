package com.example.voiceassistant.tools

import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tool: get_news — fetch latest Chinese news headlines from Sina News API.
 *
 * No API key required. Returns top 5 headlines with brief summaries.
 * API: feed.mix.sina.com.cn — Chinese server, fast in China region.
 *
 * LLM prompt: "有新闻吗" → LLM calls get_news() → reads headlines back to user.
 */
class NewsHeadlineTool : Tool {

    companion object {
        // lid category codes:
        // 2509 = 国内新闻 (domestic), 2510 = 国际新闻 (world),
        // 2511 = 社会新闻 (society), 2512 = 体育 (sports),
        // 2513 = 娱乐 (entertainment), 2514 = 科技 (tech), 2515 = 财经 (finance)
        private const val API_URL = "https://feed.mix.sina.com.cn/api/roll/get"
    }

    override val name = "get_news"
    override val description = "获取最新中文新闻头条。不需要参数，返回5条最新国内新闻标题和摘要。当用户问'有新闻吗'、'今天有什么新闻'时调用。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "category" to ToolParameter(
            type = "string",
            description = "新闻类别：国内默认不传 或 'world'(国际) 'tech'(科技) 'sports'(体育) 'finance'(财经)",
            required = false
        )
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun execute(args: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val category = args["category"] as? String ?: ""
        val lid = when (category.lowercase()) {
            "world", "国际" -> "2510"
            "tech", "科技" -> "2514"
            "sports", "体育" -> "2512"
            "finance", "财经" -> "2515"
            "entertainment", "娱乐" -> "2513"
            "society", "社会" -> "2511"
            else -> "2509" // domestic default
        }

        try {
            val url = "$API_URL?pageid=153&lid=$lid&k=&num=5&page=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 VoiceAssistant/1.0")
                .header("Referer", "https://news.sina.com.cn/")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext "新闻获取失败：空响应"

            @Suppress("UNCHECKED_CAST")
            val json = gson.fromJson(body, Map::class.java) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val result = json["result"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val data = result?.get("data") as? List<Map<String, Any?>> ?: return@withContext "暂无新闻数据"

            if (data.isEmpty()) return@withContext "暂无新闻"

            val sb = StringBuilder()
            val catName = when (category.lowercase()) {
                "world", "国际" -> "国际新闻"
                "tech", "科技" -> "科技新闻"
                "sports", "体育" -> "体育新闻"
                "finance", "财经" -> "财经新闻"
                else -> "今日新闻"
            }
            sb.append("📰 $catName：\n")

            data.take(5).forEachIndexed { i, item ->
                val title = item["title"] as? String ?: ""
                val intro = item["intro"] as? String ?: ""
                // Concise: title + first sentence of intro
                val summary = if (intro.length > 60) intro.take(60) + "…" else intro
                sb.append("${i + 1}. $title")
                if (summary.isNotBlank()) sb.append(" —— $summary")
                sb.append("\n")
            }

            sb.toString().take(1500)
        } catch (e: Exception) {
            "获取新闻失败：${e.message?.take(100) ?: "网络错误"}"
        }
    }
}
