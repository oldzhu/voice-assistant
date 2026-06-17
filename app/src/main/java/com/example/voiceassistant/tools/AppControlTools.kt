package com.example.voiceassistant.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter

/**
 * Tool: launch_app — open any installed app by package name or Chinese name.
 *
 * Common apps mapped in [COMMON_APPS]. Falls back to querying PackageManager.
 */
class LaunchAppTool(private val context: () -> Context) : Tool {
    override val name = "launch_app"
    override val description = "打开指定的应用。传入应用的中文名或包名。常用应用：京东、淘宝、微信、支付宝、抖音、美团、饿了么、百度、高德地图、计算器、相机、设置等。" +
        "当用户说「打开XX」「启动XX」「进XX」时调用此工具。"
    override val parameters = mapOf(
        "app" to ToolParameter("string", "应用名称(中文名或包名)，如「京东」「微信」「com.jingdong.app.mall」", required = true)
    )

    companion object {
        private const val TAG = "LaunchApp"
        val COMMON_APPS = mapOf(
            "京东" to "com.jingdong.app.mall",
            "淘宝" to "com.taobao.taobao",
            "微信" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "抖音" to "com.ss.android.ugc.aweme",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "百度" to "com.baidu.searchbox",
            "高德地图" to "com.autonavi.minimap",
            "计算器" to "com.coloros.calculator",
            "相机" to "com.oppo.camera",
            "设置" to "com.android.settings",
            "时钟" to "com.coloros.alarmclock",
            "日历" to "com.coloros.calendar",
            "微博" to "com.sina.weibo",
            "小红书" to "com.xingin.xhs",
            "拼多多" to "com.xunmeng.pinduoduo",
            "QQ" to "com.tencent.mobileqq",
            "网易云音乐" to "com.netease.cloudmusic",
            "QQ音乐" to "com.tencent.qqmusic"
        )
    }

    override suspend fun execute(args: Map<String, Any?>): String {
        val appName = (args["app"] as? String)?.trim() ?: return "错误：请提供应用名称"

        // Check common app mapping first
        val packageName = COMMON_APPS[appName] ?: appName  // use as-is if unknown

        val ctx = context()
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ctx.startActivity(intent)
                Log.i(TAG, "Launched: $appName → $packageName")
                "已打开$appName"
            } else {
                // Try as a system-level shortcut
                val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val activities = ctx.packageManager.queryIntentActivities(mainIntent, 0)
                if (activities.isNotEmpty()) {
                    ctx.startActivity(mainIntent)
                    Log.i(TAG, "Launched via ACTION_MAIN: $appName → $packageName")
                    "已打开$appName"
                } else {
                    "未找到应用「$appName」。可用的常用应用：${COMMON_APPS.keys.joinToString("、")}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed: $appName", e)
            "打开「$appName」失败：${e.message}"
        }
    }
}

/**
 * Tool: press_key — press system navigation keys via accessibility service.
 */
class PressKeyTool : Tool {
    override val name = "press_key"
    override val description = "按下系统导航按键。可用键：back(返回)、home(主页)、recents(最近任务)。" +
        "当用户说「返回」「回上一页」「回到桌面」等时调用。"
    override val parameters = mapOf(
        "key" to ToolParameter("string", "按键名称", required = true,
            enum = listOf("back", "home", "recents"))
    )

    companion object { private const val TAG = "PressKey" }

    override suspend fun execute(args: Map<String, Any?>): String {
        if (!GestureManager.isAvailable) return "错误：无障碍服务未开启，无法执行按键操作。请到设置→无障碍→猪头助手中开启。"

        val key = (args["key"] as? String)?.trim()?.lowercase()
            ?: return "错误：请指定按键。可用：back, home, recents"

        return when (key) {
            "back" -> GestureManager.performBack().fold(
                onSuccess = { "已按返回键" },
                onFailure = { "返回键失败：${it.message}" }
            )
            "home" -> GestureManager.performHome().fold(
                onSuccess = { "已按Home键" },
                onFailure = { "Home键失败：${it.message}" }
            )
            "recents" -> GestureManager.performRecents().fold(
                onSuccess = { "已打开最近任务" },
                onFailure = { "最近任务键失败：${it.message}" }
            )
            else -> "错误：未知按键「$key」。可用：back, home, recents"
        }
    }
}
