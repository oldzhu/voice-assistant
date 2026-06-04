# Tool Calling Engine — Implementation Plan (Feature 1 of 5)

> **For Hermes:** Implement task-by-task. Each task = build, deploy, verify on phone.

**Goal:** Build an extensible tool-calling framework where the LLM can invoke local tools (stop listening, change settings, web search) via function_call, with multi-turn execution loop.

**Architecture:** Three layers — (1) `Tool` interface + registry, (2) `ToolCallEngine` that runs LLM↔tool loop, (3) `CloudLLMBackend` extended to pass tools to API and parse function_call responses. VoiceService delegates `processQuery()` to the engine.

**Tech Stack:** Kotlin, OkHttp + Gson (existing), OpenAI function-calling compatible API (DeepSeek supports it).

**Duration:** ~15 tasks, ~2 hours.

---

## Architecture Overview

```
VoiceService.processQuery("set speech rate to 1.5")
  → ToolCallEngine.chat(userMessage, history, tools)
    → CloudLLMBackend.chatWithTools(messages, tools)
      → POST /v1/chat/completions {tools: [...], tool_choice: "auto"}
      ← {finish_reason: "tool_calls", tool_calls: [{function: {name, arguments}}]}
    → ToolRegistry.execute("set_speech_rate", {"rate": 1.5})
    → CloudLLMBackend.chatWithTools(messages + tool_result, tools)
      ← {finish_reason: "stop", content: "已将语速调到1.5倍"}
  → VoiceService receives plain text → speakTts
```

**Data flow:**
```
User speech → ASR → processQuery → ToolCallEngine
  → loop { LLM (with tools) → if function_call: execute tool → feed result back to LLM }
  → final text response → TTS
```

---

## File Plan

| File | Action | Purpose |
|------|--------|---------|
| `llm/Tool.kt` | **Create** | Tool interface: name, description, parameters schema, execute() |
| `llm/ToolRegistry.kt` | **Create** | Registry: register tools, lookup by name, execute by name |
| `llm/CloudLLMBackend.kt` | **Modify** | Add `chatWithTools()` method + data classes for tools/function_call |
| `llm/ToolCallEngine.kt` | **Create** | Orchestrator: LLM↔tool loop, max 5 turns, timeout |
| `tools/ControlTools.kt` | **Create** | First batch: stop/start listening, set_speech_rate, set_barge_in_mode, clear_history |
| `VoiceService.kt` | **Modify** | Wire ToolCallEngine into processQuery(), pass tools |
| `config/ConfigManager.kt` | **Modify** | Persist tool enable/disable prefs (future) |

---

## Task Breakdown

### Task 1: Create `Tool` interface and `ToolParameter` data class

**Objective:** Define the contract all tools must implement.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/llm/Tool.kt`

**Step 1: Write the interface**

```kotlin
package com.example.voiceassistant.llm

import com.google.gson.annotations.SerializedName

/**
 * JSON Schema property definition for a tool parameter.
 */
data class ToolParameter(
    val type: String,           // "string" | "number" | "boolean" | "integer"
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null  // for constrained string params
)

/**
 * A tool (function) callable by the LLM.
 *
 * Implement this interface for each new tool.
 * The LLM sees [name], [description], and [parameters] as the function schema.
 * When the LLM calls it, [execute] receives the deserialized arguments as a Map.
 */
interface Tool {
    /** Unique function name (e.g., "set_speech_rate") */
    val name: String

    /** Human-readable description the LLM uses to decide when to call this */
    val description: String

    /** Parameter schema. Key = param name, value = schema. */
    val parameters: Map<String, ToolParameter>

    /**
     * Execute the tool with arguments from the LLM.
     *
     * @param args Parsed arguments from the function_call (Map of param_name → value)
     * @return Result string to feed back to the LLM
     */
    suspend fun execute(args: Map<String, Any?>): String

    /**
     * Serialize to OpenAI-compatible function definition JSON.
     * Used by CloudLLMBackend to build the tools array in the API request.
     */
    fun toFunctionDef(): Map<String, Any?> {
        val props = mutableMapOf<String, Map<String, Any?>>()
        val required = mutableListOf<String>()
        for ((key, param) in parameters) {
            val propDef = mutableMapOf<String, Any?>(
                "type" to param.type,
                "description" to param.description
            )
            param.enum?.let { propDef["enum"] = it }
            props[key] = propDef
            if (param.required) required.add(key)
        }
        return mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to props,
                    "required" to required
                )
            )
        )
    }
}
```

**Step 2: Build**

```bash
cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle assembleDebug
```

**Step 3: Verify** — No compile errors. Commit.

```bash
git add app/src/main/java/com/example/voiceassistant/llm/Tool.kt
git commit -m "feat: add Tool interface and ToolParameter data class"
```

---

### Task 2: Create `ToolRegistry`

**Objective:** Central registry to register and execute tools by name.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/llm/ToolRegistry.kt`

