package com.example.voiceassistant.skill

import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter

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

    /** The skill's description, exposed to the LLM for dispatch decisions. */
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
