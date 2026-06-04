# DORMANT 休眠状态设计

> 日期：2026-06-04 | 版本：v1.3.1

## 问题

用户说 "别听了" 后，原实现调用 `stop_listening` 直接停掉 ASR，导致：
1. ASR 完全停止后无法通过语音唤醒（必须手动点按钮）
2. stop_listening → LLM 回复 "已停止" → TTS 把状态从 DORMANT 覆盖成 SPEAKING

## 设计

### 状态机新增 DORMANT 状态

```
LISTENING ←→ DORMANT
    ↑          ↓ (唤醒词)
    └──────────┘
```

- **进入 DORMANT**: `stop_listening` 工具执行 → `updateState(State.DORMANT)`
- **DORMANT 行为**: ASR 持续运行，`onResult` 回调中过滤输入：
  - 匹配唤醒词（"开始听"/"开始监听"/"继续"/"回来"/"猪头回来"/"猪头"）→ `startListening()` 恢复
  - 其他输入 → 静默丢弃（不触发 LLM、不播报）
- **退出 DORMANT**: `startManaging` 工具或唤醒词命中 → `startListening()` → `State.LISTENING`

### TTS 抑制逻辑

`processQuery()` 在收到 LLM 回复后检查状态：

```kotlin
// VoiceService.kt processQuery()
if (state == State.DORMANT) {
    debugLog("In DORMANT after tool call, suppressing TTS response")
    return  // 不播报、不更新 state
}
```

流程：
```
"别听了" → LLM 调用 stop_listening() → state=DORMANT
           → LLM 回复 "好的，我休息了" → processQuery 检测到 DORMANT → 吞掉回复 ✅
           → 保持 DORMANT，不发出任何声音
```

### 唤醒词列表

```kotlin
private val WAKE_PHRASES = listOf("开始听", "开始监听", "继续", "回来", "猪头回来", "猪头")
```

使用 `text.contains()` 模糊匹配（非精确匹配），提高唤醒成功率。

### UI 状态显示

```
State.DORMANT → "💤 休眠中"
```

## 涉及文件

| 文件 | 修改 |
|------|------|
| `VoiceService.kt` | State 枚举 + DORMANT，onResult 过滤，processQuery TTS 抑制，stop_listening 行为 |
| `MainActivity.kt` | State.DORMANT → "💤 休眠中" 显示 |

## 已知限制

- 休眠期间 ASR 仍在后台消耗 CPU/电量（因为没有真正的 wake word 引擎，是软件层过滤）
- 唤醒词匹配是子串匹配，可能误触（如 "猪头" 在任何句子中出现都触发）
- 未来可考虑用 Sherpa-ONNX KeywordSpotter 做硬件级唤醒以省电
