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
