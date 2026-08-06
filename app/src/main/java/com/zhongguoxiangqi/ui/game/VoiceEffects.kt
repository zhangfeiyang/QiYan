package com.zhongguoxiangqi.ui.game

import android.content.Context
import android.speech.tts.TextToSpeech
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.ui.settings.AppSettings
import java.util.Locale

/**
 * 对局语音播报: 吃子、将军、绝杀、战术杀法(铁门栓/马后炮).
 *
 * 用系统 TTS 播报中文短语, 不打包任何音频资源. 与 [GameFeedback] 的
 * 敲击音效并存: 敲击负责"落子"的手感, 这里负责"发生了什么"的信息.
 * 跟随设置里的音效开关 —— 关掉音效就不说话.
 *
 * 使用方: Activity 在 onCreate 创建, onDestroy 调 [shutdown].
 */
class VoiceEffects(context: Context) {

    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = if (appContext.resources.configuration.locales[0].language == "zh") {
                    Locale.SIMPLIFIED_CHINESE
                } else {
                    Locale.getDefault()
                }
                val result = tts?.setLanguage(locale)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    /** 被将军 */
    fun onCheck() = speak("将军")

    /** 将死对方 */
    fun onCheckmate() = speak("绝杀")

    /** 战术杀法, 如 铁门栓 / 马后炮 */
    fun onTactic(name: String) = speak(name)

    /**
     * 吃子.
     * @param piece 被吃的子(取它的实际棋子名, 红帅/黑将等)
     * @param notation 本步的中文记谱(如 "车九进四"), 非空时连起来说
     */
    fun onCapture(piece: Piece, notation: String?) {
        val pieceName = if (piece.isRed) piece.redChar else piece.blackChar
        val text = if (notation != null) "$notation，吃$pieceName" else "吃$pieceName"
        speak(text)
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun speak(text: String) {
        if (!settings.soundEnabled || !ready) return
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xiangqi_voice")
        }
    }
}
