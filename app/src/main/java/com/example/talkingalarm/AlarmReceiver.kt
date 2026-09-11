package com.example.talkingalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val alarm = AlarmStore.get(context, id) ?: return

        if (alarm.days.isEmpty()) {
            // One-off alarm: switch it off now that it has fired.
            AlarmStore.upsert(context, alarm.copy(enabled = false))
        } else {
            AlarmScheduler.schedule(context, alarm)
        }

        val service = Intent(context, AlarmService::class.java).putExtra(EXTRA_ALARM_ID, id)
        ContextCompat.startForegroundService(context, service)
    }
}
