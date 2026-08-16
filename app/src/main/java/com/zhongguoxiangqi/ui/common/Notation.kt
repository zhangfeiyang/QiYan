package com.zhongguoxiangqi.ui.common

import android.content.Context
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.ui.board.BoardView
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 记谱与棋子字符集的选择.
 *
 * 界面文案早就是双语的, 但盘面和棋谱一直只有中文一种形态: 走法记作
 * "炮二平五", 棋子印着"炮". 对读不了汉字的人, 这两处不是"另一种写法",
 * 而是没有信息 —— 他能读懂按钮上的 Hint, 却对不上被提示的是哪枚子、
 * 棋谱里那一行在说什么。真正的中英文对照必须覆盖到这一层。
 *
 * 两项都默认**跟随界面语言**, 而不是写死: 用户切到英文就该同时拿到
 * WXF 记谱和拉丁棋子, 不必再进设置里翻两个开关; 想要英文界面配汉字棋子
 * (很多海外棋友就是这么下的) 也仍然改得回来。
 */
object Notation {

    enum class Style { CHINESE, WXF }

    /**
     * 界面语言是不是中文.
     *
     * 读 resources.configuration 而不是 Locale.getDefault(): 用户可能在
     * App 内单独切过语言 (AppCompatDelegate.setApplicationLocales), 那时
     * 系统默认语言并没有变, 只有 Configuration 反映了实际显示的语言。
     */
    private fun isChineseUi(context: Context): Boolean {
        val locales = context.resources.configuration.locales
        return !locales.isEmpty && locales[0].language == "zh"
    }

    fun styleFor(context: Context): Style = when (AppSettings(context).notationMode) {
        1 -> Style.CHINESE
        2 -> Style.WXF
        else -> if (isChineseUi(context)) Style.CHINESE else Style.WXF
    }

    fun pieceSetFor(context: Context): BoardView.PieceSet =
        when (AppSettings(context).pieceSetMode) {
            1 -> BoardView.PieceSet.HANZI
            2 -> BoardView.PieceSet.LATIN
            else -> if (isChineseUi(context)) BoardView.PieceSet.HANZI else BoardView.PieceSet.LATIN
        }

    /** 按当前设置把一步棋写成字符串. [position] 必须是**走这一步之前**的局面. */
    fun format(context: Context, move: Move, position: Position): String =
        when (styleFor(context)) {
            Style.CHINESE -> Move.toChineseString(move, position)
            Style.WXF -> Move.toWxfString(move, position)
        }
}
