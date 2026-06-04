# Pig Head Assistant — Project Roadmap

> Offline voice assistant agent for Android, targeting Hermes / OpenClaw capabilities

## ✅ Completed (v1.0)

| Module | Description |
|--------|-------------|
| 🎤 ASR | Sherpa-ONNX Zipformer CTC, offline Chinese recognition |
| 🧠 LLM | DeepSeek API, streaming conversation |
| 🔊 TTS | System TTS (Yuemeng engine), 275 voices, clear Chinese |
| 🔇 AEC | AudioRecord Acoustic Echo Cancellation, prevents self-loop |
| ⚡ Speech Rate | 0.5x–2.5x adjustable via settings slider |
| 📋 History | Backup / restore / delete conversation logs |

## ✅ Completed (v1.1)

| Module | Description |
|--------|-------------|
| 🌐 Bilingual ASR | Upgraded to Paraformer bilingual (`sherpa-onnx-streaming-paraformer-bilingual-zh-en`), supports mixed CN/EN |
| 📡 Streaming Recognition | OfflineRecognizer → OnlineRecognizer, real-time incremental results with built-in endpoint detection |

## ✅ Completed (v1.2)

| Module | Description |
|--------|-------------|
| 🗣 Barge-in Modes | Three switchable modes: A (off) / B (voice interrupt) / C (keyword interrupt, default "pig head") |
| 📊 Status Display | Fixed bug where state stuck at "replying" after TTS finished; now correctly returns to "listening" |
| 🔊 Full-duplex AEC | AudioSource → VOICE_COMMUNICATION + MODE_IN_COMMUNICATION, hardware AEC for barge-in modes |

## ✅ Completed (v1.3)

| Module | Description |
|--------|-------------|
| 🔧 Tool Calling Framework | OpenAI function calling compatible. Tool interface → ToolRegistry → ToolCallEngine multi-turn execution loop |
| 🎛 Voice Control Tools | `set_speech_rate` / `stop_listening` / `start_listening` / `set_barge_in_mode` / `clear_history` — control the assistant via natural language |

## ✅ Completed (v1.3.1)

| Module | Description |
|--------|-------------|
| 🌐 External Web Tools | `web_search` (DuckDuckGo) / `web_fetch` (page fetch) / `get_weather` — no API key required |
| 💤 Dormant State | Say "stop listening" to sleep; ASR keeps running, only wake phrases ("start listening" / "come back") accepted |
| 🐛 DeepSeek Thinking Fix | Preserve `reasoning_content` in multi-turn tool calls to avoid API 400 errors |

## 🔜 Near-term (v1.4)

### 1. Wake Word / Low-power Sleep
- Hands-free "pig head pig head" always-on wake word
- Sherpa-ONNX KeywordSpotter for low-power listening, replacing software filtering
- Battery-efficient sleep strategy

## 📅 Mid-term (v1.5–v2.0)

| Module | Description |
|--------|-------------|
| 🧠 Context Memory | Remember user preferences, key conversation details |
| 🗄 Local KB | RAG over personal notes / documents |
| 🎵 Media | Music search / download / playback |
| ⏰ System Tools | Alarm, reminders, calendar, WeChat messages |
| 🔧 Plugins | Third-party tool registration |

## 🚀 Long-term (v2.0+)

| Module | Description |
|--------|-------------|
| 📈 Trading | Stock quotes, backtesting, order execution |
| 📷 Multimodal | Camera recognition, screen understanding |
| 🤖 Agent Orchestration | Multi-step autonomous task planning |
| 🔗 Multi-device | Phone ↔ Desktop ↔ Server sync |
| 🗣 Personality | Custom assistant character, persistent memory |

## 📂 Project Structure

```
voice-assistant/
├── app/
│   ├── src/main/java/com/example/voiceassistant/
│   │   ├── MainActivity.kt          # Main UI
│   │   ├── VoiceService.kt          # Foreground service, state machine
│   │   ├── config/ConfigManager.kt  # Persistent config
│   │   ├── llm/                     # LLM backends + tool engine
│   │   │   ├── LLMBackend.kt        # Common interface
│   │   │   ├── CloudLLMBackend.kt   # DeepSeek API + function calling
│   │   │   ├── LocalLLMBackend.kt   # Ollama local
│   │   │   ├── Tool.kt              # Tool interface definition
│   │   │   ├── ToolRegistry.kt      # Tool registration hub
│   │   │   └── ToolCallEngine.kt    # LLM↔tool execution loop
│   │   ├── tools/                   # Tool implementations
│   │   │   ├── ControlTools.kt       # Voice control toolset (5 tools)
│   │   │   └── ExternalTools.kt      # External tools (search/fetch/weather, 3 tools)
│   │   └── speech/                  # Speech engines
│   │       ├── SherpaAsrEngine.kt   # Offline ASR
│   │       ├── SystemTtsEngine.kt   # System TTS
│   │       └── SherpaTtsEngine.kt   # Offline TTS (deprecated)
│   ├── src/main/assets/             # Models (download separately)
│   └── build.gradle.kts
├── docs/                            # Project documentation
│   ├── ROADMAP_zh.md / _en.md
│   ├── CHANGELOG_zh.md / _en.md
│   └── plans/                       # Implementation plans
└── CHANGELOG.md                     # (root, deprecated)
```

## 🔗 Tech Stack

| Layer | Technology |
|-------|------------|
| ASR | Sherpa-ONNX OnlineRecognizer (Paraformer bilingual int8) |
| TTS | Android TextToSpeech (Yuemeng) |
| LLM | DeepSeek API (function calling) / Ollama |
| Tool Framework | OpenAI function calling compatible |
| UI | Jetpack Compose + Material 3 |
| Audio | AudioRecord + AEC + MODE_IN_COMMUNICATION |
| Build | Gradle 9.x + Kotlin 2.x |
