# Skill System — 技能系统

猪头助手的技能系统，支持多步骤工作流、进度播报和 .skill.md 文件分发。

## 概述

### Skill vs Tool

| | Tool（工具） | Skill（技能） |
|---|---|---|
| 执行时间 | <30s | 最长 5 分钟 |
| 步骤数 | 1 次原子调用 | N 个顺序步骤 |
| 进度播报 | 无 | TTS 实时播报每步进度 |
| 编排方式 | LLM 编排多个 tool call | 内部链式调用 tool |
| 注册方式 | 直接 `Tool` 接口 | `Skill` → `SkillToolAdapter` → `Tool` |

### 架构

```
VoiceService
  ├── SkillRegistry        ← 管理 Skill 实例
  │     ├── MorningRoutineSkill  (内置)
  │     └── FileDefinedSkill     (从 .skill.md 加载)
  ├── SkillExecutor        ← 创建 SkillContext（注入 tool + TTS）
  └── ToolRegistry
        └── SkillToolAdapter  ← 把 Skill 包装成 Tool
              └── LLM 视角：skill_morning_routine 就是一个普通 tool
```

### 工作流

```
用户: "早上好"
  → LLM 决定调用 skill_morning_routine
  → ToolCallEngine 看到它是个普通 Tool
  → SkillToolAdapter.execute()
  → MorningRoutineSkill 发射 Flow<SkillStep>:
       ├─ RUNNING: "正在获取你的位置..."     → TTS 播报
       ├─ DONE:    "位置获取完成"
       ├─ RUNNING: "正在查看今天的天气..."   → TTS 播报
       ├─ DONE:    "天气获取完成"
       ├─ RUNNING: "正在获取今天的新闻..."   → TTS 播报
       └─ DONE:    "早晨 routine 完成"      → 结果返回 LLM
  → LLM 收到最终结果，向用户总结
```

## 创建 Skill

### 方式 1：Kotlin 类（内置 skill）

```kotlin
class MySkill : Skill {
    override val name = "my_skill"
    override val description = "当用户说XX时调用..."

    override suspend fun execute(args: Map<String, Any?>, context: SkillContext): Flow<SkillStep> = flow {
        emit(SkillStep(StepStatus.RUNNING, "正在执行第一步..."))
        val result = context.callTool("some_tool", mapOf("param" to "value"))
        emit(SkillStep(StepStatus.DONE, "完成", result))
    }
}

// 注册
skillRegistry.register(MySkill())
```

### 方式 2：.skill.md 文件（用户可安装）

```markdown
---
name: my-skill
description: 我的自定义技能
version: 1.0.0
steps:
  - say: 正在获取天气...
    tool: get_weather
  - say: 正在获取新闻...
    tool: get_news
    args: { "category": "科技" }
---

# My Skill
文档说明...
```

放在 `filesDir/skills/*.skill.md`，启动时自动加载。

## 核心 API

### Skill 接口

```kotlin
interface Skill {
    val name: String
    val description: String
    suspend fun execute(args: Map<String, Any?>, context: SkillContext): Flow<SkillStep>
}
```

### SkillContext

```kotlin
data class SkillContext(
    val callTool: suspend (String, Map<String, Any?>) -> String,  // 调用任意已注册 tool
    val speakProgress: suspend (String) -> Unit,                   // TTS 播报进度
    val filesDir: File                                             // skill 本地存储
)
```

### SkillStep

```kotlin
data class SkillStep(
    val status: StepStatus,  // RUNNING | DONE | FAILED
    val message: String,     // 人类可读的进度文本
    val result: Any? = null  // 结构化输出（链式传递）
)
```

## 文件结构

```
app/src/main/java/com/example/voiceassistant/
├── skill/
│   ├── Skill.kt              — Skill 接口 + SkillStep + SkillContext
│   ├── SkillRegistry.kt      — 注册/加载/管理 Skill
│   ├── SkillToolAdapter.kt   — Skill → Tool 适配器
│   ├── SkillParser.kt        — .skill.md 解析器
│   ├── SkillExecutor.kt      — 创建 SkillContext（注入真实 tool + TTS）
│   └── builtin/
│       └── MorningRoutineSkill.kt  — 内置示例 skill
└── assets/
    └── skills/
        └── morning-routine.skill.md  — 内置 .skill.md（文档+分发）
```

## 测试

```bash
# 直接测试
python3 tests/runner.py --test skill_system --no-build

# 全量测试（含 skill）
python3 tests/runner.py
```

测试内容：
1. 确认 skill 在 SkillRegistry 中注册
2. 确认 skill 作为 `skill_<name>` 暴露在 ToolRegistry
3. 执行 skill 并验证返回结果
