# External Tools — Implementation Plan (Feature 2 of 5)

> **Goal:** Add web_search, web_fetch, and get_weather tools callable via natural language.

**Architecture:** Each tool implements the `Tool` interface. OkHttp for HTTP (already a dependency). No new Gradle deps.

**Tech Stack:** Kotlin, OkHttp, Gson. APIs: DuckDuckGo Instant Answer, wttr.in, raw URL fetch.

**Duration:** ~8 tasks, ~1.5 hours.

---

## File Plan

| File | Action | Purpose |
|------|--------|---------|
| `tools/ExternalTools.kt` | **Create** | WebSearchTool, WebFetchTool, WeatherTool |
| `VoiceService.kt` | **Modify** | Register 3 new tools in initEngines() |
| `docs/plans/2026-06-04-external-tools.md` | **Create** | This plan |

---

## Tool Design

### 1. WebSearchTool

**API:** DuckDuckGo Instant Answer (`https://api.duckdungo.com/?q=QUERY&format=json&no_html=1`)
**Input:** `query` (string) — search term
**Output:** Abstract text + 3 related topic URLs/titles
**No API key required**

### 2. WebFetchTool

**API:** Direct HTTP GET to any URL
**Input:** `url` (string)
**Output:** Page title + extracted text (strip HTML tags, truncate to 2000 chars)
**OkHttp only, no Jsoup**

### 3. WeatherTool

**API:** wttr.in (`https://wttr.in/CITY?format=j1`)
**Input:** `city` (string, optional, defaults to auto-detect)
**Output:** Temperature, condition, humidity, wind
**No API key required**

---

## Task Breakdown (implemented inline)

### Task 1: Create ExternalTools.kt with all 3 tools
### Task 2: Register in VoiceService.kt
### Task 3: Compile + commit
### Task 4: Deploy + test (when phone online)
### Task 5: Update docs
