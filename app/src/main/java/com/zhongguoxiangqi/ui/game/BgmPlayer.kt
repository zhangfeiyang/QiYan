package com.zhongguoxiangqi.ui.game

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ui.settings.AppSettings
import com.zhongguoxiangqi.ui.settings.SettingsActivity
import kotlin.concurrent.thread

/** Quiet, original instrumental music. Call resume/pause/release with the host lifecycle. */
class BgmPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val settings = AppSettings(appContext)
    private val prefs = appContext.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var player: MediaPlayer? = null
    private var fade: ValueAnimator? = null
    private var volume = 0f
    private var foreground = false
    private var hasFocus = false
    private var ducked = false
    private var selfDucked = false
    private var selfDuckGeneration = 0
    private var preparing = false
    private var released = false
    private var generation = 0

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (!released) when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (foreground && settings.bgmEnabled && settings.bgmVolume > 0) {
                        hasFocus = true
                        ducked = false
                        startOrPrepare()
                    } else {
                        // A queued focus callback can arrive after the Activity has paused.
                        abandonFocus()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    fadeTo(targetVolume(), 180L)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    hasFocus = false
                    pausePlayer()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    pausePlayer()
                    abandonFocus()
                }
            }
        }, main)
        .build()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsActivity.KEY_BGM || key == SettingsActivity.KEY_BGM_VOLUME || key == null) {
            main.post {
                if (!released && foreground) {
                    if (settings.bgmEnabled && settings.bgmVolume > 0) {
                        resume()
                    } else {
                        fadeTo(0f, 220L) {
                            pausePlayer()
                            abandonFocus()
                        }
                    }
                }
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun resume() {
        if (released) return
        foreground = true
        if (!settings.bgmEnabled || settings.bgmVolume == 0) {
            pausePlayer()
            abandonFocus()
            return
        }
        if (!hasFocus) {
            hasFocus = runCatching {
                audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
        }
        if (hasFocus) startOrPrepare()
    }

    private fun startOrPrepare() {
        player?.let { p ->
            runCatching { if (!p.isPlaying) p.start() }
                .onSuccess { fadeTo(targetVolume(), 900L) }
            return
        }
        if (preparing) return
        preparing = true
        val ticket = generation
        // Decoder preparation stays off the main thread, including on slow devices.
        thread(name = "bgm-prepare", isDaemon = true) {
            val prepared = createPlayer()
            main.post {
                if (released || ticket != generation) {
                    runCatching { prepared?.release() }
                    return@post
                }
                preparing = false
                if (prepared == null) {
                    abandonFocus()
                    return@post
                }
                player = prepared
                if (foreground && hasFocus && settings.bgmEnabled && settings.bgmVolume > 0) {
                    startOrPrepare()
                }
            }
        }
    }

    /** Silence immediately on backgrounding; a delayed callback cannot restart the screen. */
    fun pause() {
        foreground = false
        pausePlayer()
        abandonFocus()
    }

    fun stop() = pause()

    fun release() {
        if (released) return
        pause()
        released = true
        generation++
        preparing = false
        main.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        runCatching { player?.release() }
        player = null
    }

    private fun pausePlayer() {
        fade?.removeAllListeners()
        fade?.cancel()
        fade = null
        volume = 0f
        runCatching {
            player?.setVolume(0f, 0f)
            player?.takeIf { it.isPlaying }?.pause()
        }
    }

    private fun abandonFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        hasFocus = false
        ducked = false
    }

    private fun targetVolume(): Float =
        settings.bgmVolume / 100f * MAX_VOLUME * if (ducked || selfDucked) 0.2f else 1f

    /**
     * 语音播报期间自行压低 BGM (与系统音频焦点的 duck 相互独立).
     *
     * 连续多段语音共用一次 duck: generation 令牌保证只有最后一段的
     * 恢复回调生效, 前一段的到期回调不会把音量提前抬回去.
     */
    fun duckFor(ms: Long) {
        if (released || player == null) return
        val gen = ++selfDuckGeneration
        if (!selfDucked) {
            selfDucked = true
            fadeTo(targetVolume(), 180L)
        }
        main.postDelayed({
            if (!released && gen == selfDuckGeneration) {
                selfDucked = false
                fadeTo(targetVolume(), 400L)
            }
        }, ms)
    }

    private fun fadeTo(target: Float, durationMs: Long, after: (() -> Unit)? = null) {
        fade?.removeAllListeners()
        fade?.cancel()
        if (player == null) {
            after?.invoke()
            return
        }
        fade = ValueAnimator.ofFloat(volume, target).apply {
            duration = durationMs
            addUpdateListener {
                volume = it.animatedValue as Float
                runCatching { player?.setVolume(volume, volume) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { after?.invoke() }
            })
            start()
        }
    }

    private fun createPlayer(): MediaPlayer? {
        val candidate = runCatching { MediaPlayer() }.getOrNull() ?: return null
        return runCatching {
            candidate.setAudioAttributes(attributes)
            appContext.resources.openRawResourceFd(R.raw.bgm_ambient).use { fd ->
                candidate.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            candidate.isLooping = true
            candidate.setVolume(0f, 0f)
            candidate.prepare()
            candidate
        }.getOrElse {
            runCatching { candidate.release() }
            null
        }
    }

    private companion object {
        const val MAX_VOLUME = 0.45f
    }
}
