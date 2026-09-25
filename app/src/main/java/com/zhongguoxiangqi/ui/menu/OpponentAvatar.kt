package com.zhongguoxiangqi.ui.menu

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ui.board.BoardView
import com.zhongguoxiangqi.ui.common.Notation

/** Eight ivory chess-piece portraits, from pawn to general, each with its own tier color. */
object OpponentAvatar {

    /**
     * 八档各对应一枚棋子, 按子力从兵升到帅。拉丁字母与盘面棋子字符集一致。
     *
     * 原先五档时是 兵马炮车帥, 扩到八档后只在尾巴上补了 将/相/帥 —— 第七档
     * 「棋圣」拿到的是相(象), 第八档又和第五档撞了同一个帥. 现在前四档不动,
     * 大师及以上都是"将帅"一级, 靠刻环/字色区分.
     */
    private val LADDER = arrayOf("兵", "马", "炮", "车", "将", "将", "帥", "帥")
    private val LATIN = arrayOf("P", "N", "C", "R", "K", "K", "K", "K")

    /** 八档各一种刻环与字色, 日夜两种象牙面上都过 AA Large (见 quality_colors.xml) */
    private val TIER_COLORS = intArrayOf(
        R.color.avatar_tier_1,
        R.color.avatar_tier_2,
        R.color.avatar_tier_3,
        R.color.avatar_tier_4,
        R.color.avatar_tier_5,
        R.color.avatar_tier_6,
        R.color.avatar_tier_7,
        R.color.avatar_tier_8,
    )

    private fun tierColorFor(levelId: Int): Int =
        TIER_COLORS[(levelId - 1).coerceIn(TIER_COLORS.indices)]

    /**
     * 把 [view] 装扮成该档位的头像.
     *
     * 与盘上的象牙棋子同构: 象牙面由 drawable 提供, 档位色只上两处 ——
     * 头像的**字**和 drawable 里那圈独立的"刻环"(avatar_rim).
     *
     * 刻环必须用 setStroke 换色, 不能 setColorFilter: GradientDrawable 在
     * 有滤色器而没有 solid 时会把填充画笔改成不透明色再过滤 (见其 draw()),
     * SRC_IN 之后整只圆被填满档位色 —— 同色的字也就看不见了, 首页和选对手页
     * 的头像一度全是一枚枚素色圆片.
     *
     * 背景是 shape drawable 的共享常量, 直接改会污染同一份 ConstantState
     * 下的所有实例 —— 列表里的头像会全部变成最后一次设的那个颜色。所以先
     * mutate() 断开共享(它会连子层一起断开)。
     */
    fun bind(view: TextView, level: DifficultyLevel) {
        val letters = Notation.pieceSetFor(view.context) == BoardView.PieceSet.LATIN
        val glyphs = if (letters) LATIN else LADDER
        view.text = glyphs[(level.id - 1).coerceIn(glyphs.indices)]
        val tier = ContextCompat.getColor(view.context, tierColorFor(level.id))
        view.setTextColor(tier)
        val bg = ContextCompat.getDrawable(view.context, R.drawable.bg_opponent_avatar)
            ?.mutate() ?: return
        ((bg as? LayerDrawable)?.findDrawableByLayerId(R.id.avatar_rim) as? GradientDrawable)
            ?.setStroke(view.resources.getDimensionPixelSize(R.dimen.avatar_rim_width), tier)
        view.background = bg
    }
}
