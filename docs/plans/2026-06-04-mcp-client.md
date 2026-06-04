# MCP Client 实现计划

> 日期：2026-06-04 | 版本：v1.4 | 对标：Hermes native MCP client

## 目标

让猪头助手能连接 MCP (Model Context Protocol) server，自动发现并注册其工具。

## 架构

```
VoiceService.onStartCommand()
  └── McpClient.connect(serverConfig)
        ├── StdioMcpTransport ── ProcessBuilder → ./mcp-server (本地进程)
        └── HttpMcpTransport  ── OkHttp → https://... (远程)
              │
              ▼ JSON-RPC 2.0
        initialize() → tools/list() → tools/call()
              │
              ▼
        McpToolAdapter ── wraps MCP tool as Tool interface
              │
              ▼
        ToolRegistry.register(mcpTool)
```

## 文件清单

| 文件 | 说明 |
|------|------|
| `llm/transport/McpTransport.kt` | McpTransport 接口 + JSON-RPC 类型定义 |
| `llm/transport/StdioMcpTransport.kt` | ProcessBuilder 实现的 stdio transport |
| `llm/transport/HttpMcpTransport.kt` | OkHttp 实现的 HTTP transport |
| `llm/McpClient.kt` | JSON-RPC 协议处理：initialize, listTools, callTool |
| `llm/McpToolAdapter.kt` | 将 MCP tool schema 适配为我们的 Tool 接口 |
| `VoiceService.kt` (修改) | 启动时连接 MCP servers，注册工具 |
| `config/ConfigManager.kt` (修改) | 持久化 MCP server 配置 |

## JSON-RPC 协议

```
// initialize
→ {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"pighead","version":"1.0"}}}
← {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{...},"capabilities":{"tools":{}}}}

// notified initialized
→ {"jsonrpc":"2.0","method":"notifications/initialized"}

// list tools
→ {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
← {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"...","description":"...","inputSchema":{...}}]}}

// call tool
→ {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"...","arguments":{...}}}
← {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"..."}]}}
```

## 配置格式 (ConfigManager)

```json
{
  "mcp_servers": [
    {
      "name": "time",
      "transport": "stdio",
      "command": "/data/local/tmp/mcp-server-time",
      "args": [],
      "timeout": 30
    },
    {
      "name": "filesystem",
      "transport": "http",
      "url": "https://mcp.example.com/mcp",
      "headers": {"Authorization": "Bearer xxx"},
      "timeout": 60
    }
  ]
}
```

## 工具命名

MCP 工具注册到 ToolRegistry 时使用前缀：`mcp_{server_name}_{tool_name}`

- `mcp_time_get_current_time`
- `mcp_filesystem_read_file`

## 设计决策

1. **Android 上 stdio transport 能力有限**：没有 npx/uvx，只能用 Go/Rust 编译的独立二进制或 shell 脚本
2. **HTTP transport 优先级更高**：手机上可连接本地 PC 或远程 MCP server
3. **连接在后台线程管理**：MCP 协议交互用 Dispatchers.IO
4. **失败静默降级**：MCP server 连接失败不影响核心功能
