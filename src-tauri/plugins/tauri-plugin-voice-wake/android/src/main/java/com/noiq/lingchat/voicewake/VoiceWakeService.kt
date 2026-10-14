package com.noiq.lingchat.voicewake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.tauri.plugin.JSObject
import java.util.Locale

/**
 * A best-effort, on-device wake-word listener.
 *
 * The service only proceeds when Android exposes an on-device recognizer. It
 * never falls back to a network recognizer, so "local" remains true even when
 * an OEM does not support this mode.
 */
class VoiceWakeService : Service(), RecognitionListener {
    companion object {
        private const val CHANNEL_ID = "lingchat_voice_wake"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_START = "com.noiq.lingchat.voicewake.START"
        private const val ACTION_STOP = "com.noiq.lingchat.voicewake.STOP"
        private const val ACTION_PAUSE = "com.noiq.lingchat.voicewake.PAUSE"
        private const val ACTION_RESUME = "com.noiq.lingchat.voicewake.RESUME"
        private const val EXTRA_PHRASE = "phrase"
        private const val PREFS_NAME = "voice_wake"
        private const val PREF_PHRASE = "phrase"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isSuspended: Boolean = false
            private set

        @Volatile
        var currentPhrase: String = "灵灵"
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var lastDetectionSerial: Long = 0L
            private set

        @Volatile
        var lastDetectionAt: Long = 0L
            private set

        @Volatile
        var lastDetectionTranscript: String? = null
            private set

        fun start(context: Context, phrase: String) {
            val intent = Intent(context, VoiceWakeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PHRASE, phrase)
            isRunning = true
            isSuspended = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Throwable) {
                isRunning = false
                lastError = error.message ?: "start_service_failed"
                throw error
            }
        }

        fun stop(context: Context) {
            isRunning = false
            isSuspended = false
            context.stopService(Intent(context, VoiceWakeService::class.java))
        }

        fun setPaused(context: Context, paused: Boolean) {
            if (!isRunning) return
            val action = if (paused) ACTION_PAUSE else ACTION_RESUME
            context.startService(Intent(context, VoiceWakeService::class.java).setAction(action))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognitionActive = false
    private var stoppingRecognition = false
    private var wakeHandled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var fallbackResume: Runnable? = null
    private var restartRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        currentPhrase = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_PHRASE, currentPhrase)
            .orEmpty()
            .ifBlank { "灵灵" }
        createNotificationChannel()
        startForegroundCompat(buildNotification("正在等待唤醒词"))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                suspendRecognition()
                updateNotification("语音唤醒已暂停")
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeRecognition()
                return START_STICKY
            }
            ACTION_START -> {
                val phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty().trim()
                if (phrase.isNotBlank()) {
                    currentPhrase = phrase
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_PHRASE, phrase)
                        .apply()
                }
                isSuspended = false
                wakeHandled = false
                startForegroundCompat(buildNotification("正在等待“$currentPhrase”"))
                ensureRecognizer()
                startRecognition()
                return START_STICKY
            }
        }
        if (!isSuspended) startRecognition()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user explicitly enabled the mode. Keep the foreground service
        // alive when the task is removed; the system still owns final process
        // termination and can stop us under memory pressure.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        stopRecognition()
        recognizer?.destroy()
        recognizer = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureRecognizer() {
        if (recognizer != null) return
        val onDeviceAvailable = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        } catch (_: Throwable) {
            false
        }
        if (!onDeviceAvailable) {
            lastError = "on_device_unavailable"
            updateNotification("此设备不支持纯本地语音识别")
            stopSelf()
            return
        }
        recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
                it.setRecognitionListener(this)
            }
        } catch (error: Throwable) {
            lastError = error.message ?: "recognizer_create_failed"
            updateNotification("本地识别引擎创建失败")
            null
        }
    }

    private fun startRecognition() {
        if (isSuspended) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "permission_required"
            updateNotification("缺少麦克风权限")
            return
        }
        ensureRecognizer()
        val engine = recognizer ?: return
        if (recognitionActive) return
        wakeHandled = false
        stoppingRecognition = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            engine.startListening(intent)
            recognitionActive = true
            lastError = null
            updateNotification("正在等待“$currentPhrase”")
        } catch (error: Throwable) {
            lastError = error.message ?: "start_listening_failed"
            scheduleRecognitionRestart(800L)
        }
    }

    private fun stopRecognition() {
        stoppingRecognition = true
        recognitionActive = false
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
            // Ignore cancellation races from the speech service.
        }
    }

    private fun suspendRecognition() {
        isSuspended = true
        cancelFallbackResume()
        stopRecognition()
    }

    private fun resumeRecognition() {
        isSuspended = false
        cancelFallbackResume()
        wakeHandled = false
        scheduleRecognitionRestart(250L)
    }

    private fun scheduleRecognitionRestart(delayMs: Long) {
        if (isSuspended) return
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            restartRunnable = null
            if (!isSuspended) startRecognition()
        }
        restartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun handleResults(results: Bundle?, final: Boolean) {
        val values = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return
        val matched = values.firstOrNull { containsWakeWord(it) } ?: return
        if (wakeHandled) return
        wakeHandled = true
        val hasListener = VoiceWakeEventBridge.hasListener("detected")
        lastDetectionSerial += 1
        lastDetectionAt = System.currentTimeMillis()
        lastDetectionTranscript = matched

        if (hasListener) {
            suspendRecognition()
            updateNotification("已唤醒，等待 LingChat 接收")
            val payload = JSObject().apply {
                put("phrase", currentPhrase)
                put("transcript", matched)
                put("timestamp", lastDetectionAt)
                put("serial", lastDetectionSerial)
            }
            VoiceWakeEventBridge.emit("detected", payload)
            scheduleFallbackResume(90_000L)
        } else {
            updateNotification("检测到“$currentPhrase”，但 LingChat 界面未就绪")
            scheduleRecognitionRestart(5_000L)
        }

        if (final) {
            recognitionActive = false
        }
    }

    private fun scheduleFallbackResume(delayMs: Long) {
        cancelFallbackResume()
        val runnable = Runnable {
            if (isSuspended) {
                resumeRecognition()
            }
        }
        fallbackResume = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelFallbackResume() {
        fallbackResume?.let { mainHandler.removeCallbacks(it) }
        fallbackResume = null
    }

    private fun containsWakeWord(text: String): Boolean {
        val normalizedText = normalize(text)
        val normalizedPhrase = normalize(currentPhrase)
        return normalizedPhrase.isNotEmpty() && normalizedText.contains(normalizedPhrase)
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.getDefault())
            .replace(Regex("[\\s\\p{Punct}，。！？、；：·]"), "")

    override fun onPartialResults(partialResults: Bundle?) {
        handleResults(partialResults, final = false)
    }

    override fun onResults(results: Bundle?) {
        recognitionActive = false
        handleResults(results, final = true)
        if (!wakeHandled && !isSuspended) {
            scheduleRecognitionRestart(300L)
        }
    }

    override fun onError(error: Int) {
        recognitionActive = false
        if (stoppingRecognition) {
            stoppingRecognition = false
            return
        }
        lastError = if (
            error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        ) {
            null
        } else {
            "speech_error_$error"
        }
        if (!isSuspended) {
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1_200L
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 250L
                else -> 800L
            }
            scheduleRecognitionRestart(delay)
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        updateNotification("正在等待“$currentPhrase”")
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LingChat 语音唤醒",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "锁屏时持续监听本地唤醒词"
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LingChat 语音唤醒")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_btn_speak_now,
                    "停止",
                    stopIntent
                ).build()
            )
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:voice-wake"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }
}

