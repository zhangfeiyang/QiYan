package com.zhongguoxiangqi.ui.game

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ui.settings.AppSettings
import kotlin.concurrent.thread

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
 *
 * 第四条是后来用 ANR 换来的: **准备播放器不能在主线程做**.
 * `MediaPlayer.create()` 内部会同步 `prepare()` —— 初始化解码器 + 读出整段
 * 音频, 这是一次实打实的阻塞 IO. 它原来直接写在 `onResume()` 里, 模拟器上
 * 抓到的 ANR 主线程栈就停在 `MediaPlayer.prepare`(dropbox 里两次 ANR 都是
 * 同一条栈). 低端机上只会更慢. 现在 resume() 立即返回, 播放器在后台线程
 * 备好之后再回主线程 start.
 */
class BgmPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null

    /**
     * 用户此刻是不是想听到声音(界面在前台且没关设置).
     *
     * 有了后台准备就必须有这个意图标记: 播放器备好时界面可能已经 pause 或
     * finish 了, 那时候绝不能再 start —— 否则退出对局后音乐还在响.
     */
    private var wantPlaying = false

    /** 防止 onResume 连续触发时并发准备出好几个播放器 */
    private var preparing = false

    /** 用户在设置里关掉后, 即使 Activity 恢复也不要再响 */
    private fun enabled(): Boolean = AppSettings(context).bgmEnabled

    fun resume() {
        if (!enabled()) {
            stop()
            return
        }
        wantPlaying = true

        player?.let { p ->
            if (!p.isPlaying) runCatching { p.start() }
            return
        }
        if (preparing) return
        preparing = true

        thread(name = "bgm-prepare", isDaemon = true) {
            val prepared = create()
            main.post {
                preparing = false
                if (prepared == null) return@post
                // 备好的这段时间里界面可能已经退到后台, 或者用户关掉了背景音乐
                if (!wantPlaying || !enabled()) {
                    runCatching { prepared.release() }
                    return@post
                }
                player = prepared
                runCatching { prepared.start() }
            }
        }
    }

    fun pause() {
        wantPlaying = false
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun stop() {
        wantPlaying = false
        runCatching { player?.stop() }
        release()
    }

    fun release() {
        wantPlaying = false
        runCatching { player?.release() }
        player = null
    }

    /** 只在后台线程调用: 这里面是同步 prepare(). 返回的播放器还没 start. */
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
    }.getOrNull()

    private companion object {
        const val BG_VOLUME = 0.22f
    }
}
