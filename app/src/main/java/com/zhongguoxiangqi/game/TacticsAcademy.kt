package com.zhongguoxiangqi.game

import com.zhongguoxiangqi.R

/**
 * 经典杀法宝典数据定义.
 */
data class TacticTopic(
    val id: String,
    val name: String,
    val icon: String,
    val rhymeRes: Int,
    val descriptionRes: Int,
    val puzzleId: Int,
    val voiceRes: Int,
)

object TacticsAcademy {

    val topics: List<TacticTopic> = listOf(
        TacticTopic(
            id = "mahoupao",
            name = "马后炮",
            icon = "🐎",
            rhymeRes = R.string.tactic_rhyme_mahoupao,
            descriptionRes = R.string.tactic_desc_mahoupao,
            puzzleId = 8,
            voiceRes = R.raw.sfx_win_mahoupao
        ),
        TacticTopic(
            id = "tiemenshuan",
            name = "铁门栓",
            icon = "🚪",
            rhymeRes = R.string.tactic_rhyme_bolt,
            descriptionRes = R.string.tactic_desc_bolt,
            puzzleId = 5,
            voiceRes = R.raw.sfx_win_tiemenshuan
        ),
        TacticTopic(
            id = "zhongpao",
            name = "重炮",
            icon = "💥",
            rhymeRes = R.string.tactic_rhyme_double_cannon,
            descriptionRes = R.string.tactic_desc_double_cannon,
            puzzleId = 2,
            voiceRes = R.raw.sfx_win_zhongpao
        ),
        TacticTopic(
            id = "shuangche",
            name = "双车错",
            icon = "🏎️",
            rhymeRes = R.string.tactic_rhyme_double_rook,
            descriptionRes = R.string.tactic_desc_double_rook,
            puzzleId = 9,
            voiceRes = R.raw.sfx_win_shuangche
        ),
        TacticTopic(
            id = "wochaoma",
            name = "卧槽马",
            icon = "🐴",
            rhymeRes = R.string.tactic_rhyme_pocket,
            descriptionRes = R.string.tactic_desc_pocket,
            puzzleId = 7,
            voiceRes = R.raw.sfx_win_wochaoma
        ),
        TacticTopic(
            id = "guajiaoma",
            name = "挂角马",
            icon = "📐",
            rhymeRes = R.string.tactic_rhyme_corner,
            descriptionRes = R.string.tactic_desc_corner,
            puzzleId = 19,
            voiceRes = R.raw.sfx_win_guajiaoma
        ),
        TacticTopic(
            id = "bailianjiang",
            name = "白脸将",
            icon = "👑",
            rhymeRes = R.string.tactic_rhyme_face,
            descriptionRes = R.string.tactic_desc_face,
            puzzleId = 4,
            voiceRes = R.raw.sfx_win_bailianjiang
        ),
        TacticTopic(
            id = "tiandipao",
            name = "天地炮",
            icon = "⚡",
            rhymeRes = R.string.tactic_rhyme_heaven,
            descriptionRes = R.string.tactic_desc_heaven,
            puzzleId = 22,
            voiceRes = R.raw.sfx_win_tiandipao
        ),
        TacticTopic(
            id = "diaoyuma",
            name = "钓鱼马",
            icon = "🎣",
            rhymeRes = R.string.tactic_rhyme_fishing,
            descriptionRes = R.string.tactic_desc_fishing,
            puzzleId = 21,
            voiceRes = R.raw.sfx_win_diaoyuma
        ),
        TacticTopic(
            id = "dadaowanxin",
            name = "大刀剜心",
            icon = "🗡️",
            rhymeRes = R.string.tactic_rhyme_heart,
            descriptionRes = R.string.tactic_desc_heart,
            puzzleId = 27,
            voiceRes = R.raw.sfx_win_dadaowanxin
        ),
        TacticTopic(
            id = "mengong",
            name = "闷宫",
            icon = "🔒",
            rhymeRes = R.string.tactic_rhyme_smothered,
            descriptionRes = R.string.tactic_desc_smothered,
            puzzleId = 1,
            voiceRes = R.raw.sfx_win_mengong
        )
    )
}
