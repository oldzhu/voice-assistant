# 会话持久化设计 — 猪头助手

## 问题

Android（尤其 OPPO/Realme 中国 ROM）会频繁杀后台进程。每次重启，对话历史丢失——助手忘记刚聊过什么。

## 设计

### 存储内容

- **对话历史**（`conversation_history.json`）：最近 50 条 `{role, content}`，JSON 数组
- **用户记忆**（`assistant_memory.json`）：已由 RememberTool/RecallTool 持久化，无需改动

### 保存时机

| 时机 | 说明 |
|------|------|
| 每次 LLM 回复后 | 主要保存点，持久化在 `processQuery()` 末尾 |
| `onDestroy()` | 兜底保存，进程被杀前的最后机会 |

### 加载时机

`initEngines()` 完成后 → 从文件加载 → 追加到 `conversationHistory`

### 原子写入

写临时文件 → `rename` 替换 → 防止进程中途被杀导致文件损坏。

### 清除

`clear_history` 工具和 `clearConversation()` 同时删除持久化文件。

## 测试

**两阶段测试**（`tests/runner.py --test tool_persistence`）：

| 阶段 | 操作 | 验证 |
|------|------|------|
| Phase 1 | App 在 test mode 写 2 条合成对话 | `[PERSISTENCE:WRITTEN] count=2` |
| Phase 2 | Force-stop → 重启 | `[PERSISTENCE:LOADED] count=2` |

## 文件

| 文件 | 作用 |
|------|------|
| `config/ConversationStore.kt` | 保存/加载/清除对话历史 |
| `VoiceService.kt` | 集成：加载 + 自动保存 + onDestroy 兜底 |
| `tests/runner.py` | `tool_persistence` 两阶段测试 |