**Step 1: Write registry**

```kotlin
package com.example.voiceassistant.llm

/**
 * Registry for tool instances. Each tool is registered by its [Tool.name].
 *
 * Usage:
 *   val registry = ToolRegistry()
 *   registry.register(SetSpeechRateTool(...))
 *   val result = registry.execute("set_speech_rate", mapOf("rate" to 1.5))
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getAll(): List<Tool> = tools.values.toList()

    /** Get all tools as function definitions for the LLM API request */
    fun getFunctionDefs(): List<Map<String, Any?>> =
        tools.values.map { it.toFunctionDef() }

    /**
     * Execute a tool by name with parsed arguments.
     *
     * @return Result string, or error message if tool not found or execution fails.
     */
    suspend fun execute(name: String, args: Map<String, Any?>): String {
        val tool = tools[name]
            ?: return "Error: unknown tool '$name'. Available: ${tools.keys}"

        return try {
            tool.execute(args)
        } catch (e: Exception) {
            "Error executing '$name': ${e.message}"
        }
    }

    fun has(name: String): Boolean = tools.containsKey(name)
}
```

**Step 2: Build & verify**

```bash
cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle assembleDebug
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/example/voiceassistant/llm/ToolRegistry.kt
git commit -m "feat: add ToolRegistry for tool registration and execution"
```

---

### Task 3: Extend `CloudLLMBackend` with function calling support

**Objective:** Add `chatWithTools()` method that sends tools to the API and parses function_call responses.

**Files:**
- Modify: `app/src/main/java/com/example/voiceassistant/llm/CloudLLMBackend.kt`

**Step 1: Add data classes for function calling**

Add to `CloudLLMBackend.kt` after existing data classes:

```kotlin
// --- Function calling data classes ---

/** OpenAI-compatible tool definition in the request */
data class ApiTool(
    val type: String,           // always "function"
    val function: ApiFunction
)

data class ApiFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>  // JSON Schema
)

/** Request message with tool_calls (assistant role) */
data class ToolCallMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

data class ToolCallFunction(
    val name: String,
    val arguments: String  // JSON string
)

/** Tool result message (tool role) */
data class ToolResultMessage(
    val role: String = "tool",
    @SerializedName("tool_call_id") val toolCallId: String,
    val content: String
)
```

Also modify `Message` to use `@SerializedName`:

```kotlin
data class Message(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String?
)
```

And modify `Choice` to include `tool_calls`:

```kotlin
data class Choice(
    @SerializedName("index") val index: Int?,
    @SerializedName("message") val message: Message?,
    @SerializedName("finish_reason") val finishReason: String?
)
```

And modify `ChatResponse`:

```kotlin
data class ChatResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("choices") val choices: List<Choice>?
)
```

**Step 2: Add `chatWithTools()` method**

