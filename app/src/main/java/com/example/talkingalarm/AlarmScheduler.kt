package com.example.talkingalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {

    private const val SNOOZE_OFFSET = 500_000

    private fun manager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun firePendingIntent(context: Context, alarmId: Int, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction("fire_$requestCode")
            .putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Schedules the next occurrence, or cancels it if the alarm is switched off. */
    fun schedule(context: Context, alarm: Alarm) {
        val pi = firePendingIntent(context, alarm.id, alarm.id)
        if (!alarm.enabled) {
            manager(context).cancel(pi)
            return
        }
        setClock(context, nextTriggerMillis(alarm), pi)
    }

    fun cancel(context: Context, alarm: Alarm) {
        manager(context).cancel(firePendingIntent(context, alarm.id, alarm.id))
        manager(context).cancel(firePendingIntent(context, alarm.id, alarm.id + SNOOZE_OFFSET))
    }

    fun scheduleSnooze(context: Context, alarm: Alarm, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        setClock(context, at, firePendingIntent(context, alarm.id, alarm.id + SNOOZE_OFFSET))
    }

    fun rescheduleAll(context: Context) {
        AlarmStore.all(context).forEach { schedule(context, it) }
    }

    private fun setClock(context: Context, triggerAt: Long, operation: PendingIntent) {
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // setAlarmClock is the API meant for user-facing alarms: it survives Doze
        // and does not need the "exact alarm" special permission.
        manager(context).setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
    }

    fun nextTriggerMillis(alarm: Alarm, from: Long = System.currentTimeMillis()): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (alarm.days.isEmpty()) {
            if (base.timeInMillis <= from) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        for (i in 0..7) {
            val c = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            if (c.timeInMillis > from && alarm.days.contains(c.get(Calendar.DAY_OF_WEEK))) {
                return c.timeInMillis
            }
        }
        return base.timeInMillis + 24 * 60 * 60 * 1000L
    }
}
