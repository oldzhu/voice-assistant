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

---

## 待讨论 📋

### 选项 A：L3 自生成 MCP 工具 🧠
**功能**：LLM 给自己写工具脚本。用户"我需要汇率转换"→ LLM 生成 Python→stdio MCP 启动→注册 ToolRegistry。
**自动测试**：`tool_mcp_create` — 生成简单 tool → 验证注册成功 → 执行正确。
**难度**：中。架构已就绪（StdioMcpTransport + McpClient）。

### 选项 B：会话持久化 💾
**功能**：app 重启恢复上次对话。手机杀进程后不丢失上下文。
**自动测试**：`tool_persistence` — 模拟对话 → 序列化 → 反序列化 → LLM 引用历史。
**难度**：低。SharedPreferences + JSON。

### 选项 C：定时提醒/闹钟 ⏰
**功能**："15 分钟后提醒我喝水"→ AlarmManager 定时 → TTS 播报。
**自动测试**：`tool_reminder` — 注册闹钟 → `dumpsys alarm` 验证。
**难度**：中。需要精准的 AlarmManager + 前台 Service 唤醒。

### 选项 D：清理 + 加固 🧹
- 删除旧 `runTtsTest()` 重复触发
- 添加 `tool_weather` 测试
- 网络断开错误边界测试
**自动测试**：`tool_weather` + `tool_network_error`
**难度**：低。主要是清理和补测试。

### 选项 E：媒体搜索 + 播放 🎵
**功能**：搜歌曲/视频/小说 → 不是只给链接，而是播放。
- **小说**：web_fetch 抓内容 → TTS 朗读 ✅ 已有能力
- **歌曲**：搜到后跳转音乐 App（Intent）或找免费音频源
- **视频**：搜到后 `Intent.ACTION_VIEW` 打开 YouTube/B站
**自动测试**：`tool_media_search` — 搜索 → 验证返回可播放内容。
**难度**：小说低、歌曲高、视频中。

### 选项 F：用户提议的其他功能
（待讨论补充）
