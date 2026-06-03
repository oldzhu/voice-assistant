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

## 🔜 短期 (v1.1–v1.3)

### 1. ASR 升级 — 中英双语识别
- 替换纯中文模型为 `sherpa-onnx-streaming-zipformer-bilingual-zh-en`
- 改用 OnlineRecognizer 流式 API，支持中英混杂
- 英文术语如 "linux kernel"、"python API" 直接识别

### 2. 工具调用框架 (MCP 风格)
```
用户: "帮我搜索深圳天气"
  → LLM 输出 function_call: web_search("深圳天气")
  → App 执行 → 结果回传 LLM → TTS 播报
```
首批工具：
- 🌐 网页搜索
- 📍 获取位置
- 🌤 天气查询

### 3. 唤醒词 / 持续监听
- 免按按钮，"猪头猪头" 唤醒
- 静音检测自动休眠

## 📅 中期 (v1.4–v2.0)

| 模块 | 说明 |
|------|------|
| 🌐 联网能力 | HTTP 请求、网页抓取、RSS 订阅 |
| 🎵 媒体播放 | 音乐搜索/下载/播放 |
| ⏰ 系统工具 | 闹钟、提醒、日历、发微信 |
| 🗄 本地知识库 | RAG 检索个人笔记、文档 |
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
│   │   ├── llm/                     # LLM 后端
│   │   │   ├── CloudLLMBackend.kt   # DeepSeek API
│   │   │   └── LocalLLMBackend.kt   # Ollama 本地
│   │   └── speech/                  # 语音引擎
│   │       ├── SherpaAsrEngine.kt   # 离线 ASR
│   │       ├── SystemTtsEngine.kt   # 系统 TTS
│   │       └── SherpaTtsEngine.kt   # 离线 TTS (已弃用)
│   ├── src/main/assets/             # 模型文件 (需单独下载)
│   └── build.gradle.kts
└── docs/                            # 项目文档
```

## 🔗 技术栈

| 层 | 技术 |
|----|------|
| ASR | Sherpa-ONNX (Zipformer CTC / Transducer) |
| TTS | Android TextToSpeech (悦盟) |
| LLM | DeepSeek API / Ollama |
| UI | Jetpack Compose + Material 3 |
| 音频 | AudioRecord + AEC |
| 构建 | Gradle 9.x + Kotlin 2.x |
