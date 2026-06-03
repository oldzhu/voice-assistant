package com.example.voiceassistant.llm

/**
 * Common interface for LLM backends (cloud + local).
 */
interface LLMBackend {
    /**
     * Send a user message to the LLM and get a text response.
     *
     * @param userMessage The user's transcribed speech
     * @param history Previous chat messages for context (oldest first)
     * @return LLM response text
     */
    suspend fun chat(userMessage: String, history: List<ChatMessage> = emptyList()): Result<String>

    /**
     * A single chat message in the conversation history.
     */
    data class ChatMessage(
        val role: String, // "user", "assistant", "system"
        val content: String
    )
}
