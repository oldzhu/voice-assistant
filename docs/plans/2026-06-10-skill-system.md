# Skill System — Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Upgrade voice assistant from single-function tools to multi-step skills with progress reporting.

**Architecture:** `Skill` interface with `Flow<SkillStep>` execution, wrapped as a `Tool` via `SkillToolAdapter` so the LLM can call skills through the existing tool-calling pipeline. `SkillExecutor` handles progress→TTS updates. `SkillParser` loads `.skill.md` files for user-installable skills.

**Tech Stack:** Kotlin coroutines + Flow, YAML frontmatter parsing (manual, no library deps), existing ToolRegistry + ToolCallEngine.

---

## Design Summary

```
User: "帮我设置早晨 routine"
  → LLM decides: call morning_routine skill
  → ToolCallEngine sees skill as a Tool (via SkillToolAdapter)
  → SkillToolAdapter.execute() → SkillExecutor.run(skill, args)
  → SkillExecutor collects Flow<SkillStep>
    ├── Step: "正在获取天气..." → TTS speaks progress
    ├── Step: "正在设置闹钟..." → TTS speaks progress
    └── Step: DONE → returns final summary to LLM
  → LLM gets final result, replies to user
```

**Key differences from regular Tools:**
| | Tool | Skill |
|---|---|---|
| Duration | <30s | up to 5 min |
| Steps | 1 atomic call | N sequential steps |
| Progress | none | TTS spoken updates |
| Composition | LLM orchestrates multiple tool calls | Internal tool chaining |
| Registration | Direct `Tool` | `Skill` → `SkillToolAdapter` → `Tool` |

---

### Task 1: Create Skill interface and SkillStep data class

**Objective:** Define the core Skill abstraction — a multi-step, progress-emitting workflow.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/skill/Skill.kt`

**Step 1: Write the file**

```kotlin
package com.example.voiceassistant.skill

import kotlinx.coroutines.flow.Flow

/**
 * Progress status for a single step within a skill.
 */
enum class StepStatus {
    /** Step is currently executing. */
    RUNNING,
    /** Step completed successfully. */
    DONE,
    /** Step failed — skill may continue or abort. */
    FAILED
}

/**
 * A single step emitted during skill execution.
 *
 * @param status Current status of this step.
 * @param message Human-readable progress text (spoken via TTS).
 * @param result Optional structured output for chaining to next step.
 */
data class SkillStep(
    val status: StepStatus,
    val message: String,
    val result: Any? = null
)

/**
 * Execution context passed to a Skill during execution.
 * Provides access to system capabilities the skill needs.
 */
data class SkillContext(
    /** Execute a tool by name (reuse built-in tools from ToolRegistry). */
    val callTool: suspend (String, Map<String, Any?>) -> String,
    /** Speak a progress message to the user via TTS. */
    val speakProgress: suspend (String) -> Unit,
    /** The app's files directory for skill-local storage. */
    val filesDir: java.io.File
)

/**
 * A multi-step workflow that can be executed by the assistant.
 *
 * Unlike a [Tool] which does one thing and returns a String,
 * a Skill emits a [Flow] of [SkillStep]s — each step reports
 * progress, and the final step contains the result.
 *
 * Skills can call other tools internally via [SkillContext.callTool].
 *
 * Usage:
 *   class MorningRoutineSkill : Skill {
 *       override val name = "morning_routine"
 *       override val description = "执行早晨 routine..."
 *       override suspend fun execute(args, ctx) = flow {
 *           emit(SkillStep(RUNNING, "正在获取天气..."))
 *           val weather = ctx.callTool("get_weather", emptyMap())
 *           emit(SkillStep(RUNNING, "正在设置闹钟..."))
 *           // ...
 *           emit(SkillStep(DONE, "早晨 routine 完成", result))
 *       }
 *   }
 */
interface Skill {
    /** Unique name, exposed to LLM as function name (prefixed with "skill_"). */
    val name: String

    /** Human-readable description for the LLM to decide when to use. */
    val description: String

    /**
     * Execute the skill as a coroutine Flow that emits progress updates.
     *
     * @param args Arguments from the LLM's function call.
     * @param context Execution context providing tool access and progress feedback.
     * @return A cold Flow of SkillStep — collect until DONE or FAILED.
     */
    suspend fun execute(args: Map<String, Any?>, context: SkillContext): Flow<SkillStep>
}
```

**Step 2: Verify compilation**

Run: `cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 2: Create SkillRegistry

