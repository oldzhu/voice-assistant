package com.example.voiceassistant.llm.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Stdio-based MCP transport — spawns a local process and communicates via stdin/stdout.
 *
 * On Android, this is limited to executables that exist on the device filesystem
 * (e.g., Go/Rust-compiled binaries pushed via adb, or shell scripts).
 * No npx/uvx — those require Node.js/Python which aren't available on stock Android.
 *
 * Usage:
 *   val transport = StdioMcpTransport("/data/local/tmp/mcp-server-time", listOf("--port", "8080"))
 *   transport.connect()
 *   val response = transport.send(requestJson)
 *   transport.disconnect()
 */
class StdioMcpTransport(
    private val command: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap()
) : McpTransport {

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(command, *args.toTypedArray())
        pb.environment().putAll(env)
        pb.redirectErrorStream(true) // Merge stderr into stdout

        process = pb.start()
        reader = BufferedReader(InputStreamReader(process!!.inputStream))
        writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
    }

    override suspend fun send(requestJson: String): String = withContext(Dispatchers.IO) {
        val w = writer ?: throw IllegalStateException("Transport not connected")
        val r = reader ?: throw IllegalStateException("Transport not connected")

        // MCP stdio uses newline-delimited JSON
        w.write(requestJson.replace("\n", "") + "\n")
        w.flush()

        // Read response — single line of JSON
        r.readLine() ?: throw IllegalStateException("No response from MCP server")
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null; reader = null; writer = null
    }
}
