package com.example.voiceassistant.speech

/**
 * Strips Markdown formatting from text before TTS playback.
 *
 * LLMs naturally use Markdown (**bold**, *italic*, [links](url), code blocks, etc.)
 * for readability, but TTS reads these characters literally, producing unnatural speech.
 *
 * This is Layer 2 of a two-layer defense:
 *   Layer 1: System prompt tells LLM to output plain spoken text (~90% effective).
 *   Layer 2: This function strips any residual markdown as a deterministic safety net.
 *
 * Rules (applied in order):
 *   1. Links:        [text](url) → text
 *   2. Bold:         **text** / __text__ → text
 *   3. Italic:       *text* / _text_ → text
 *   4. Inline code:  `code` → code
 *   5. Strikethrough: ~~text~~ → text
 *   6. Headings:     ### Heading → Heading
 *   7. Unordered list bullets: - / * / + item → item
 *   8. Ordered list numbers: 1. item → item
 *   9. Code block fences: ```lang → removed
 *
 * @see docs/TTS_SANITIZER_DESIGN_zh.md
 * @see docs/TTS_SANITIZER_DESIGN_en.md
 */
object TtsTextSanitizer {

    /**
     * Remove all Markdown formatting, returning clean spoken text.
     *
     * Idempotent: sanitize(cleanText) == cleanText.
     *
     * @param text Raw LLM output that may contain Markdown.
     * @return Plain text suitable for TTS playback.
     */
    fun sanitize(text: String): String {
        var result = text

        // 1. Links: [text](url) → text
        result = result.replace(Regex("\\[(.+?)]\\(.*?\\)"), "$1")

        // 2. Bold: **text** or __text__ (must happen before italic)
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        result = result.replace(Regex("__(.+?)__"), "$1")

        // 3. Italic: *text* or _text_
        result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
        result = result.replace(Regex("(?<!_)_(?!_)(.+?)(?<!_)_(?!_)"), "$1")

        // 4. Inline code: `text` (single or triple backtick inline)
        result = result.replace(Regex("`+(.+?)`+"), "$1")

        // 5. Strikethrough: ~~text~~
        result = result.replace(Regex("~~(.+?)~~"), "$1")

        // 6. Headings: ### Heading → Heading
        result = result.replace(Regex("(?m)^#{1,6}\\s+"), "")

        // 7. Unordered list bullets: -/*/+ item → item
        result = result.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")

        // 8. Ordered list: 1. / 12. item → item
        result = result.replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")

        // 9. Code block fences: ```lang → removed
        result = result.replace(Regex("```[\\w]*\\n?"), "")

        // 10. Horizontal rules: --- / *** → removed
        result = result.replace(Regex("(?m)^[-*_]{3,}\\s*$"), "")

        // 11. HTML tags that slipped through
        result = result.replace(Regex("<[^>]+>"), "")

        // 12. Collapse excessive whitespace but keep paragraph breaks
        result = result.replace(Regex("\\n{3,}"), "\n\n")
        result = result.replace(Regex("[ \\t]{2,}"), " ")

        return result.trim()
    }
}