**Objective:** Central registry that manages Skill instances and exposes them as Tools.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/skill/SkillRegistry.kt`

**Step 1: Write the file**

```kotlin
package com.example.voiceassistant.skill

import com.example.voiceassistant.llm.ToolRegistry
import com.example.voiceassistant.llm.Tool

/**
 * Registry for [Skill] instances.
 *
 * Skills are registered here, then exposed to the LLM as [Tool]s
 * via [ToolRegistry] using [SkillToolAdapter].
 */
class SkillRegistry(
    private val toolRegistry: ToolRegistry,
    private val skillContextProvider: () -> SkillContext
) {
    private val skills = mutableMapOf<String, Skill>()
    /** Track which tool names we've registered so we can unregister cleanly. */
    private val registeredToolNames = mutableSetOf<String>()

    /**
     * Register a skill and expose it as a Tool to the LLM.
     * Tool name = "skill_<skill.name>" to distinguish from regular tools.
     */
    fun register(skill: Skill) {
        skills[skill.name] = skill
        val toolName = "skill_${skill.name}"
        val adapter = SkillToolAdapter(skill, skillContextProvider)
        toolRegistry.register(adapter)
        registeredToolNames.add(toolName)
    }

    /** Remove a skill and its tool adapter. */
    fun unregister(name: String) {
        skills.remove(name)
        val toolName = "skill_$name"
        toolRegistry.unregister(toolName)
        registeredToolNames.remove(toolName)
    }

    /** Get a skill by name (without "skill_" prefix). */
    fun get(name: String): Skill? = skills[name]

    /** All registered skills. */
    fun getAll(): List<Skill> = skills.values.toList()

    /** Number of registered skills. */
    fun size(): Int = skills.size

    /**
     * Load a skill from a .skill.md file and register it.
     * Returns the skill name on success, or null on failure.
     */
    suspend fun loadFromFile(file: java.io.File): String? {
        if (!file.exists() || !file.name.endsWith(".skill.md")) return null
        val content = file.readText()
        val parsed = SkillParser.parse(content) ?: return null
        register(parsed)
        return parsed.name
    }

    /**
     * Load all .skill.md files from a directory.
     */
    suspend fun loadFromDirectory(dir: java.io.File): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.listFiles()?.filter { it.name.endsWith(".skill.md") }?.forEach { file ->
            if (loadFromFile(file) != null) count++
        }
        return count
    }

    /** Clean up all tool adapters. */
    fun clear() {
        registeredToolNames.forEach { toolRegistry.unregister(it) }
        registeredToolNames.clear()
        skills.clear()
    }
}
```

**Step 2: Verify compilation**

Run: `cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (may fail due to missing SkillToolAdapter — that's next task)

---

### Task 3: Create SkillToolAdapter

**Objective:** Wrap a Skill as a Tool so the LLM can see and call it through the existing tool-calling pipeline.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/skill/SkillToolAdapter.kt`

**Step 1: Write the file**

```kotlin
package com.example.voiceassistant.skill

import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import kotlinx.coroutines.flow.toList

/**
 * Wraps a [Skill] as a [Tool] so the LLM can call it through the
 * standard tool-calling pipeline.
 *
 * The adapter collects the skill's [Flow]<[SkillStep]> into a list,
 * reports progress via [SkillContext.speakProgress], and returns
 * a summary string to the LLM.
 *
 * Tool name: "skill_<skill.name>" — the "skill_" prefix distinguishes
 * skills from regular tools in function definitions.
 */
class SkillToolAdapter(
    private val skill: Skill,
    private val skillContextProvider: () -> SkillContext
) : Tool {

    /** Exposed to LLM: "skill_morning_routine" */
    override val name = "skill_${skill.name}"

    /** The skill's description, prefixed to guide LLM usage. */
    override val description = skill.description

    /** Skills have no typed parameters — the LLM passes arbitrary args. */
    override val parameters = emptyMap<String, ToolParameter>()

    override suspend fun execute(args: Map<String, Any?>): String {
        val context = skillContextProvider()
        val steps = mutableListOf<SkillStep>()

        try {
            skill.execute(args, context).collect { step ->
                steps.add(step)
                when (step.status) {
                    StepStatus.RUNNING -> context.speakProgress(step.message)
                    StepStatus.FAILED -> context.speakProgress("失败：${step.message}")
                    StepStatus.DONE -> { /* final step — no TTS, result goes to LLM */ }
                }
            }
        } catch (e: Exception) {
            context.speakProgress("技能执行出错：${e.message}")
            return "❌ 技能 '${skill.name}' 执行失败：${e.message}"
        }

        // Find the final result
        val finalStep = steps.lastOrNull { it.status == StepStatus.DONE }
        return finalStep?.result?.toString()
            ?: finalStep?.message
            ?: "✅ 技能 '${skill.name}' 完成"
    }
}
```

**Step 2: Verify compilation**

Run: `cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 4: Create SkillParser

**Objective:** Parse `.skill.md` files into `Skill` instances. Use simple manual YAML frontmatter parsing (no library dependency).

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/skill/SkillParser.kt`

**Step 1: Write the file**

```kotlin
package com.example.voiceassistant.skill

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Parses .skill.md files into [Skill] instances.
 *
 * .skill.md format (subset of Hermes SKILL.md):
 * ```
 * ---
 * name: morning-routine
 * description: 执行早晨 routine（天气+闹钟+新闻）
 * version: 1.0.0
 * steps:
 *   - say: 正在获取今天的天气...
 *     tool: get_weather
 *   - say: 正在设置一个30分钟后的闹钟...
 *     tool: set_reminder
 *     args: { "text": "起床", "seconds": 1800 }
 * ---
 * # Morning Routine
 * (markdown body is documentation only, not executed)
 * ```
 *
 * The `steps` array defines the skill workflow. Each step has:
 * - `say`:  Progress message spoken via TTS
 * - `tool`: Tool name to call (from ToolRegistry)
 * - `args`: Optional arguments map
 */
object SkillParser {

    data class ParsedSkill(
        val name: String,
        val description: String,
        val steps: List<StepDef>
    )

    data class StepDef(
        val say: String,
        val tool: String,
        val args: Map<String, String> = emptyMap()
    )

    /**
     * Parse a .skill.md content string.
     * Returns a [Skill] if valid, or null if parsing fails.
     */
    fun parse(content: String): Skill? {
        val parsed = parseYamlFrontmatter(content) ?: return null
        return FileDefinedSkill(parsed)
    }

    private fun parseYamlFrontmatter(content: String): ParsedSkill? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null

        // Find closing ---
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIdx < 0) return null

        val frontmatter = lines.subList(1, endIdx + 1)

        var name: String? = null
        var description: String? = null
        val steps = mutableListOf<StepDef>()

        var i = 0
        while (i < frontmatter.size) {
            val line = frontmatter[i].trim()
            when {
                line.startsWith("name:") -> name = line.removePrefix("name:").trim()
                line.startsWith("description:") -> description = line.removePrefix("description:").trim()
                line == "steps:" -> {
                    i++
                    var currentSay: String? = null
                    var currentTool: String? = null
                    var currentArgs = mutableMapOf<String, String>()
                    while (i < frontmatter.size) {
                        val stepLine = frontmatter[i]
                        val trimmed = stepLine.trim()
                        when {
                            trimmed.startsWith("- say:") -> {
                                // Flush previous step
                                if (currentTool != null && currentSay != null) {
                                    steps.add(StepDef(currentSay, currentTool, currentArgs))
                                }
                                currentSay = trimmed.removePrefix("- say:").trim()
                                currentTool = null
                                currentArgs = mutableMapOf()
                            }
                            trimmed.startsWith("  tool:") -> {
                                currentTool = trimmed.removePrefix("  tool:").trim()
                            }
                            trimmed.startsWith("  args:") -> {
                                val argsStr = trimmed.removePrefix("  args:").trim()
                                currentArgs = parseInlineArgs(argsStr)
                            }
                            trimmed.startsWith("- ") && currentTool == null -> {
                                // Next step — flush and reset
                                break
                            }
                        }
                        i++
                    }
                    // Flush last step
                    if (currentTool != null && currentSay != null) {
                        steps.add(StepDef(currentSay, currentTool, currentArgs))
                    }
                    i-- // compensate for outer while's i++
                }
            }
            i++
        }

        if (name == null || description == null) return null
        return ParsedSkill(name, description, steps)
    }

    /**
     * Parse inline args like `{ "text": "起床", "seconds": 1800 }`.
     * Simple manual parser — no JSON library dependency for this simple case.
     */
    private fun parseInlineArgs(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Strip outer braces
        val inner = raw.trim().removeSurrounding("{", "}").trim()
        if (inner.isEmpty()) return result

        // Split by comma, respecting quoted strings crudely
        val pairs = inner.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
        for (pair in pairs) {
            val parts = pair.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().removeSurrounding("\"")
                val value = parts[1].trim().removeSurrounding("\"")
                result[key] = value
            }
        }
        return result
    }

    /**
     * A [Skill] whose workflow is defined by parsed .skill.md steps.
     * Each step calls a tool via [SkillContext.callTool].
     */
    private class FileDefinedSkill(
        private val parsed: ParsedSkill
    ) : Skill {
        override val name = parsed.name
        override val description = parsed.description

        override suspend fun execute(args: Map<String, Any?>, context: SkillContext): Flow<SkillStep> = flow {
            val results = mutableListOf<String>()

            for ((index, step) in parsed.steps.withIndex()) {
                // Report progress
                emit(SkillStep(StepStatus.RUNNING, step.say))

                try {
                    // Merge file-defined args with caller-supplied args
                    val mergedArgs = step.args.toMutableMap<String, Any?>()
                    for ((k, v) in args) {
                        mergedArgs[k] = v
                    }

                    val result = context.callTool(step.tool, mergedArgs)
                    emit(SkillStep(StepStatus.DONE, "${step.say} → 完成"))

                    val short = if (result.length > 200) result.take(200) + "…" else result
                    results.add("${index + 1}. ${step.say}: $short")
                } catch (e: Exception) {
                    emit(SkillStep(StepStatus.FAILED, "${step.say} → ${e.message}"))
                    results.add("${index + 1}. ${step.say}: ❌ ${e.message}")
                }
            }

            val summary = results.joinToString("\n")
            emit(SkillStep(StepStatus.DONE, "技能完成", summary))
        }
    }
}
```

**Step 2: Verify compilation**

Run: `cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 5: Create SkillExecutor with TTS progress

