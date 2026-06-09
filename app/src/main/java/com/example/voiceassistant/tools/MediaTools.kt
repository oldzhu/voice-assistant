package com.example.voiceassistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════
// SearchMediaTool — search for songs, videos, novels
// ══════════════════════════════════════════════════════════

/**
 * Tool: search_media — search for media content.
 *
 * User: "帮我找周杰伦的晴天" → search_media(query="周杰伦 晴天", type="song")
 * User: "搜一下功夫熊猫视频" → search_media(query="功夫熊猫", type="video")
 *
 * Uses cn.bing.com (works in China). Parses <li class="b_algo"> result blocks.
 */
class SearchMediaTool : Tool {
    override val name = "search_media"
    override val description = "搜索媒体内容。当用户说'帮我找XX歌曲'、'搜XX视频'、'找XX小说'时调用。" +
        "返回最相关的结果和链接，用户可以说'打开'或'播放'来启动。"
    override val parameters = mapOf(
        "query" to ToolParameter("string", "搜索词，如'周杰伦 晴天'、'功夫熊猫'"),
        "type" to ToolParameter("string", "媒体类型：song(歌曲)、video(视频)、novel(小说/文章)", required = false)
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, Any?>): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext "错误：缺少 query 参数"
        val type = args["type"] as? String ?: ""

        val searchQuery = when (type) {
            "song" -> "$query 歌曲"
            "video" -> "$query 视频"
            "novel" -> "$query 小说 在线阅读"
            else -> query
        }

        try {
            val encoded = URLEncoder.encode(searchQuery, "UTF-8")
            val url = "https://cn.bing.com/search?q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) VoiceAssistant/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext "搜索失败：无响应"

            // Bing result structure:
            // <li class="b_algo">
            //   <div class="b_algoheader"><a href="URL" h="ID=SERP,..."><h2>Title</h2></a></div>
            //   <div class="b_caption"><p>...</p></div>
            // </li>
            val results = mutableListOf<Pair<String, String>>()

            // Match: <a href="URL" h="ID=SERP..."><h2>Title</h2></a>
            val h2LinkRegex = Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*h="ID=SERP[^"]*"[^>]*>\s*<h2[^>]*>([\s\S]*?)</h2>\s*</a>""")
            h2LinkRegex.findAll(html).forEach { match ->
                if (results.size >= 3) return@forEach
                val link = match.groupValues[1]
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (title.isNotBlank() && link.startsWith("http")) {
                    results.add(title to link)
                }
            }

            if (results.isEmpty()) {
                return@withContext "未找到「$query」的相关结果。请尝试更具体的关键词。"
            }

            val typeLabel = when (type) {
                "song" -> "歌曲"
                "video" -> "视频"
                "novel" -> "小说"
                else -> "内容"
            }

            val sb = StringBuilder("🔍 找到「$query」的${typeLabel}：\n")
            for ((i, r) in results.withIndex()) {
                sb.append("${i + 1}. ${r.first}\n   ${r.second}\n")
            }
            sb.append("\n说「打开第1个」来播放/查看。")
            sb.toString().take(1500)
        } catch (e: Exception) {
            "搜索失败：${e.message?.take(100) ?: "未知错误"}"
        }
    }
}

// ══════════════════════════════════════════════════════════
// PlayMediaTool — open media in appropriate app
// ══════════════════════════════════════════════════════════

/**
 * Tool: play_media — open a URL or search result in the appropriate app.
 *
 * User: "打开第1个" → play_media(url="https://...")
 * User: "播放" → play_media(url="...", type="song")
 *
 * For videos: fires ACTION_VIEW → YouTube/Bilibili/browser
 * For songs: fires ACTION_VIEW → music apps or browser
 */
class PlayMediaTool(private val context: () -> Context) : Tool {
    override val name = "play_media"
    override val description = "打开或播放媒体内容。当用户说'打开'、'播放'、'看第一个'、'放这个'时调用。" +
        "用 ACTION_VIEW Intent 调起系统对应 App（音乐/视频/浏览器）。"
    override val parameters = mapOf(
        "url" to ToolParameter("string", "要播放的媒体链接（通常是 search_media 返回的链接）"),
        "type" to ToolParameter("string", "媒体类型提示：song、video（帮助选择合适的 App）", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val url = args["url"] as? String ?: return "错误：缺少 url 参数"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误：无效的链接格式"
        }

        val type = args["type"] as? String ?: ""

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context().startActivity(intent)

            val typeDesc = when (type) {
                "song" -> "音乐 App"
                "video" -> "视频 App"
                else -> "浏览器"
            }
            "✅ 已在${typeDesc}中打开：$url"
        } catch (e: Exception) {
            "无法打开链接：${e.message?.take(100) ?: "未知错误"}。请检查是否安装了对应的 App。"
        }
    }
}
