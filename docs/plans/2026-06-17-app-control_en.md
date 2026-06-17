# App Control (应用操控) — Feature Plan

> **For Hermes:** Use this plan to implement and debug the app control feature.  
> **For Humans:** Design doc for the app-control capability in 猪头助手.

**Goal:** Let 猪头助手 control other apps on the phone — open, search, read, post — hands-free via voice commands.

**Status (2025-06-17):** ✅ Implemented & deployed. Two known issues remain.

---

## What Users Can Say

- "打开京东搜一下机械键盘100到200块的"
- "看看微信老同学群最近的消息，总结一下"
- "打开设置把蓝牙关了"
- "打开淘宝购物车看看里面有什么"

---

## Architecture

```
User Voice → ASR → LLM (deepseek-v4-pro) → ToolCallEngine
                                              ├── launch_app     → PackageManager.getLaunchIntentForPackage()
                                              ├── tap_screen     → AccessibilityService.dispatchGesture()
                                              ├── type_text      → AccessibilityNodeInfo.ACTION_SET_TEXT
                                              ├── swipe_screen   → AccessibilityService.dispatchGesture()
                                              ├── press_key      → AccessibilityService.performGlobalAction()
                                              ├── get_screen_elements → AccessibilityNodeInfo tree walk
                                              ├── wait_for_element   → AccessibilityEvent polling
                                              └── capture_screen → AccessibilityService.takeScreenshot() → Vision VLM
```

### Key Design Decisions

| Decision | Rationale |
|---|---|
| AccessibilityService, not ADB/uiautomator2 | Already integrated; no external deps; works on-device |
| `canPerformGestures` + `canRetrieveWindowContent` flags | Required for tap/swipe and reading UI elements |
| `launch_app` via PackageManager, not icon tapping | Works regardless of launcher page; faster |
| Flat tools (one per action), not composite | LLM decides workflow step-by-step; more flexible |

---

## Tools (7 new)

| # | Tool | Description | Parameters |
|---|---|---|---|
| 1 | `launch_app` | Open app by Chinese name or package | `app` (required) |
| 2 | `get_screen_elements` | List clickable/editable elements with coordinates | (none) |
| 3 | `tap_screen` | Tap by coordinates or text match | `x`, `y`, or `text` |
| 4 | `type_text` | Enter text into focused field | `text` (required) |
| 5 | `swipe_screen` | Scroll in direction or point-to-point | `direction` or `x1,y1,x2,y2` |
| 6 | `press_key` | System navigation keys | `key` (back/home/recents) |
| 7 | `wait_for_element` | Wait until text appears | `text`, `timeout` |

### Common App Package Names

Built into `LaunchAppTool.COMMON_APPS`: 京东, 淘宝, 微信, 支付宝, 抖音, 美团, 饿了么, 百度, 高德地图, 拼多多, QQ, 小红书, 微博, 网易云音乐, 计算器, 相机, 设置, 时钟, 日历.

---

## Files

| File | Role |
|---|---|
| `tools/GestureManager.kt` | Bridge: tools → AccessibilityService (tap, swipe, type, read, wait) |
| `tools/AppControlTools.kt` | `LaunchAppTool`, `PressKeyTool` |
| `tools/ScreenInteractionTools.kt` | `ScreenTapTool`, `TypeTextTool`, `SwipeScreenTool` |
| `tools/ScreenElementTools.kt` | `ScreenElementsTool`, `WaitForElementTool` |
| `tools/ScreenCaptureAccessibilityService.kt` | Extended: gestures + window content callbacks |
| `res/xml/accessibility_service_config.xml` | Flags: `canPerformGestures`, `canRetrieveWindowContent` |
| `llm/ToolCallEngine.kt` | System prompt: keyword routing, app control workflow, sensitivity gate |

---

## Sensitivity Gate

System prompt rule:

> 支付、转账、下单、确认购买、提交订单 → must tell user amount + item, wait for explicit "确认"

| Safe (auto-execute) | Must Confirm |
|---|---|
| Open app, search, read, scroll, view details | Pay, transfer, submit order, confirm purchase |

---

## Known Issues

### Issue 1: LLM ignores `launch_app`, tries to tap launcher icons

**Symptom:** User says "打开拼多多" → LLM calls `get_screen_elements` + `tap_screen` to find the app icon on the launcher. If icon not on current page → reports "应用未安装".

**Attempted fixes (didn't work):**
- Keyword routing in system prompt: "打开XX → 立即调用 launch_app"
- Emphasis: "绝对不要用 tap_screen 去点图标"

**Possible root cause:** DeepSeek v4-pro may prioritize visual/tap approach over tool calling for app launch. The tool description says "打开指定的应用" but the LLM might interpret "打开" as a visual action.

**Potential fixes to try:**
- Rename tool to something more machine-like (e.g., `system_launch_app`)
- Add a composited skill that handles the full "open app + do X" flow
- Pre-process trigger words at the app level (before LLM): detect "打开XX" → directly call `launch_app` then pass result to LLM

### Issue 2: Complex flows exceed 10-turn limit

**Symptom:** "搜蛋糕并找最便宜的" → LLM runs out of turns (maxTurns=10) and says "操作步骤有点多".

**Root cause:** Multi-step flows like search → scroll → find → capture → summarize need 8-12 turns depending on retries.

**Potential fixes:**
- Increase maxTurns to 15
- Add a higher-level `app_search(app, query)` composite tool
- Better prompt: instruct LLM to summarize at turn 7-8 instead of doing more actions

### Issue 3: Accessibility Service needs re-enable after APK update

**Root cause:** Android requires re-enabling when service XML flags change.

**Workaround:** User manually toggles in Settings → 辅助功能 → 猪头助手.