```kotlin
/**
 * Chat with function calling support.
 *
 * @param messages Full message list including system, user, assistant, and tool messages
 * @param functionDefs Tool function definitions from Tool.toFunctionDef()
 * @return Either a text response (finish_reason="stop") or a function call request
 */
data class ToolChatResult(
    val textResponse: String?,          // non-null if finish_reason="stop"
    val functionCall: Pair<String, Map<String, Any?>>?  // (name, args) if tool_calls
)

suspend fun chatWithTools(
    messages: List<Map<String, Any?>>,
    functionDefs: List<Map<String, Any?>>
): Result<ToolChatResult> = withContext(Dispatchers.IO) {
    try {
        val requestBody = mapOf<String, Any?>(
            "model" to model,
            "messages" to messages,
            "stream" to false,
            "max_tokens" to 500,
            "temperature" to 0.7,
            "tools" to functionDefs,
            "tool_choice" to "auto"
        )

        val jsonBody = gson.toJson(requestBody)
        Log.d(TAG, "ToolChat request: ${jsonBody.take(300)}...")

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            Log.e(TAG, "ToolChat error: ${response.code} — $body")
            return@withContext Result.failure(Exception("API error ${response.code}"))
        }

        // Parse response — handle both text and function_call
        val json = gson.fromJson(body, Map::class.java) as Map<String, Any?>
        val choices = json["choices"] as? List<Map<String, Any?>> ?: emptyList()
        val choice = choices.firstOrNull() ?: return@withContext Result.success(
            ToolChatResult(textResponse = "", functionCall = null)
        )

        val message = choice["message"] as? Map<String, Any?>
        val finishReason = choice["finish_reason"] as? String ?: "stop"

        if (finishReason == "tool_calls") {
            val toolCalls = message?.get("tool_calls") as? List<Map<String, Any?>>
            val tc = toolCalls?.firstOrNull()
            val func = tc?.get("function") as? Map<String, Any?>
            if (func != null) {
                val funcName = func["name"] as? String ?: ""
                val argsJson = func["arguments"] as? String ?: "{}"
                val args: Map<String, Any?> = try {
                    @Suppress("UNCHECKED_CAST")
                    (gson.fromJson(argsJson, Map::class.java) as? Map<String, Any?>) ?: emptyMap()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse tool args: $argsJson", e)
                    emptyMap()
                }
                Result.success(ToolChatResult(
                    textResponse = null,
                    functionCall = Pair(funcName, args)
                ))
            } else {
                Result.success(ToolChatResult(textResponse = null, functionCall = null))
            }
        } else {
            val content = message?.get("content") as? String ?: ""
            Result.success(ToolChatResult(textResponse = content.trim(), functionCall = null))
        }
    } catch (e: Exception) {
        Log.e(TAG, "ToolChat failed", e)
        Result.failure(e)
    }
}
```

**Step 3: Build & verify**

```bash
cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle assembleDebug
```

**Step 4: Commit**

```bash
git add app/src/main/java/com/example/voiceassistant/llm/CloudLLMBackend.kt
git commit -m "feat: add chatWithTools() with function calling support to CloudLLMBackend"
```

---

### Task 4: Create `ToolCallEngine`

**Objective:** The orchestrator that runs the LLM↔tool execution loop.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/llm/ToolCallEngine.kt`

**Step 1: Write the engine**

```kotlin
package com.example.voiceassistant.llm

import android.util.Log

/**
 * Orchestrates the LLM ↔ tool execution loop.
 *
 * Flow:
 * 1. Send user message + history + tool defs to LLM
 * 2. If LLM returns text → done, return text
 * 3. If LLM returns function_call → execute tool → feed result back to LLM
 * 4. Repeat until text response or max turns reached
 */
