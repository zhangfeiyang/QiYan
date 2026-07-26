package com.zhongguoxiangqi.ui.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.zhongguoxiangqi.ui.settings.AppSettings
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 落子的听觉/触觉反馈.
 *
 * 音效直接用代码合成一段短促的"木质敲击"波形, 不依赖任何音频资源文件 ——
 * 项目里没有 res/raw 音频, 但设置里有音效开关, 总不能是个摆设.
 *
 * 使用方: Activity 在 onDestroy 里调用 [release].
 */
class GameFeedback(context: Context) {

    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** 普通落子: 干脆的高音敲击 */
    private val moveTrack by lazy { createTrack(buildClick(baseHz = 900.0, decay = 55.0, gain = 0.55)) }

    /** 吃子: 更低更重的一声 */
    private val captureTrack by lazy { createTrack(buildClick(baseHz = 520.0, decay = 32.0, gain = 0.85)) }

    /**
     * 走子反馈.
     * @param captured 本步是否吃子(吃子用更重的音效与震动)
     */
    fun onMove(captured: Boolean) {
        if (settings.soundEnabled) playClick(if (captured) captureTrack else moveTrack)
        if (settings.vibrateEnabled) vibrate(if (captured) CAPTURE_VIBRATION_MS else MOVE_VIBRATION_MS)
    }

    /** 被将军: 两下短震 + 吃子音 */
    fun onCheck() {
        if (settings.soundEnabled) playClick(captureTrack)
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        runCatching {
            val pattern = longArrayOf(0, 30, 80, 30)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    fun release() {
        runCatching { moveTrack?.release() }
        runCatching { captureTrack?.release() }
    }

    // ====== 内部 ======

    private fun playClick(track: AudioTrack?) {
        val t = track ?: return
        runCatching {
            // MODE_STATIC 重播的标准做法: 停下 -> 把播放头移回开头 -> 再播
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    private fun vibrate(durationMs: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /**
     * 合成一声敲击: 基频加一个八度泛音, 指数衰减包络.
     * 衰减越快听感越"硬", 像棋子磕在木盘上.
     */
    private fun buildClick(baseHz: Double, decay: Double, gain: Double): ShortArray {
        val frameCount = (SAMPLE_RATE * CLICK_SECONDS).toInt()
        val samples = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = exp(-decay * t)
            val wave = sin(2 * PI * baseHz * t) + 0.5 * sin(2 * PI * baseHz * 2 * t)
            val value = envelope * wave * gain * Short.MAX_VALUE / 1.5
            samples[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun createTrack(samples: ShortArray): AudioTrack? = runCatching {
        val sizeBytes = samples.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(sizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track
    }.getOrNull()

    private companion object {
        const val SAMPLE_RATE = 44100
        const val CLICK_SECONDS = 0.12
        const val MOVE_VIBRATION_MS = 18L
        const val CAPTURE_VIBRATION_MS = 35L
    }
}
