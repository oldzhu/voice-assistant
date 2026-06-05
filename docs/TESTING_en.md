# Auto-Test Framework

Automated testing framework for the Voice Assistant Android app, designed for WSL + real device deployment.

## Architecture

```
┌─────────────────────────────────┐
│  Python test runner (host)      │
│  tests/runner.py                │
│  ├─ adb_utils.py   ADB helpers  │
│  └─ Build → Install → Launch →   │
│      Wait → Parse → Report      │
└──────────────┬──────────────────┘
               │ ADB (USB/WiFi)
┌──────────────▼──────────────────┐
│  Android phone (Realme)         │
│  ┌────────────────────────────┐ │
│  │  VoiceService              │ │
│  │  ├─ TestEngine (markers)   │ │
│  │  ├─ TestRunner (orchestr.) │ │
│  │  └─ debug.log ← results   │ │
│  └────────────────────────────┘ │
└─────────────────────────────────┘
```

## Test Types

### Core Tests

| Test | test_type | Description |
|------|-----------|-------------|
| Engine Init | `init` | ASR + TTS + LLM + ToolRegistry all start without crash |
| TTS Round-trip | `tts_roundtrip` | Text → TTS → speaker → mic → ASR → similarity |
| LLM Connectivity | `llm_connectivity` | Send known prompt, verify non-empty response + latency |
| LLM Tools | `llm_tools` | Tool-triggering prompt, verify tool calling pipeline |
| Full E2E | `e2e_full_pipeline` | Full UX: TTS→ASR→LLM→TTS→ASR, verify 3 phases non-empty |

### Direct Tool Tests (v2.0)

Invoke tools directly via `toolRegistry.execute()` — no LLM involved.

| Test | test_type | What it tests | Depends on |
|------|-----------|---------------|------------|
| Location | `tool_location` | get_location returns city/lat/lon JSON | GPS permission (skips gracefully if denied) |
| News | `tool_news` | get_news returns non-empty headlines | Sina API network |
| Web Fetch | `tool_web_fetch` | Fetch example.com without OOM/crash | Network |
| Config | `tool_config` | update_config returns success message | None |
| Memory | `tool_memory` | remember → what_do_you_know round-trip | None |
| Barge-in | `tool_barge_in` | Switch off/on/keyword modes, all succeed | None |
| Clear History | `tool_clear_history` | clear_history returns "cleared" | None |

### LLM-Mediated Test (v2.0)

| Test | test_type | What it tests |
|------|-----------|---------------|
| Multi Tool Call | `llm_multi_tool` | Prompt triggers ≥2 tool calls, validates no API 400 |

### All

| Test | test_type | Description |
|------|-----------|-------------|
| All | `all` | Runs all 13 tests in sequence |

## Quick Start

```bash
# Run all tests
python3 tests/runner.py

# Run TTS round-trip only
python3 tests/runner.py --test tts_roundtrip

# Run all direct tool tests (fast, no LLM)
python3 tests/runner.py --test tool_config
python3 tests/runner.py --test tool_memory
# ... etc

# Build + install only (no tests)
python3 tests/runner.py --install-only

# Skip build (test on installed APK)
python3 tests/runner.py --no-build

# Save structured report
python3 tests/runner.py --report report.json
```

## Run Single Test (Manual ADB)

```bash
ADB=/mnt/c/temp-adb/platform-tools/adb.exe
DEV=192.168.31.79:5555

# Force-stop old process
$ADB -s $DEV shell am force-stop com.example.voiceassistant

# Clear logs
$ADB -s $DEV logcat -c
$ADB -s $DEV shell run-as com.example.voiceassistant rm -f files/debug.log

# Launch test via test_type extra
$ADB -s $DEV shell am start -n com.example.voiceassistant/.MainActivity --es test_type tts_roundtrip

# Wait for init (~12s)
sleep 12

# View test results
$ADB -s $DEV shell run-as com.example.voiceassistant cat files/debug.log | grep '\[TEST:'
```

## Marker Format

Structured markers in debug.log:

```
[TEST:START:suite/name] key1=val1 key2=val2
[TEST:RESULT:suite/name] metric_name=value
[TEST:LOG:suite/name] arbitrary log message
[TEST:END:suite/name] duration_ms=1234 passed=true
```

