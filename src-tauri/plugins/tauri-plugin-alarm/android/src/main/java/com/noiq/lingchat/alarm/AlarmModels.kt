package com.noiq.lingchat.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class AlarmRecord(
    var id: String,
    var label: String,
    var hour: Int,
    var minute: Int,
    var days: List<Int>,
    var enabled: Boolean,
    var ringtoneUri: String?,
    var ringtoneTitle: String,
    var nextTriggerAt: Long?,
    var exact: Boolean,
) {
    fun toJson(): JSONObject {
        val daysJson = JSONArray()
        days.forEach { daysJson.put(it) }
        return JSONObject()
            .put("id", id)
            .put("label", label)
            .put("hour", hour)
            .put("minute", minute)
            .put("days", daysJson)
            .put("enabled", enabled)
            .put("ringtoneUri", ringtoneUri ?: JSONObject.NULL)
            .put("ringtoneTitle", ringtoneTitle)
            .put("nextTriggerAt", nextTriggerAt ?: JSONObject.NULL)
            .put("nextTriggerText", nextTriggerAt?.let(::formatTriggerTime) ?: JSONObject.NULL)
            .put("exact", exact)
    }

    companion object {
        fun fromJson(value: JSONObject): AlarmRecord {
            val days = mutableListOf<Int>()
            val daysJson = value.optJSONArray("days") ?: JSONArray()
            for (index in 0 until daysJson.length()) {
                days += daysJson.optInt(index)
            }
            val ringtoneUri = if (value.isNull("ringtoneUri")) {
                null
            } else {
                value.optString("ringtoneUri").takeIf { it.isNotBlank() }
            }
            val nextTriggerAt = if (!value.has("nextTriggerAt") || value.isNull("nextTriggerAt")) {
                null
            } else {
                value.optLong("nextTriggerAt")
            }
            return AlarmRecord(
                id = value.getString("id"),
                label = value.optString("label", "闹钟"),
                hour = value.optInt("hour"),
                minute = value.optInt("minute"),
                days = days,
                enabled = value.optBoolean("enabled", true),
                ringtoneUri = ringtoneUri,
                ringtoneTitle = value.optString("ringtoneTitle", "默认闹钟"),
                nextTriggerAt = nextTriggerAt,
                exact = value.optBoolean("exact", true),
            )
        }

        private fun formatTriggerTime(value: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))
    }
}

internal class AlarmStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun all(): MutableList<AlarmRecord> {
        val raw = preferences.getString(ALARMS_KEY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                AlarmRecord.fromJson(array.getJSONObject(index))
            }
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    @Synchronized
    fun get(id: String): AlarmRecord? = all().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(record: AlarmRecord): AlarmRecord {
        val alarms = all()
        val index = alarms.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            alarms[index] = record
        } else {
            alarms += record
        }
        save(alarms)
        return record
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val alarms = all()
        val removed = alarms.removeAll { it.id == id }
        if (removed) {
            save(alarms)
        }
        return removed
    }

    @Synchronized
    fun replaceAll(alarms: List<AlarmRecord>) {
        save(alarms)
    }

    private fun save(alarms: List<AlarmRecord>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        preferences.edit().putString(ALARMS_KEY, array.toString()).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "lingchat_alarms"
        const val ALARMS_KEY = "alarms"
    }
}
