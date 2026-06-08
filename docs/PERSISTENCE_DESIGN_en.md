# Session Persistence Design — PigHead Assistant

## Problem

Android (especially OPPO/Realme Chinese ROMs) aggressively kills background processes. Every restart is a blank slate — the assistant forgets everything you just talked about.

## Design

### What's stored

- **Conversation history** (`conversation_history.json`): last 50 `{role, content}` pairs, JSON array
- **User memory** (`assistant_memory.json`): already persisted by RememberTool/RecallTool, no changes needed

### When it saves

| Trigger | Notes |
|---------|-------|
| After each LLM response | Primary save point, at end of `processQuery()` |
| `onDestroy()` | Last-resort safety net before process death |

### When it loads

After `initEngines()` completes → load from file → append to `conversationHistory`

### Atomic writes

Write to temp file → `rename` to target → prevents corruption if killed mid-write.

### Clearing

`clear_history` tool and `clearConversation()` also delete the persistence file.

## Testing

**Two-phase test** (`tests/runner.py --test tool_persistence`):

| Phase | Action | Verify |
|-------|--------|--------|
| Phase 1 | App in test mode writes 2 synthetic messages | `[PERSISTENCE:WRITTEN] count=2` |
| Phase 2 | Force-stop → restart app | `[PERSISTENCE:LOADED] count=2` |

## Files

| File | Role |
|------|------|
| `config/ConversationStore.kt` | Save/load/clear conversation history |
| `VoiceService.kt` | Integration: load + auto-save + onDestroy safety net |
| `tests/runner.py` | `tool_persistence` two-phase test |
