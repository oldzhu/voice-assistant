# Changelog — 猪头助手

所有值得注意的变更记录。遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

## [v1.3.1] — 2026-06-04

### 新增
- **外部联网工具**：3 个网络工具，LLM 可自行搜索和获取信息
  - `web_search` — DuckDuckGo 网页搜索（无需 API key）
  - `web_fetch` — 抓取网页内容并提取纯文本
  - `get_weather` — wttr.in 天气查询（无需 API key）
- **休眠状态 (DORMANT)**：说"别听了"/"休息吧"/"睡觉"等进入休眠
  - ASR 继续监听但不回复一般语音，仅响应唤醒词（"开始听"/"猪头回来"等）
  - 进入休眠播告别语"好的，我休息了，随时呼我"
  - 唤醒播问候语"我回来了，有啥要聊的？"
  - 休眠命令通过关键词直接匹配（绕过 LLM），保证可靠性

### 修复
- **DeepSeek 思考模式兼容**：开启推理模式后 `reasoning_content` 必须原样传回，否则 API 400
  - `CloudLLMBackend.chatWithTools()` 保留原始消息对象，第二轮请求携带 `reasoning_content`
  - `ToolCallEngine` 从原始消息提取 `tool_call_id`，避免丢失 DeepSeek 特有字段
- **休眠 TTS 串扰**：`processQuery()` 检测到 DORMANT 状态时直接 return，不播报 LLM 回复

### 变更
- `stop_listening` 工具不再完全停 ASR → 改为进入 DORMANT 状态
- 工具总数：5 个控制工具 + 3 个外部工具 = 8 个

## [v1.3] — 2026-06-04

### 新增
- **工具调用框架**：OpenAI function calling 兼容协议，LLM 可自动调用本地工具
  - `Tool` 接口：统一的工具定义（name/description/parameters/execute）
  - `ToolRegistry`：工具注册与执行中心
  - `ToolCallEngine`：LLM↔工具多轮执行循环（最多 5 轮，30s 超时）
  - `CloudLLMBackend.chatWithTools()`：发送 tools 定义到 API + 解析 function_call 响应
- **5 个语音控制工具**，自然语言控制猪头：
  - `set_speech_rate` — 调节语速（"把语速调到1.5倍"）
  - `stop_listening` — 停止监听（"别听了"）
  - `start_listening` — 恢复监听（"开始听"）
  - `set_barge_in_mode` — 切换打断模式（"切成关键词打断"）
  - `clear_history` — 清空对话（"忘记之前的对话"）

### 变更
- `VoiceService.processQuery()` 现在优先走 ToolCallEngine，支持工具调用
- 升级 ROADMAP：v1.3 完成标记，v1.4 外部工具规划
- 新增 `tools/` 包，项目结构扩展

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
- **模式 C 无效 Bug**：`onResult` 条件 `!= "off"` 导致 B/C 行为相同
  - 修复：加 `bargeInKeywordDetected` 标志，`onPartial` 检测关键词即停 TTS，`onResult` 只处理关键词命中

### 变更
- AudioSource → `VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION`，启用硬件全双工回声消除
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
