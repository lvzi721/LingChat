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
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import app.tauri.plugin.JSObject
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val EXTRA_ACK_ENABLED = "ackEnabled"
        private const val EXTRA_ACK_TEXT = "ackText"
        private const val EXTRA_ACK_AUDIO = "ackAudioPath"
        private const val PREFS_NAME = "voice_wake"
        private const val PREF_PHRASE = "phrase"
        private const val PREF_ACK_ENABLED = "ackEnabled"
        private const val PREF_ACK_TEXT = "ackText"
        private const val PREF_ACK_AUDIO = "ackAudioPath"
        private const val ASSET_DIR = "kws"
        private const val MODEL_DIR = "kws"
        private const val ASR_ASSET_DIR = "asr"
        private const val ASR_MODEL_DIR = "asr"
        private const val ASR_MODEL = "model.int8.onnx"
        private const val ASR_TOKENS = "tokens.txt"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600 // 100ms
        private const val MAX_COMMAND_MS = 10_000L
        private const val FALLBACK_RESUME_MS = 90_000L
        private const val ACK_TIMEOUT_MS = 2_500L
        private const val MEDIA_LONG_PRESS_MS = 600L

        private const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val TOKENS = "tokens.txt"
        private const val KEYWORDS = "keywords.txt"

        private val MODEL_FILES =
            listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS)
        private val ASR_FILES = listOf(ASR_MODEL, ASR_TOKENS)

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
        var ackEnabled: Boolean = true
            private set

        @Volatile
        var ackText: String = "我在"
            private set

        @Volatile
        var ackAudioPath: String? = null
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

        /** 是否正持有麦克风；暂停唤醒后要等它释放再交还给前端录音。 */
        @Volatile
        var micBusy: Boolean = false
            private set

        /** 前端不可用时暂存的一次识别结果，等界面回来再取走。 */
        @Volatile
        var pendingText: String? = null
            private set

        fun takePendingText(): String? {
            val text = pendingText
            pendingText = null
            return text
        }

        /** APK 内是否打进了指令识别模型。 */
        fun detectAsrPresent(context: Context): Boolean =
            try {
                (context.assets.list(ASR_ASSET_DIR)?.size ?: 0) >= ASR_FILES.size
            } catch (_: Throwable) {
                false
            }

        /** APK 内是否打进了唤醒模型（无需启动服务即可判断）。 */
        fun detectModelPresent(context: Context): Boolean =
            try {
                (context.assets.list(ASSET_DIR)?.size ?: 0) >= MODEL_FILES.size
            } catch (_: Throwable) {
                false
            }

        fun start(
            context: Context,
            phrase: String,
            ackOn: Boolean,
            ackLine: String,
            ackAudio: String?,
        ) {
            isRunning = true
            isSuspended = false
            try {
                val intent = Intent(context, VoiceWakeService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PHRASE, phrase)
                    .putExtra(EXTRA_ACK_ENABLED, ackOn)
                    .putExtra(EXTRA_ACK_TEXT, ackLine)
                    .putExtra(EXTRA_ACK_AUDIO, ackAudio)
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaSession: MediaSession? = null
    private var mediaKeyDownAt = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        modelPresent = detectModelPresent(this)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentPhrase = prefs.getString(PREF_PHRASE, currentPhrase).orEmpty().ifBlank { "灵灵" }
        ackEnabled = prefs.getBoolean(PREF_ACK_ENABLED, true)
        ackText = prefs.getString(PREF_ACK_TEXT, ackText).orEmpty().ifBlank { "我在" }
        ackAudioPath = prefs.getString(PREF_ACK_AUDIO, null)?.takeIf { it.isNotBlank() }
        if (ackEnabled) ensureAckEngine()
        createNotificationChannel()
        startForegroundCompat(buildNotification("准备中"))
        acquireWakeLock()
        setupMediaSession()
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
                val ackOn = intent.getBooleanExtra(EXTRA_ACK_ENABLED, ackEnabled)
                val ackLine = intent.getStringExtra(EXTRA_ACK_TEXT).orEmpty().trim()
                val ackAudio = intent.getStringExtra(EXTRA_ACK_AUDIO)?.takeIf { it.isNotBlank() }
                if (phrase.isNotBlank() && phrase != currentPhrase) {
                    currentPhrase = phrase
                }
                ackEnabled = ackOn
                if (ackLine.isNotBlank()) ackText = ackLine
                ackAudioPath = ackAudio
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_PHRASE, currentPhrase)
                    .putBoolean(PREF_ACK_ENABLED, ackEnabled)
                    .putString(PREF_ACK_TEXT, ackText)
                    .putString(PREF_ACK_AUDIO, ackAudioPath)
                    .apply()
                if (ackEnabled) ensureAckEngine()
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
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
            // ignore
        }
        tts = null
        ttsReady = false
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Throwable) {
            // ignore
        }
        mediaSession = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 耳机媒体键 ──────────────────────────────────────────────

    /**
     * 注册一个常驻 MediaSession，让蓝牙耳机的播放/暂停键优先送到 LingChat。
     *
     * 只消费「长按约 0.6 秒」：短按返回 false 交回系统，尽量不影响正常播放控制。
     * 代价是开启唤醒模式期间，耳机播放键的归属会变成 LingChat。
     */
    private fun setupMediaSession() {
        if (mediaSession != null) return
        val session = MediaSession(this, "LingChatWake")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event =
                        mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    return handleMediaKey(event)
                }
            },
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE,
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build(),
        )
        session.isActive = true
        mediaSession = session
    }

    private fun handleMediaKey(event: KeyEvent?): Boolean {
        if (event == null) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> Unit
            else -> return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                mediaKeyDownAt = SystemClock.elapsedRealtime()
                return false
            }
            KeyEvent.ACTION_UP -> {
                val held = SystemClock.elapsedRealtime() - mediaKeyDownAt
                mediaKeyDownAt = 0L
                if (held >= MEDIA_LONG_PRESS_MS) {
                    triggerByMediaKey()
                    return true
                }
                return false
            }
        }
        return false
    }

    /** 耳机长按：释放 KWS 麦克风后走与唤醒词相同的「应答 + 本地识别」流程。 */
    private fun triggerByMediaKey() {
        if (!isRunning) return
        requestStop()
        mainHandler.post { onKeywordDetected("耳机键") }
    }

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
            micBusy = true
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
            micBusy = false
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
        lastDetectionSerial += 1
        lastDetectionAt = System.currentTimeMillis()
        lastDetectionTranscript = text

        // 应答与前端状态无关：锁屏时 Activity 可能已被回收（hasListener=false），
        // 但用户仍然需要听到"我在"这一声确认。
        playAck {
            Thread({
                // 应答刚播完，先让音频路由从播放切回采集（蓝牙耳机尤其明显）。
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                // 与「测试本地识别」共用同一实现，避免两条路径行为不一致。
                val (spoken, error) = LocalAsr.recognize(applicationContext, MAX_COMMAND_MS)
                lastError = error?.takeIf { it.isNotBlank() }
                mainHandler.post { finishCommand(text, spoken) }
            }, "voice-wake-asr").start()
        }
    }

    /**
     * 唤醒后的指令识别收尾：优先把文字交给前端；前端不在就暂存，等界面回来再取。
     * 本地识别失败/没听到内容时退回旧流程（交给前端录音识别）。
     */
    private fun finishCommand(wakePhrase: String, spoken: String?) {
        if (isRunning.not()) return

        if (!spoken.isNullOrBlank()) {
            updateNotification("识别：${spoken.take(24)}")
            if (VoiceWakeEventBridge.hasListener("recognized")) {
                VoiceWakeEventBridge.emit(
                    "recognized",
                    JSObject().apply {
                        put("text", spoken)
                        put("phrase", wakePhrase)
                        put("timestamp", System.currentTimeMillis())
                    },
                )
            } else {
                pendingText = spoken
            }
            isSuspended = false
            startListening()
            return
        }

        val error = lastError ?: "asr_empty"

        // 只有「模型缺失」才回落到前端录音识别；锁屏下前端本来就不可用，
        // 其余失败一律写明原因并继续监听，避免界面被"唤醒暂停"覆盖。
        if (error == "asr_model_missing" && VoiceWakeEventBridge.hasListener("detected")) {
            isSuspended = true
            cancelFallbackResume()
            updateNotification("本地模型缺失，改由界面识别")
            VoiceWakeEventBridge.emit(
                "detected",
                JSObject().apply {
                    put("phrase", currentPhrase)
                    put("transcript", wakePhrase)
                    put("timestamp", lastDetectionAt)
                    put("serial", lastDetectionSerial)
                },
            )
            scheduleFallbackResume()
            return
        }

        isSuspended = false
        updateNotification("未识别到语音（$error），继续监听")
        startListening()
    }

    /**
     * 播放唤醒应答：优先用「保存设置时用角色本地音色预合成的缓存音频」，
     * 缓存缺失或播放失败时退回系统 TTS，都不可用则直接继续（不阻塞唤醒）。
     */
    private fun playAck(then: () -> Unit) {
        if (!ackEnabled) {
            then()
            return
        }
        val finished = AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) {
                mainHandler.post(then)
            }
        }

        val path = ackAudioPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile && file.length() > 0L) {
                try {
                    val player = MediaPlayer()
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    player.setDataSource(path)
                    player.setOnCompletionListener { p ->
                        try {
                            p.release()
                        } catch (_: Throwable) {
                            // ignore
                        }
                        finish()
                    }
                    player.setOnErrorListener { p, _, _ ->
                        try {
                            p.release()
                        } catch (_: Throwable) {
                            // ignore
                        }
                        speakSystemAck(finish)
                        true
                    }
                    player.prepare()
                    player.start()
                    // 兜底：个别机型 onCompletion 不回调，避免永久卡住唤醒流程
                    mainHandler.postDelayed(finish, 8_000L)
                    return
                } catch (_: Throwable) {
                    // 落到系统 TTS
                }
            }
        }
        speakSystemAck(finish)
    }

    private fun speakSystemAck(onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ttsReady) {
            onDone()
            return
        }
        val spoken = AtomicBoolean(false)
        val once = {
            if (spoken.compareAndSet(false, true)) mainHandler.post(onDone)
        }
        try {
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        once()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        once()
                    }
                },
            )
            val result = engine.speak(ackText, TextToSpeech.QUEUE_FLUSH, null, "wake-ack")
            if (result != TextToSpeech.SUCCESS) {
                once()
                return
            }
            mainHandler.postDelayed({ once() }, ACK_TIMEOUT_MS)
        } catch (_: Throwable) {
            once()
        }
    }

    private fun ensureAckEngine() {
        if (tts != null) return
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                try {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                } catch (_: Throwable) {
                    // 语言设置失败时用引擎默认语言
                }
            }
        }
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

    private fun ensureAsrFiles(): File? {
        val dir = File(filesDir, ASR_MODEL_DIR)
        return try {
            dir.mkdirs()
            for (name in ASR_FILES) {
                val target = File(dir, name)
                if (!target.isFile || target.length() == 0L) {
                    assets.open("$ASR_ASSET_DIR/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            dir
        } catch (_: Throwable) {
            lastError = "asr_model_missing"
            null
        }
    }

    /**
     * 唤醒后的一段指令录音 → 本地流式识别（流式 zipformer2 CTC）。
     *
     * 用 sherpa 自带的端点检测判断一句话结束；最长 10 秒兜底。识别结果为空
     * （没说话/太吵）时返回 null，由调用方退回旧的前端录音流程。
     */
    private fun captureCommand(): String? {
        val dir = ensureAsrFiles() ?: return null
        var recognizer: OnlineRecognizer? = null
        var record: AudioRecord? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        return try {
            val engine = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                            model = File(dir, ASR_MODEL).absolutePath,
                        ),
                        tokens = File(dir, ASR_TOKENS).absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer2",
                    ),
                    enableEndpoint = true,
                ),
            )
            recognizer = engine
            val st = engine.createStream()
            stream = st

            val rec = createRecord() ?: return null
            record = rec
            recordRef = rec
            rec.startRecording()
            micBusy = true
            notifyInMain("正在听你说")

            val buffer = ShortArray(CHUNK_SAMPLES)
            val deadline = System.currentTimeMillis() + MAX_COMMAND_MS
            while (System.currentTimeMillis() < deadline) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                st.acceptWaveform(samples, SAMPLE_RATE)
                while (engine.isReady(st)) engine.decode(st)
                if (engine.isEndpoint(st)) break
            }
            val text = engine.getResult(st).text.trim()
            if (text.isEmpty()) {
                lastError = "asr_empty"
            }
            text
        } catch (error: Throwable) {
            lastError = (error.message ?: "asr_failed").take(160)
            null
        } finally {
            try {
                record?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                }
            } catch (_: Throwable) {
                // ignore
            }
            try {
                record?.release()
            } catch (_: Throwable) {
                // ignore
            }
            recordRef = null
            micBusy = false
            try {
                stream?.release()
            } catch (_: Throwable) {
                // ignore
            }
            try {
                recognizer?.release()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

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

