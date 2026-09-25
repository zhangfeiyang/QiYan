package com.zhongguoxiangqi.ui.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 听觉与触觉反馈 (JJ象棋风格原生专业音效系统).
 *
 * 采用 SoundPool 零延迟播放真实实木落子敲击、沉重吃子对撞、
 * 威武战锣「将军！」、胜局凯歌「绝杀！」「大获全胜！」等殿堂级音频资源，
 * 彻底告别系统 TTS 的生硬机械音。
 *
 * 2026-09 配音补齐: 开局「红方先行」、悔棋、残局对错、闯关过关、
 * 拍照快门/识别成败、实时锁定、对手走子独立音色; 语音类音效播放前
 * 通过 [onVoiceClip] 通知宿主把 BGM 压低 (ducking), 避免音乐盖过人声.
 *
 * @param light 轻量模式: 只加载功能页 (拍照识别/实时支招) 用到的 5 个短音效,
 *              不为一个提示音背上 30+ 个音频的 SoundPool.
 */
class GameFeedback(context: Context, private val light: Boolean = false) {

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

    private val soundPool: SoundPool = runCatching {
        SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }.getOrElse {
        @Suppress("DEPRECATION")
        SoundPool(6, AudioManager.STREAM_MUSIC, 0)
    }

    private var soundMove = 0
    private var soundMoveOpponent = 0
    private var soundCapture = 0
    private var soundCheck = 0
    private var soundCheckmate = 0
    private var soundWin = 0
    private var soundLoss = 0
    private var soundDraw = 0
    private var soundGameStart = 0
    private var soundPuzzleSolved = 0
    private var soundPuzzleFail = 0
    private var soundCampaignClear = 0
    private var soundUndo = 0
    private var soundShutter = 0
    private var soundChime = 0
    private var soundWrong = 0
    private var soundLock = 0
    private val tacticSounds = mutableMapOf<String, Int>()
    private val tacticWinSounds = mutableMapOf<String, Int>()

    /**
     * 语音类音效开播前回调, 参数是估计的语音时长 (ms).
     * 宿主 (GameActivity) 用它把 BGM duck 下来, 人声不被音乐盖住.
     */
    var onVoiceClip: ((Long) -> Unit)? = null

    init {
        runCatching {
            // 轻量模式只装功能页短音效
            soundUndo = soundPool.load(appContext, R.raw.sfx_undo, 1)
            soundShutter = soundPool.load(appContext, R.raw.sfx_shutter, 1)
            soundChime = soundPool.load(appContext, R.raw.sfx_chime, 1)
            soundWrong = soundPool.load(appContext, R.raw.sfx_wrong, 1)
            soundLock = soundPool.load(appContext, R.raw.sfx_lock, 1)
            if (light) return@runCatching

            soundMove = soundPool.load(appContext, R.raw.sfx_move, 1)
            soundMoveOpponent = soundPool.load(appContext, R.raw.sfx_move_opponent, 1)
            soundCapture = soundPool.load(appContext, R.raw.sfx_capture, 1)
            soundCheck = soundPool.load(appContext, R.raw.sfx_check, 1)
            soundCheckmate = soundPool.load(appContext, R.raw.sfx_checkmate, 1)
            soundWin = soundPool.load(appContext, R.raw.sfx_win, 1)
            soundLoss = soundPool.load(appContext, R.raw.sfx_loss, 1)
            soundDraw = soundPool.load(appContext, R.raw.sfx_draw, 1)
            soundGameStart = soundPool.load(appContext, R.raw.sfx_game_start, 1)
            soundPuzzleSolved = soundPool.load(appContext, R.raw.sfx_puzzle_solved, 1)
            soundPuzzleFail = soundPool.load(appContext, R.raw.sfx_puzzle_fail, 1)
            soundCampaignClear = soundPool.load(appContext, R.raw.sfx_campaign_clear, 1)

            tacticSounds["马后炮"] = soundPool.load(appContext, R.raw.sfx_tactic_mahoupao, 1)
            tacticSounds["铁门栓"] = soundPool.load(appContext, R.raw.sfx_tactic_tiemenshuan, 1)
            tacticSounds["卧槽马"] = soundPool.load(appContext, R.raw.sfx_tactic_wochaoma, 1)
            tacticSounds["双车错"] = soundPool.load(appContext, R.raw.sfx_tactic_shuangche, 1)
            tacticSounds["重炮"] = soundPool.load(appContext, R.raw.sfx_tactic_zhongpao, 1)
            tacticSounds["双炮"] = tacticSounds["重炮"] ?: 0

            tacticWinSounds["马后炮"] = soundPool.load(appContext, R.raw.sfx_win_mahoupao, 1)
            tacticWinSounds["铁门栓"] = soundPool.load(appContext, R.raw.sfx_win_tiemenshuan, 1)
            tacticWinSounds["重炮"] = soundPool.load(appContext, R.raw.sfx_win_zhongpao, 1)
            tacticWinSounds["双炮"] = tacticWinSounds["重炮"] ?: 0
            tacticWinSounds["双车错"] = soundPool.load(appContext, R.raw.sfx_win_shuangche, 1)
            tacticWinSounds["卧槽马"] = soundPool.load(appContext, R.raw.sfx_win_wochaoma, 1)
            tacticWinSounds["挂角马"] = soundPool.load(appContext, R.raw.sfx_win_guajiaoma, 1)
            tacticWinSounds["白脸将"] = soundPool.load(appContext, R.raw.sfx_win_bailianjiang, 1)
            tacticWinSounds["天地炮"] = soundPool.load(appContext, R.raw.sfx_win_tiandipao, 1)
            tacticWinSounds["闷宫"] = soundPool.load(appContext, R.raw.sfx_win_mengong, 1)
            tacticWinSounds["大刀剜心"] = soundPool.load(appContext, R.raw.sfx_win_dadaowanxin, 1)
            tacticWinSounds["钓鱼马"] = soundPool.load(appContext, R.raw.sfx_win_diaoyuma, 1)
        }
    }

