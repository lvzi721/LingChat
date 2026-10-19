package com.noiq.lingchat.voicewake

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@TauriPlugin
class VoiceWakePlugin(private val hostActivity: Activity) : Plugin(hostActivity) {
    private val permissionRequestCode = 7301

    override fun load(webView: WebView) {
        VoiceWakeEventBridge.register(this)
        super.load(webView)
    }

    override fun onDestroy(activity: AppCompatActivity) {
        VoiceWakeEventBridge.unregister(this)
        super.onDestroy(activity)
    }

    @Command
    fun start(invoke: Invoke) {
        val args = invoke.getArgs()
        val phrase = args.optString("phrase", "").trim()
        val phraseEnabled = args.optBoolean("phraseEnabled", true)
        val ackEnabled = args.optBoolean("ackEnabled", true)
        val ackText = args.optString("ackText", "我在").trim().ifBlank { "我在" }
        val ackAudioPath =
            if (args.has("ackAudioPath") && !args.isNull("ackAudioPath")) {
                args.optString("ackAudioPath", "").trim().ifEmpty { null }
            } else {
                null
            }
        if (phrase.isBlank()) {
            invoke.reject("唤醒词不能为空", "wake_phrase_empty")
            return
        }
        if (!hasRecordingPermission()) {
            requestWakePermissionsInternal()
            invoke.reject("需要麦克风权限", "permission_required")
            return
        }
        try {
            VoiceWakeService.start(
                hostActivity,
                phrase,
                phraseEnabled,
                ackEnabled,
                ackText,
                ackAudioPath,
            )
            invoke.resolve(statusObject(phrase))
        } catch (error: Throwable) {
            invoke.reject(error.message ?: "启动语音唤醒服务失败", "start_service_failed")
        }
    }

    @Command
    fun stop(invoke: Invoke) {
        VoiceWakeService.stop(hostActivity)
        invoke.resolve(statusObject())
    }

    @Command
    fun status(invoke: Invoke) {
        invoke.resolve(statusObject())
    }

    /** 取走锁屏时暂存的识别结果（前端重新就绪后回填）。 */
    @Command
    fun takePendingText(invoke: Invoke) {
        val out = JSObject()
        VoiceWakeService.takePendingText(hostActivity)?.takeIf { it.isNotBlank() }?.let {
            out.put("text", it)
        }
        invoke.resolve(out)
    }

    /** 手动麦克风：用内置模型做一次识别（最长 maxMs），返回 {text} 或 {error}。 */
    @Command
    fun recognizeOnce(invoke: Invoke) {
        val maxMs = invoke.getArgs().optLong("maxMs", 15000L)
        Thread({
            val (text, error) = LocalAsr.recognize(hostActivity, maxMs)
            val out = JSObject()
            if (!text.isNullOrBlank()) out.put("text", text)
            if (!error.isNullOrBlank()) out.put("error", error)
            invoke.resolve(out)
        }, "voice-wake-once").start()
    }

    /** 诊断：录音 5 秒用内置模型识别一次，直接回传文本或错误。 */
    @Command
    fun testRecognition(invoke: Invoke) {
        val maxMs = invoke.getArgs().optLong("maxMs", 5000L)
        Thread({
            val (text, error) = LocalAsr.recognize(hostActivity, maxMs)
            val out = JSObject()
            if (!text.isNullOrBlank()) out.put("text", text)
            if (!error.isNullOrBlank()) out.put("error", error)
            invoke.resolve(out)
        }, "voice-wake-test").start()
    }

    @Command
    fun setPaused(invoke: Invoke) {
        val paused = invoke.getArgs().optBoolean("paused", false)
        VoiceWakeService.setPaused(hostActivity, paused)
        // 暂停是为了把麦克风让给前端录音：等原生侧真正释放，避免 getUserMedia 抢不到设备。
        if (paused) {
            val deadline = System.currentTimeMillis() + 800
            while (VoiceWakeService.micBusy && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        invoke.resolve(statusObject())
    }

    @Command
    fun requestWakePermissions(invoke: Invoke) {
        requestWakePermissionsInternal()
        invoke.resolve()
    }

    @Command
    fun openSystemSettings(invoke: Invoke) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            hostActivity.startActivity(intent)
            invoke.resolve()
        } catch (_: Throwable) {
            try {
                val fallback = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${hostActivity.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                hostActivity.startActivity(fallback)
                invoke.resolve()
            } catch (error: Throwable) {
                invoke.reject(error.message ?: "无法打开系统设置", "open_settings_failed")
            }
        }
    }

    private fun requestWakePermissionsInternal() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        hostActivity.requestPermissions(permissions.toTypedArray(), permissionRequestCode)
    }

    private fun hasRecordingPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            hostActivity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hostActivity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun statusObject(phrase: String = VoiceWakeService.currentPhrase): JSObject {
        val modelPresent = VoiceWakeService.detectModelPresent(hostActivity)
        val out = JSObject()
        out.put("supported", modelPresent)
        out.put("modelPresent", modelPresent)
        out.put("localAsrPresent", VoiceWakeService.detectAsrPresent(hostActivity))
        out.put("engineReady", VoiceWakeService.engineReady)
        out.put("serviceRunning", VoiceWakeService.isRunning)
        out.put("permissionGranted", hasRecordingPermission())
        out.put("notificationPermissionGranted", hasNotificationPermission())
        out.put("suspended", VoiceWakeService.isSuspended)
        out.put("phrase", phrase)
        VoiceWakeService.keywordTokens?.let { out.put("keywordTokens", it) }
        out.put("lastDetectionSerial", VoiceWakeService.lastDetectionSerial)
        out.put("lastDetectionAt", VoiceWakeService.lastDetectionAt)
        VoiceWakeService.lastDetectionTranscript?.let {
            out.put("lastDetectionTranscript", it)
        }
        VoiceWakeService.pendingText?.let { out.put("pendingText", it) }
        VoiceWakeService.ackAudioPath?.let { out.put("ackAudioPath", it) }
        out.put("ackAudioReady", VoiceWakeService.isAckAudioReady())
        VoiceWakeService.lastError?.let { out.put("lastError", it) }
        return out
    }
}