class ToolCallEngine(
    private val llmBackend: CloudLLMBackend,
    private val toolRegistry: ToolRegistry,
    private val maxTurns: Int = 5
) {
    companion object {
        private const val TAG = "ToolCallEngine"
    }

    /**
     * Process a user message with tool calling.
     *
     * @param userMessage The user's input text
     * @param history Previous chat messages
     * @return Final text response from LLM (after any tool calls are resolved)
     */
    suspend fun chat(
        userMessage: String,
        history: List<LLMBackend.ChatMessage>
    ): Result<String> {
        // Build initial message list as Maps (for flexibility with tool messages)
        val messages = mutableListOf<Map<String, Any?>>()

        // System prompt
        messages.add(mapOf(
            "role" to "system",
            "content" to buildSystemPrompt()
        ))

        // History
        val recentHistory = history.takeLast(10)
        for (msg in recentHistory) {
            messages.add(mapOf("role" to msg.role, "content" to msg.content))
        }

        // Current user message
        messages.add(mapOf("role" to "user", "content" to userMessage))

        // Tool calling loop
        var turn = 0
        while (turn < maxTurns) {
            turn++
            Log.d(TAG, "Turn $turn: messages=${messages.size}, tools=${toolRegistry.getAll().size}")

            val result = llmBackend.chatWithTools(messages, toolRegistry.getFunctionDefs())
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("LLM call failed"))
            }

            val toolResult = result.getOrNull() ?: continue

            // If LLM returned text, we're done
            if (toolResult.textResponse != null) {
                Log.d(TAG, "Turn $turn: text response (${toolResult.textResponse.length} chars)")
                return Result.success(toolResult.textResponse)
            }

            // If LLM wants to call a tool
            val (funcName, funcArgs) = toolResult.functionCall ?: run {
                Log.w(TAG, "Turn $turn: no text and no function_call, breaking")
                return Result.success("我暂时无法处理这个请求")
            }

            Log.d(TAG, "Turn $turn: function_call → $funcName($funcArgs)")

            // Execute the tool
            val toolOutput = toolRegistry.execute(funcName, funcArgs)
            Log.d(TAG, "Turn $turn: tool output → ${toolOutput.take(100)}")

            // Add assistant message with tool_call
            val toolCallId = "call_${funcName}_$turn"
            messages.add(mapOf(
                "role" to "assistant",
                "content" to null,
                "tool_calls" to listOf(mapOf(
                    "id" to toolCallId,
                    "type" to "function",
                    "function" to mapOf(
                        "name" to funcName,
                        "arguments" to gson.toJson(funcArgs)
                    )
                ))
            ))

            // Add tool result message
            messages.add(mapOf(
                "role" to "tool",
                "tool_call_id" to toolCallId,
                "content" to toolOutput
            ))
        }

        // Max turns exceeded
        Log.w(TAG, "Max turns ($maxTurns) exceeded")
        return Result.success("处理稍微复杂了点，请换个方式再说一遍")
    }

    private val gson = com.google.gson.Gson()

    private fun buildSystemPrompt(): String = buildString {
        append("你是猪头助手，一个友好的中文语音助手。")
        append("你可以调用工具来执行操作（如调整设置、搜索信息等）。")
        append("当用户要求执行某个操作时，请调用对应的工具函数。")
        append("工具执行完毕后，用口语化的中文总结结果，控制在2-3句话以内。")
        append("重要：用户的输入来自语音识别，可能对中英混杂词汇识别不准，请根据上下文自动纠正。")
    }
}
```

**Step 2: Build & verify**

```bash
cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle assembleDebug
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/example/voiceassistant/llm/ToolCallEngine.kt
git commit -m "feat: add ToolCallEngine — LLM↔tool execution loop"
```

---

### Task 5: Create first tool — `SetSpeechRate`

**Objective:** First concrete tool implementation. LLM can call `set_speech_rate` to adjust TTS speed.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/tools/ControlTools.kt`

**Step 1: Write the tool**

```kotlin
package com.example.voiceassistant.tools

import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter

/**
 * Tool: set_speech_rate — adjusts TTS playback speed.
 *
 * LLM sees: "Adjust the speech rate. 1.0 is normal, lower is slower, higher is faster."
 */
class SetSpeechRateTool(
    private val onSetRate: (Float) -> Unit
) : Tool {
    override val name = "set_speech_rate"
    override val description = "调整语音播报语速。1.0是正常速度，小于1是慢速，大于1是快速。范围0.5到2.5。"
    override val parameters = mapOf(
        "rate" to ToolParameter(
            type = "number",
            description = "语速，范围0.5-2.5，1.0是正常速度",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val rate = (args["rate"] as? Number)?.toFloat()
            ?: return "错误：缺少语速参数"
        val clamped = rate.coerceIn(0.5f, 2.5f)
        onSetRate(clamped)
        val label = when {
            clamped < 0.8f -> "慢速"
            clamped < 1.2f -> "正常"
            clamped < 1.8f -> "较快"
            else -> "快速"
        }
        return "语速已调到${clamped}倍（${label}）"
    }
}
```

**Step 2: Build & verify**

```bash
cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle assembleDebug
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/example/voiceassistant/tools/
git commit -m "feat: add SetSpeechRate tool"
```

---

### Task 6: Create remaining control tools (4 tools)

**Objective:** `stop_listening`, `start_listening`, `set_barge_in_mode`, `clear_history`

**Files:**
- Modify: `app/src/main/java/com/example/voiceassistant/tools/ControlTools.kt` (append)

**Step 1: Add tools to ControlTools.kt**

