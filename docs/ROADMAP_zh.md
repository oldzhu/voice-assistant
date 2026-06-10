# 猪头助手 — 开发路线图 (Roadmap)

## 已完成 ✅

### v1 — 核心语音管道
- Sherpa-ONNX ASR（Paraformer 中英双语流式）
- 系统 TTS（悦盟引擎，275 音色）
- 前台 Service 常驻监听
- DORMANT 休眠/唤醒状态机
- 打断模式（off/on/keyword）+ AEC

### v2 — LLM 工具调用
- ToolCallEngine + 13 个工具
- 多 tool_call 修复（DeepSeek API 400）
- 自我优化 L2（update_config）+ L4（remember/what_do_you_know）
- 外部工具（天气/新闻/定位/网页抓取）
- 位置感知（GPS + 逆地理编码）

### v3 — 自动测试框架
- TestEngine 标记系统（Python runner 解析）
- 5 个核心测试 + 7 个直接工具测试 + 1 个 LLM 介导测试 = 13 个
- 声学往返测试（TTS→mic→ASR→相似度）
- Test-First 开发规则

### v4 — 会话持久化 + TTS 清洗
- **TTS 文本清洗器**：双层防御（LLM system prompt + Kotlin regex）剥离 Markdown，确保 TTS 朗读自然
- **会话持久化**：ConversationStore — 原子写入（temp→rename），app 重启/被杀后恢复对话上下文
- **README.md**：完整项目文档（架构图、工具矩阵、中英双语文档索引）

### v5 — 清理 + 加固 🧹
- 删除旧测试代码重复
- 添加 tool_weather + tool_network_error 测试
- 网络断开错误边界测试

### v6 — L3 自生成工具 🧠
- create_tool 元工具：LLM 给自己写 prompt-template 工具
- DynamicTool：prompt-template 执行（无递归）
- 工具持久化到磁盘，重启后恢复

### v7 — 定时提醒 ⏰
- set_reminder, cancel_reminder, list_reminders 工具
- AlarmManager 定时 + TTS 播报
- app 重启后提醒不丢失

### v8 — 媒体搜索 + 播放 🎵
- search_media：cn.bing.com 抓取搜索结果
- play_media：ACTION_VIEW Intent 跳转浏览器/App
- 小说朗读：web_fetch → TTS

### v9 — 技能系统 🧩
- 多步骤工作流 + TTS 进度播报（`Flow<SkillStep>`）
- `.skill.md` 文件格式，支持用户自行安装技能
- `SkillToolAdapter`：Skill → Tool 透明包装（LLM 视角与普通 tool 无异）
- 内置 `MorningRoutineSkill`（天气+新闻链式调用）
- 技能自动测试（`skill_system`）

---

## 待讨论 📋

### 选项 A：上下文记忆 🧠
跨会话记住用户偏好 — 常用城市、偏好新闻类别、语速偏好等。
**难度**：低-中。基于现有 ConfigManager + RememberTool。

### 选项 B：Agent Swarm 多智能体 🐝
多 Agent 协作，spawn 子 Agent 并行执行任务（如同时搜索天气和新闻）。
**难度**：高。需要 AgentSwarmBus + 子 Agent 生命周期管理。

### 选项 C：多模态 + 人格 🎭
摄像头集成、屏幕理解、自定义人格/语气。
**难度**：高。需要 CLIP/VLM 集成 + 人格系统。

### 选项 D：用户提议的其他功能
（待讨论补充）
