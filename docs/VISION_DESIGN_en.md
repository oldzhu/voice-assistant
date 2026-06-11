# Visual Recognition System — Pig Head Assistant

> Architecture and configuration for the dual-mode visual recognition system (local OCR + cloud VLM).

## Overview

The vision system supports two backends, selectable at runtime:

| Mode | Backend | What it does | Privacy |
|------|---------|-------------|---------|
| **Local OCR** | Tesseract 4 (`tesseract4android`) | Extracts text from images; supports Chinese + English simultaneously | ✅ Image stays on device |
| **Remote VLM** | OpenAI-compatible vision API | Full image understanding (description, QA, analysis) | ⚠️ Image sent to cloud |

Default: **local OCR** (no API key needed, works offline).

## Architecture

```
┌──────────────┐     ┌───────────────────┐
│  Tool Layer  │────→│  VisionProvider   │  ← interface
│              │     │  (suspend fun      │
│ describe_photo│     │   describe())     │
│ capture_screen│     └──────┬────────────┘
└──────────────┘            │
                  ┌─────────┴─────────┐
                  │                   │
          ┌───────▼──────┐  ┌────────▼────────┐
          │ LocalVision  │  │ RemoteVision    │
          │ Provider     │  │ Provider        │
          │              │  │                 │
          │ Tesseract 4  │  │ OkHttp →        │
          │ chi_sim+eng  │  │ /v1/chat/       │
          │              │  │ completions     │
          └──────────────┘  └─────────────────┘
```

### Key Files

| File | Role |
|------|------|
| `llm/VisionProvider.kt` | Abstract interface: `suspend fun describe(prompt, imageBase64): Result<String>` |
| `llm/LocalVisionProvider.kt` | Tesseract OCR backend; `chi_sim+eng` language pack |
| `llm/RemoteVisionProvider.kt` | OpenAI-compatible VLM backend; uses vision-specific creds with LLM fallback |
| `config/ConfigManager.kt` | Stores `vision_provider`, `vision_model`, `vision_api_key`, `vision_base_url` |
| `tools/DescribeImageTool.kt` | Tool: `describe_photo` — captures camera photo → VisionProvider |
| `tools/ScreenCaptureTool.kt` | Tool: `capture_screen` — silent screenshot → VisionProvider |
| `tools/ScreenCaptureManager.kt` | MediaProjection bridge (fallback) |
| `services/AccessibilityService.kt` | Accessibility-based silent screenshot (preferred, zero popups) |

## Configuration

### Settings Page (⚙ button → scroll to 👁 Vision Settings)

| Setting | Key | Description |
|---------|-----|-------------|
| **识别方式** | `vision_provider` | `local` (OCR) / `remote` (VLM) / `auto` (tries local) |
| **Vision API Key** | `vision_api_key` | Cloud VLM key. Falls back to main LLM `api_key` when empty |
| **Vision URL** | `vision_base_url` | VLM endpoint. Falls back to `base_url` when empty |
| **Vision Model** | `vision_model` | VLM model name (e.g. `gpt-4o`, `qwen-vl-max`) |

### Via Voice Command

The LLM can modify these via the `update_config` tool:

```
User: "把视觉改成远程，用 qwen-vl-max 模型"
→ update_config(key="vision_provider", value="remote")
→ update_config(key="vision_model", value="qwen-vl-max")

User: "切回本地 OCR"
→ update_config(key="vision_provider", value="local")
```

### Fallback Logic

When `vision_provider = "remote"`:
- `vision_api_key` → if blank, uses `api_key` (main LLM key)
- `vision_base_url` → if blank, uses `base_url` (main LLM URL)
- `vision_model` → required; if blank, describeImage() returns guidance message

## Screen Capture

### Preferred: AccessibilityService (Android 11+)

- Uses `AccessibilityService.takeScreenshot()` — **zero permission popups**
- One-time enable: Settings → Accessibility → 猪头助手 → On
- Captures any app silently

### Fallback: MediaProjection

- Uses `MediaProjection` with 3-second delay
- Requires one-time authorization popup per session
- Used when AccessibilityService is not enabled

## Language Support (Local OCR)

`LocalVisionProvider` uses Tesseract 4 with dual-language mode:

| Language | Data File | Size | Source |
|----------|-----------|------|--------|
| Chinese (Simplified) | `chi_sim.traineddata` | 43 MB | tessdata_best |
| English | `eng.traineddata` | 4.0 MB | tessdata_fast |

Bundled in `app/src/main/assets/tessdata/`. Initialized once on first use (~1 second).

## Tool Routing

The system prompt prevents LLM misrouting:

```
When user says "这一页", "当前页面", "屏幕上", "截屏" → MUST call `capture_screen`
Never use `web_search` for on-screen content.
When user says "拍照识别", "看看这个" → MUST call `describe_photo`
```

## Dependencies

- `com.google.android.gms:play-services-vision` → `tesseract4android` (JitPack)
- `com.otaliastudios.opengl:transcoder` → `tesseract4android`
- No Google Play Services required (Chinese ROM compatible)
