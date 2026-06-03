package com.example.voiceassistant.speech

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Converts Chinese text to Bopomofo (注音) using the model's lexicon.
 * Bypasses sherpa-onnx's C++ text frontend to avoid ARM64 issues.
 */
object BopomofoConverter {
    private var lexicon: Map<String, String>? = null

    fun load(context: Context): Boolean {
        if (lexicon != null) return true
        return try {
            val map = mutableMapOf<String, String>()
            context.assets.open("sherpa-onnx-vits-zh-ll/lexicon.txt").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                    val parts = trimmed.split(" ", limit = 2)
                    if (parts.size == 2) {
                        map[parts[0]] = parts[1]
                    }
                }
            }
            lexicon = map
            android.util.Log.i("BopomofoConverter", "Loaded ${map.size} entries")
            true
        } catch (e: Exception) {
            android.util.Log.e("BopomofoConverter", "Failed to load lexicon", e)
            false
        }
    }

    /**
     * Convert Chinese text to bopomofo string.
     * Unknown characters are kept as-is.
     */
    fun convert(text: String): String {
        val dict = lexicon ?: return text
        val sb = StringBuilder()
        for (ch in text) {
            val bpmf = dict[ch.toString()]
            if (bpmf != null) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                sb.append(bpmf)
            }
        }
        return sb.toString().trim()
    }
}