**Objective:** Wire Skill execution into VoiceService — provide `SkillContext` with real TTS and tool access.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/skill/SkillExecutor.kt`
- Modify: `app/src/main/java/com/example/voiceassistant/VoiceService.kt`

**Step 1: Write SkillExecutor**

```kotlin
package com.example.voiceassistant.skill

import com.example.voiceassistant.llm.ToolRegistry

/**
 * Creates [SkillContext] instances wired to real system services.
 *
 * One SkillExecutor per VoiceService instance — it holds references
 * to the ToolRegistry and TTS engine needed for skill execution.
 */
class SkillExecutor(
    private val toolRegistry: ToolRegistry,
    private val ttsSpeaker: suspend (String) -> Unit,
    private val filesDir: java.io.File
) {
    /**
     * Create a fresh SkillContext for a skill execution.
     * Each skill invocation gets its own context.
     */
    fun createContext(): SkillContext = SkillContext(
        callTool = { name, args -> toolRegistry.execute(name, args) },
        speakProgress = { msg -> ttsSpeaker(msg) },
        filesDir = filesDir
    )
}
```

**Step 2: Wire into VoiceService**

In `VoiceService.initEngines()`, after toolRegistry is initialized, add:

```kotlin
// In VoiceService.kt, after toolRegistry is assigned:

