# 视觉识别系统 — 猪头助手

> 双模视觉识别系统（本地 OCR + 云端 VLM）的架构与配置说明。

## 概述

视觉系统支持两种后端，运行时可切换：

| 模式 | 后端 | 功能 | 隐私 |
|------|------|------|------|
| **本地 OCR** | Tesseract 4 (`tesseract4android`) | 提取图片中的文字；同时支持中英文 | ✅ 图片不离开手机 |
| **云端 VLM** | OpenAI 兼容视觉 API | 完整图片理解（描述、问答、分析） | ⚠️ 图片上传至云端 |

默认：**本地 OCR**（无需 API Key，离线可用）。

## 架构

```
┌──────────────┐     ┌───────────────────┐
│  工具层       │────→│  VisionProvider   │  ← 抽象接口
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

### 关键文件

| 文件 | 职责 |
|------|------|
| `llm/VisionProvider.kt` | 抽象接口：`suspend fun describe(prompt, imageBase64): Result<String>` |
| `llm/LocalVisionProvider.kt` | Tesseract OCR 后端；`chi_sim+eng` 双语 |
| `llm/RemoteVisionProvider.kt` | OpenAI 兼容 VLM 后端；使用视觉专属凭证，可回退到 LLM 凭证 |
| `config/ConfigManager.kt` | 存储 `vision_provider`、`vision_model`、`vision_api_key`、`vision_base_url` |
| `tools/DescribeImageTool.kt` | 工具：`describe_photo` — 拍照 → VisionProvider |
| `tools/ScreenCaptureTool.kt` | 工具：`capture_screen` — 静默截屏 → VisionProvider |
| `tools/ScreenCaptureManager.kt` | MediaProjection 桥接（回退方案） |
| `services/AccessibilityService.kt` | 无障碍静默截屏（首选方案，零弹窗） |

## 配置

### 设置页面（⚙ 按钮 → 滚动到 👁 视觉设置）

| 设置项 | 键 | 说明 |
|--------|-----|------|
| **识别方式** | `vision_provider` | `local`（OCR）/ `remote`（VLM）/ `auto`（优先本地） |
| **Vision API Key** | `vision_api_key` | 云端 VLM 密钥。为空时回退到 LLM 的 `api_key` |
| **Vision URL** | `vision_base_url` | VLM 接口地址。为空时回退到 LLM 的 `base_url` |
| **Vision Model** | `vision_model` | VLM 模型名（如 `gpt-4o`、`qwen-vl-max`） |

### 通过语音指令

LLM 可通过 `update_config` 工具修改：

```
用户："把视觉改成远程，用 qwen-vl-max 模型"
→ update_config(key="vision_provider", value="remote")
→ update_config(key="vision_model", value="qwen-vl-max")

用户："切回本地 OCR"
→ update_config(key="vision_provider", value="local")
```

### 回退逻辑

当 `vision_provider = "remote"` 时：
- `vision_api_key` → 为空则用 `api_key`（主 LLM 密钥）
- `vision_base_url` → 为空则用 `base_url`（主 LLM 地址）
- `vision_model` → 必填；为空时 describeImage() 返回引导提示

## 截屏方案

### 首选：无障碍服务（Android 11+）

- 使用 `AccessibilityService.takeScreenshot()` — **零权限弹窗**
- 一次性开启：设置 → 无障碍 → 猪头助手 → 开启
- 可在任意应用内静默截屏

### 回退：MediaProjection

- 使用 `MediaProjection`，3 秒延迟
- 每次会话需一次授权弹窗
- 无障碍服务未开启时使用

## 语言支持（本地 OCR）

`LocalVisionProvider` 使用 Tesseract 4 双语模式：

| 语言 | 数据文件 | 大小 | 来源 |
|------|----------|------|------|
| 简体中文 | `chi_sim.traineddata` | 43 MB | tessdata_best |
| 英文 | `eng.traineddata` | 4.0 MB | tessdata_fast |

打包于 `app/src/main/assets/tessdata/`。首次使用时初始化（约 1 秒）。

## 工具路由

系统提示词防止 LLM 误判路由：

```
用户说"这一页"、"当前页面"、"屏幕上"、"截屏" → 必须调用 capture_screen
不要对屏幕内容使用 web_search。
用户说"拍照识别"、"看看这个" → 必须调用 describe_photo
```

## 依赖

- `com.google.android.gms:play-services-vision` → `tesseract4android`（JitPack）
- `com.otaliastudios.opengl:transcoder` → `tesseract4android`
- 无需 Google Play Services（中国 ROM 兼容）
