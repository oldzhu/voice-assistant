# 自动测试框架 (Auto-Test Framework)

猪头助手（Voice Assistant）的自动化测试框架，适用于 WSL + Android 真机部署流程。

## 架构

```
┌─────────────────────────────────┐
│  Python 测试运行器 (host)        │
│  tests/runner.py                │
│  ├─ adb_utils.py   ADB 工具封装  │
│  └─ 构建 → 安装 → 启动 → 等待 →   │
│      解析结果 → 生成报告          │
└──────────────┬──────────────────┘
               │ ADB (USB/WiFi)
┌──────────────▼──────────────────┐
│  Android 手机 (Realme)          │
│  ┌────────────────────────────┐ │
│  │  VoiceService              │ │
│  │  ├─ TestEngine (标记输出)   │ │
│  │  ├─ TestRunner (测试调度)   │ │
│  │  └─ debug.log ← 测试结果   │ │
│  └────────────────────────────┘ │
└─────────────────────────────────┘
```

## 测试类型

### 基础测试

| 测试 | test_type | 描述 |
|------|-----------|------|
| 引擎初始化 | `init` | ASR + TTS + LLM + 工具注册表全部启动无崩溃 |
| TTS 往返 | `tts_roundtrip` | 文本 → TTS 播放 → 麦克风拾音 → ASR 识别 → 文本相似度对比 |
| LLM 连通性 | `llm_connectivity` | 向 LLM 发送已知 prompt，验证非空响应 + 延迟 |
| LLM 工具调用 | `llm_tools` | 发送工具触发 prompt（调语速），验证 tool calling 管道完整 |
| 全链路 E2E | `e2e_full_pipeline` | 完整用户体验：TTS→ASR→LLM→TTS→ASR，验证三阶段均非空 |

### 直接工具测试（v2.0 新增）

不经过 LLM，直接调 `toolRegistry.execute()` 验证工具逻辑。

| 测试 | test_type | 测什么 | 依赖 |
|------|-----------|--------|------|
| 位置 | `tool_location` | 调用 get_location，验证返回 city/lat/lon JSON | GPS 权限（无权限时跳过，不计失败） |
| 新闻 | `tool_news` | 调用 get_news，验证返回非空头条列表 | 新浪 API 网络 |
| 网页抓取 | `tool_web_fetch` | 抓 example.com，验证不 OOM/不崩溃 | 网络 |
| 配置 | `tool_config` | 调用 update_config → 验证返回"已更新" | 无 |
| 记忆 | `tool_memory` | remember → what_do_you_know 往返，验证召回包含存储的内容 | 无 |
| 打断模式 | `tool_barge_in` | 依次切换 off/on/keyword 三种模式，验证均成功 | 无 |
| 清空历史 | `tool_clear_history` | 调用 clear_history，验证返回"已清空" | 无 |

### LLM 介导测试（v2.0 新增）

| 测试 | test_type | 测什么 |
|------|-----------|--------|
| 多工具调用 | `llm_multi_tool` | prompt"把语速调到1.3，然后清空对话记录"，验证 LLM 同时调用 ≥2 个 tool 不报 400 |

### 全部

| 测试 | test_type | 描述 |
|------|-----------|------|
| 全部 | `all` | 依次运行以上 13 个测试 |

## 快速开始

```bash
# 运行全部测试
python3 tests/runner.py

# 只运行 TTS 往返测试
python3 tests/runner.py --test tts_roundtrip

# 运行所有直接工具测试（无 LLM，速度快）
python3 tests/runner.py --test tool_config
python3 tests/runner.py --test tool_memory
# ... 等等

# 只构建 + 安装（不测试）
python3 tests/runner.py --install-only

# 跳过构建（在已安装的 APK 上测试）
python3 tests/runner.py --no-build

# 保存结构化报告
python3 tests/runner.py --report report.json
```

## 运行单个测试（手动 ADB）

```bash
ADB=/mnt/c/temp-adb/platform-tools/adb.exe
DEV=192.168.31.79:5555

# 停止旧进程
$ADB -s $DEV shell am force-stop com.example.voiceassistant

# 清除日志
$ADB -s $DEV logcat -c
$ADB -s $DEV shell run-as com.example.voiceassistant rm -f files/debug.log

# 启动测试（使用 test_type extra）
$ADB -s $DEV shell am start -n com.example.voiceassistant/.MainActivity --es test_type tts_roundtrip

# 等待初始化（~12秒）
sleep 12

# 查看测试结果
$ADB -s $DEV shell run-as com.example.voiceassistant cat files/debug.log | grep '\[TEST:'
```

## 测试标记格式

debug.log 中的结构化标记：

```
[TEST:START:suite/name] key1=val1 key2=val2
[TEST:RESULT:suite/name] metric_name=value
[TEST:LOG:suite/name] 任意日志消息
[TEST:END:suite/name] duration_ms=1234 passed=true
```

## 各测试详细说明

### init — 引擎初始化

```
验证：
- ASR 引擎非空
- TTS 引擎（系统或 Sherpa）至少一个非空
- LLM 后端非空
- 工具注册表非空
```

### tts_roundtrip — TTS 往返

```
序列：
1. startListening()  → ASR 开始监听
2. 延迟 100ms        → 音频管道稳定
3. speakForTest()    → TTS 播放（无回调，ASR 持续监听）
4. 等待 ASR 端点检测  → 最长 15 秒
5. stop()            → 停止 ASR
6. textSimilarity()  → 中文字符编辑距离 → 相似度 %
7. 如果 < 60%        → 重试（最多 3 次）
```