// Skill system
val skillDir = File(filesDir, "skills").also { it.mkdirs() }
lateinit var skillRegistry: SkillRegistry
skillRegistry = SkillRegistry(toolRegistry) {
    skillExecutor.createContext()
}
skillExecutor = SkillExecutor(
    toolRegistry,
    ttsSpeaker = { msg ->
        val sanitized = TtsTextSanitizer.sanitize(msg)
        speakTts(sanitized)
    },
    filesDir = filesDir
)
// Load bundled skills from assets
lifecycleScope.launch { loadBundledSkills(skillDir, skillRegistry) }
```

Add new method:

```kotlin
private suspend fun loadBundledSkills(skillDir: File, registry: SkillRegistry) {
    // Copy bundled .skill.md files from assets to filesDir
    try {
        val assetFiles = assets.list("skills") ?: emptyArray()
        for (filename in assetFiles) {
            if (!filename.endsWith(".skill.md")) continue
            val content = assets.open("skills/$filename").bufferedReader().use { it.readText() }
            val file = File(skillDir, filename)
            file.writeText(content)
        }
    } catch (_: Exception) {
        debugLog("No bundled skills found in assets/skills/")
    }

    // Load from filesDir
    val count = registry.loadFromDirectory(skillDir)
    debugLog("Skills loaded: $count")
}
```

Also add system prompt guidance for skills to `ToolCallEngine.buildSystemPrompt()`:

```kotlin
append("你可以调用技能（skill_开头的工具）来执行多步骤任务。")
append("技能会自动报告每一步的进度，你只需要在技能完成后向用户总结结果。")
append("使用技能时，直接调用对应的 skill_ 函数即可，技能内部会自动处理所有步骤。")
```

**Step 3: Verify compilation**

Run: `cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 6: Create a built-in demo skill — Morning Routine

