# 应用操控 — 功能方案

> **给 Hermes：** 用此方案来实施和调试应用操控功能。  
> **给人看：** 猪头助手的应用操控功能设计文档。

**目标：** 让猪头助手能代替用户操控手机上的其他应用——打开、搜索、阅读、发布——全程免提语音控制。

**状态（2025-06-17）：** ✅ 已实现并部署。存在两个已知问题。

---

## 用户可以说什么

- "打开京东搜一下机械键盘100到200块的"
- "看看微信老同学群最近的消息，总结一下"
- "打开设置把蓝牙关了"
- "打开淘宝购物车看看里面有什么"

---

## 架构

```
用户语音 → ASR → LLM (deepseek-v4-pro) → ToolCallEngine
                                              ├── launch_app     → PackageManager.getLaunchIntentForPackage()
                                              ├── tap_screen     → AccessibilityService.dispatchGesture()
                                              ├── type_text      → AccessibilityNodeInfo.ACTION_SET_TEXT
                                              ├── swipe_screen   → AccessibilityService.dispatchGesture()
                                              ├── press_key      → AccessibilityService.performGlobalAction()
                                              ├── get_screen_elements → AccessibilityNodeInfo 树遍历
                                              ├── wait_for_element   → AccessibilityEvent 轮询
                                              └── capture_screen → AccessibilityService.takeScreenshot() → Vision VLM
```

### 关键设计决策

| 决策 | 理由 |
|---|---|
| 用 AccessibilityService，不用 ADB/uiautomator2 | 已集成；无外部依赖；纯本地运行 |
| 开启 `canPerformGestures` + `canRetrieveWindowContent` | 为点击/滑动和读取界面元素所需 |
| `launch_app` 用 PackageManager，不点图标 | 无论桌面在第几页都能打开；更快 |
| 平铺工具（一个动作一个工具），不做组合 | LLM 逐步决策工作流；更灵活 |

---

## 工具（7 个新增）

| # | 工具名 | 描述 | 参数 |
|---|---|---|---|
| 1 | `launch_app` | 通过包名或中文名打开应用 | `app`（必填） |
| 2 | `get_screen_elements` | 列出可点击/可编辑的元素及其坐标 | （无参数） |
| 3 | `tap_screen` | 通过坐标或文字匹配点击 | `x`, `y` 或 `text` |
| 4 | `type_text` | 在焦点输入框中输入文字 | `text`（必填） |
| 5 | `swipe_screen` | 按方向滑动或点对点滑动 | `direction` 或 `x1,y1,x2,y2` |
| 6 | `press_key` | 系统导航键 | `key`（back/home/recents） |
| 7 | `wait_for_element` | 等待指定文字出现 | `text`, `timeout` |

### 常用应用包名

内置在 `LaunchAppTool.COMMON_APPS` 中：京东、淘宝、微信、支付宝、抖音、美团、饿了么、百度、高德地图、拼多多、QQ、小红书、微博、网易云音乐、计算器、相机、设置、时钟、日历。

---

## 文件清单

| 文件 | 作用 |
|---|---|
| `tools/GestureManager.kt` | 桥接层：工具 → AccessibilityService（点击、滑动、输入、读取、等待） |
| `tools/AppControlTools.kt` | `LaunchAppTool`、`PressKeyTool` |
| `tools/ScreenInteractionTools.kt` | `ScreenTapTool`、`TypeTextTool`、`SwipeScreenTool` |
| `tools/ScreenElementTools.kt` | `ScreenElementsTool`、`WaitForElementTool` |
| `tools/ScreenCaptureAccessibilityService.kt` | 扩展：手势 + 窗口内容回调 |
| `res/xml/accessibility_service_config.xml` | 标志位：`canPerformGestures`、`canRetrieveWindowContent` |
| `llm/ToolCallEngine.kt` | 系统提示词：关键词路由、应用操控流程、敏感操作门禁 |

---

## 敏感操作门禁

系统提示词规则：

> 支付、转账、下单、确认购买、提交订单 → 必须先告知用户金额和商品信息，等用户明确说「确认」后才能继续。

| 安全（自动执行） | 必须确认 |
|---|---|
| 打开应用、搜索、阅读、滚动、查看详情 | 支付、转账、提交订单、确认购买 |

---

## 已知问题

### 问题 1：LLM 忽略 `launch_app`，试图在桌面点图标

**症状：** 用户说「打开拼多多」→ LLM 调用 `get_screen_elements` + `tap_screen` 在桌面找应用图标。如果图标不在当前页 → 报告「应用未安装」。

**已尝试的修复（未生效）：**
- 系统提示词关键词路由：「打开XX → 立即调用 launch_app」
- 强调：「绝对不要用 tap_screen 去点图标」

**可能根因：** DeepSeek v4-pro 可能优先走视觉/点击路径而非工具调用。工具描述写的是「打开指定的应用」，但 LLM 可能把「打开」理解为视觉操作。

**待尝试的修复：**
- 把工具名改成偏机器风格的（如 `system_launch_app`）
- 增加组合技能，把「打开应用+做XX」做成一个整体流程
- 在应用层预处理触发词（LLM 之前）：检测「打开XX」→ 直接调 `launch_app`，结果再交给 LLM

### 问题 2：复杂流程超过 10 轮限制

**症状：** 「搜蛋糕并找最便宜的」→ LLM 耗尽轮次（maxTurns=10），返回「操作步骤有点多」。

**根因：** 搜索→滚动→查找→截屏→总结这类多步流程需要 8-12 轮，加上重试会更多。

**待尝试的修复：**
- 把 maxTurns 提到 15
- 增加高层组合工具 `app_search(app, query)`
- 优化提示词：让 LLM 在第 7-8 轮就开始总结，不要再做更多操作

### 问题 3：APK 更新后需重新开启无障碍服务

**根因：** 无障碍服务 XML 标志位变化时，Android 要求重新开启。

**临时方案：** 用户在 设置 → 辅助功能 → 猪头助手 中手动关闭再打开。
