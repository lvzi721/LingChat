package com.noiq.lingchat.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@TauriPlugin
class AlarmPlugin(private val activity: Activity) : Plugin(activity) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val context: Context
        get() = activity.applicationContext

    @Command
    fun upsert(invoke: Invoke) {
        executor.execute {
            try {
                val args = invoke.getArgs()
                val hour = args.optInt("hour", -1)
                val minute = args.optInt("minute", -1)
                if (hour !in 0..23 || minute !in 0..59) {
                    respondError(invoke, "hour 必须在 0-23，minute 必须在 0-59")
                    return@execute
                }

                val days = parseDays(args.optJSONArray("days"))
                if (days == null) {
                    respondError(invoke, "days 只能包含 1-7（周日=1）")
                    return@execute
                }

                val store = AlarmStore(context)
                val requestedId = args.optString("id", "").trim().takeIf { it.isNotEmpty() }
                val existing = requestedId?.let(store::get)
                if (existing == null && store.all().size >= MAX_ALARMS) {
                    respondError(invoke, "闹钟数量已达上限（$MAX_ALARMS）")
                    return@execute
                }
                val label = if (args.has("label")) {
                    args.optString("label").trim().ifBlank { "闹钟" }.take(MAX_LABEL_CHARS)
                } else {
                    existing?.label ?: "闹钟"
                }

                val ringtoneUri = if (args.has("ringtoneUri")) {
                    normalizeRingtoneUri(args.optString("ringtoneUri"))
                } else {
                    existing?.ringtoneUri ?: defaultAlarmRingtone().toString()
                }
                val ringtoneTitle = ringtoneTitle(ringtoneUri)

                val alarm = existing ?: AlarmRecord(
                    id = requestedId ?: UUID.randomUUID().toString(),
                    label = label,
                    hour = hour,
                    minute = minute,
                    days = days,
                    enabled = true,
                    ringtoneUri = ringtoneUri,
                    ringtoneTitle = ringtoneTitle,
                    nextTriggerAt = null,
                    exact = true,
                )
                alarm.label = label
                alarm.hour = hour
                alarm.minute = minute
                alarm.days = days
                alarm.enabled = args.optBoolean("enabled", existing?.enabled ?: true)
                alarm.ringtoneUri = ringtoneUri
                alarm.ringtoneTitle = ringtoneTitle

                if (alarm.enabled) {
                    val plan = AlarmScheduler.schedule(context, alarm)
                    if (plan == null) {
                        respondError(invoke, "无法计算下一次响铃时间")
                        return@execute
                    }
                    alarm.nextTriggerAt = plan.triggerAtMillis
                    alarm.exact = plan.exact
                } else {
                    AlarmScheduler.cancel(context, alarm.id)
                    AlarmService.stop(context)
                    alarm.nextTriggerAt = null
                }

                respondOk(invoke, store.upsert(alarm).toJson())
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "创建或更新闹钟失败")
            }
        }
    }

    @Command
    fun list(invoke: Invoke) {
        executor.execute {
            try {
                val alarms = JSONArray()
                AlarmStore(context).all().forEach { alarms.put(it.toJson()) }
                respondOk(invoke, JSONObject().put("alarms", alarms))
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "读取闹钟列表失败")
            }
        }
    }

    @Command
    fun cancel(invoke: Invoke) {
        executor.execute {
            try {
                val id = invoke.getArgs().optString("id", "").trim()
                if (id.isEmpty()) {
                    respondError(invoke, "缺少 id")
                    return@execute
                }
                AlarmScheduler.cancel(context, id)
                AlarmService.stop(context)
                val removed = AlarmStore(context).remove(id)
                respondOk(
                    invoke,
                    JSONObject()
                        .put("ok", removed)
                        .put(
                            "message",
                            if (removed) "闹钟已取消" else "未找到该闹钟",
                        ),
                )
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "取消闹钟失败")
            }
        }
    }

    @Command
    fun set_ringtone(invoke: Invoke) {
        executor.execute {
            try {
                val args = invoke.getArgs()
                val id = args.optString("id", "").trim()
                if (id.isEmpty()) {
                    respondError(invoke, "缺少 id")
                    return@execute
                }
                val store = AlarmStore(context)
                val alarm = store.get(id)
                if (alarm == null) {
                    respondError(invoke, "未找到该闹钟")
                    return@execute
                }
                val uri = normalizeRingtoneUri(args.optString("uri", ""))
                alarm.ringtoneUri = uri
                alarm.ringtoneTitle = ringtoneTitle(uri)
                respondOk(invoke, store.upsert(alarm).toJson())
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "修改闹钟铃声失败")
            }
        }
    }

    @Command
    fun list_ringtones(invoke: Invoke) {
        executor.execute {
            try {
                val result = JSONArray()
                result.put(
                    JSONObject()
                        .put("title", "静音")
                        .put("uri", AlarmService.SILENT_URI)
                        .put("isDefault", false),
                )
                val manager = RingtoneManager(context).apply {
                    setType(RingtoneManager.TYPE_ALARM)
                }
                val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val cursor = manager.cursor
                cursor.use {
                    while (it.moveToNext()) {
                        val uri = manager.getRingtoneUri(it.position) ?: continue
                        val title = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                            ?: "未命名铃声"
                        result.put(
                            JSONObject()
                                .put("title", title)
                                .put("uri", uri.toString())
                                .put("isDefault", uri == defaultUri),
                        )
                    }
                }
                respondOk(invoke, JSONObject().put("ringtones", result))
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "读取铃声列表失败")
            }
        }
    }

    @Command
    fun stop(invoke: Invoke) {
        executor.execute {
            try {
                AlarmService.stop(context)
                respondOk(invoke, JSONObject().put("ok", true).put("message", "已停止当前闹钟"))
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "停止闹钟失败")
            }
        }
    }

    @Command
    fun permissions(invoke: Invoke) {
        executor.execute {
            try {
                respondOk(invoke, permissionState().toJson())
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "读取闹钟权限状态失败")
            }
        }
    }

    @Command
    fun request_permissions(invoke: Invoke) {
        executor.execute {
            try {
                activity.runOnUiThread {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        activity.requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_NOTIFICATIONS,
                        )
                    } else if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !exactAlarmManager().canScheduleExactAlarms()
                    ) {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${activity.packageName}"),
                            ),
                        )
                    } else if (!isIgnoringBatteryOptimizations()) {
                        activity.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }
                }
                respondOk(invoke, permissionState().toJson())
            } catch (t: Throwable) {
                respondError(invoke, t.message ?: "请求闹钟权限失败")
            }
        }
    }

    private data class PermissionState(
        val notificationsGranted: Boolean,
        val exactAlarmAllowed: Boolean,
        val batteryOptimizationIgnored: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("notificationsGranted", notificationsGranted)
            .put("exactAlarmAllowed", exactAlarmAllowed)
            .put("batteryOptimizationIgnored", batteryOptimizationIgnored)
    }

    private fun permissionState(): PermissionState = PermissionState(
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
        exactAlarmAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            exactAlarmManager().canScheduleExactAlarms(),
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(),
    )

    private fun exactAlarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun normalizeRingtoneUri(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value == AlarmService.SILENT_URI) {
            return AlarmService.SILENT_URI
        }
        val uri = Uri.parse(value)
        val ringtone = RingtoneManager.getRingtone(context, uri)
            ?: throw IllegalArgumentException("无法读取该铃声")
        if (ringtone.getTitle(context).isNullOrBlank()) {
            throw IllegalArgumentException("该铃声没有可用的标题")
        }
        return uri.toString()
    }

    private fun ringtoneTitle(uri: String): String {
        if (uri == AlarmService.SILENT_URI) {
            return "静音"
        }
        return try {
            RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context)
                ?: "未知铃声"
        } catch (_: Throwable) {
            "未知铃声"
        }
    }

    private fun defaultAlarmRingtone(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Uri.parse(AlarmService.SILENT_URI)

    private fun parseDays(array: JSONArray?): List<Int>? {
        if (array == null) {
            return emptyList()
        }
        val days = mutableListOf<Int>()
        for (index in 0 until array.length()) {
            val day = array.optInt(index, -1)
            if (day !in 1..7) {
                return null
            }
            if (!days.contains(day)) {
                days += day
            }
        }
        return days.sorted()
    }

    private fun respondOk(invoke: Invoke, data: Any?) {
        val response = JSObject()
        response.put("ok", true)
        response.put("data", data ?: JSONObject.NULL)
        invoke.resolve(response)
    }

    private fun respondError(invoke: Invoke, message: String) {
        val response = JSObject()
        response.put("ok", false)
        response.put("error", message)
        invoke.resolve(response)
    }

    private companion object {
        const val MAX_ALARMS = 100
        const val MAX_LABEL_CHARS = 80
        const val REQUEST_NOTIFICATIONS = 4201
    }
}
