# Skill System

PigHead Assistant's skill system — multi-step workflows with progress reporting and .skill.md distribution.

## Overview

### Skill vs Tool

| | Tool | Skill |
|---|---|---|
| Duration | <30s | up to 5 min |
| Steps | 1 atomic call | N sequential steps |
| Progress | none | TTS spoken updates |
| Composition | LLM orchestrates multiple tool calls | Internal tool chaining |
| Registration | Direct `Tool` interface | `Skill` → `SkillToolAdapter` → `Tool` |

### Architecture

```
VoiceService
  ├── SkillRegistry        ← Manages Skill instances
  │     ├── MorningRoutineSkill  (built-in)
  │     └── FileDefinedSkill     (loaded from .skill.md)
  ├── SkillExecutor        ← Creates SkillContext (injects tool + TTS)
  └── ToolRegistry
        └── SkillToolAdapter  ← Wraps Skill as Tool
              └── LLM perspective: skill_morning_routine looks like any other tool
```

### Workflow

```
User: "morning routine"
  → LLM decides: call skill_morning_routine
  → ToolCallEngine sees it as a regular Tool
  → SkillToolAdapter.execute()
  → MorningRoutineSkill emits Flow<SkillStep>:
       ├─ RUNNING: "Getting your location..."    → TTS speaks
       ├─ DONE:    "Location acquired"
       ├─ RUNNING: "Checking today's weather..." → TTS speaks
       ├─ DONE:    "Weather acquired"
       ├─ RUNNING: "Fetching news headlines..."  → TTS speaks
       └─ DONE:    "Morning routine complete"    → Result back to LLM
  → LLM receives final result, summarizes for user
```

## Creating Skills

### Method 1: Kotlin class (built-in)

```kotlin
class MySkill : Skill {
    override val name = "my_skill"
    override val description = "Called when user says..."

    override suspend fun execute(args: Map<String, Any?>, context: SkillContext): Flow<SkillStep> = flow {
        emit(SkillStep(StepStatus.RUNNING, "Executing step 1..."))
        val result = context.callTool("some_tool", mapOf("param" to "value"))
        emit(SkillStep(StepStatus.DONE, "Complete", result))
    }
}

// Register
skillRegistry.register(MySkill())
```

### Method 2: .skill.md file (user-installable)

```markdown
---
name: my-skill
description: My custom skill
version: 1.0.0
steps:
  - say: Getting weather...
    tool: get_weather
  - say: Getting news...
    tool: get_news
    args: { "category": "tech" }
---

# My Skill
Documentation...
```

Place in `filesDir/skills/*.skill.md` — auto-loaded on startup.

## Core API

### Skill Interface

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
    val callTool: suspend (String, Map<String, Any?>) -> String,  // Call any registered tool
    val speakProgress: suspend (String) -> Unit,                   // TTS progress broadcast
    val filesDir: File                                             // Skill-local storage
)
```

### SkillStep

```kotlin
data class SkillStep(
    val status: StepStatus,  // RUNNING | DONE | FAILED
    val message: String,     // Human-readable progress text
    val result: Any? = null  // Structured output (chainable)
)
```

## File Structure

```
app/src/main/java/com/example/voiceassistant/
├── skill/
│   ├── Skill.kt              — Skill interface + SkillStep + SkillContext
│   ├── SkillRegistry.kt      — Register/load/manage skills
│   ├── SkillToolAdapter.kt   — Skill → Tool adapter
│   ├── SkillParser.kt        — .skill.md parser
│   ├── SkillExecutor.kt      — Create SkillContext (injects real tool + TTS)
│   └── builtin/
│       └── MorningRoutineSkill.kt  — Built-in demo skill
└── assets/
    └── skills/
        └── morning-routine.skill.md  — Bundled .skill.md (docs + distribution)
```

## Testing

```bash
# Direct test
python3 tests/runner.py --test skill_system --no-build

# Full suite (includes skill)
python3 tests/runner.py
```

Test verifies:
1. Skill registered in SkillRegistry
2. Skill exposed as `skill_<name>` in ToolRegistry
3. Skill execution returns valid results
