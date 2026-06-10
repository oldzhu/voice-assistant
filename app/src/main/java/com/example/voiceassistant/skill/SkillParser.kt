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
                                currentArgs = parseInlineArgs(argsStr).toMutableMap()
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
