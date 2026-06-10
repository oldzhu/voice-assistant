package com.example.voiceassistant.skill

import com.example.voiceassistant.llm.ToolRegistry

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
