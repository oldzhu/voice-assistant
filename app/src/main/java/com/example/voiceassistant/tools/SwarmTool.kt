package com.example.voiceassistant.tools

import com.example.voiceassistant.llm.LLMBackend
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Tool: swarm_query — execute multiple independent LLM queries in parallel.
 *
 * When the user asks a complex question that requires multiple independent
 * lookups (e.g., "compare weather in Beijing and Shanghai"), the LLM can
 * call swarm_query with separate sub-queries. Each runs as an independent
 * LLM call in parallel, and results are combined.
 *
 * Uses plain chat (no tool recursion) to keep each sub-query focused.
 *
 * Example:
 *   swarm_query(
 *     queries=["北京今天天气如何？只返回温度和天气状况，不超过20字",
 *              "上海今天天气如何？只返回温度和天气状况，不超过20字"]
 *   )
 *   → results combined with labels
 */
class SwarmTool(
    private val llmBackend: () -> LLMBackend?
) : Tool {
    override val name = "swarm_query"
    override val description = "并行执行多个独立的查询。当需要对比、同时查询多个信息时使用。" +
        "例如用户问「对比北京和上海的天气」「查一下科技和体育新闻」，" +
        "将每个子问题作为一个独立的查询，结果会并行执行并汇总。"
    override val parameters = mapOf(
        "queries" to ToolParameter("string",
            "用 ||| 分隔的多个独立查询。每个查询应该是一个自包含的问题，" +
            "要求 LLM 简短回答（不超过 30 字）。例如：'北京天气如何？简短回答|||上海天气如何？简短回答'")
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val queriesRaw = args["queries"] as? String
            ?: return "错误：缺少 queries 参数"
        val queries = queriesRaw.split("|||")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (queries.isEmpty()) return "错误：queries 不能为空"
        if (queries.size > 5) return "错误：最多支持 5 个并行查询"

        val llm = llmBackend() ?: return "错误：LLM 后端未初始化"

        return try {
            val results = coroutineScope {
                queries.mapIndexed { index, query ->
                    async {
                        val label = "${index + 1}. 「$query」"
                        try {
                            val result = llm.chat(query, emptyList())
                            val answer = result.getOrElse { ex -> "失败：${ex.message}" }
                            "$label → ${answer.take(100)}"
                        } catch (e: Exception) {
                            "$label → 错误：${e.message}"
                        }
                    }
                }.map { it.await() }
            }
            results.joinToString("\n")
        } catch (e: Exception) {
            "swarm 执行失败：${e.message}"
        }
    }
}
