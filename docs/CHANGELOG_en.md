# Changelog — Pig Head Assistant

All notable changes are documented here. Follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) format.

## [v1.5] — 2026-06-05

### Added
- **Article Reading Tool (`read_article`)**: Search + fetch + clean web content for TTS narration
  - Supports both `query` (Bing search with Baidu fallback) and `url` input modes
  - Smart text cleaning: strips nav/ads/script residue, extracts main content
  - Long article truncation (1500 chars) with "say continue for next section" prompt
  - System prompt: well-known poems recited directly by LLM, no tool needed
- **Auto-tests expanded to 14**:
  - `tool_read_article` — direct tool test (URL fetch validation)
  - `tool_location`, `tool_news`, `tool_web_fetch` — external tool direct tests
  - `tool_config`, `tool_memory`, `tool_barge_in`, `tool_clear_history` — local tool direct tests
  - `llm_multi_tool` — multi-tool-call regression test (prevents API 400)
- **Test-First development rule**: every new feature requires auto test design before coding

### Fixed
- **Post-test hang**: `runTest()` didn't reset `testMode=false` + `startListening()`, leaving app unresponsive
- **read_article timeout**: search backend changed from DuckDuckGo to Bing (China-accessible), Baidu fallback

### Documentation
- `docs/ROADMAP_zh.md` / `docs/ROADMAP_en.md` — roadmap
- `docs/TESTING_zh.md` / `docs/TESTING_en.md` — complete test docs (14 tests)
- Skill `auto-test-framework.md` — test architecture + Test-First workflow

## [v1.4] — 2026-06-04

### Added
- **MCP Client**: connect to MCP (Model Context Protocol) servers, auto-discover and register tools
  - `StdioMcpTransport` — ProcessBuilder-based local MCP server (Go/Rust binaries)
  - `HttpMcpTransport` — OkHttp-based remote HTTP MCP server
  - `McpClient` — full JSON-RPC 2.0 protocol (initialize / tools/list / tools/call)
  - `McpToolAdapter` — auto-adapts MCP tool schema to Tool interface
  - Config persistence: `ConfigManager.mcpServers` (JSON array, multi-server)
  - Tool naming: `mcp_{server_name}_{tool_name}` to prevent collisions
- `ConfigManager`: new `McpServerConfig` data class and `mcpServers` property

### Architecture
```
VoiceService → connectMcpServers()
  ├── StdioMcpTransport (local process)
  └── HttpMcpTransport  (remote HTTP)
        │
        ▼ McpClient (JSON-RPC)
  initialize() → tools/list() → McpToolAdapter → ToolRegistry
```

> See [docs/plans/2026-06-04-mcp-client.md](plans/2026-06-04-mcp-client.md)

## [v1.3.1] — 2026-06-04

### Added
- **External web tools**: 3 network tools enabling LLM to search and fetch info
  - `web_search` — DuckDuckGo web search (no API key needed)
  - `web_fetch` — Fetch webpage and extract plain text
  - `get_weather` — wttr.in weather lookup (no API key needed)
- **Dormant state (DORMANT)**: say "stop listening" / "rest" / "sleep" to enter sleep mode
  - ASR keeps running but ignores general speech, only responds to wake phrases ("start listening", "come back", etc.)
  - Plays farewell "好的，我休息了，随时呼我" on entering
  - Plays greeting "我回来了，有啥要聊的？" on wake
  - Sleep commands matched via keyword (bypass LLM for reliability)

### Fixed
- **DeepSeek thinking mode**: when reasoning is enabled, `reasoning_content` must be passed back verbatim or API returns 400
  - `CloudLLMBackend.chatWithTools()` preserves raw message objects with `reasoning_content`
  - `ToolCallEngine` extracts `tool_call_id` from original messages, preserving DeepSeek-specific fields
- **Dormant TTS crosstalk**: `processQuery()` checks for DORMANT state and returns early, suppressing the LLM confirmation message

### Changed
- `stop_listening` tool no longer fully stops ASR → enters DORMANT state instead
- Total tools: 5 control + 3 external = 8

## [v1.3] — 2026-06-04

### Added
- **Tool Calling Framework**: OpenAI function calling compatible protocol enabling LLM to invoke local tools
  - `Tool` interface: unified tool definition (name/description/parameters/execute)
  - `ToolRegistry`: tool registration and execution hub
  - `ToolCallEngine`: LLM↔tool multi-turn execution loop (max 5 turns, 30s timeout)
  - `CloudLLMBackend.chatWithTools()`: sends tools to API + parses function_call responses
- **5 voice control tools**, control the assistant with natural language:
  - `set_speech_rate` — adjust TTS speed ("set speech rate to 1.5")
  - `stop_listening` — pause ASR ("stop listening")
  - `start_listening` — resume ASR ("start listening")
  - `set_barge_in_mode` — change interrupt mode ("switch to keyword interrupt")
  - `clear_history` — clear conversation ("forget previous chat")

### Changed
- `VoiceService.processQuery()` now routes through ToolCallEngine when available
- Upgraded ROADMAP: v1.3 marked complete, v1.4 external tools planned
- New `tools/` package, expanded project structure

## [v1.2] — 2026-06-04

### Added
- **Barge-in modes**: Three switchable modes via settings UI
  - A. Off (default) — pause ASR during TTS, resume after playback
  - B. Voice interrupt — keep ASR active during TTS, interrupt on any speech detected
  - C. Keyword interrupt — keep ASR active during TTS, interrupt only on keyword (default "pig head")
- Settings page: "Barge-in Mode" dropdown selector

### Fixed
- **Status display bug**: State stuck at "replying" after TTS finished; now correctly returns to "listening"
  - Root cause: `startListening()` assigned directly to `state` field, bypassing `updateState()` UI notification
  - Fix: Use `updateState()` for all state transitions
- **Mode C no-op bug**: `onResult` condition `!= "off"` made B and C behave identically
  - Fix: added `bargeInKeywordDetected` flag; `onPartial` stops TTS on keyword; `onResult` only processes keyword hits

### Changed
- AudioSource → `VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION`, enabling hardware full-duplex echo cancellation
- Corrected ROADMAP v1.1 model name (Zipformer → Paraformer)
- Updated tech stack ASR layer to Sherpa-ONNX OnlineRecognizer (Paraformer bilingual int8)

## [v1.1] — 2026-06-03

### Added
- **Bilingual streaming ASR**: OfflineRecognizer → OnlineRecognizer with real-time partial results
  - Model: `sherpa-onnx-streaming-paraformer-bilingual-zh-en` (int8, ~227MB)
  - Mixed CN/EN recognition, terms like "linux kernel", "python API" recognized directly
  - Built-in endpoint detection, auto-completes after speech + silence

### Fixed
- Endpoint false trigger: added `hasSpeech` guard, only fires endpoint when actual speech was detected

### Known Issues
- Zipformer transducer models failed to load due to sherpa-onnx v1.13.2 onnxruntime protobuf compatibility; worked around by switching to Paraformer
- APK size large (~794MB), contains unused CTC and transducer models

## [v1.0] — 2026-06-02

### Added
- 🎤 Sherpa-ONNX Zipformer CTC offline Chinese speech recognition
- 🧠 DeepSeek API streaming LLM conversation
- 🔊 System TTS speech synthesis (Yuemeng engine, 275 voices)
- 🔇 AudioRecord acoustic echo cancellation (AEC)
- ⚡ Adjustable speech rate 0.5x–2.5x
- 📋 Local conversation history with backup/restore
- Jetpack Compose + Material 3 UI
