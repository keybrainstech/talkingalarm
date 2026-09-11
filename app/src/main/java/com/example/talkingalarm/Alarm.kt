package com.example.talkingalarm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

const val EXTRA_ALARM_ID = "extra_alarm_id"

/**
 * One alarm. [days] holds Calendar.SUNDAY..Calendar.SATURDAY values.
 * An empty [days] set means the alarm fires once and then switches itself off.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val text: String,
    val enabled: Boolean = true,
    val days: Set<Int> = emptySet()
) {
    val minutesOfDay: Int get() = hour * 60 + minute

    fun timeText(): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    fun daysText(): String {
        if (days.isEmpty()) return "Once"
        if (days.size == 7) return "Every day"
        val names = mapOf(
            Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun"
        )
        return WEEK_ORDER.filter { days.contains(it) }.joinToString(" ") { names[it] ?: "" }
    }

    companion object {
        val WEEK_ORDER = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
    }
}

object AlarmStore {

    private const val PREFS = "talking_alarm_prefs"
    private const val KEY_LIST = "alarms"

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private var loaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val raw = prefs(context).getString(KEY_LIST, "[]") ?: "[]"
        val list = mutableListOf<Alarm>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(fromJson(arr.getJSONObject(i)))
        }
        _alarms.value = list.sortedBy { it.minutesOfDay }
        loaded = true
    }

    fun all(context: Context): List<Alarm> {
        ensureLoaded(context)
        return _alarms.value
    }

    fun get(context: Context, id: Int): Alarm? {
        ensureLoaded(context)
        return _alarms.value.firstOrNull { it.id == id }
    }

    fun nextId(context: Context): Int {
        ensureLoaded(context)
        return (_alarms.value.maxOfOrNull { it.id } ?: 0) + 1
    }

    @Synchronized
    fun upsert(context: Context, alarm: Alarm) {
        ensureLoaded(context)
        val list = _alarms.value.filter { it.id != alarm.id } + alarm
        persist(context, list.sortedBy { it.minutesOfDay })
    }

    @Synchronized
    fun delete(context: Context, id: Int) {
        ensureLoaded(context)
        persist(context, _alarms.value.filter { it.id != id })
    }

    private fun persist(context: Context, list: List<Alarm>) {
        _alarms.value = list
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun toJson(a: Alarm): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("hour", a.hour)
        put("minute", a.minute)
        put("text", a.text)
        put("enabled", a.enabled)
        put("days", JSONArray().also { arr -> a.days.forEach { arr.put(it) } })
    }

    private fun fromJson(o: JSONObject): Alarm {
        val daysArr = o.optJSONArray("days") ?: JSONArray()
        val days = mutableSetOf<Int>()
        for (i in 0 until daysArr.length()) days.add(daysArr.getInt(i))
        return Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            text = o.optString("text", ""),
            enabled = o.optBoolean("enabled", true),
            days = days
        )
    }
}
