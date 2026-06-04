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

## 🔜 Near-term (v1.3)

### 1. Tool Calling Framework (MCP-style)
```
User: "Search Shenzhen weather for me"
  → LLM outputs function_call: web_search("Shenzhen weather")
  → App executes → result back to LLM → TTS response
```
Initial tools:
- 🌐 Web search
- 📍 Location
- 🌤 Weather

### 2. Wake Word / Always Listening
- Hands-free "pig head pig head" wake word
- Silence detection for auto-sleep

## 📅 Mid-term (v1.4–v2.0)

| Module | Description |
|--------|-------------|
| 🌐 Networking | HTTP requests, web scraping, RSS feeds |
| 🎵 Media | Music search / download / playback |
| ⏰ System Tools | Alarm, reminders, calendar, WeChat messages |
| 🗄 Local KB | RAG over personal notes / documents |
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
│   │   ├── llm/                     # LLM backends
│   │   │   ├── CloudLLMBackend.kt   # DeepSeek API
│   │   │   └── LocalLLMBackend.kt   # Ollama local
│   │   └── speech/                  # Speech engines
│   │       ├── SherpaAsrEngine.kt   # Offline ASR
│   │       ├── SystemTtsEngine.kt   # System TTS
│   │       └── SherpaTtsEngine.kt   # Offline TTS (deprecated)
│   ├── src/main/assets/             # Models (download separately)
│   └── build.gradle.kts
└── docs/                            # Project documentation
```

## 🔗 Tech Stack

| Layer | Technology |
|-------|------------|
| ASR | Sherpa-ONNX OnlineRecognizer (Paraformer bilingual int8) |
| TTS | Android TextToSpeech (Yuemeng) |
| LLM | DeepSeek API / Ollama |
| UI | Jetpack Compose + Material 3 |
| Audio | AudioRecord + AEC |
| Build | Gradle 9.x + Kotlin 2.x |
