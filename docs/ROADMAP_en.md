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

### v4 — Session Persistence + TTS Sanitizer
- **TTS text sanitizer**: two-layer defense (LLM system prompt + Kotlin regex) strips Markdown for natural TTS speech
- **Session persistence**: ConversationStore — atomic writes (temp→rename), conversation survives app restart/kill
- **README.md**: comprehensive project docs (architecture diagram, tool matrix, bilingual doc index)

### v5 — Cleanup + Hardening 🧹
- Removed legacy test code duplicates
- Added tool_weather + tool_network_error direct tests
- Network-disconnect error boundary tests

### v6 — L3 Self-Generated MCP Tools 🧠
- create_tool meta-tool: LLM writes its own prompt-template tools
- DynamicTool: prompt-template execution (no recursion)
- Generated tools persisted to disk, restored on startup

### v7 — Timed Reminders ⏰
- set_reminder, cancel_reminder, list_reminders tools
- AlarmManager scheduling + TTS announcement
- Survives app restart (persisted reminder store)

### v8 — Media Search + Playback 🎵
- search_media: cn.bing.com scrape → result list
- play_media: ACTION_VIEW Intent → open in browser/app
- Novel reading via web_fetch → TTS

### v9 — Skill System 🧩
- Multi-step workflows with TTS progress reporting (`Flow<SkillStep>`)
- `.skill.md` file format for user-installable skills
- `SkillToolAdapter`: Skill → Tool transparent wrapping (LLM sees skills as tools)
- Built-in `MorningRoutineSkill` (weather + news chain)
- Skill auto-test (`skill_system`)

### v10 — Context Memory 🧠
- `MemoryManager`: centralized store with dedup, access tracking
- Auto-inject top 5 memories into system prompt each turn
- LLM auto-remembers personal facts without explicit "remember X" command
- Memory dedup: duplicates refresh timestamp instead of duplicating

### v11 — Agent Swarm 🐝
- Parallel tool execution: when LLM emits multiple tool_calls, run concurrently
- `swarm_query` tool: explicit parallel LLM queries for comparisons
- System prompt teaches LLM to use swarm for multi-item lookups

### v12 — Personality 🎭
- Configurable assistant name, tone (friendly/professional/funny/concise), catchphrase
- `set_personality` tool: natural language persona changes
- Personality injected at top of system prompt each turn
- Friendly tone default with natural colloquial Chinese

---

## For Discussion 📋

### Option A: Multi-modal — Camera + Vision 📷
Take photo → LLM vision API description → TTS read aloud.
**Difficulty**: Medium-High. Needs camera permission, image capture, vision-capable LLM endpoint.

### Option B: Screen Understanding 📱
Screenshot → OCR → context-aware assistance.
**Difficulty**: Medium. Needs MediaProjection + OCR.

### Option C: Multi-device Sync 🔗
Phone ↔ PC ↔ Server agent synchronization.
**Difficulty**: High. Needs network infrastructure + protocol.

### Option D: User-Proposed Features
(TBD — open for discussion)
