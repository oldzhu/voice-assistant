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

## 🔜 Near-term (v1.1–v1.3)

### 1. Bilingual ASR Upgrade
- Replace Chinese-only model with `sherpa-onnx-streaming-zipformer-bilingual-zh-en`
- Switch to OnlineRecognizer streaming API for mixed CN/EN input
- Direct recognition of terms like "linux kernel", "python API"

### 2. Tool Calling Framework (MCP-style)
```
User: "Search Shenzhen weather for me"
  → LLM outputs function_call: web_search("Shenzhen weather")
  → App executes → result back to LLM → TTS response
```
Initial tools:
- 🌐 Web search
- 📍 Location
- 🌤 Weather

### 3. Wake Word / Always Listening
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
| ASR | Sherpa-ONNX (Zipformer CTC / Transducer) |
| TTS | Android TextToSpeech (Yuemeng) |
| LLM | DeepSeek API / Ollama |
| UI | Jetpack Compose + Material 3 |
| Audio | AudioRecord + AEC |
| Build | Gradle 9.x + Kotlin 2.x |
