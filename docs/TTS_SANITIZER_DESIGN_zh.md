# TTS 文本清洗设计 — 猪头助手

## 问题

当前语音流水线：

```
ASR → 文本 → LLM → 原始回复文本 → TTS 直接朗读
```

LLM 天然会使用 Markdown 格式（`**加粗**`、`*斜体*`、`[链接](url)`、代码块、列表符号等）来提高文字的可读性。但在纯语音场景下，TTS 引擎会逐字朗读这些格式符号，导致语音输出非常不自然：

- `**你好**` 被读成 "星号星号你好星号星号"
- `[点击这里](https://...)` 被完整读出 URL
- 无序列表 `- 第一点` 被读出横杠符号

## 备选方案分析

| 方案 | 延迟 | 成本 | 可靠性 | 自然度 | 是否采用 |
|------|------|------|--------|--------|----------|
| A. Prompt 工程：让 LLM 纯文本输出 | 0ms | $0 | ⭐⭐⭐（偶尔违规） | ⭐⭐⭐ | ✅ |
| B. 后处理：正则清洗 Markdown | 0ms | $0 | ⭐⭐⭐⭐⭐（100% 可靠） | ⭐⭐⭐ | ✅ |
| C. 第二遍 LLM：「改写为口语」 | +3s | $$ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ❌ 太慢太贵 |
| D. 多模态音频模型（GPT-4o audio 等） | +500ms | $$ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ❌ 无本地中文方案 |

**决策：A + B 双层防护。**

- 第一层（Prompt）：告诉 LLM 输出纯文本口语。覆盖 90% 的 case。
- 第二层（后处理）：无论 LLM 输出什么，TTS 之前都过一遍正则洗掉残留的 Markdown。作为确定性安全网，100% 兜底。

C/D 暂不做，未来如果发现两层防护后语音仍不够自然（如 LLM 输出列表结构、长文段落），再考虑 C。

## 实现

### 1. 系统提示词修改（`ToolCallEngine.buildSystemPrompt()`）

新增一行：

```
你的回复会被语音朗读（TTS），禁止使用任何Markdown格式
（**加粗**、*斜体*、`代码`、[链接](url)、列表符号等），
只用纯文本自然口语表达。
```

### 2. 正则清洗函数（新文件：`speech/TtsTextSanitizer.kt`）

```kotlin
object TtsTextSanitizer {
    fun sanitize(text: String): String
}
```

处理规则（按顺序）：

1. 链接：`[text](url)` → `text`
2. 加粗：`**text**` / `__text__` → `text`
3. 斜体：`*text*` / `_text_` → `text`
4. 行内代码：`` `code` `` → `code`
5. 删除线：`~~text~~` → `text`
6. 标题：`### Heading` → `Heading`
7. 无序列表符号：`- item` / `* item` / `+ item` → `item`
8. 有序列表序号：`1. item` → `item`
9. 代码块标记：``` ``` ``` 行删除

### 3. 调用点（`VoiceService.processQuery()`）

```kotlin
// Before:
speakTts(response)

// After:
speakTts(TtsTextSanitizer.sanitize(response))
```

保存到日志/历史记录仍使用原始 `response`，只对 TTS 输入进行清洗。

## 测试要点

- 纯 Markdown 输入（全是 `**text**` 等格式）
- 混合输入（部分口语 + 部分格式）
- 干净文本输入（无任何格式，确认不变形）
- 边界情况：空字符串、纯符号、换行符
