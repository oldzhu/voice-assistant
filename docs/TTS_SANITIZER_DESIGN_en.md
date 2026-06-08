# TTS Text Sanitizer Design — PigHead Assistant

## Problem

Current voice pipeline:

```
ASR → text → LLM → raw reply text → TTS reads directly
```

LLMs naturally use Markdown formatting (`**bold**`, `*italic*`, `[links](url)`, code blocks, list bullets, etc.) to improve readability. But in a voice-only scenario, TTS engines read these formatting characters literally, making speech sound robotic and unnatural:

- `**hello**` read as "asterisk asterisk hello asterisk asterisk"
- `[click here](https://...)` reads out the full URL
- `- item one` reads the dash

## Alternatives Considered

| Approach | Latency | Cost | Reliability | Naturalness | Adopted? |
|----------|---------|------|-------------|-------------|----------|
| A. Prompt engineering: tell LLM to output plain text | 0ms | $0 | ⭐⭐⭐ (occasional violations) | ⭐⭐⭐ | ✅ |
| B. Post-processing: regex strip markdown | 0ms | $0 | ⭐⭐⭐⭐⭐ (100% reliable) | ⭐⭐⭐ | ✅ |
| C. Second LLM pass: "rewrite for speaking" | +3s | $$ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ❌ too slow/expensive |
| D. Multimodal audio model (GPT-4o audio, etc.) | +500ms | $$ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ❌ no local Chinese solution |

**Decision: A + B as a two-layer defense.**

- Layer 1 (Prompt): instruct LLM to output plain spoken text. Catches ~90% of cases.
- Layer 2 (Post-process): regex strip residual markdown before TTS. Deterministic safety net, 100% coverage.

C and D are deferred. If after both layers the speech still sounds unnatural (e.g., list-like structures, long paragraphs), revisit C.

## Implementation

### 1. System prompt change (`ToolCallEngine.buildSystemPrompt()`)

Add one line:

```
Your replies will be read aloud via TTS. Do NOT use any Markdown formatting
(**bold**, *italic*, `code`, [links](url), list bullets, etc.).
Use plain text natural spoken language only.
```

### 2. Regex sanitizer (new file: `speech/TtsTextSanitizer.kt`)

```kotlin
object TtsTextSanitizer {
    fun sanitize(text: String): String
}
```

Processing rules (in order):

1. Links: `[text](url)` → `text`
2. Bold: `**text**` / `__text__` → `text`
3. Italic: `*text*` / `_text_` → `text`
4. Inline code: `` `code` `` → `code`
5. Strikethrough: `~~text~~` → `text`
6. Headings: `### Heading` → `Heading`
7. Unordered list bullets: `- item` / `* item` / `+ item` → `item`
8. Ordered list numbers: `1. item` → `item`
9. Code block fences: remove ````` `` ``` `` lines

### 3. Call site (`VoiceService.processQuery()`)

```kotlin
// Before:
speakTts(response)

// After:
speakTts(TtsTextSanitizer.sanitize(response))
```

The raw `response` is still saved to logs and conversation history — only the TTS input is sanitized.

## Test Cases

- Pure markdown input (all `**bold**` style)
- Mixed input (part spoken + part formatted)
- Clean text (no formatting, verify unchanged)
- Edge cases: empty string, symbols only, newlines
