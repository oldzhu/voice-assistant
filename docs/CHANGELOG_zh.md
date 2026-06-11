# Changelog — 猪头助手

所有值得注意的变更记录。遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 格式。

## [v1.7] — 2026-06-10

### 新增
- **技能系统（`skill/` 包）**：多步骤工作流 + TTS 进度播报
  - `Skill` 接口：`execute(args, context) → Flow<SkillStep>` — 每步发射进度
  - `SkillContext`：向 skill 提供 `callTool`、`speakProgress`、`filesDir`
  - `SkillRegistry`：管理 skill 生命周期，从磁盘加载 `.skill.md` 文件
  - `SkillToolAdapter`：透明地将 `Skill` 包装为 `Tool` — LLM 调用 `skill_<name>` 与普通 tool 无异
  - `SkillParser`：解析 `.skill.md` YAML frontmatter 为可执行的 `FileDefinedSkill`
  - `SkillExecutor`：创建接入真实 ToolRegistry + TTS 的 `SkillContext`
  - 内置 `MorningRoutineSkill`：链式调用 get_location → get_weather → get_news，带 TTS 进度
  - 内置资源：`assets/skills/morning-routine.skill.md` 用于演示和分发
  - LLM system prompt 已更新：LLM 知道对多步骤任务调用 `skill_*` 工具
- **自动测试**：`skill_system` 测试已加入 TestRunner + runner.py
- **文档**：`docs/SKILL_SYSTEM_zh.md` + `docs/SKILL_SYSTEM_en.md`
- **路线图**：已更新 — v5–v9 标记为已完成，新增 A–D 选项供讨论

## [v1.9] — 2026-06-11

### 新增
- **VisionProvider 架构**：抽象接口 + 双后端
  - `LocalVisionProvider`：Tesseract 4 OCR（`chi_sim+eng`，47 MB 内置），零云端隐私
  - `RemoteVisionProvider`：OpenAI 兼容 VLM，完整图片理解
  - 运行时配置：`vision_provider`（本地/远程/自动）、`vision_model`、`vision_api_key`、`vision_base_url`
  - 凭证回退：`vision_api_key`/`vision_base_url` 为空时自动使用主 LLM 凭证
- **无障碍静默截屏**（Android 11+）：通过无障碍服务 `takeScreenshot()` — 零权限弹窗
  - 无障碍服务未开启时回退到 MediaProjection
- **设置页面重设计**：LLM 设置可编辑（API Key、Base URL、Model）+ 独立的视觉设置区段
  - 视觉 provider 三选一（本地/云端/自动）、Vision API Key、Vision URL、Vision Model — 均可点击编辑

### 变更
- `SettingsDialog`：只读标签改为可点击行；添加 ScrollView 支持长内容
- `SelfImprovementTools.update_config`：支持 `vision_api_key` 和 `vision_base_url` 键
- `VoiceService.createVisionProvider()`：使用 `effectiveVisionApiKey()`/`effectiveVisionBaseUrl()` 带回退逻辑

### 文档
- `docs/VISION_DESIGN_zh.md` + `docs/VISION_DESIGN_en.md`

## [v1.8] — 2026-06-10

### 新增
- **多模态 — 拍照 + 截屏识别（Phase 5）**：视觉 AI 理解图像内容
  - `describe_photo` 工具：启动系统相机 → 拍照 → Base64 → DeepSeek Vision API → TTS 描述
  - `capture_screen` 工具：MediaProjection 截屏 → JPEG → Vision API → 文字提取 + 画面描述
  - 三种模式：`text`（仅 OCR 提取文字）、`describe`（仅描述画面）、`full`（提取文字 + 描述）
  - `ScreenCaptureManager`：静态桥接 Service ↔ Activity（解决 Service 无法 `startActivityForResult` 的问题）
  - `CloudLLMBackend.describeImage()`：OpenAI 兼容 multimodal message 格式发送 Base64 图片
  - `DescribeImageTool`：相机 FileProvider + EXTRA_OUTPUT + 轮询等待照片写入
- **AndroidManifest 更新**：CAMERA 权限、FileProvider 注册、file_paths.xml
- **自动测试**：`tool_describe_photo` + `tool_capture_screen` 测试类型（手动测试为主，需相机/屏幕权限）
- **System prompt 更新**：LLM 学会何时调用视觉工具（"看看这是什么"→describe_photo，"截屏识别"→capture_screen）

