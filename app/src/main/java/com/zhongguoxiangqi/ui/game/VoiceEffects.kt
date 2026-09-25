package com.zhongguoxiangqi.ui.game

import android.content.Context
import com.zhongguoxiangqi.game.Piece

/**
 * 语音播报组件 (已升级至 JJ 象棋高保真 SoundPool 音频系统并整合入 [GameFeedback]).
 *
 * 为彻底消除系统 TTS 机械音，所有高质量武侠象棋音频（将军、绝杀、大获全胜、战术杀法等）
 * 均已统一由 [GameFeedback] 使用原生 SoundPool 进行零延迟混音播放，不再调用任何系统 TTS 朗读。
 * 此类保留对外接口以保持兼容性。
 */
class VoiceEffects(@Suppress("UNUSED_PARAMETER") context: Context) {

    /** 被将军 (由 GameFeedback 播放高质量战锣与将军原声) */
    fun onCheck() {}

    /** 将死对方 */
    fun onCheckmate() {}

    /** 终局获胜 */
    fun onVictory() {}

    /** 终局惜败 */
    fun onDefeat() {}

    /** 握手言和 */
    fun onDraw() {}

    /** 战术杀法 */
    fun onTactic(@Suppress("UNUSED_PARAMETER") name: String) {}

    /** 吃子 */
    fun onCapture(
        @Suppress("UNUSED_PARAMETER") piece: Piece,
        @Suppress("UNUSED_PARAMETER") notation: String?
    ) {}

    fun shutdown() {}
}
