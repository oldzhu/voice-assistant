package com.example.voiceassistant.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.voiceassistant.llm.Tool
import com.example.voiceassistant.llm.ToolParameter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

// ══════════════════════════════════════════════════════════
// AlarmReceiver — handles fired reminders
// ══════════════════════════════════════════════════════════

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("reminder_message") ?: "时间到了！"
        val reminderId = intent.getStringExtra("reminder_id") ?: return

        // Broadcast to VoiceService to speak the reminder
        val serviceIntent = Intent(context, com.example.voiceassistant.VoiceService::class.java).apply {
            action = ACTION_REMINDER_FIRED
            putExtra("reminder_message", message)
            putExtra("reminder_id", reminderId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Clean up persisted reminder
        try {
            val dir = File(context.filesDir, "reminders")
            File(dir, "${reminderId}.json").delete()
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_REMINDER_FIRED = "com.example.voiceassistant.REMINDER_FIRED"
    }
}

// ══════════════════════════════════════════════════════════
// Reminder data class
// ══════════════════════════════════════════════════════════

data class ReminderEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val message: String,
    val delayMinutes: Int,
    val triggerTimeMs: Long,  // SystemClock.elapsedRealtime() when it fires
    val createdAt: Long = System.currentTimeMillis()
)

// ══════════════════════════════════════════════════════════
// SetReminderTool
// ══════════════════════════════════════════════════════════

/**
 * Tool: set_reminder — schedule a timed voice reminder.
 *
 * User: "15分钟后提醒我喝水" → set_reminder(minutes=15, message="喝水")
 * Uses AlarmManager ELAPSED_REALTIME_WAKEUP for reliability.
 * Persisted to filesDir/reminders/ for survival across restarts.
 */
class SetReminderTool(
    private val context: () -> Context,
    private val filesDir: () -> File
) : Tool {
    override val name = "set_reminder"
    override val description = "设置定时提醒。当用户说'X分钟后提醒我XX'、'X分钟后叫我XX'、'过X分钟提醒我'时调用。提醒时间到时会语音播报。"
    override val parameters = mapOf(
        "minutes" to ToolParameter("string", "多少分钟后提醒，如 5、10、30"),
        "message" to ToolParameter("string", "提醒内容，如'喝水'、'开会'、'吃药'")
    )

    private val gson = Gson()

    override suspend fun execute(args: Map<String, Any?>): String {
        val minutesStr = args["minutes"] as? String ?: return "错误：缺少 minutes 参数"
        val minutes = minutesStr.toIntOrNull() ?: return "错误：minutes 必须是数字"
        val message = (args["message"] as? String)?.trim() ?: ""
        if (message.isBlank()) return "错误：缺少 message 参数（提醒内容）"
        if (minutes <= 0) return "错误：分钟数必须大于 0"
        if (minutes > 1440) return "错误：最长支持 24 小时（1440 分钟）"

        val ctx = context()
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = SystemClock.elapsedRealtime() + minutes * 60_000L

        val reminder = ReminderEntry(
            message = message,
            delayMinutes = minutes,
            triggerTimeMs = triggerTime
        )

        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "REMINDER_${reminder.id}"
            putExtra("reminder_message", message)
            putExtra("reminder_id", reminder.id)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            ctx, reminder.id.hashCode(), intent, flags
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (e: SecurityException) {
            return "错误：缺少闹钟权限。请在系统设置中允许'闹钟和提醒'权限。"
        }

        // Persist
        try {
            val dir = File(filesDir(), "reminders").also { it.mkdirs() }
            File(dir, "${reminder.id}.json").writeText(gson.toJson(reminder))
        } catch (_: Exception) {}

        return "✅ 已设置提醒：${minutes}分钟后提醒「${message}」"
    }
}

// ══════════════════════════════════════════════════════════
// CancelReminderTool
// ══════════════════════════════════════════════════════════

class CancelReminderTool(
    private val context: () -> Context,
    private val filesDir: () -> File
) : Tool {
    override val name = "cancel_reminder"
    override val description = "取消定时提醒。当用户说'取消提醒'、'不用提醒了'时调用。如果未指定ID则取消所有。"
    override val parameters = mapOf(
        "reminder_id" to ToolParameter("string", "提醒ID（从 list_reminders 获取），留空则取消全部", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): String {
        val ctx = context()
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val reminderId = args["reminder_id"] as? String

        if (reminderId != null) {
            // Cancel specific
            val intent = Intent(ctx, AlarmReceiver::class.java).apply { action = "REMINDER_$reminderId" }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
            val pi = PendingIntent.getBroadcast(ctx, reminderId.hashCode(), intent, flags)
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
            File(filesDir(), "reminders/${reminderId}.json").delete()
            return "✅ 已取消提醒 $reminderId"
        } else {
            // Cancel all
            val dir = File(filesDir(), "reminders")
            var count = 0
            dir.listFiles()?.forEach { file ->
                try {
                    val reminder: ReminderEntry = gson.fromJson(file.readText(), ReminderEntry::class.java)
                    val intent = Intent(ctx, AlarmReceiver::class.java).apply { action = "REMINDER_${reminder.id}" }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                    } else {
                        PendingIntent.FLAG_NO_CREATE
                    }
                    val pi = PendingIntent.getBroadcast(ctx, reminder.id.hashCode(), intent, flags)
                    if (pi != null) {
                        alarmManager.cancel(pi)
                        pi.cancel()
                    }
                    file.delete()
                    count++
                } catch (_: Exception) {}
            }
            return "✅ 已取消 $count 个提醒"
        }
    }

    companion object { private val gson = Gson() }
}

// ══════════════════════════════════════════════════════════
// ListRemindersTool
// ══════════════════════════════════════════════════════════

class ListRemindersTool(
    private val filesDir: () -> File
) : Tool {
    override val name = "list_reminders"
    override val description = "列出当前所有活跃的提醒。当用户问'我有哪些提醒'、'还有几个提醒'时调用。"
    override val parameters = emptyMap<String, ToolParameter>()

    private val gson = Gson()

    override suspend fun execute(args: Map<String, Any?>): String {
        val dir = File(filesDir(), "reminders")
        if (!dir.exists() || dir.listFiles()?.isEmpty() != false) {
            return "当前没有活跃的提醒。"
        }

        val now = SystemClock.elapsedRealtime()
        val reminders = dir.listFiles()?.mapNotNull { file ->
            try {
                gson.fromJson(file.readText(), ReminderEntry::class.java)
            } catch (_: Exception) { null }
        }?.sortedBy { it.triggerTimeMs } ?: return "无法读取提醒列表。"

        if (reminders.isEmpty()) return "当前没有活跃的提醒。"

        val sb = StringBuilder("当前有 ${reminders.size} 个提醒：\n")
        for ((i, r) in reminders.withIndex()) {
            val remainingSec = (r.triggerTimeMs - now) / 1000
            val remainingMin = remainingSec / 60
            val timeDesc = if (remainingMin > 0) "约${remainingMin}分钟后" else "即将"
            sb.append("${i + 1}. [${r.id}] ${timeDesc}提醒「${r.message}」\n")
        }
        return sb.toString().trimEnd()
    }
}
