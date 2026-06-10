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
 * Chains: get_location → get_weather → get_news → summarize for user.
 * Demonstrates multi-step progress reporting with TTS.
 */
class MorningRoutineSkill : Skill {
    override val name = "morning_routine"
    override val description = "执行早晨 routine：获取天气和新闻头条。当用户说「早上好」「早晨 routine」「开始我的一天」时调用。"

    override suspend fun execute(args: Map<String, Any?>, context: SkillContext): Flow<SkillStep> = flow {
        val results = mutableListOf<String>()

        // Step 1: Get location first (needed for accurate weather)
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

        // Step 3: Get news headlines
        emit(SkillStep(StepStatus.RUNNING, "正在获取今天的新闻..."))
        val news = try {
            context.callTool("get_news", mapOf("category" to "综合"))
        } catch (e: Exception) {
            emit(SkillStep(StepStatus.FAILED, "新闻获取失败"))
            "新闻暂不可用"
        }
        emit(SkillStep(StepStatus.DONE, "新闻获取完成"))
        results.add("📰 $news")

        // Final summary
        val summary = results.joinToString("\n\n")
        emit(SkillStep(StepStatus.DONE, "早晨 routine 完成", summary))
    }
}
