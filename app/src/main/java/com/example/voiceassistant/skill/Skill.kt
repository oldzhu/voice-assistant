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
