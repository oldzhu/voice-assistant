package com.example.voiceassistant.config

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Centralized memory store with deduplication, access tracking, and context injection.
 *
 * Replaces raw file I/O in RememberTool/RecallTool with structured management.
 *
 * Memory file: filesDir/assistant_memory.json (JSON lines, one MemoryEntry per line)
 */
class MemoryManager(private val memoryFile: File) {
    data class MemoryEntry(
        val fact: String,
        val category: String = "general",
        var timestamp: Long = System.currentTimeMillis(),
        var accessCount: Int = 0,
        var lastAccessed: Long = System.currentTimeMillis()
    )

    private val gson = Gson()
    private var entries: MutableList<MemoryEntry> = mutableListOf()
    private var loaded = false

    /** Load memories from disk. Idempotent — only loads once. */
    fun load() {
        if (loaded) return
        loaded = true
        if (!memoryFile.exists()) return
        try {
            entries = memoryFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { gson.fromJson(line, MemoryEntry::class.java) }
                    catch (_: Exception) { null }
                }
                .toMutableList()
        } catch (_: Exception) {
            entries = mutableListOf()
        }
    }

    /** Save all entries to disk atomically. */
    private fun save() {
        try {
            val lines = entries.joinToString("\n") { gson.toJson(it) } + "\n"
            val tmp = File(memoryFile.parent, "${memoryFile.name}.tmp")
            tmp.writeText(lines)
            tmp.renameTo(memoryFile)
        } catch (_: Exception) { /* best-effort */ }
    }

    /**
     * Add a fact. Deduplicates by exact fact text.
     * Returns true if added, false if duplicate.
     */
    fun remember(fact: String, category: String = "general"): Boolean {
        val trimmed = fact.trim()
        // Dedup: check if same fact already exists
        if (entries.any { it.fact.equals(trimmed, ignoreCase = true) }) {
            // Update timestamp on the existing entry
            entries.find { it.fact.equals(trimmed, ignoreCase = true) }?.let {
                it.timestamp = System.currentTimeMillis()
                it.accessCount++
            }
            save()
            return false // duplicate, but refreshed
        }
        entries.add(MemoryEntry(trimmed, category.trim()))
        // Keep max 50 memories, remove oldest
        if (entries.size > 50) {
            entries.sortByDescending { it.accessCount }
            entries = entries.take(50).toMutableList()
        }
        save()
        return true
    }

    /**
     * Query memories by keyword (substring match on fact text).
     * Records access for matched entries.
     */
    fun query(keyword: String = "", limit: Int = 10): List<MemoryEntry> {
        val results = if (keyword.isBlank()) {
            entries.toList()
        } else {
            entries.filter { it.fact.contains(keyword, ignoreCase = true) }
        }
        // Mark accessed
        val now = System.currentTimeMillis()
        results.forEach {
            it.accessCount++
            it.lastAccessed = now
        }
        if (results.any { it.accessCount > 0 }) save()
        return results.sortedByDescending { it.accessCount * 1000L + it.timestamp }.take(limit)
    }

    /**
     * Get top N most relevant memories for context injection.
     * Sort by access count (frequently used = more relevant), then recency.
     */
    fun getContextMemories(limit: Int = 5): List<MemoryEntry> {
        return entries
            .sortedByDescending { it.accessCount * 1000L + it.timestamp }
            .take(limit)
    }

    /**
     * Format memories as a string for system prompt injection.
     */
    fun formatForPrompt(limit: Int = 5): String {
        val mems = getContextMemories(limit)
        if (mems.isEmpty()) return ""
        return buildString {
            append("关于用户的重要信息（来自之前的对话）：\n")
            for ((i, m) in mems.withIndex()) {
                append("${i + 1}. [${m.category}] ${m.fact}\n")
            }
        }
    }

    /** Number of stored memories. */
    fun size(): Int = entries.size

    /** Clear all memories. */
    fun clear() {
        entries.clear()
        save()
    }

    /** Delete a specific memory by its fact text (exact match). */
    fun forget(fact: String): Boolean {
        val removed = entries.removeAll { it.fact.equals(fact.trim(), ignoreCase = true) }
        if (removed) save()
        return removed
    }

    /** Format query results as a readable string (for what_do_you_know tool). */
    fun formatResults(keyword: String = ""): String {
        val results = query(keyword)
        if (results.isEmpty()) {
            return if (keyword.isBlank()) "我还没有记住任何信息。"
            else "没有找到关于'$keyword'的记忆。"
        }
        return buildString {
            append("我的记忆（${results.size}条）：\n")
            for ((i, entry) in results.withIndex()) {
                append("${i + 1}. [${entry.category}] ${entry.fact}\n")
            }
        }.trimEnd().take(1500)
    }
}
