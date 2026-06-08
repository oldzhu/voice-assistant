package com.example.voiceassistant.config

import android.util.Log
import com.example.voiceassistant.llm.LLMBackend
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Persists conversation history across app restarts.
 *
 * Android aggressively kills background services, especially on Chinese ROMs
 * (OPPO/Realme battery optimization). Without persistence, every restart is a
 * fresh session — the assistant forgets everything you just talked about.
 *
 * Storage:
 *   filesDir/conversation_history.json — JSON array of {role, content} pairs.
 *   Writes are atomic (temp file → rename) to prevent corruption on kill.
 *
 * @param storageDir App's internal files directory (context.filesDir).
 */
class ConversationStore(private val storageDir: File) {
    companion object {
        private const val TAG = "ConversationStore"
        private const val FILENAME = "conversation_history.json"
        private const val MAX_HISTORY = 50  // keep more than in-memory (20) as buffer
    }

    private val gson = Gson()
    private val file: File get() = File(storageDir, FILENAME)
    private val tempFile: File get() = File(storageDir, "$FILENAME.tmp")

    /**
     * Save conversation history to disk atomically.
     *
     * Only keeps the most recent [MAX_HISTORY] messages to bound file size.
     * Writes to a temp file first, then renames — if the process is killed
     * mid-write, the original file stays intact.
     */
    fun save(history: List<LLMBackend.ChatMessage>) {
        try {
            val toSave = history.takeLast(MAX_HISTORY).map {
                mapOf("role" to it.role, "content" to it.content)
            }
            val json = gson.toJson(toSave)
            tempFile.writeText(json)
            tempFile.renameTo(file)
            Log.d(TAG, "Saved ${toSave.size} messages (${json.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history: ${e.message}")
        }
    }

    /**
     * Load previously saved conversation history.
     *
     * Returns an empty list if no history file exists or if the file is corrupt.
     * Corrupt files are deleted to avoid blocking future saves.
     */
    fun load(): List<LLMBackend.ChatMessage> {
        return try {
            if (!file.exists()) {
                Log.d(TAG, "No saved history found — starting fresh")
                return emptyList()
            }
            val json = file.readText()
            val type = object : TypeToken<List<Map<String, String>>>() {}.type
            val raw: List<Map<String, String>> = gson.fromJson(json, type) ?: emptyList()
            val messages = raw.mapNotNull { entry ->
                val role = entry["role"] ?: return@mapNotNull null
                val content = entry["content"] ?: return@mapNotNull null
                LLMBackend.ChatMessage(role, content)
            }
            Log.d(TAG, "Loaded ${messages.size} messages from history")
            messages
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history (deleting corrupt file): ${e.message}")
            try { file.delete() } catch (_: Exception) {}
            emptyList()
        }
    }

    /**
     * Delete the persisted history file (e.g., when user clears history).
     */
    fun clear() {
        try {
            file.delete()
            tempFile.delete()
            Log.d(TAG, "History file cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear history: ${e.message}")
        }
    }
}