**Objective:** Ship one concrete skill to validate the entire pipeline end-to-end.

**Files:**
- Create: `app/src/main/java/com/example/voiceassistant/skill/builtin/MorningRoutineSkill.kt`
- Create: `app/src/main/assets/skills/morning-routine.skill.md`

**Step 1: Write MorningRoutineSkill.kt**

```kotlin
package com.example.voiceassistant.skill.builtin

import com.example.voiceassistant.skill.Skill
import com.example.voiceassistant.skill.SkillContext
import com.example.voiceassistant.skill.SkillStep
import com.example.voiceassistant.skill.StepStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Built-in skill: Morning Routine.
 *
 * Chains: get_weather → get_news → summarize for user.
 * Demonstrates multi-step progress reporting.
 */
class MorningRoutineSkill : Skill {
    override val name = "morning_routine"
    override val description = "执行早晨 routine：获取天气和新闻头条。当用户说「早上好」「早晨 routine」「开始我的一天」时调用。"

    override suspend fun execute(args: Map<String, Any?>, context: SkillContext): Flow<SkillStep> = flow {
        val results = mutableListOf<String>()

        // Step 1: Get location first
        emit(SkillStep(StepStatus.RUNNING, "正在获取你的位置..."))
        val location = try {
            context.callTool("get_location", emptyMap())
        } catch (e: Exception) {
            emit(SkillStep(StepStatus.FAILED, "无法获取位置"))
            "位置未知"
        }
        emit(SkillStep(StepStatus.DONE, "位置获取完成"))
        results.add("📍 $location")

        // Step 2: Get weather
        emit(SkillStep(StepStatus.RUNNING, "正在查看今天的天气..."))
        val weather = try {
            context.callTool("get_weather", emptyMap())
        } catch (e: Exception) {
            emit(SkillStep(StepStatus.FAILED, "天气获取失败"))
            "天气数据暂不可用"
        }
        emit(SkillStep(StepStatus.DONE, "天气获取完成"))
        results.add("🌤 $weather")

        // Step 3: Get news
        emit(SkillStep(StepStatus.RUNNING, "正在获取今天的新闻..."))
        val news = try {
            context.callTool("get_news", mapOf("category" to "综合"))
        } catch (e: Exception) {
            emit(SkillStep(StepStatus.FAILED, "新闻获取失败"))
            "新闻暂不可用"
        }
        emit(SkillStep(StepStatus.DONE, "新闻获取完成"))
        results.add("📰 $news")

        // Final
        val summary = results.joinToString("\n\n")
        emit(SkillStep(StepStatus.DONE, "早晨 routine 完成", summary))
    }
}
```

**Step 2: Write morning-routine.skill.md (for documentation/distribution)**

Create `app/src/main/assets/skills/morning-routine.skill.md`:

