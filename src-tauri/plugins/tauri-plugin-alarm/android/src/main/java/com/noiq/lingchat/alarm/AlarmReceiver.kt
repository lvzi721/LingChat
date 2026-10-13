package com.noiq.lingchat.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) {
            return
        }
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val store = AlarmStore(context)
        val alarm = store.get(id) ?: return
        if (!alarm.enabled) {
            return
        }

        if (alarm.days.isEmpty()) {
            alarm.enabled = false
            alarm.nextTriggerAt = null
        } else {
            val plan = AlarmScheduler.schedule(context, alarm)
            alarm.nextTriggerAt = plan?.triggerAtMillis
            alarm.exact = plan?.exact ?: false
        }
        store.upsert(alarm)
        AlarmService.start(context, id)
    }
}
