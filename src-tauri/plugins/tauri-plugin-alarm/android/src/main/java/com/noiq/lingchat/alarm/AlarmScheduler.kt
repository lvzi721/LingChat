package com.noiq.lingchat.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

internal data class ScheduledTrigger(
    val triggerAtMillis: Long,
    val exact: Boolean,
)

internal object AlarmScheduler {
    const val EXTRA_ALARM_ID = "alarm_id"
    const val ACTION_FIRE = "com.noiq.lingchat.alarm.FIRE"

    fun schedule(context: Context, alarm: AlarmRecord): ScheduledTrigger? {
        val triggerAt = nextTriggerAt(alarm) ?: return null
        return scheduleAt(context, alarm, triggerAt)
    }

    fun scheduleAt(context: Context, alarm: AlarmRecord, triggerAtMillis: Long): ScheduledTrigger {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = operationPendingIntent(context, alarm.id)
        val showIntent = showPendingIntent(context, alarm.id)
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)

        return try {
            if (canScheduleExact(manager)) {
                manager.setAlarmClock(info, operation)
                ScheduledTrigger(triggerAtMillis, exact = true)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                ScheduledTrigger(triggerAtMillis, exact = false)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            ScheduledTrigger(triggerAtMillis, exact = false)
        }
    }

    fun cancel(context: Context, id: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(id),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            manager.cancel(pending)
            pending.cancel()
        }
    }

    fun rescheduleAll(context: Context) {
        val store = AlarmStore(context)
        val alarms = store.all()
        alarms.forEach { alarm ->
            if (alarm.enabled) {
                val plan = schedule(context, alarm)
                alarm.nextTriggerAt = plan?.triggerAtMillis
                alarm.exact = plan?.exact ?: false
            } else {
                cancel(context, alarm.id)
                alarm.nextTriggerAt = null
            }
        }
        store.replaceAll(alarms)
    }

    private fun nextTriggerAt(alarm: AlarmRecord): Long? {
        val now = Calendar.getInstance()
        val days = alarm.days.distinct().filter { it in 1..7 }

        if (days.isEmpty()) {
            val candidate = calendarAt(now, alarm.hour, alarm.minute)
            if (!candidate.after(now)) {
                candidate.add(Calendar.DAY_OF_MONTH, 1)
            }
            return candidate.timeInMillis
        }

        for (offset in 0..7) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis
                add(Calendar.DAY_OF_MONTH, offset)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (days.contains(candidate.get(Calendar.DAY_OF_WEEK)) && candidate.after(now)) {
                return candidate.timeInMillis
            }
        }
        return null
    }

    private fun calendarAt(now: Calendar, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private fun operationPendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ALARM_ID, id)
        return PendingIntent.getBroadcast(
            context,
            requestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showPendingIntent(context: Context, id: String): PendingIntent {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return PendingIntent.getActivity(
            context,
            requestCode(id),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(id: String): Int = id.hashCode() and 0x7fffffff
}