## Test Details

### init — Engine Initialization

Verifies ASR, TTS (system or Sherpa), LLM backend, and ToolRegistry are all non-null.

### tts_roundtrip — TTS Round-Trip

Speaks known text via TTS, captures via ASR, computes Chinese-character Levenshtein similarity. Retries up to 3 times if similarity < 60%.

**Acoustic test mode**: During testing, ASR switches to `VOICE_RECOGNITION` + `MODE_NORMAL` (AEC off) so the mic can capture speaker output. Restores `VOICE_COMMUNICATION` + AEC after tests.

### llm_connectivity — LLM Connectivity

Sends "你好，请回复'测试成功'两个字" to LLM. Verifies non-empty response within 15s. Flags responses containing error keywords (出错/失败/超时).

### llm_tools — LLM Tool Calling

Sends "把语速调到1.2倍" to ToolCallEngine. Verifies the LLM emits a function_call, the tool executes, and a natural-language response is returned.

### e2e_full_pipeline — Full E2E

Three-phase test: (1) TTS + ASR capture of "你好猪头", (2) LLM processing, (3) TTS + ASR capture of LLM response. All three phases must produce non-empty output.

### tool_location — Location Tool

Calls `get_location` directly. Parses JSON for city/lat/lon. If permission is denied or location is disabled, the test passes with a note — not a code bug.

### tool_news — News Tool

Calls `get_news` directly. Verifies output contains "📰" or "1. " or "新闻". If API returns "暂无新闻" (rate-limited), test passes with a note.

### tool_web_fetch — Web Fetch Safety

Calls `web_fetch` on `https://example.com`. Primary validation: the app does not OOM or crash. Secondary: response is non-empty and not an error message.

### tool_config — Config Tool

Calls `update_config` with a unique test key. Verifies the tool returns a success message containing "已更新"/"已保存"/"已设置".

### tool_memory — Memory Tool

Stores a unique fact via `remember`, then retrieves it via `what_do_you_know`. Verifies the recall output contains the stored keyword.

### tool_barge_in — Barge-in Mode

Calls `set_barge_in_mode` with all three modes (off/on/keyword). Verifies each call returns a success message.

### tool_clear_history — Clear History

Calls `clear_history`. Verifies the tool returns "已清空" or equivalent.

### llm_multi_tool — Multi Tool Call

Sends "把语速调到1.3，然后清空对话记录" to ToolCallEngine. The prompt should trigger both `set_speech_rate` and `clear_history` in a single LLM response. This validates the multi-tool-call fix: every `tool_call_id` must have a matching tool response, or DeepSeek returns HTTP 400.

If the response contains "400" or "tool_call_id", the fix may be incomplete.

## Interpreting Results

### TTS Round-Trip Similarity

- **≥60%**: TTS is correctly recognized by ASR (PASS)
- **20–59%**: Partial recognition — audio has content but noisy
- **<20%**: Audio is mostly noise/unrecognizable (text processing may be broken)

## Adding a New Test

### 1. Add test method in TestRunner.kt

```kotlin
suspend fun testMyNewFeature(): Boolean {
    TestEngine.start("my_suite", "my_test", mapOf("param" to "value"))
    // ... test logic ...
    TestEngine.pass()  // or TestEngine.fail("reason")
    return true
}
```

### 2. Register in run()

```kotlin
suspend fun run(testType: String, ...): Boolean = when (testType) {
    "my_test" -> testMyNewFeature()
    // ...
}
```

### 3. Run via ADB

```bash
adb shell am start -n com.example.voiceassistant/.MainActivity --es test_type my_test
```

## Troubleshooting

### Device not connected
```bash
$ADB devices
# Reconnect: USB → Windows adb tcpip 5555 → WSL adb connect IP:5555
```

### Install failure
```bash
$ADB shell pm list packages | grep voiceassistant
$ADB shell pm uninstall com.example.voiceassistant
```

### No test output
```bash
$ADB shell run-as com.example.voiceassistant cat files/debug.log | grep "TEST MODE"
$ADB shell run-as com.example.voiceassistant cat files/debug.log | tail -50
```

### Crash
```bash
$ADB logcat -d -b crash | tail -30
```
