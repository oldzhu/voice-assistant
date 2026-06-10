---
name: morning-routine
description: 执行早晨 routine（获取天气和新闻）。当用户说「早上好」「早晨 routine」时调用。
version: 1.0.0
steps:
  - say: 正在获取你的位置...
    tool: get_location
  - say: 正在查看今天的天气...
    tool: get_weather
  - say: 正在获取今天的新闻...
    tool: get_news
    args: { "category": "综合" }
---

# 早晨 Routine

获取天气和新闻头条，帮助你快速了解今天的情况。

## 触发条件
- "早上好"
- "早晨 routine"
- "开始我的一天"
- "今天怎么样"

## 步骤
1. 获取位置（用于精准天气）
2. 获取天气信息
3. 获取新闻头条
