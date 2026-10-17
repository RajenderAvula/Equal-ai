package com.equalai.assistant

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Scheduler {

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun add(ctx: Context, item: ScheduledActionItem) {
        TaskStore.add(ctx, item)
        AssistantBridge.onMain {
            val list = (AssistantBridge.scheduledTasks.toList() + item).sortedBy { it.triggerAt }
            AssistantBridge.scheduledTasks.clear()
            AssistantBridge.scheduledTasks.addAll(list)
        }
        setAlarm(ctx, item, item.triggerAt)
        AssistantBridge.log("Scheduled ${item.actionType} to ${item.target} at ${item.timeLabel}")
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx, id))
        TaskStore.remove(ctx, id)
        AssistantBridge.onMain { AssistantBridge.scheduledTasks.removeAll { it.id == id } }
    }

    /** Called after reboot / app update. Re-arms every saved task. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (t in TaskStore.all(ctx)) {
            when {
                t.triggerAt >= now -> setAlarm(ctx, t, t.triggerAt)
                now - t.triggerAt <= 30 * 60_000L -> setAlarm(ctx, t, now + 5_000L)   // recently missed: run shortly
                else -> {
                    TaskStore.remove(ctx, t.id)                                        // too old: drop
                    AssistantBridge.onMain { AssistantBridge.scheduledTasks.removeAll { it.id == t.id } }
                }
            }
        }
    }

    private fun pendingIntent(ctx: Context, id: Int): PendingIntent {
        val intent = Intent(ctx, ScheduledTaskReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(ctx: Context, item: ScheduledActionItem, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, item.id)
        if (canExact(ctx)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return
            } catch (e: SecurityException) {
                // fall through to inexact
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        AssistantBridge.log("Exact alarms not allowed - task may fire a few minutes late (see Setup).")
    }
}

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id == -1) return
        val app = context.applicationContext
        AppSettings.init(app)
        val task = TaskStore.get(app, id) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ActionExecutor.executeTask(app, task)
            } catch (e: Exception) {
                AssistantBridge.log("Scheduled task failed: ${e.localizedMessage}")
            } finally {
                TaskStore.remove(app, id)
                withContext(Dispatchers.Main) {
                    AssistantBridge.scheduledTasks.removeAll { it.id == id }
                }
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        AppSettings.init(app)
        Scheduler.rescheduleAll(app)
        // Android 14 forbids starting a microphone service from boot, so ask the user to tap once.
        if (a == Intent.ACTION_BOOT_COMPLETED) notifyResume(app)
    }

    @SuppressLint("MissingPermission")
    private fun notifyResume(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel("equal_boot_channel", "Equal AI reminders", NotificationManager.IMPORTANCE_DEFAULT)
                ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, "equal_boot_channel")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("Equal AI")
                .setContentText("Tap to resume voice listening. Scheduled tasks are restored.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(2001, n)
        } catch (e: Exception) {
            // notification permission not granted - ignore
        }
    }
}
