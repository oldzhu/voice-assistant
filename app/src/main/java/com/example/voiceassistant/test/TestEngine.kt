package com.example.voiceassistant.test

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured test framework for automated quality assurance.
 *
 * Writes [TEST:...] markers to debug.log for host-side parsing.
 * The Python test runner reads these via `adb shell run-as ... cat files/debug.log`.
 *
 * Marker format:
 *   [TEST:START:<suite>/<test>] params...
 *   [TEST:RESULT:<suite>/<test>] key=value ...
 *   [TEST:END:<suite>/<test>] duration_ms=N passed=true|false
 *
 * Test suites (each test name is a unique ID):
 *   tts/roundtrip    — TTS → ASR → similarity check
 *   init/engines     — All engines initialize without crash
 *   llm/connectivity — LLM ping → response
 *   llm/tools        — Single tool call pipeline
 *   llm/multi_tool   — Multiple tool calls in one response (v2)
 *   e2e/full_pipeline — Full user experience pipeline
 *   tool/location    — GPS + reverse geocoding (v2)
 *   tool/news        — Sina news headlines (v2)
 *   tool/web_fetch   — HTML fetch + OOM safety check (v2)
 *   tool/config      — update_config write test (v2)
 *   tool/memory      — remember → what_do_you_know round-trip (v2)
 *   tool/barge_in    — set_barge_in_mode all modes (v2)
 *   tool/clear_history — clear_history test (v2)
 */
object TestEngine {

    private const val TAG = "TestEngine"

    /** Current test suite + name, e.g. "tts/roundtrip" */
    @Volatile var currentTest: String = ""
    @Volatile private var testStartTime: Long = 0

    /** Callbacks that the host test runner subscribes to via VoiceService */
    @Volatile var onTestCompleted: ((suite: String, test: String, passed: Boolean, results: Map<String, String>) -> Unit)? = null

    private var context: Context? = null
    private var logWriter: FileWriter? = null

    fun init(ctx: Context) {
        context = ctx
    }

    // ── Marker output ───────────────────────────────────────

    fun start(suite: String, test: String, params: Map<String, String> = emptyMap()) {
        currentTest = "$suite/$test"
        testStartTime = System.currentTimeMillis()
        val paramStr = params.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val msg = "[TEST:START:$currentTest] $paramStr"
        writeLog(msg)
        Log.i(TAG, msg)
    }

    fun result(key: String, value: String) {
        val msg = "[TEST:RESULT:$currentTest] $key=$value"
        writeLog(msg)
        Log.i(TAG, msg)
    }

    fun end(passed: Boolean, extra: Map<String, String> = emptyMap()) {
        val durationMs = System.currentTimeMillis() - testStartTime
        val entries = mutableMapOf("duration_ms" to durationMs.toString(), "passed" to passed.toString())
        entries.putAll(extra)
        val msg = "[TEST:END:$currentTest] ${entries.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        writeLog(msg)
        Log.i(TAG, "$msg")

        val suite = currentTest.substringBefore("/")
        val name = currentTest.substringAfter("/")
        onTestCompleted?.invoke(suite, name, passed, entries + mapOf("suite" to suite, "test" to name))
        currentTest = ""
    }

    fun pass() = end(true)
    fun fail(reason: String = "") = end(false, if (reason.isNotEmpty()) mapOf("reason" to reason) else emptyMap())

    fun log(msg: String) {
        writeLog("[TEST:LOG:$currentTest] $msg")
        Log.i(TAG, msg)
    }

    // ── Utility ─────────────────────────────────────────────

    fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n)
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return dp[m][n]
    }

    /** Similarity 0-100, comparing Chinese characters only */
    fun textSimilarity(a: String, b: String): Int {
        val clean = { s: String -> s.replace(Regex("[^\\u4e00-\\u9fff]"), "") }
        val ca = clean(a); val cb = clean(b)
        if (ca.isEmpty() || cb.isEmpty()) return 0
        val maxLen = maxOf(ca.length, cb.length)
        val dist = levenshtein(ca, cb)
        return ((maxLen - dist).toDouble() / maxLen * 100).toInt()
    }

    /** Elapsed time in ms */
    fun elapsedMs(): Long = System.currentTimeMillis() - testStartTime

    // ── Internal ────────────────────────────────────────────

    private fun writeLog(msg: String) {
        val ctx = context ?: return
        try {
            val f = File(ctx.filesDir, "debug.log")
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            FileWriter(f, true).use { it.write("${sdf.format(Date())} $msg\n") }
        } catch (_: Exception) {}
    }
}
