# 🐷 猪头助手 (PigHead Assistant)

> A hands-free, always-listening Chinese voice assistant for Android — built for runners who don't want to touch their phone.  
> 一个免提、常驻监听的 Android 中文语音助手 — 为跑步时不想碰手机的人打造。

---

## 📐 Architecture / 架构

```
┌─────────────────────────────────────────────────────────┐
│                      Voice Pipeline                       │
│                                                           │
│   🎤 Mic ──→ ASR ──→ LLM ──→ TTS ──→ 🔊 Speaker          │
│   (16kHz)    │        │        │                          │
│              │        │        │                          │
│   Sherpa-    │   ToolCall    TtsTextSanitizer             │
│   ONNX       │   Engine      (strip Markdown)             │
│   Paraformer │        │        │                          │
│              │   DeepSeek     System TTS                   │
│              │   v4-flash     (悦盟 275 voices)            │
└──────────────┼────────┼────────┼──────────────────────────┘
               │        │        │
          ┌────▼──┐ ┌──▼────────▼──┐
          │ Tools  │ │   State      │
          │  14    │ │   Machine    │
          │ tools  │ │  LISTENING   │
          │        │ │  THINKING    │
          │ weather│ │  SPEAKING    │
          │  news  │ │  DORMANT     │
          │ search │ │              │
          │  ...   │ │  Barge-in    │
          └────────┘ └──────────────┘
```

### Pipeline / 流水线

| Stage | Tech | Notes |
|-------|------|-------|
| **ASR** (Speech → Text) | Sherpa-ONNX Paraformer bilingual zh-en | Streaming, int8 quantized, 227MB |
| **LLM** (Text → Reply) | DeepSeek v4-flash via API | Tool-calling with 14 tools |
| **TTS** (Reply → Speech) | System TTS (com.yuemeng.speechsuite) | 275 voices, local, ~1s init |
| **VAD** | Silero VAD ONNX | Prevents silent mic noise from triggering ASR |

### State Machine / 状态机

```
  LISTENING ←────────────────────┐
     │  (speech detected)         │
     ▼                           │
  THINKING ──→ LLM + Tools       │
     │                           │
     ▼                           │
  SPEAKING ──→ TTS plays         │
     │  (finished)               │
     └───────────────────────────┘
     
  Any state ──(sleep phrase)──→ DORMANT ──(wake word)──→ LISTENING
```

---

## 🧰 Tools / 工具 (14 total)

| Tool | Description |
|------|-------------|
| `get_weather` | Weather by city (Open-Meteo API) |
| `get_news` | News headlines by category |
| `get_location` | GPS + reverse geocoding |
| `web_search` | Bing web search |
| `web_fetch` | Fetch & clean webpage content |
| `read_article` | Search + fetch + read aloud (TTS) |
| `remember` | Save user facts to persistent memory |
| `what_do_you_know` | Recall saved memories |
| `update_config` | Change settings at runtime |
| `set_speech_rate` | Adjust TTS speed |
| `set_barge_in_mode` | Toggle interrupt mode (off/on/keyword) |
| `stop_listening` | Enter DORMANT state |
| `start_listening` | Exit DORMANT state |
| `clear_history` | Reset conversation history |

---

## 📂 Project Structure / 项目结构

```
voice-assistant/
├── app/
│   └── src/main/java/com/example/voiceassistant/
│       ├── VoiceService.kt          # Core foreground service, state machine
│       ├── MainActivity.kt          # Minimal UI launcher
│       ├── config/
│       │   └── ConfigManager.kt     # Runtime config persistence
│       │   ├── ConversationStore.kt  # Session persistence (atomic writes)
│       ├── llm/
│       │   ├── CloudLLMBackend.kt   # DeepSeek API client
│       │   ├── LocalLLMBackend.kt   # Ollama local LLM client
│       │   ├── ToolCallEngine.kt    # LLM ↔ tool orchestration loop
│       │   ├── ToolRegistry.kt      # Tool registration & dispatch
│       │   ├── Tool.kt              # Tool interface definitions
│       │   ├── McpClient.kt         # Model Context Protocol client
│       │   └── transport/           # MCP transports (stdio, HTTP)
│       ├── speech/
│       │   ├── SherpaAsrEngine.kt   # Sherpa-ONNX streaming ASR
│       │   ├── SystemTtsEngine.kt   # System TTS (悦盟)
│       │   ├── SherpaTtsEngine.kt   # Sherpa-ONNX TTS (备用)
│       │   ├── TtsTextSanitizer.kt  # Strip Markdown before TTS
│       │   └── BopomofoConverter.kt # Zhuyin conversion utility
│       ├── tools/
│       │   ├── ControlTools.kt      # Barge-in, speech rate, state control
│       │   ├── ExternalTools.kt     # Weather, search, web fetch
│       │   ├── LocationTool.kt      # GPS location
│       │   ├── NewsHeadlineTool.kt  # News headlines
│       │   ├── ReadAloudTool.kt     # Article search + TTS read-aloud
│       │   └── SelfImprovementTools.kt  # Memory & config tools
│       └── test/
│           ├── TestEngine.kt        # ADB-driven test harness
│           └── TestRunner.kt        # Test case runner
├── tests/
│   ├── runner.py                    # Python test runner (ADB)
│   └── adb_utils.py                # ADB helper utilities
├── docs/                            # Bilingual docs (zh + en)
│   ├── README_zh.md / README_en.md
│   ├── ROADMAP_zh.md / ROADMAP_en.md
│   ├── TESTING_zh.md / TESTING_en.md
│   ├── CHANGELOG_zh.md / CHANGELOG_en.md
│   ├── TTS_SANITIZER_DESIGN_zh.md / TTS_SANITIZER_DESIGN_en.md
│   ├── SELF_IMPROVEMENT.md
│   └── plans/                       # Design proposals
└── app/build/outputs/apk/debug/
    └── app-debug.apk                # Build artifact
```

