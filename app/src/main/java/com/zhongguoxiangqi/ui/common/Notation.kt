package com.zhongguoxiangqi.ui.common

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
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
     * 先问 AppCompatDelegate, 再退回 configuration —— 顺序不能反。
     *
     * 本 App 的语言一律走 per-app locale (Application.onCreate 里默认设成
     * "en"), 而 AppCompat 的这套回退实现在 API 33 以下只改写 **Activity**
     * 的 Configuration, Application 的 Configuration 仍然是系统语言。
     * 于是只看 configuration 的话, 同一次对局里 GameViewModel(拿的是
     * getApplication())和 GameActivity(拿的是自己)会得出两种语言 ——
     * 界面是英文、棋谱却记成"炮二平五"。getApplicationLocales() 记的就是
     * "这个 App 显示成哪种语言", 各版本上都准。
     */
    private fun isChineseUi(context: Context): Boolean {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return isChineseUi(
            perAppLanguage = if (appLocales.isEmpty) null else appLocales[0]?.language,
            configLanguage = context.resources.configuration.locales
                .takeIf { !it.isEmpty }?.get(0)?.language
        )
    }

    /**
     * 上面那条判断里不依赖 Android 的部分, 单独拆出来是为了能直接测 ——
     * "per-app locale 优先于 configuration"正是这个 bug 的全部内容,
     * 而它只在 API 33 以下才显形, 靠跑 App 很难看见。
     */
    internal fun isChineseUi(perAppLanguage: String?, configLanguage: String?): Boolean =
        (perAppLanguage ?: configLanguage) == "zh"

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