```kotlin
/**
 * Tool: stop_listening — pauses ASR.
 */
class StopListeningTool(
    private val onStop: () -> Unit
) : Tool {
    override val name = "stop_listening"
    override val description = "停止语音监听。当用户说'别听了'、'暂停'、'停下'等时调用。"
    override val parameters = emptyMap<String, ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): String {
        onStop()
        return "已停止监听。说'开始监听'可以恢复。"
    }
}

/**
 * Tool: start_listening — resumes ASR.
 */
class StartListeningTool(
    private val onStart: () -> Unit
) : Tool {
    override val name = "start_listening"
    override val description = "开始语音监听。当用户说'开始听'、'继续'等时调用。"
    override val parameters = emptyMap<String, ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): String {
        onStart()
        return "已开始监听，请说话。"
    }
}

/**
 * Tool: set_barge_in_mode — changes interrupt mode.
 */
class SetBargeInModeTool(
    private val onSetMode: (String) -> Unit
) : Tool {
    override val name = "set_barge_in_mode"
    override val description = "设置打断模式。off=不打断(助手的时暂停识别), on=允许打断, keyword=关键词打断。"
    override val parameters = mapOf(
        "mode" to ToolParameter(
            type = "string",
            description = "打断模式",
            enum = listOf("off", "on", "keyword")
        )
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val mode = args["mode"] as? String ?: return "错误：缺少模式参数"
        onSetMode(mode)
        val label = when (mode) {
            "off" -> "不打断"
            "on" -> "允许打断"
            "keyword" -> "关键词打断"
            else -> mode
        }
        return "打断模式已切换为：${label}"
    }
}

/**
 * Tool: clear_history — clears conversation history.
 */
class ClearHistoryTool(
    private val onClear: () -> Unit
) : Tool {
    override val name = "clear_history"
    override val description = "清空对话历史记录。当用户说'清空记录'、'忘记之前的对话'、'重新开始'时调用。"
    override val parameters = emptyMap<String, ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): String {
        onClear()
        return "已清空对话记录，我们重新开始。"
    }
}
```

**Step 2: Build & verify**

```bash
cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle assembleDebug
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/example/voiceassistant/tools/ControlTools.kt
git commit -m "feat: add stop/start listening, set_barge_in_mode, clear_history tools"
```

---

### Task 7: Wire into `VoiceService.processQuery()`

**Objective:** Replace direct LLM call with ToolCallEngine in the main conversation handler.

**Files:**
- Modify: `app/src/main/java/com/example/voiceassistant/VoiceService.kt`

**Step 1: Add fields**

In the VoiceService class body (around line 77-82):

```kotlin
// Tool calling
private lateinit var toolRegistry: ToolRegistry
private lateinit var toolCallEngine: ToolCallEngine
```

**Step 2: Initialize tools in `initEngines()`**

After `llmBackend` is created (around line 148), add:

```kotlin
// Initialize tool system
val config = ConfigManager(this@VoiceService)
toolRegistry = ToolRegistry().apply {
    register(SetSpeechRateTool { rate ->
        config.speechRate = rate
        sysTtsEngine?.setRate(rate)
    })
    register(StopListeningTool {
        asrEngine?.stop()
        updateState(State.STOPPED)
    })
    register(StartListeningTool {
        lifecycleScope.launch { startListening() }
    })
    register(SetBargeInModeTool { mode ->
        setBargeInMode(mode)
    })
    register(ClearHistoryTool {
        conversationHistory.clear()
    })
}
toolCallEngine = ToolCallEngine(
    llmBackend = llmBackend as CloudLLMBackend,
    toolRegistry = toolRegistry
)
debugLog("Tools registered: ${toolRegistry.getAll().map { it.name }}")
```

**Step 3: Modify `processQuery()`**

Replace lines 387-433 (the entire `processQuery` function body after the null check):

```kotlin
private suspend fun processQuery(text: String) {
    val backend = llmBackend ?: run {
        val msg = "请先在设置里填入API密钥"
        updateState(State.SPEAKING, msg)
        speakTts(msg)
        return
    }
    conversationHistory.add(LLMBackend.ChatMessage("user", text))
    saveConversationLine("👤 用户", text)
    updateState(State.THINKING)

    try {
        val result = withTimeoutOrNull(30000L) {
            toolCallEngine.chat(text, conversationHistory)
        }
        val response = when {
            result == null -> "回复超时了"
            result.isSuccess -> result.getOrNull() ?: "没听清楚，再说一次？"
            else -> {
                debugLog("ToolCallEngine error: ${result.exceptionOrNull()?.message}")
                "出了点问题，再试一次"
            }
        }
        conversationHistory.add(LLMBackend.ChatMessage("assistant", response))
        if (conversationHistory.size > HISTORY_MAX_SIZE) conversationHistory.removeAt(0)
        saveConversationLine("🐷 猪头", response)
        updateState(State.SPEAKING, response)
        speakTts(response)
    } catch (e: Exception) {
        debugLog("LLM error: ${e.message}")
        val msg = when {
            e.message?.contains("timeout", true) == true -> "网络超时，再试一次"
            e.message?.contains("401", true) == true -> "API密钥不对，检查设置"
            else -> "出了点问题，再试一次"
        }
        updateState(State.SPEAKING, msg)
        speakTts(msg)
    }
}
```

