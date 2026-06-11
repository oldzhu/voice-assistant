package com.example.voiceassistant.llm

/**
 * Abstraction for vision/image analysis.
 *
 * Supports pluggable backends:
 * - RemoteVisionProvider: sends image to cloud VLM (e.g., gpt-4o)
 * - LocalVisionProvider:  OCR on-device via Tesseract, optional LLM composition
 *
 * Configured via vision_provider config key: "remote" | "local" | "auto"
 */
interface VisionProvider {
    /**
     * Describe an image.
     *
     * @param prompt       What to ask about the image (e.g., "extract text", "describe scene")
     * @param imageBase64  Base64-encoded JPEG (without "data:" prefix)
     * @return Description text, or failure
     */
    suspend fun describe(prompt: String, imageBase64: String): Result<String>
}