## [v1.6] — 2026-06-09

### 新增
- **TTS 文本清洗器 (`TtsTextSanitizer`)**：双层防御剥离 Markdown，确保 TTS 朗读自然
  - Layer 1: 系统提示词明确要求 LLM 不输出 Markdown（"你的回复会被语音朗读，禁止使用Markdown格式"）
  - Layer 2: Kotlin regex 后处理，剥离 **加粗**、*斜体*、`代码`、[链接]、#标题、列表符号、下划线、删除线
  - `VoiceService.processQuery()` 中 `speakTts(sanitizeForTts(response))` 确保干净语音
- **会话持久化 (`ConversationStore`)**：app 重启/被杀后恢复对话上下文
  - 原子写入（temp file → rename）防 corruption
  - `save()` 每次 LLM 回复后自动保存
  - `load()` 在 `initEngines()` 后恢复历史
  - `clear()` 通过 `clear_history` 工具触发
  - `onDestroy()` 兜底保存
  - 自动测试 `tool_persistence`：写→杀→重启→验证恢复
- **项目 README**：完整文档，含架构图、工具矩阵（14 tools）、中英双语文档索引、快速开始指南

### 修复
- Python test runner `tool_persistence` 在 WSL 下 `subprocess.run` + Windows ADB 的可靠性改进

## [v1.5] — 2026-06-05

### 新增
- **文章朗读工具 (`read_article`)**：搜索 + 抓取 + 清洗网页内容，TTS 直接朗读
  - 支持 query（Bing 搜索 + 百度兜底）和 url 两种输入方式
  - 智能文本清洗：去除导航/广告/脚本残留，提取正文段落
  - 长文截断（1500字），自动提示「说继续听下一段」
  - 系统提示词优化：古诗/名篇 LLM 直接背诵，不调工具
- **自动测试扩展至 14 个**：
  - `tool_read_article` — 新工具直接测试（URL 抓取验证）
  - `tool_location`, `tool_news`, `tool_web_fetch` — 外部工具直接测试
  - `tool_config`, `tool_memory`, `tool_barge_in`, `tool_clear_history` — 本地工具直接测试
  - `llm_multi_tool` — 多 tool_call 回归测试（防 API 400）
- **Test-First 开发规则**：新功能必须先设计自动测试再编写代码

### 修复
- **测试后卡死**：`runTest()` 完成后未恢复 `testMode=false` + `startListening()`，导致 app 无法继续监听
- **read_article 超时**：搜索后端从 DuckDuckGo 改为 Bing（中国可访问），百度兜底

### 文档
- `docs/ROADMAP_zh.md` / `docs/ROADMAP_en.md` — 路线图
- `docs/TESTING_zh.md` / `docs/TESTING_en.md` — 完整测试文档（14 个测试）
- 技能 `auto-test-framework.md` — 测试架构 + Test-First 工作流

## [v1.4] — 2026-06-04

### 新增
- **MCP Client**：支持连接 MCP (Model Context Protocol) server，自动发现并注册工具
  - `StdioMcpTransport` — ProcessBuilder 启动本地 MCP server（Go/Rust 编译的二进制）
  - `HttpMcpTransport` — OkHttp 连接远程 HTTP MCP server
  - `McpClient` — 完整 JSON-RPC 2.0 协议实现（initialize / tools/list / tools/call）
  - `McpToolAdapter` — MCP tool schema 自动适配为 Tool 接口
  - 配置持久化：`ConfigManager.mcpServers`（JSON 数组，支持多 server）
  - 工具命名：`mcp_{server_name}_{tool_name}` 防止冲突
- `ConfigManager` 新增 `McpServerConfig` 数据类和 `mcpServers` 属性

### 架构
```
VoiceService → connectMcpServers()
  ├── StdioMcpTransport (本地进程)
  └── HttpMcpTransport  (远程 HTTP)
        │
        ▼ McpClient (JSON-RPC)
  initialize() → tools/list() → McpToolAdapter → ToolRegistry
```

> 详见 [docs/plans/2026-06-04-mcp-client.md](plans/2026-06-04-mcp-client.md)

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
