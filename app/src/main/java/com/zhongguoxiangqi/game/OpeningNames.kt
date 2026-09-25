package com.zhongguoxiangqi.game

import android.content.Context
import com.zhongguoxiangqi.R

/** 开局定式在界面上的叫法. 检测器内部仍用中文键. */
object OpeningNames {
    private val resources = mapOf(
        "中炮对屏风马" to R.string.opening_central_vs_screen,
        "顺手炮" to R.string.opening_same_cannon,
        "列手炮" to R.string.opening_opposite_cannon,
        "中炮对反宫马" to R.string.opening_central_vs_reversed,
        "中炮对单提马" to R.string.opening_central_vs_single,
        "中炮对三步虎" to R.string.opening_central_vs_tiger,
        "仙人指路对卒底炮" to R.string.opening_pawn_vs_cannon,
        "仙人指路互进卒" to R.string.opening_mutual_pawn,
        "仙人指路对中炮" to R.string.opening_pawn_vs_central,
        "仙人指路对进马" to R.string.opening_pawn_vs_horse,
        "仙人指路对飞相" to R.string.opening_pawn_vs_elephant,
        "飞相局对左中炮" to R.string.opening_elephant_vs_left,
        "飞相局对右中炮" to R.string.opening_elephant_vs_right,
        "飞相局对进马" to R.string.opening_elephant_vs_horse,
        "飞相局对进卒" to R.string.opening_elephant_vs_pawn,
        "飞相局对士角炮" to R.string.opening_elephant_vs_corner,
        "飞相局对过宫炮" to R.string.opening_elephant_vs_crossing,
        "起马局对挺卒" to R.string.opening_horse_vs_pawn,
        "起马局对中炮" to R.string.opening_horse_vs_central,
        "起马局对进马" to R.string.opening_horse_vs_horse,
        "当头炮" to R.string.opening_central_cannon,
        "仙人指路" to R.string.opening_pawn,
        "飞相局" to R.string.opening_elephant,
        "起马局" to R.string.opening_horse,
        "过宫炮" to R.string.opening_crossing_cannon,
        "士角炮" to R.string.opening_corner_cannon,
        "金钩炮" to R.string.opening_hook_cannon,
        "铁滑车" to R.string.opening_iron_roller,
        "边马局" to R.string.opening_edge_horse,
        "边兵局" to R.string.opening_edge_pawn,
    )

    fun label(context: Context, name: String?): String {
        if (name.isNullOrBlank()) return ""
        val res = resources[name] ?: return name
        return context.getString(res)
    }
}
