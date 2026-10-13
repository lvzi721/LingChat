package com.noiq.lingchat.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo

class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringingAlarmId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }

        val id = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        if (id.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val alarm = AlarmStore(this).get(id)
        if (alarm == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        ringingAlarmId = id
        ensureWakeLock()
        val notification = buildNotification(alarm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId(id),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(notificationId(id), notification)
        }
        startVibration()
        startPlayer(alarm.ringtoneUri)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        stopVibration()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startPlayer(ringtoneUri: String?) {
        releasePlayer()
        if (ringtoneUri == SILENT_URI) {
            return
        }
        val preferred = ringtoneUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val uri = preferred
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        try {
            player = createPlayer(uri)
        } catch (_: Throwable) {
            releasePlayer()
            val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (fallback != null && fallback != uri) {
                try {
                    player = createPlayer(fallback)
                } catch (_: Throwable) {
                    releasePlayer()
                }
            }
        }
    }

    private fun createPlayer(uri: Uri): MediaPlayer =
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setDataSource(this@AlarmService, uri)
            isLooping = true
            prepare()
            start()
        }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 700, 700)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
    }

    private fun ensureWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LingChat:Alarm",
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun stopRinging() {
        releasePlayer()
        stopVibration()
        releaseWakeLock()
        ringingAlarmId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(alarm: AlarmRecord): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LingChat 闹钟",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "LingChat 自建闹钟与提醒"
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, AlarmActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = "%02d:%02d".format(alarm.hour, alarm.minute)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(alarm.label.ifBlank { "LingChat 闹钟" })
            .setContentText(time)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.noiq.lingchat.alarm.START"
        const val ACTION_STOP = "com.noiq.lingchat.alarm.STOP"
        const val SILENT_URI = "silent"

        private const val CHANNEL_ID = "lingchat_alarm"

        fun start(context: Context, id: String) {
            val intent = Intent(context, AlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }

        private fun notificationId(id: String): Int = id.hashCode() and 0x7fffffff
    }
}
