package com.zhongguoxiangqi.ui.menu

import android.graphics.PorterDuff
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ui.board.BoardView
import com.zhongguoxiangqi.ui.common.Notation

/** Five ivory chess-piece portraits, from pawn to general, with a distinct tier color. */
object OpponentAvatar {

    /** 八档各对应一枚棋子。拉丁字母与盘面棋子字符集一致。 */
    private val LADDER = arrayOf("兵", "马", "炮", "车", "帥", "将", "相", "帥")
    private val LATIN = arrayOf("P", "N", "C", "R", "K", "K", "B", "K")

    fun glyphFor(levelId: Int): String =
        LADDER[(levelId - 1).coerceIn(LADDER.indices)]

    /**
     * 五档各对应一种刻环与字色。
     *
     * 五个色值都过了"白色大字压实心底"的 AA Large (3:1) —— 头像上的字是
     * 大号粗体, 走的是这条线而不是正文的 4.5:1。
     */
    private val TIER_COLORS = intArrayOf(
        R.color.rank_bronze,       // 1
        R.color.rank_silver,       // 2
        R.color.rank_gold,         // 3
        R.color.rank_diamond,      // 4
        R.color.rank_grandmaster   // 5
    )

    private fun tierColorFor(levelId: Int): Int =
        TIER_COLORS[(levelId - 1).coerceIn(TIER_COLORS.indices)]

    /**
     * 把 [view] 装扮成该档位的头像.
     *
     * 与盘上的象牙棋子同构: 象牙面由 drawable 提供, 档位色只上两处 ——
     * 头像的**字**和 drawable 里那圈独立的"刻环"(avatar_rim). 整份
     * drawable 直接 SRC_IN 会把象牙面一起染掉, 五枚子又变回一排色块.
     *
     * 背景是 shape drawable 的共享常量, 直接 setColorFilter 会污染同一份
     * ConstantState 下的所有实例 —— 列表里 五个头像会全部变成最后一次设
     * 的那个颜色。所以先 mutate() 断开共享(它会连子层一起断开)。
     */
    fun bind(view: TextView, level: DifficultyLevel) {
        val letters = Notation.pieceSetFor(view.context) == BoardView.PieceSet.LATIN
        val glyphs = if (letters) LATIN else LADDER
        view.text = glyphs[(level.id - 1).coerceIn(glyphs.indices)]
        val tier = ContextCompat.getColor(view.context, tierColorFor(level.id))
        view.setTextColor(tier)
        val bg = ContextCompat.getDrawable(view.context, R.drawable.bg_opponent_avatar)
            ?.mutate() ?: return
        (bg as? android.graphics.drawable.LayerDrawable)
            ?.findDrawableByLayerId(R.id.avatar_rim)
            ?.setColorFilter(tier, PorterDuff.Mode.SRC_IN)
        view.background = bg
    }
}
