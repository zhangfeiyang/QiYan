package com.zhongguoxiangqi.game

import android.content.Context
import com.zhongguoxiangqi.R

/** 杀法在界面上的叫法. 检测器内部仍用中文键. */
object TacticNames {
    private val resources = mapOf(
        "马后炮" to R.string.tactic_label_mahoupao,
        "铁门栓" to R.string.tactic_label_bolt,
        "重炮" to R.string.tactic_label_double_cannon,
        "双车错" to R.string.tactic_label_double_rook,
        "卧槽马" to R.string.tactic_label_pocket,
        "挂角马" to R.string.tactic_label_corner,
        "钓鱼马" to R.string.tactic_label_fishing,
        "白脸将" to R.string.tactic_label_face,
        "天地炮" to R.string.tactic_label_heaven,
        "大刀剜心" to R.string.tactic_label_heart,
        "闷宫" to R.string.tactic_label_smothered,
        "拔簧" to R.string.tactic_label_spring,
        "绝杀" to R.string.tactic_label_mate,
    )

    fun label(context: Context, tactic: String?): String {
        val key = tactic ?: "绝杀"
        val res = resources[key] ?: return key
        return context.getString(res)
    }
}
