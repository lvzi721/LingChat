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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import app.tauri.plugin.JSObject
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * 锁屏常驻的本地唤醒词服务。
 *
 * 使用 sherpa-onnx 的中文 KWS 模型在设备本地做关键词检测：完全离线、不依赖
 * 系统语音识别（华为等机型没有 Android 标准设备端识别接口）。检测到唤醒词后
 * 服务会释放麦克风并通知前端接管一轮完整录音。
 */
class VoiceWakeService : Service() {
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
        private const val ASSET_DIR = "kws"
        private const val MODEL_DIR = "kws"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600 // 100ms
        private const val FALLBACK_RESUME_MS = 90_000L

        private const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val TOKENS = "tokens.txt"
        private const val KEYWORDS = "keywords.txt"

        private val MODEL_FILES =
            listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS)

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
        var engineReady: Boolean = false
            private set

        @Volatile
        var modelPresent: Boolean = false
            private set

        @Volatile
        var keywordTokens: String? = null
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

        /** APK 内是否打进了唤醒模型（无需启动服务即可判断）。 */
        fun detectModelPresent(context: Context): Boolean =
            try {
                (context.assets.list(ASSET_DIR)?.size ?: 0) >= MODEL_FILES.size
            } catch (_: Throwable) {
                false
            }

        fun start(context: Context, phrase: String) {
            isRunning = true
            isSuspended = false
            try {
                val intent = Intent(context, VoiceWakeService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PHRASE, phrase)
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
            engineReady = false
            context.stopService(Intent(context, VoiceWakeService::class.java))
        }

        fun setPaused(context: Context, paused: Boolean) {
            if (!isRunning) return
            val action = if (paused) ACTION_PAUSE else ACTION_RESUME
            try {
                context.startService(Intent(context, VoiceWakeService::class.java).setAction(action))
            } catch (_: Throwable) {
                // 服务已被回收：由前端下次启动时重新拉起
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenThread: Thread? = null
    @Volatile
    private var listening = false
    @Volatile
    private var recordRef: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var fallbackResume: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        modelPresent = detectModelPresent(this)
        currentPhrase = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_PHRASE, currentPhrase)
            .orEmpty()
            .ifBlank { "灵灵" }
        createNotificationChannel()
        startForegroundCompat(buildNotification("准备中"))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                suspendListening()
                updateNotification("语音唤醒已暂停")
                return START_STICKY
            }
            ACTION_RESUME -> {
                resumeListening()
                return START_STICKY
            }
            ACTION_START -> {
                val phrase = intent.getStringExtra(EXTRA_PHRASE).orEmpty().trim()
                if (phrase.isNotBlank() && phrase != currentPhrase) {
                    currentPhrase = phrase
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_PHRASE, phrase)
                        .apply()
                }
                isSuspended = false
                cancelFallbackResume()
                startListening()
                return START_STICKY
            }
        }
        if (!isSuspended) startListening()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户显式开启了该模式：划掉最近任务时保留前台服务，直到系统回收。
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        engineReady = false
        cancelFallbackResume()
        mainHandler.removeCallbacksAndMessages(null)
        requestStop()
        listenThread?.let { thread ->
            if (thread.isAlive && thread !== Thread.currentThread()) {
                try {
                    thread.join(1500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        listenThread = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 监听生命周期 ────────────────────────────────────────────

    private fun startListening() {
        if (listening) return
        val previous = listenThread
        if (previous != null && previous.isAlive && previous !== Thread.currentThread()) {
            try {
                previous.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        listening = true
        listenThread = Thread({ runLoop() }, "voice-wake").also { it.start() }
    }

    private fun requestStop() {
        listening = false
        try {
            recordRef?.stop()
        } catch (_: Throwable) {
            // 已停止或已释放
        }
    }

    private fun suspendListening() {
        isSuspended = true
        cancelFallbackResume()
        requestStop()
    }

    private fun resumeListening() {
        isSuspended = false
        cancelFallbackResume()
        startListening()
    }

    private fun scheduleFallbackResume() {
        cancelFallbackResume()
        val runnable = Runnable { if (isSuspended && isRunning) resumeListening() }
        fallbackResume = runnable
        mainHandler.postDelayed(runnable, FALLBACK_RESUME_MS)
    }

    private fun cancelFallbackResume() {
        fallbackResume?.let { mainHandler.removeCallbacks(it) }
        fallbackResume = null
    }

    // ── 检测主循环（工作线程） ──────────────────────────────────

    private fun runLoop() {
        var spotter: KeywordSpotter? = null
        var detected: String? = null
        try {
            val dir = ensureModelFiles() ?: return
            val known = loadTokens(File(dir, TOKENS))
            val line = PinyinKeywords.buildKeywordLine(currentPhrase)
            val unmapped = PinyinKeywords.findUnmappedToken(line, known)
            if (unmapped != null) {
                lastError = "unmapped_token:$unmapped"
                notifyInMain("唤醒词无法映射到模型词表")
                return
            }
            keywordTokens = line

            val engine = KeywordSpotter(config = buildConfig(dir))
            spotter = engine
            engineReady = true
            lastError = null

            val stream = engine.createStream(line)
            val record = createRecord()
            if (record == null) {
                lastError = "audio_init_failed"
                notifyInMain("无法初始化麦克风")
                return
            }
            recordRef = record
            record.startRecording()
            notifyInMain("正在监听“$currentPhrase”")

            val buffer = ShortArray(CHUNK_SAMPLES)
            while (listening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                    val result = engine.getResult(stream)
                    if (result.keyword.isNotEmpty()) {
                        detected = PinyinKeywords.displayText(result.keyword)
                        engine.reset(stream)
                        break
                    }
                }
                if (detected != null) break
            }
        } catch (error: Throwable) {
            lastError = (error.message ?: "engine_failed").take(160)
            notifyInMain("本地唤醒引擎异常")
        } finally {
            try {
                recordRef?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            } catch (_: Throwable) {
                // ignore
            }
            try {
                recordRef?.release()
            } catch (_: Throwable) {
                // ignore
            }
            recordRef = null
            engineReady = false
            try {
                spotter?.release()
            } catch (_: Throwable) {
                // ignore
            }
        }

        detected?.let { text -> mainHandler.post { onKeywordDetected(text) } }
    }

    private fun onKeywordDetected(text: String) {
        isSuspended = true
        cancelFallbackResume()
        lastDetectionSerial += 1
        lastDetectionAt = System.currentTimeMillis()
        lastDetectionTranscript = text

        if (!VoiceWakeEventBridge.hasListener("detected")) {
            updateNotification("检测到“$text”，但 LingChat 界面未就绪")
            scheduleFallbackResume()
            return
        }

        updateNotification("已唤醒，等待 LingChat 接收")
        VoiceWakeEventBridge.emit(
            "detected",
            JSObject().apply {
                put("phrase", currentPhrase)
                put("transcript", text)
                put("timestamp", lastDetectionAt)
                put("serial", lastDetectionSerial)
            },
        )
        scheduleFallbackResume()
    }

    private fun notifyInMain(message: String) {
        mainHandler.post { if (isRunning) updateNotification(message) }
    }

    // ── 模型与音频 ──────────────────────────────────────────────

    private fun ensureModelFiles(): File? {
        val dir = File(filesDir, MODEL_DIR)
        return try {
            dir.mkdirs()
            for (name in MODEL_FILES) {
                val target = File(dir, name)
                if (!target.isFile || target.length() == 0L) {
                    assets.open("$ASSET_DIR/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            modelPresent = true
            dir
        } catch (error: Throwable) {
            modelPresent = false
            lastError = "model_missing"
            notifyInMain("缺少本地唤醒模型")
            null
        }
    }

    private fun loadTokens(file: File): Set<String> =
        file.readLines()
            .mapNotNull { it.trim().split(' ').firstOrNull() }
            .filter { it.isNotEmpty() && !it.startsWith("<") && !it.startsWith("#") }
            .toSet()

    private fun buildConfig(dir: File) = KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(dir, ENCODER).absolutePath,
                decoder = File(dir, DECODER).absolutePath,
                joiner = File(dir, JOINER).absolutePath,
            ),
            tokens = File(dir, TOKENS).absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer2",
        ),
        maxActivePaths = 4,
        keywordsFile = File(dir, KEYWORDS).absolutePath,
        keywordsScore = 1.5f,
        keywordsThreshold = 0.25f,
        numTrailingBlanks = 2,
    )

    private fun createRecord(): AudioRecord? {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "permission_required"
            return null
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, CHUNK_SAMPLES * 4),
            )
        } catch (_: Throwable) {
            null
        } ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }

    // ── 通知与电源 ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LingChat 语音唤醒",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "锁屏时持续监听本地唤醒词"
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
                    stopIntent,
                ).build(),
            )
        if (contentIntent != null) builder.setContentIntent(contentIntent)
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 常驻监听必须让 CPU 保持可运行：设备进入休眠后 AudioRecord 往往停止回调，
     * 唤醒词就会失效。代价是待机功耗略高，仅在用户主动开启时持有。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:voice-wake",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }
}

