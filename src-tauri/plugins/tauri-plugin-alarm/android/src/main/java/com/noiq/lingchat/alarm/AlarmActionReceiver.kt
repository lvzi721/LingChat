package com.noiq.lingchat.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmService.ACTION_STOP) {
            AlarmService.stop(context)
        }
    }
}
