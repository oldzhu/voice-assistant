# Self-Improvement Architecture

猪头助手的自我进化路线图。目标：让助理能通过对话自我配置、学习用户偏好、以及（远期）自主扩展工具。

---

## 能力层级

```
L1: 静态工具     [已实现]  set_speech_rate, get_weather, get_news 等 (13 tools)
L2: 自我配置     [已实现]  update_config — 运行时修改自身行为
L3: 自生成工具   [规划中]  LLM 写脚本 → stdio MCP → 自动注册
L4: 记忆学习     [已实现]  remember / what_do_you_know — 跨会话持久化
L5: 运行时改代码 [不可行]  需 kotlinc + DEX 编译，被签名校验阻止
L6: 自我修改APK  [不可行]  Android 安全模型禁止
```

---

## L2: 自我配置 (update_config) ✅

**场景：**
```
用户："以后回答简短一点"
  → LLM: update_config(key="response_style", value="concise")
  → 下次对话：回复自动变短

用户："记住我住在深圳"
  → LLM 同时发出: update_config(user_city=深圳) + remember(住在深圳)
  → 两个 tool_call 都被执行，无孤儿 tool_call_id
```

**实现文件：**
| 文件 | 改动 |
|------|------|
| `config/ConfigManager.kt` | `setUserPreference(key, value)` / `getUserPreference(key)` / `getAllUserPreferences()` |
| `tools/SelfImprovementTools.kt` | `UpdateConfigTool` — 通用键值存储，LLM 可调用 |

**已支持的配置键：**

| key | 行为影响 |
|-----|----------|
| `response_style` | 回复风格（concise/detailed/friendly） |
| `default_news_category` | get_news 默认类别 |
| `user_city` | get_weather 默认城市 |
| `user_name` | 对话中的称呼 |

---

## L4: 记忆学习 (remember / what_do_you_know) ✅

**场景：**
```
用户："我喜欢看科技新闻"
  → LLM: remember(fact="用户偏好科技新闻", category="preference")

用户："还记得我喜欢什么吗？"
  → LLM: what_do_you_know(query="喜欢")
  → 返回匹配的记忆

用户："你都知道我什么？"
  → LLM: what_do_you_know()
  → 列出所有记忆
```

**实现文件：**
| 文件 | 改动 |
|------|------|
| `tools/SelfImprovementTools.kt` | `RememberTool` + `RecallTool` |
| 存储 | `files/assistant_memory.json`（每行一个 JSON） |

**记忆格式：** `{"fact": "...", "category": "personal|preference|context", "timestamp": 1234567890}`

**连锁效果：**
```
对话1: "记住我住在深圳" + "默认看科技新闻"
  → update_config(user_city=深圳)
  → remember(fact="用户住在深圳")
  → update_config(default_news_category=tech)
  → remember(fact="用户偏好科技新闻")

对话2: "今天天气怎么样"
  → get_location → 取到坐标 → 逆地理编码
  → 读取 user_city=深圳 → 直接用深圳查天气 ✓

对话2: "有新闻吗"
  → 读取 default_news_category=tech → get_news(category="tech") ✓
```

---

## L3: 自生成 MCP 工具（规划中）

**场景：**
```
用户："我需要汇率换算功能"
  → LLM 生成 Python 脚本 → 保存到 files/mcp_tools/
  → 以 stdio MCP 启动 → 自动注册到 ToolRegistry
  → 下次用户："100美元多少人民币" → 直接调用
```

**当前基础设施（已支持手动配置）：**
- `StdioMcpTransport` + `HttpMcpTransport` — MCP 协议传输层
- `McpClient` — MCP 客户端，自动发现工具
- `VoiceService.connectMcpServers()` — 启动时连接配置的 MCP 服务器

**自生成需解决的问题：**
- MCP 双向 JSON-RPC 在 Android 上的稳定性
- Python 运行时（Termux 或 SL4A）
- 生成代码的沙箱安全

---

## 关键技术修复：多 tool_call 并发处理

**问题：** DeepSeek 收到 "记住我住在深圳" 后一次性发出多个 tool_call：

```json
{
  "tool_calls": [
    {"function": {"name": "update_config", "arguments": "{\"key\":\"user_city\",\"value\":\"深圳\"}"}},
    {"function": {"name": "remember", "arguments": "{\"fact\":\"用户住在深圳\",\"category\":\"preference\"}"}}
  ]
}
```

旧代码只处理第一个 → 第二个 `tool_call_id` 成为孤儿 → API 400 拒绝。

**修复文件：**

| 文件 | 改动 |
|------|------|
| `CloudLLMBackend.kt` | `ToolChatResult` 新增 `functionCalls: List<...>`，`chatWithTools()` 提取全部 tool_calls |
| `ToolCallEngine.kt` | 遍历执行所有 function calls，为每个 tool_call_id 添加对应的 tool 响应 |

---

## Android 自我修改的硬限制

| 操作 | 可行性 | 原因 |
|------|--------|------|
| 修改 SharedPreferences | ✅ | 应用沙箱内允许 |
| 写入内部存储文件 | ✅ | filesDir 可读写 |
| 动态加载 DEX/JAR | ⚠️ | Android 10+ 限制 |
| 修改已安装 APK | ❌ | 签名校验 |
| 运行时生成 Activity/Service | ❌ | 需 Manifest 声明 |
| 调用系统 API 修改自身 | ❌ | SELinux + 权限模型 |

**结论：** 助理可以改变自己的**数据**和**行为参数**（L2+L4），但不能改变自己的**代码结构**（L5+L6）。工具扩展靠 MCP 协议（L3）而非动态代码生成。
