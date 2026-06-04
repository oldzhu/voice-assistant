# Changelog — 猪头助手

所有值得注意的变更记录。遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

## [v1.2] — 2026-06-04

### 新增
- **打断模式**：三种模式可通过设置界面切换
  - A. 关闭打断（默认）— TTS 播报期间暂停 ASR，播完恢复
  - B. 语音打断 — TTS 播报期间持续监听，检测到语音自动中断 TTS
  - C. 关键词打断 — TTS 播报期间持续监听，仅检测到关键词（默认 "猪头"）时中断
- 设置界面新增「打断模式」下拉选择器

### 修复
- **状态显示 Bug**：回复完成后状态卡在"回复中"，现在正确切回"监听中"
  - 根因：`startListening()` 直接给 `state` 字段赋值，未走 `updateState()` 通知 UI
  - 修复：改用 `updateState()` 统一状态流转

### 变更
- 修正 ROADMAP 中 v1.1 模型名称（Zipformer → Paraformer）
- 更新技术栈 ASR 层描述为 Sherpa-ONNX OnlineRecognizer (Paraformer bilingual int8)

## [v1.1] — 2026-06-03

### 新增
- **双语流式 ASR**：OfflineRecognizer → OnlineRecognizer，支持实时部分结果输出
  - 模型：`sherpa-onnx-streaming-paraformer-bilingual-zh-en` (int8, ~227MB)
  - 中英混合识别，术语如 "linux kernel"、"python API" 直接识别
  - 内置端点检测，检测到语音 + 静音后自动结束

### 修复
- 端点检测空触发：增加 `hasSpeech` 检查，仅实际有语音时触发 endpoint

### 已知限制
- Zipformer transducer 模型因 sherpa-onnx v1.13.2 onnxruntime protobuf 兼容性问题无法加载，改用 Paraformer 绕过
- APK 体积较大 (~794MB)，含旧 CTC 和 transducer 模型未清理

## [v1.0] — 2026-06-02

### 新增
- 🎤 Sherpa-ONNX Zipformer CTC 离线中文语音识别
- 🧠 DeepSeek API 流式 LLM 对话
- 🔊 系统 TTS 语音合成（悦盟引擎，275 种语音）
- 🔇 AudioRecord 回声消除 (AEC)
- ⚡ 语速 0.5x–2.5x 可调
- 📋 对话历史本地存储、备份/恢复
- Jetpack Compose + Material 3 UI
