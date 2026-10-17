package com.noiq.lingchat.voicewake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File

/**
 * 内置中文流式 ASR（streaming zipformer2 CTC，int8）。
 *
 * 唤醒服务与「手动麦克风」共用这一个实现：模型随 APK 打包，运行时拷贝到
 * filesDir 后按文件路径加载，完全不依赖云端识别服务商。
 */
object LocalAsr {
    private const val ASSET_DIR = "asr"
    private const val MODEL_DIR = "asr"
    private const val MODEL = "model.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SAMPLES = 1600 // 100ms

    fun isPresent(context: Context): Boolean =
        try {
            (context.assets.list(ASSET_DIR)?.size ?: 0) >= 2
        } catch (_: Throwable) {
            false
        }

    /** @return (text, error)；成功时 error 为 null。 */
    fun recognize(context: Context, maxMs: Long): Pair<String?, String?> {
        val dir = ensureModel(context) ?: return null to "asr_model_missing"
        var record: AudioRecord? = null
        var recognizer: OnlineRecognizer? = null
        return try {
            val engine = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                            model = File(dir, MODEL).absolutePath,
                        ),
                        tokens = File(dir, TOKENS).absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer2",
                    ),
                    enableEndpoint = true,
                ),
            )
            recognizer = engine
            val stream = engine.createStream()

            val rec = createRecord(context) ?: return null to "audio_init_failed"
            record = rec
            rec.startRecording()

            val buffer = ShortArray(CHUNK_SAMPLES)
            val deadline = System.currentTimeMillis() + maxMs
            while (System.currentTimeMillis() < deadline) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (engine.isReady(stream)) engine.decode(stream)
                if (engine.isEndpoint(stream)) break
            }
            val text = engine.getResult(stream).text.trim()
            try {
                stream.release()
            } catch (_: Throwable) {
                // ignore
            }
            if (text.isEmpty()) null to "asr_empty" else text to null
        } catch (error: Throwable) {
            null to (error.message ?: "asr_failed")
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
            try {
                recognizer?.release()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun ensureModel(context: Context): File? {
        val dir = File(context.filesDir, MODEL_DIR)
        return try {
            dir.mkdirs()
            for (name in listOf(MODEL, TOKENS)) {
                val target = File(dir, name)
                if (!target.isFile || target.length() == 0L) {
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            dir
        } catch (_: Throwable) {
            null
        }
    }

    private fun createRecord(context: Context): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
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
}

