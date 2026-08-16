package com.zhongguoxiangqi.ui.menu

import android.graphics.PorterDuff
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.zhongguoxiangqi.R

/**
 * 把一个难度档位画成一枚棋子头像.
 *
 * 15 档原来只有一个数字("Lv.11")和一段文字, 用户得逐条读完才知道谁比谁强.
 * 象棋本身就有一条现成的强弱阶梯 —— 兵 仕 相 马 炮 车 将 帥 —— 把档位映射
 * 到这条阶梯上, 一屏 15 枚子摆开, 谁轻谁重不用读字就看得出来。
 *
 * 底色**不**复用 [DifficultyLevel.color]. 那是玩家段位那套 8 级色阶, 借来
 * 标 15 档难度时最高档 rank_king 和中段 rank_gold 恰好是同一个色值 —— 15 枚
 * 子并排摆开时, Lv.14/15 看起来和 Lv.5~7 一模一样, 顶档反而显得不如紫色的
 * Lv.12/13 强。段位色是给"玩家现在在哪一级"用的, 单个出现时不会暴露这个
 * 问题, 摆成一排就露馅了。
 *
 * 这里另给一条**单调**的 5 级色阶(铜 → 银 → 金 → 青 → 紫), 每级三档, 与棋子
 * 阶梯同向, 于是"棋子越重 + 颜色越贵"两条线索指向同一个方向。
 *
 * 棋子上的字始终是汉字, 不跟随 [BoardView] 的拉丁字符集: 这里的字不是要
 * 让人读出兵种(选对手时兵种并不重要), 而是当一个分量刻度用 —— 拉丁字母
 * 的 P/A/B/N/C/R/K 没有这层"越写越复杂 = 越强"的直觉。
 */
object OpponentAvatar {

    /** 档位 1..15 → 棋子字. 每两档升一级兵种, 顶档单独给"帥". */
    private val LADDER = arrayOf(
        "兵", "卒",   // 1  2
        "仕", "士",   // 3  4
        "相", "象",   // 5  6
        "马", "馬",   // 7  8
        "炮", "砲",   // 9  10
        "车", "車",   // 11 12
        "将", "將",   // 13 14
        "帥"          // 15
    )

    fun glyphFor(levelId: Int): String =
        LADDER[(levelId - 1).coerceIn(LADDER.indices)]

    /**
     * 档位 1..15 → 底色. 每三档升一级, 五级单调递进.
     *
     * 五个色值都过了"白色大字压实心底"的 AA Large (3:1) —— 头像上的字是
     * 大号粗体, 走的是这条线而不是正文的 4.5:1。
     */
    private val TIER_COLORS = intArrayOf(
        R.color.rank_bronze,       // 1  2  3
        R.color.rank_silver,       // 4  5  6
        R.color.rank_gold,         // 7  8  9
        R.color.rank_diamond,      // 10 11 12
        R.color.rank_grandmaster   // 13 14 15
    )

    private fun tierColorFor(levelId: Int): Int =
        TIER_COLORS[((levelId - 1) / 3).coerceIn(TIER_COLORS.indices)]

    /**
     * 把 [view] 装扮成该档位的头像.
     *
     * 背景是 shape drawable 的共享常量, 直接 setColorFilter 会污染同一份
     * ConstantState 下的所有实例 —— 列表里 15 个头像会全部变成最后一次设
     * 的那个颜色。所以先 mutate() 断开共享。
     */
    fun bind(view: TextView, level: DifficultyLevel) {
        view.text = glyphFor(level.id)
        view.setTextColor(ContextCompat.getColor(view.context, R.color.piece_text))
        val bg = ContextCompat.getDrawable(view.context, R.drawable.bg_opponent_avatar)
            ?.mutate() ?: return
        bg.setColorFilter(
            ContextCompat.getColor(view.context, tierColorFor(level.id)),
            PorterDuff.Mode.SRC_IN
        )
        view.background = bg
    }
}
