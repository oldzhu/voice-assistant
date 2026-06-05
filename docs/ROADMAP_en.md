# Voice Assistant — Development Roadmap

## Completed ✅

### v1 — Core Voice Pipeline
- Sherpa-ONNX ASR (Paraformer bilingual CN/EN streaming)
- System TTS (Yuemeng engine, 275 voices)
- Foreground Service always-listening
- DORMANT sleep/wake state machine
- Barge-in modes (off/on/keyword) + AEC

### v2 — LLM Tool Calling
- ToolCallEngine + 13 tools
- Multi-tool-call fix (DeepSeek API 400)
- Self-improvement L2 (update_config) + L4 (remember/what_do_you_know)
- External tools (weather/news/location/web fetch)
- Location awareness (GPS + reverse geocoding)

### v3 — Auto-Test Framework
- TestEngine marker system (Python runner parses)
- 5 core + 7 direct tool + 1 LLM-mediated = 13 tests
- Acoustic round-trip (TTS→mic→ASR→similarity)
- Test-First development rule

---

## For Discussion 📋

### Option A: L3 Self-Generated MCP Tools 🧠
**Feature**: LLM writes its own tool scripts. User "I need currency conversion"→ LLM generates Python→stdio MCP launch→registered in ToolRegistry.
**Auto-test**: `tool_mcp_create` — generate simple tool → verify registration → verify execution.
**Difficulty**: Medium. Architecture ready (StdioMcpTransport + McpClient).

### Option B: Session Persistence 💾
**Feature**: Restore conversation after app restart. No context loss when phone kills process.
**Auto-test**: `tool_persistence` — simulate conversation → serialize → deserialize → LLM references history.
**Difficulty**: Low. SharedPreferences + JSON.

### Option C: Timed Reminders / Alarms ⏰
**Feature**: "Remind me to drink water in 15 minutes"→ AlarmManager schedule → TTS announcement.
**Auto-test**: `tool_reminder` — register alarm → `dumpsys alarm` verify.
**Difficulty**: Medium. Needs precise AlarmManager + foreground Service wakeup.

### Option D: Cleanup + Hardening 🧹
- Remove old `runTtsTest()` duplicate trigger
- Add `tool_weather` direct test
- Network-disconnect error boundary tests
**Auto-test**: `tool_weather` + `tool_network_error`
**Difficulty**: Low. Mostly cleanup and test coverage.

### Option E: Media Search + Playback 🎵
**Feature**: Search songs/videos/novels → play, not just return links.
- **Novels**: web_fetch content → TTS read aloud ✅ existing capability
- **Songs**: Search → open music app (Intent) or find free audio sources
- **Videos**: Search → `Intent.ACTION_VIEW` open YouTube/Bilibili
**Auto-test**: `tool_media_search` — search → verify playable content returned.
**Difficulty**: Novels low, songs high, videos medium.

### Option F: User-Proposed Features
(TBD — open for discussion)
