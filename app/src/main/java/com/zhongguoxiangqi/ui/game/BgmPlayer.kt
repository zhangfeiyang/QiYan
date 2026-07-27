package com.zhongguoxiangqi.ui.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 背景音乐.
 *
 * 曲子是随项目一起生成的原创五声音阶环境音 (res/raw/bgm_ambient.ogg),
 * 37 秒无缝循环, 不引入任何第三方音源, 因此没有授权问题.
 *
 * 三条约束决定了这个类的样子:
 *  1. **音量要压得很低**. 它是背景, 落子/吃子/将军的提示音必须压得过它,
 *     否则用户会漏掉真正要紧的反馈.
 *  2. **跟随界面生命周期**. 切到后台还在响是最容易被卸载的那类毛病.
 *  3. **随时可关**. 设置里关掉后立刻停, 不用重启 App.
 */
class BgmPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    /** 用户在设置里关掉后, 即使 Activity 恢复也不要再响 */
    private fun enabled(): Boolean = AppSettings(context).bgmEnabled

    fun resume() {
        if (!enabled()) {
            stop()
            return
        }
        val p = player ?: create() ?: return
        if (!p.isPlaying) runCatching { p.start() }
    }

    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun stop() {
        runCatching { player?.stop() }
        release()
    }

    fun release() {
        runCatching { player?.release() }
        player = null
    }

    private fun create(): MediaPlayer? = runCatching {
        MediaPlayer.create(context, R.raw.bgm_ambient)?.apply {
            isLooping = true
            // 0.22 是试出来的: 再高就会盖住落子声, 再低基本等于没有
            setVolume(BG_VOLUME, BG_VOLUME)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        }
    }.getOrNull().also { player = it }

    private companion object {
        const val BG_VOLUME = 0.22f
    }
}