**Step 4: Add imports**

At top of VoiceService.kt:

```kotlin
import com.example.voiceassistant.llm.ToolRegistry
import com.example.voiceassistant.llm.ToolCallEngine
import com.example.voiceassistant.tools.SetSpeechRateTool
import com.example.voiceassistant.tools.StopListeningTool
import com.example.voiceassistant.tools.StartListeningTool
import com.example.voiceassistant.tools.SetBargeInModeTool
import com.example.voiceassistant.tools.ClearHistoryTool
```

**Step 5: Build & verify**

```bash
cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle assembleDebug
```

**Step 6: Commit**

```bash
git add app/src/main/java/com/example/voiceassistant/VoiceService.kt
git commit -m "feat: wire ToolCallEngine into VoiceService.processQuery()"
```

---

### Task 8: Deploy & Smoke Test

**Objective:** Install on phone and test basic tool calling end-to-end.

**Step 1: Deploy**

```bash
cp ~/voice-assistant/app/build/outputs/apk/debug/app-debug.apk /mnt/c/temp-adb/
/mnt/c/temp-adb/platform-tools/adb.exe install -r C:\temp-adb\app-debug.apk
/mnt/c/temp-adb/platform-tools/adb.exe shell am start -n com.example.voiceassistant/.MainActivity
```

**Step 2: Verify logs**

```bash
# Check tool initialization
/mnt/c/temp-adb/platform-tools/adb.exe shell run-as com.example.voiceassistant grep "Tools registered\|ToolCall\|function_call" files/debug.log
```

**Step 3: Test phrases (speak to app)**

| Phrase | Expected Tool | Expected Response |
|--------|--------------|-------------------|
| "把语速调到1.5倍" | set_speech_rate(1.5) | 确认语速已调 |
| "别听了" | stop_listening | 已停止监听 |
| "开始听" | start_listening | 已开始监听 |
| "切换成不打断模式" | set_barge_in_mode("off") | 模式已切换 |
| "清空记录" | clear_history | 已清空 |

**Step 4: Commit if passes**

```bash
# Any fixes go into new commits
```

---

### Task 9: Update Documentation

**Objective:** Update ROADMAP and CHANGELOG to reflect tool calling feature.

**Files:**
- Modify: `docs/ROADMAP_zh.md`, `docs/ROADMAP_en.md`
- Modify: `docs/CHANGELOG_zh.md`, `docs/CHANGELOG_en.md`

**Step 1: Update ROADMAP** — Add v1.3 section:

```markdown
## ✅ 已完成 (v1.3)

| 模块 | 说明 |
|------|------|
| 🔧 工具调用框架 | LLM function calling: OpenAI 兼容 API，多轮工具执行循环 |
| 🎛 语音控制工具 | set_speech_rate / stop_listening / start_listening / set_barge_in_mode / clear_history |
```

**Step 2: Update CHANGELOG** — Add v1.3 entry.

**Step 3: Commit**

```bash
git add docs/
git commit -m "docs: v1.3 tool calling framework — ROADMAP + CHANGELOG"
```

---

## Verification Checklist

- [ ] Tool interface compiles cleanly
- [ ] ToolRegistry registers and executes tools
- [ ] CloudLLMBackend.chatWithTools() sends tools and parses function_call
- [ ] ToolCallEngine loops correctly (text → tool → text)
- [ ] VoiceService processQuery delegates to ToolCallEngine
- [ ] "语速调到1.5" → LLM calls set_speech_rate → TTS confirms
- [ ] "别听了" → stops ASR
- [ ] "开始听" → resumes ASR
- [ ] "打断模式切到关键词" → barge_in_mode changes
- [ ] "清空记录" → history cleared
- [ ] Docs updated (ROADMAP + CHANGELOG)

---

## Next Feature (after this)

**Feature 2: External Tools** — web_search, get_weather, web_fetch. New tool implementations + optional network permission prompt.

**Feature 3: Context Memory** — remember / recall tools for long-term user preference storage.

**Feature 4: Local Knowledge Base** — RAG index over personal documents.

**Feature 5: System Tools** — alarm, calendar, WeChat sharing via Android intents.