```markdown
---
name: morning-routine
description: 执行早晨 routine（获取天气和新闻）。当用户说「早上好」「早晨 routine」时调用。
version: 1.0.0
steps:
  - say: 正在获取你的位置...
    tool: get_location
  - say: 正在查看今天的天气...
    tool: get_weather
  - say: 正在获取今天的新闻...
    tool: get_news
    args: { "category": "综合" }
---

# 早晨 Routine

获取天气和新闻头条，帮助你快速了解今天的情况。

## 触发条件
- "早上好"
- "早晨 routine"
- "开始我的一天"
- "今天怎么样"

## 步骤
1. 获取位置（用于精准天气）
2. 获取天气信息
3. 获取新闻头条
```

**Step 3: Register in VoiceService.initEngines()**

After the SkillRegistry setup, register the built-in skill:

```kotlin
// Register built-in skills
skillRegistry.register(MorningRoutineSkill())
debugLog("Built-in skills registered: ${skillRegistry.getAll().map { it.name }}")
```

**Step 4: Verify compilation**

Run: `cd ~/voice-assistant && /mnt/c/gradle/gradle-9.2.1/bin/gradle compileDebugKotlin --no-daemon 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

---

### Task 7: Add auto-test for Skill System

**Objective:** Create automated tests that verify Skill registration, execution, and progress reporting.

**Files:**
- Modify: `app/src/main/java/com/example/voiceassistant/test/TestRunner.kt`
- Modify: `tests/runner.py`

**Step 1: Add test constant to TestRunner.kt**

```kotlin
// In companion object:
const val TEST_SKILL_SYSTEM = "skill_system"
```

**Step 2: Add dispatch case**

In the `runAll` or test dispatch method:

```kotlin
TestRunner.TEST_SKILL_SYSTEM -> testSkillSystem()
```

**Step 3: Write test method**

```kotlin
private suspend fun testSkillSystem() {
    val tag = "TEST_SKILL_SYSTEM"
    debugLog("$tag starting")
    
    val registry = toolRegistry()
    if (registry == null) {
        debugLog("$tag FAIL: toolRegistry is null")
        return
    }
    
    val skillReg = skillRegistry
    if (skillReg == null) {
        debugLog("$tag FAIL: skillRegistry is null")
        return
    }
    
    // 1. Verify skill is registered as a tool
    val hasAdapter = registry.has("skill_morning_routine")
    debugLog("$tag skill registered as tool: $hasAdapter")
    
    // 2. Verify skill is in SkillRegistry
    val skill = skillReg.get("morning_routine")
    debugLog("$tag skill in registry: ${skill != null}")
    
    // 3. Execute skill via Tool (simulates LLM calling it)
    val result = registry.execute("skill_morning_routine", emptyMap())
    val success = result.contains("早晨 routine 完成") || result.contains("天气")
    debugLog("$tag skill execution: ${if (success) "PASS" else "FAIL"}")
    debugLog("$tag result: ${result.take(200)}")
    
    val allPassed = hasAdapter && skill != null && success
    debugLog("$tag ${if (allPassed) "PASS" else "FAIL"}")
}
```

**Step 4: Add to Python test runner**

In `tests/runner.py`, add `tool_skill_system` to choices and dispatch.

---

### Task 8: Update docs and README

**Objective:** Document the Skill System in project docs.

**Files:**
- Create: `docs/SKILL_SYSTEM_zh.md`
- Create: `docs/SKILL_SYSTEM_en.md`
- Modify: `docs/ROADMAP_zh.md` / `docs/ROADMAP_en.md`
- Modify: `README.md`
- Modify: `docs/CHANGELOG_zh.md` / `docs/CHANGELOG_en.md`

**Step 1: Write SKILL_SYSTEM_zh.md and SKILL_SYSTEM_en.md**

Document:
- Skill vs Tool comparison
- How to create a skill (interface)
- How to write .skill.md files
- How skills are registered
- Progress reporting mechanism

**Step 2: Update ROADMAP**

Move Skill System from "For Discussion" to "Completed" in both zh/en.

**Step 3: Update README**

Add Skill System to architecture diagram (new `skill/` package), tool count update if skills are counted, and add doc links.

**Step 4: Update CHANGELOG**

Add v1.7 entry documenting the Skill System.

---

## Verification

After all tasks are complete:

1. **Compile**: `./gradlew assembleDebug` — BUILD SUCCESSFUL
2. **Unit test**: Run `tool_skill_system` test on device
3. **Integration test**: Say "早上好" — expect TTS progress updates + weather + news summary
4. **File skill test**: Place a .skill.md file in skills/ directory, verify it loads
