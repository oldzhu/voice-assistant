package com.example.voiceassistant.tools

import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tool: read_article — fetch and clean web page text for TTS reading.
 *
 * When the user says "读一下《三体》" or "给我读这篇文章", this tool:
 * 1. Fetches the page (URL or search → first result)
 * 2. Extracts clean, readable text (no nav, ads, boilerplate)
 * 3. Truncates to TTS-friendly length
 * 4. Returns formatted text ready for speech
 *
 * LLM prompt: "读一下XX" → read_article(query="XX")
 *             "打开这个链接读" → read_article(url="https://...")
 */
class ReadAloudTool : Tool {

    companion object {
        private const val MAX_CHARS = 1500  // TTS-friendly chunk size
        private const val MIN_PARAGRAPH_LEN = 30  // skip nav/short boilerplate lines
    }

    override val name = "read_article"
    override val description = "获取网页文章内容，清洗后用于朗读。当用户说'读一下XX'、'给我读XX'、'念一下XX'时调用。" +
        "可以传 query 搜索，也可以直接传 url 抓取。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            type = "string",
            description = "搜索关键词，如'三体 第一部'、'静夜思 全文'。与 url 二选一。",
            required = false
        ),
        "url" to ToolParameter(
            type = "string",
            description = "直接要朗读的网页地址。优先于 query。",
            required = false
        )
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: Map<String, Any?>): String = withContext(Dispatchers.IO) {
        val url = args["url"] as? String
        val query = args["query"] as? String

        val targetUrl: String
        if (!url.isNullOrBlank()) {
            targetUrl = url
        } else if (!query.isNullOrBlank()) {
            // Step 1: search for a relevant URL
            targetUrl = searchForUrl(query)
            if (targetUrl.isEmpty()) {
                return@withContext "抱歉，没找到「${query.take(30)}」的相关内容。试试换个关键词？"
            }
        } else {
            return@withContext "错误：请提供 url 或 query 参数"
        }

        // Step 2: fetch and clean
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 VoiceAssistant/1.0")
                .build()

            val response = client.newCall(request).execute()
            val rawHtml = response.body?.string()?.take(200_000) ?: ""
            response.close()

            if (rawHtml.isBlank()) {
                return@withContext "页面内容为空，可能无法访问。"
            }

            // Step 3: extract clean text
            val cleaned = extractReadableText(rawHtml)
            if (cleaned.isBlank()) {
                return@withContext "页面内容提取失败，网页结构可能不兼容。"
            }

            // Step 4: extract a rough title
            val title = extractTitle(rawHtml).take(80)
            val titleLine = if (title.isNotBlank()) "📖 $title\n\n" else "📖 文章内容：\n\n"

            // Step 5: truncate and format
            val body = if (cleaned.length > MAX_CHARS) {
                cleaned.take(MAX_CHARS).trim() + "\n\n(说「继续」听下一段)"
            } else {
                cleaned.trim()
            }

            return@withContext titleLine + body
        } catch (e: Exception) {
            "获取文章失败：${e.message?.take(100) ?: "网络错误"}"
        }
    }

    // ── Search helper ───────────────────────────────────────

    /**
     * Search for a relevant URL using Bing (accessible in China).
     * Falls back to Baidu if Bing fails.
     */
    private fun searchForUrl(query: String): String {
        // Try Bing first — clean HTML, accessible in China
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.bing.com/search?q=$encoded&setlang=zh-cn"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()?.take(100_000) ?: ""
            response.close()

            // Extract first search result URL from Bing HTML
            // Bing results: <li class="b_algo"><h2><a href="URL">
            val url = extractBingResult(body)
            if (!url.isNullOrBlank()) return url

            // Fallback: try Baidu mobile
            searchBaidu(query)
        } catch (_: Exception) {
            try { searchBaidu(query) } catch (_: Exception) { "" }
        }
    }

    private fun extractBingResult(html: String): String? {
        // Bing result links: <li class="b_algo"> ... <h2><a href="URL"
        val algoPattern = Regex("<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>.*?<a[^>]*href=\"(https?://[^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
        val match = algoPattern.find(html) ?: return null
        val url = match.groupValues.getOrNull(1) ?: return null
        // Filter out Bing internal links and ads
        if (url.contains("bing.com") || url.contains("go.microsoft.com")) return null
        return url
    }

    private fun searchBaidu(query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://m.baidu.com/s?word=$encoded"
        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()?.take(100_000) ?: ""
        response.close()

        // Baidu mobile results: <a href="URL" class="c-showurl"... or data-log with url
        // Simpler: extract any http URL from result area
        val urlPattern = Regex("class=\"c-result[^\"]*\"[^>]*>.*?href=\"(https?://[^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
        val match = urlPattern.find(body) ?: return ""
        val url = match.groupValues.getOrNull(1) ?: return ""
        if (url.contains("baidu.com")) return ""
        return url
    }

    // ── Text cleaning ───────────────────────────────────────

    /**
     * Extract readable text from HTML. Aggressive cleaning for TTS.
     */
    private fun extractReadableText(html: String): String {
        // 1. Remove <head>, <script>, <style>, <nav>, <footer>
        var text = html
            .replace(Regex("<head[^>]*>.*?</head>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<nav[^>]*>.*?</nav>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<footer[^>]*>.*?</footer>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<header[^>]*>.*?</header>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<aside[^>]*>.*?</aside>", RegexOption.DOT_MATCHES_ALL), "")

        // 2. Strip all remaining HTML tags
        text = text.replace(Regex("<[^>]+>"), " ")

        // 3. Decode common entities
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")

        // 4. Split into lines, filter boilerplate
        val lines = text.split('\n')
            .map { it.trim() }
            .filter { line ->
                line.length >= MIN_PARAGRAPH_LEN &&
                !line.matches(Regex("^[\\s\\p{Punct}\\d]+$")) &&   // not just punctuation/numbers
                !line.contains("function(", ignoreCase = true) &&
                !line.contains("var ", ignoreCase = true) &&
                !line.contains("cookie", ignoreCase = true) &&
                !line.contains("广告") &&
                !line.contains("Copyright") &&
                !line.contains("版权所有") &&
                !line.contains("导航") &&
                !line.contains("登录") &&
                !line.contains("注册") &&
                !line.contains("评论") &&
                !line.contains("分享") &&
                !line.startsWith("//") &&
                !line.startsWith("/*")
            }

        // 5. Merge into paragraphs, normalize whitespace
        return lines.joinToString("\n\n")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Extract a rough title from HTML.
     */
    private fun extractTitle(html: String): String {
        val titleMatch = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)
        val title = titleMatch?.groupValues?.getOrNull(1) ?: ""

        return title
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            // Strip site name suffixes
            .replace(Regex("\\s*[-–|—]\\s*.+$"), "")
            .replace(Regex("\\s*_.+_\\s*$"), "")
            .trim()
    }
}