**限制**：依赖声学路径（扬声器→房间→麦克风），质量受音量、房间声学、AEC 影响。

**测试模式音频切换**：测试期间 ASR 自动切换到 `VOICE_RECOGNITION` + `MODE_NORMAL`（关闭 AEC），
使麦克风能捕获扬声器输出。测试结束后自动恢复 `VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION` + AEC。

### llm_connectivity — LLM 连通性

```
序列：
1. 向 LLM 发送 "你好，请回复'测试成功'两个字"
2. 等待响应（15 秒超时）
3. 验证：非空、非 error、延迟 < 15s
4. 输出：延迟(ms)、响应长度、响应内容摘要
```

### llm_tools — LLM 工具调用

```
序列：
1. 向 ToolCallEngine 发送 "把语速调到1.2倍"
2. LLM 识别意图 → 发出 function_call(set_speech_rate)
3. 工具执行 → ConfigManager.speechRate 更新
4. LLM 接收工具输出 → 生成自然语言回复
5. 验证：响应非空、可能包含 "1.2"/"语速"/"已"
```

### e2e_full_pipeline — 全链路 E2E

```
Phase 1: TTS "你好猪头" → ASR 拾音 → 转录
Phase 2: 转录文本 → LLM → 生成回复
Phase 3: TTS 播放回复 → ASR 拾音 → 转录
验证：三阶段输出均非空
```

### tool_location — 位置工具

```
序列：
1. 调用 toolRegistry.execute("get_location", emptyMap())
2. 解析 JSON，提取 city/lat/lon
3. 权限检查：如果返回"权限"/"不可用"/"disabled" → 跳过（不算失败）
4. 验证：city 非空、lat/lon 有效
```

### tool_news — 新闻工具

```
序列：
1. 调用 toolRegistry.execute("get_news", emptyMap())
2. 验证返回包含 "📰" 或 "1. " 或 "新闻"
3. 如果返回"暂无新闻"（API 限流）→ 跳过（不算失败）
```

### tool_web_fetch — 网页抓取

```
序列：
1. 调用 toolRegistry.execute("web_fetch", mapOf("url" to "https://example.com"))
2. 验证非空、不包含"获取网页失败"
3. 测试成功的主要指标：没有 OOM 崩溃
```

### tool_config — 配置工具

```
序列：
1. 调用 update_config(key="_auto_test_config", value=唯一值)
2. 验证返回包含 "已更新"/"已保存"/"已设置"
```

### tool_memory — 记忆工具

```
序列：
1. 调用 remember(fact=唯一值, category="_auto_test")
2. 调用 what_do_you_know(query=唯一关键词)
3. 验证召回内容包含关键词
```

### tool_barge_in — 打断模式

```
序列：
1. 依次调用 set_barge_in_mode("off"/"on"/"keyword")
2. 验证每次返回包含 "已切换"/"切换"/对应 mode 名
```

### tool_clear_history — 清空历史

```
序列：
1. 调用 clear_history()
2. 验证返回包含 "已清空"/"清空"/"cleared"
```

### llm_multi_tool — 多工具调用

```
序列：
1. 向 ToolCallEngine 发送 "把语速调到1.3，然后清空对话记录"
2. LLM 应同时发出 set_speech_rate + clear_history
3. 验证响应非空（无 API 400 错误）
4. 如果返回包含 "400" 或 "tool_call_id" → 多 tool_call 修复可能不完整
```

## 测试结果解读

### TTS 往返相似度

- **≥60%**：TTS 可被 ASR 正确识别（通过）
- **20–59%**：部分识别 — 音频有内容但杂音较多
- **<20%**：音频基本是噪音/无法识别（文本处理可能损坏）

## 添加新测试

### 1. 在 TestRunner.kt 中添加测试方法

```kotlin
// app/src/main/java/com/example/voiceassistant/test/TestRunner.kt
suspend fun testMyNewFeature(): Boolean {
    TestEngine.start("my_suite", "my_test", mapOf("param" to "value"))
    // ... 测试逻辑 ...
    TestEngine.pass()  // 或 TestEngine.fail("原因")
    return true
}
```

### 2. 在 run() 中注册

```kotlin
suspend fun run(testType: String, ...): Boolean = when (testType) {
    "my_test" -> testMyNewFeature()
    // ...
}
```

### 3. 通过 ADB 运行

```bash
adb shell am start -n com.example.voiceassistant/.MainActivity --es test_type my_test
```

## 故障排查

### 设备未连接
```bash
# 检查设备
$ADB devices

# 重新连接（WSL 下无线配对不可用，需 USB→TCPIP 桥接）
# 1. 手机插 USB → Windows 端 adb tcpip 5555
# 2. WSL 端 adb connect 192.168.31.79:5555
```

### 安装失败
```bash
# 检查已安装的 APK
$ADB shell pm list packages | grep voiceassistant

# 卸载后重新安装
$ADB shell pm uninstall com.example.voiceassistant
```

### 测试无输出
```bash
# 确认测试已启动
$ADB shell run-as com.example.voiceassistant cat files/debug.log | grep "TEST MODE"

# 查看完整日志
$ADB shell run-as com.example.voiceassistant cat files/debug.log | tail -50
```

### 崩溃
```bash
# 检查崩溃日志
$ADB logcat -d -b crash | tail -30
```
