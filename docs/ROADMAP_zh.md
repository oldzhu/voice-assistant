# 猪头助手 — 项目路线图

> 手机端离线语音助手 Agent，目标对标 Hermes / OpenClaw

## ✅ 已完成 (v1.0)

| 模块 | 说明 |
|------|------|
| 🎤 语音识别 (ASR) | Sherpa-ONNX Zipformer CTC，离线中文识别 |
| 🧠 LLM 对话 | DeepSeek API，流式对话 |
| 🔊 语音合成 (TTS) | 系统 TTS（悦盟引擎），275 种语音，中文清晰 |
| 🔇 回声消除 (AEC) | AudioRecord AEC，防 TTS 自循环 |
| ⚡ 语速调节 | 0.5x–2.5x 可调，设置界面滑块 |
| 📋 对话历史 | 备份/恢复/删除，本地存储 |

## ✅ 已完成 (v1.1)

| 模块 | 说明 |
|------|------|
| 🌐 双语 ASR | 升级为 Paraformer 双语模型 (`sherpa-onnx-streaming-paraformer-bilingual-zh-en`)，支持中英混杂 |
| 📡 流式识别 | OfflineRecognizer → OnlineRecognizer，边说边出字，内置端点检测 |

## ✅ 已完成 (v1.2)

| 模块 | 说明 |
|------|------|
| 🗣 打断模式 | 三种模式可切换：A 关闭打断 / B 语音打断 / C 关键词打断（默认 "猪头"） |
| 📊 状态显示 | 修复回复完成后状态卡在"回复中"的 bug，正确切回"监听中" |
| 🔊 全双工回声消除 | AudioSource → VOICE_COMMUNICATION + MODE_IN_COMMUNICATION，硬件 AEC 兼容打断模式 |

## ✅ 已完成 (v1.3)

| 模块 | 说明 |
|------|------|
| 🔧 工具调用框架 | OpenAI function calling 兼容 API。Tool 接口 → ToolRegistry → ToolCallEngine 多轮执行循环 |
| 🎛 语音控制工具 | `set_speech_rate` / `stop_listening` / `start_listening` / `set_barge_in_mode` / `clear_history`——自然语言控制猪头 |

## ✅ 已完成 (v1.3.1)

| 模块 | 说明 |
|------|------|
| 🌐 外部联网工具 | `web_search` (DuckDuckGo) / `web_fetch` (网页抓取) / `get_weather` (天气) —— 无需 API key |
| 💤 休眠状态 | 说"别听了"进入休眠，ASR 继续运行但只响应唤醒词（"开始听"/"猪头回来"），不回复一般语音 |
| 🐛 DeepSeek 思考模式 | 修复 `reasoning_content` 丢弃导致工具调用 API 400 的 bug |

## 🔜 短期 (v1.4)

### 1. 唤醒词 / 省电休眠
- 免按按钮，"猪头猪头" 常驻唤醒
- Sherpa-ONNX KeywordSpotter 低功耗唤醒，替代软件层过滤
- 休眠时省电策略

## 📅 中期 (v1.5–v2.0)

| 模块 | 说明 |
|------|------|
| 🧠 上下文记忆 | 记住用户偏好、历史对话关键信息 |
| 🗄 本地知识库 | RAG 检索个人笔记、文档 |
| 🎵 媒体播放 | 音乐搜索/下载/播放 |
| ⏰ 系统工具 | 闹钟、提醒、日历、发微信 |
| 🔧 插件系统 | 第三方工具注册机制 |

## 🚀 长期 (v2.0+)

| 模块 | 说明 |
|------|------|
| 📈 投资工具 | 行情查询、策略回测、下单 |
| 📷 多模态 | 拍照识别、屏幕理解 |
| 🤖 Agent 编排 | 多步骤任务自动编排（类似 Hermes） |
| 🔗 多设备同步 | 手机 ↔ 电脑 ↔ 服务器 |
| 🗣 人格系统 | 自定义助手性格、记忆 |

## 📂 项目结构

```
voice-assistant/
├── app/
│   ├── src/main/java/com/example/voiceassistant/
│   │   ├── MainActivity.kt          # 主界面
│   │   ├── VoiceService.kt          # 前台服务，状态机
│   │   ├── config/ConfigManager.kt  # 配置持久化
│   │   ├── llm/                     # LLM 后端 + 工具引擎
│   │   │   ├── LLMBackend.kt        # 通用接口
│   │   │   ├── CloudLLMBackend.kt   # DeepSeek API + function calling
│   │   │   ├── LocalLLMBackend.kt   # Ollama 本地
│   │   │   ├── Tool.kt              # 工具接口定义
│   │   │   ├── ToolRegistry.kt      # 工具注册中心
│   │   │   └── ToolCallEngine.kt    # LLM↔工具执行循环
│   │   ├── tools/                   # 工具实现
│   │   │   ├── ControlTools.kt       # 语音控制工具集 (5 tools)
│   │   │   └── ExternalTools.kt      # 外部联网工具 (搜索/抓取/天气, 3 tools)
│   │   └── speech/                  # 语音引擎
│   │       ├── SherpaAsrEngine.kt   # 离线 ASR
│   │       ├── SystemTtsEngine.kt   # 系统 TTS
│   │       └── SherpaTtsEngine.kt   # 离线 TTS (已弃用)
│   ├── src/main/assets/             # 模型文件 (需单独下载)
│   └── build.gradle.kts
├── docs/                            # 项目文档
│   ├── ROADMAP_zh.md / _en.md
│   ├── CHANGELOG_zh.md / _en.md
│   └── plans/                       # 实现计划
└── CHANGELOG.md                     # (root, deprecated)
```

## 🔗 技术栈

| 层 | 技术 |
|----|------|
| ASR | Sherpa-ONNX OnlineRecognizer (Paraformer bilingual int8) |
| TTS | Android TextToSpeech (悦盟) |
| LLM | DeepSeek API (function calling) / Ollama |
| 工具框架 | OpenAI function calling 兼容协议 |
| UI | Jetpack Compose + Material 3 |
| 音频 | AudioRecord + AEC + MODE_IN_COMMUNICATION |
| 构建 | Gradle 9.x + Kotlin 2.x |