---

## 🚀 Quick Start / 快速开始

### Prerequisites / 前置条件

- Android 8.0+ (API 26) device, arm64-v8a recommended
- DeepSeek API key ([platform.deepseek.com](https://platform.deepseek.com))
- Gradle 9+ and Android SDK 34

### Build / 编译

```bash
cd voice-assistant
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Deploy / 部署

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Or via wireless:
adb connect <phone-ip>:5555
adb -s <phone-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

### First Run / 首次运行

1. Open the app → grant microphone + location permissions
2. Enter your DeepSeek API key in settings
3. Tap start → the assistant begins listening
4. Say a wake word or just start speaking

### Running Tests / 运行测试

```bash
python tests/runner.py
```

Requires ADB connected. Tests cover ASR, TTS, tools, LLM roundtrip, and acoustic loopback.

---

## 📖 Documentation / 文档

| Document | Chinese | English |
|----------|---------|---------|
| Roadmap / 路线图 | [ROADMAP_zh.md](docs/ROADMAP_zh.md) | [ROADMAP_en.md](docs/ROADMAP_en.md) |
| Testing Guide / 测试指南 | [TESTING_zh.md](docs/TESTING_zh.md) | [TESTING_en.md](docs/TESTING_en.md) |
| Changelog / 变更记录 | [CHANGELOG_zh.md](docs/CHANGELOG_zh.md) | [CHANGELOG_en.md](docs/CHANGELOG_en.md) |
| TTS Sanitizer Design / TTS 清洗设计 | [TTS_SANITIZER_DESIGN_zh.md](docs/TTS_SANITIZER_DESIGN_zh.md) | [TTS_SANITIZER_DESIGN_en.md](docs/TTS_SANITIZER_DESIGN_en.md) |
| Persistence Design / 持久化设计 | [PERSISTENCE_DESIGN_zh.md](docs/PERSISTENCE_DESIGN_zh.md) | [PERSISTENCE_DESIGN_en.md](docs/PERSISTENCE_DESIGN_en.md) |
| Self-Improvement / 自我优化 | [SELF_IMPROVEMENT.md](docs/SELF_IMPROVEMENT.md) | — |
| Design Proposals / 设计方案 | [plans/](docs/plans/) | — |

---

## 🧠 Key Design Decisions / 关键设计决策

- **Local ASR + TTS, cloud LLM** — speech I/O stays on-device for low latency; LLM is cloud for quality
- **System TTS over Sherpa-ONNX TTS** — 275 natural Chinese voices vs. robotic VITS
- **TTS text sanitizer** — two-layer defense (prompt + regex) strips Markdown before speech
- **Session persistence** — conversation history survives app restart/process death
- **DORMANT state** — keyword-triggered sleep conserves battery during runs
- **Barge-in modes** — off (stop ASR during TTS), on (continuous listening), keyword (interrupt on wake word)
- **Test-First** — new features require automated tests before implementation
- **Bilingual docs** — every design doc has `_zh.md` + `_en.md` pair

---

## 🔧 Tech Stack / 技术栈

| Layer | Choice |
|-------|--------|
| Language | Kotlin |
| Android | API 26+, compileSdk 34 |
| Build | Gradle 9 (Kotlin DSL) |
| ASR | Sherpa-ONNX 1.13.2, Paraformer streaming |
| VAD | Silero VAD ONNX |
| LLM | DeepSeek v4-flash (OpenAI-compatible API) |
| TTS | OPPO/Realme System TTS (com.yuemeng.speechsuite) |
| Testing | Python 3, ADB, pytest |
| MCP | Stdio + HTTP transports |

---

## 📄 License

MIT
