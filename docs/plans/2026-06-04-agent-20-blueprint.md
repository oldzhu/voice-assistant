# 猪头助手 2.0 架构蓝图

> 日期：2026-06-04 | 对标：Hermes Agent 能力

## 目标

把猪头助手从"语音对话+工具调用"升级为**自主 Agent 平台**，支持自我进化、技能扩展、多 Agent 协作。

## 五阶段路线

```
v1.3 ✅  Tool Calling
v1.4 🔜  MCP Client      ← 下一步
v1.5 📅  Skill System
v2.0 🚀  Agent Swarm
v2.x 🚀  Multi-modal + Personality
```

---

## Phase A: 自我进化 — Skill System (v1.5)

### 问题

当前 Tool 是单次函数调用（`execute(args) → result`），无法处理多步骤工作流。

比如"帮我写个天气 App"需要：创建项目→写代码→编译→部署→测试，20+ 步无法用单个 tool 表达。

### 设计：Skill = 可组合的工作流

```kotlin
interface Skill {
    val name: String
    val description: String
    val parameters: Map<String, ToolParameter>

    /** Execute the skill as a coroutine flow — yields progress updates */
    suspend fun execute(args: Map<String, Any?>): Flow<SkillStep>

    /** Validate that the skill can run in current environment */
    suspend fun canExecute(): Boolean
}

data class SkillStep(
    val status: StepStatus,   // RUNNING | DONE | FAILED
    val message: String,      // Human-readable progress
    val result: Any? = null   // Structured output for chaining
)
```

**Skill vs Tool 对比：**

| | Tool | Skill |
|---|---|---|
| 执行 | 单次同步/异步调用 | 流式多步骤 |
| 输出 | String | Flow<SkillStep> |
| 组合 | 需 LLM 编排 | 可内部链式调用 tool |
| 超时 | 30s | 5min+ |
| 示例 | `set_speech_rate(1.5)` | `build_app("天气App")` |

### Skill 注册机制

```kotlin
class SkillRegistry {
    fun register(skill: Skill)
    fun registerFromFile(path: String)      // .skill.md → parsed into Skill
    fun registerFromUrl(url: String)        // 远程导入（需安全审查）
    fun list(): List<Skill>
    fun find(name: String): Skill?
}
```

**安全审查（Safe Import）：**
- Skill 文件声明所需权限（文件访问/网络/系统调用）
- 导入前展示权限列表，用户确认
- 沙箱执行：每个 skill 限制可调用 tool 范围
- 代码签名：可选 GPG 签名校验

---

## Phase B: 可扩展技能 — Plugin System (v1.5)

### 设计

Skill 以独立 `.skill.md` 文件分发，格式类似 Hermes SKILL.md：

```markdown
---
name: weather-app-builder
version: 1.0.0
author: oldzhu
permissions: [terminal, file_write, network]
depends: [android-sdk, gradle]
---

# Weather App Builder

## Description
Build a simple weather app from scratch.

## Steps
1. Create Android project scaffold
2. Add weather API tool
3. Build UI with Compose
4. Compile & install
```

### 分发渠道

| 渠道 | 说明 |
|------|------|
| 本地文件 | `~/猪头助手/skills/*.skill.md` |
| GitHub Gist | `skill install <gist-url>` |
| Skill Hub | 社区仓库（未来） |

---

## Phase C: MCP Client (v1.4) ← 当前优先级

### 设计

MCP (Model Context Protocol) 是 Anthropic 提出的工具服务器协议。猪头作为 MCP Client，可以连接任意 MCP Server 获取工具。

```
猪头助手 (MCP Client)
    │
    ├── stdio → ./tools/my-mcp-server (本地进程)
    ├── HTTP  → https://api.example.com/mcp (远程)
    └── ACP   → opencode --acp (Agent Communication Protocol)
```

### 实现

```kotlin
interface McpTransport {
    suspend fun connect()
    suspend fun listTools(): List<Tool>
    suspend fun callTool(name: String, args: Map<String, Any?>): String
    suspend fun disconnect()
}

class StdioTransport(private val command: String) : McpTransport { ... }
class HttpTransport(private val url: String) : McpTransport { ... }
```

MCP Server 连接后，其暴露的工具自动注册到 ToolRegistry，与内置 tool 无差别使用。

---

## Phase D: 多 Agent 协作 — Agent Swarm (v2.0)

### 设计

```kotlin
class AgentSwarmBus {
    /** Spawn a sub-agent with a goal */
    suspend fun spawn(goal: String, tools: List<String>): AgentHandle

    /** Send message to sub-agent */
    suspend fun send(agentId: String, message: String)

    /** Wait for sub-agent completion */
    suspend fun await(agentId: String): AgentResult
}

data class AgentHandle(val id: String, val goal: String)
```

### 通信模型

```
主 Agent (猪头)
  ├── spawn("写前端界面") → Sub-Agent A (UI)
  ├── spawn("写后端逻辑") → Sub-Agent B (Logic)
  └── 汇总结果 → TTS 播报
```

每个 sub-agent 是独立协程，有独立 tool 清单和超时。主 agent 通过 Bus 发送指令、接收结果。

---

## Phase E: 多模态 + 人格 (v2.x)

| 能力 | 说明 |
|------|------|
| 📷 拍照识别 | 调用相机 → CLIP/VLM 识别 → 语音描述 |
| 📱 屏幕理解 | 截图 → OCR → 上下文理解 |
| 🗣 人格系统 | 自定义语气、口头禅、知识偏好 |
| 🔗 多设备 | 手机 ↔ 电脑 ↔ 服务器 Agent 同步 |

---

## 技术选型

| 层 | 当前 | 2.0 目标 |
|----|------|----------|
| LLM | DeepSeek API | DeepSeek + 本地 Qwen |
| Tool | 8 个内置 | 内置 + MCP Server + Skill |
| 编排 | ToolCallEngine | SkillExecutor + AgentSwarmBus |
| 存储 | 对话历史 | 对话 + 记忆 + 知识库 |
| 安全 | 无 | 权限声明 + 沙箱执行 |

---

## 实施优先级

1. **v1.4 MCP Client** — 打开生态，立即可用外部工具
2. **v1.5 Skill System** — 多步骤工作流，安全导入
3. **v1.5 Context Memory** — 记住用户偏好
4. **v2.0 Agent Swarm** — 多 Agent 协作
5. **v2.x Multi-modal + Personality** — 拍照、人格