    /** 选中棋子: 清脆微触感反馈 */
    fun onSelectPiece() {
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    /** 开局: 柔锣 + 「红方先行。」 */
    fun onGameStart() {
        playVoice(soundGameStart, 2600L)
    }

    /** 普通落子: 实木清脆碰撞; 对手走子用低八度音色区分敌我 */
    fun onMove(captured: Boolean, byPlayer: Boolean = true) {
        if (captured) {
            // 吃子声是关于"被吃的子"的, 敌我共用
            playSound(soundCapture, 1.0f)
            vibrate(CAPTURE_VIBRATION_MS, isHeavy = true)
        } else if (byPlayer) {
            playSound(soundMove, 0.9f)
            vibrate(MOVE_VIBRATION_MS, isHeavy = false)
        } else {
            playSound(soundMoveOpponent, 0.8f)
        }
    }

    /** 悔棋: 下滑 whoosh + 木鱼 tick */
    fun onUndo() {
        playSound(soundUndo, 0.9f)
    }

    /** 被将军: 战锣 + 英雄声「将军！」 */
    fun onCheck() {
        playVoice(soundCheck, 2400L)
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        runCatching {
            val pattern = longArrayOf(0, 30, 80, 30)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /** 战术将军: 如「马后炮！」「铁门栓！」 */
    fun onTactic(tactic: String) {
        val soundId = tacticSounds[tactic]
        if (soundId != null && soundId > 0) {
            playVoice(soundId, 2400L)
        } else {
            onCheck()
        }
    }

    /** 绝杀: 凯旋锣鼓 + 英雄声「绝杀！」 */
    fun onCheckmate() {
        playVoice(soundCheckmate, 3200L)
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        runCatching {
            val pattern = longArrayOf(0, 80, 60, 40, 100, 40, 200)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /** 终局获胜: JJ象棋大铜锣凯歌 + 「大获全胜！」(若有战术定式杀法，则播报专属杀法定式语音) */
    fun onVictory(tactic: String? = null) {
        val soundId = if (tactic != null) tacticWinSounds[tactic] ?: soundWin else soundWin
        playVoice(if (soundId > 0) soundId else soundWin, 4800L)
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        runCatching {
            val pattern = longArrayOf(0, 70, 60, 90, 60, 180)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /** 终局惜败: 幽咽铜锣 + 「棋差一着，惜败。」 */
    fun onDefeat() {
        playVoice(soundLoss, 3600L)
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** 终局和棋: 铜钟「握手言和。」 */
    fun onDraw() {
        playVoice(soundDraw, 3000L, 0.9f)
    }

    /** 吃子专用音效 */
    fun onCapturePiece(@Suppress("UNUSED_PARAMETER") piece: com.zhongguoxiangqi.game.Piece) {
        playSound(soundCapture, 1.0f)
        if (settings.vibrateEnabled) vibrate(CAPTURE_VIBRATION_MS, isHeavy = true)
    }

    /** 残局/每日一题解出: 明亮五声 chime + 「妙手！」(比对局凯旋轻快) */
    fun onPuzzleSolved() {
        playVoice(soundPuzzleSolved, 2200L)
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    /** 残局失败: 闷低锣 + 「再来一次。」 */
    fun onPuzzleFailed() {
        playVoice(soundPuzzleFail, 2400L)
    }

    /** 残局走错一步 (偏离正解): 短促闷音, 立刻可悔棋 */
    fun onPuzzleWrong() {
        playSound(soundWrong, 0.9f)
        vibrate(MOVE_VIBRATION_MS, isHeavy = false)
    }

    /** 闯关过一关: 锣 + 短号角 + 「过关！」 */
    fun onCampaignCleared() {
        playVoice(soundCampaignClear, 3000L)
    }

    /** 拍照识别: 快门 */
    fun onRecognizeStart() {
        playSound(soundShutter, 1.0f)
    }

    /** 拍照识别成功: 拨弦上行 */
    fun onRecognizeOk() {
        playSound(soundChime, 1.0f)
    }

    /** 拍照识别失败: 闷低双音 */
    fun onRecognizeFail() {
        playSound(soundWrong, 0.9f)
    }

    /** 实时支招锁定棋盘: 干净短 blip + 微触感 */
    fun onLiveLock() {
        playSound(soundLock, 0.9f)
        if (!settings.vibrateEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun playSound(soundId: Int, volume: Float = 1.0f) {
        if (!settings.soundEnabled || soundId <= 0) return
        runCatching {
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f)
        }
    }

    /** 语音类音效: 先请宿主 duck BGM, 再播放 */
    private fun playVoice(soundId: Int, durationMs: Long, volume: Float = 1.0f) {
        if (!settings.soundEnabled || soundId <= 0) return
        onVoiceClip?.invoke(durationMs)
        playSound(soundId, volume)
    }

    private fun vibrate(durationMs: Long, isHeavy: Boolean = false) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || !settings.vibrateEnabled) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = if (isHeavy) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                } else {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    fun release() {
        runCatching { soundPool.release() }
    }

    private companion object {
        const val MOVE_VIBRATION_MS = 18L
        const val CAPTURE_VIBRATION_MS = 38L
    }
}
