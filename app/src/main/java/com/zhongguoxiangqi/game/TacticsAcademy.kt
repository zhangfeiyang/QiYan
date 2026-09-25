package com.zhongguoxiangqi.game

import com.zhongguoxiangqi.R

/**
 * 经典杀法宝典数据定义.
 */
data class TacticTopic(
    val id: String,
    val name: String,
    /** 这一杀法里出力的主子, 列表上画成一枚棋子徽记 */
    val keyPiece: KeyPiece,
    val rhymeRes: Int,
    val descriptionRes: Int,
    val puzzleId: Int,
    val voiceRes: Int,
)

/**
 * 杀法徽记上的棋子.
 *
 * 以前每条杀法前面是一个 emoji (🐎 🚪 💥 🏎️ …), 双车错配的是一辆赛车 ——
 * 与整套水墨棋子的视觉语言不搭, 各家系统字体下也长得不一样. 换成这一杀法
 * 的主子, 初学者扫一眼就知道"这招靠什么子".
 */
enum class KeyPiece(val hanzi: String, val latin: String) {
    CANNON("炮", "C"),
    ROOK("车", "R"),
    HORSE("马", "N"),
    KING("帅", "K"),
}

object TacticsAcademy {

    val topics: List<TacticTopic> = listOf(
        TacticTopic(
            id = "mahoupao",
            name = "马后炮",
            keyPiece = KeyPiece.CANNON,
            rhymeRes = R.string.tactic_rhyme_mahoupao,
            descriptionRes = R.string.tactic_desc_mahoupao,
            puzzleId = 8,
            voiceRes = R.raw.sfx_win_mahoupao
        ),
        TacticTopic(
            id = "tiemenshuan",
            name = "铁门栓",
            keyPiece = KeyPiece.ROOK,
            rhymeRes = R.string.tactic_rhyme_bolt,
            descriptionRes = R.string.tactic_desc_bolt,
            puzzleId = 5,
            voiceRes = R.raw.sfx_win_tiemenshuan
        ),
        TacticTopic(
            id = "zhongpao",
            name = "重炮",
            keyPiece = KeyPiece.CANNON,
            rhymeRes = R.string.tactic_rhyme_double_cannon,
            descriptionRes = R.string.tactic_desc_double_cannon,
            puzzleId = 2,
            voiceRes = R.raw.sfx_win_zhongpao
        ),
        TacticTopic(
            id = "shuangche",
            name = "双车错",
            keyPiece = KeyPiece.ROOK,
            rhymeRes = R.string.tactic_rhyme_double_rook,
            descriptionRes = R.string.tactic_desc_double_rook,
            puzzleId = 9,
            voiceRes = R.raw.sfx_win_shuangche
        ),
        TacticTopic(
            id = "wochaoma",
            name = "卧槽马",
            keyPiece = KeyPiece.HORSE,
            rhymeRes = R.string.tactic_rhyme_pocket,
            descriptionRes = R.string.tactic_desc_pocket,
            puzzleId = 7,
            voiceRes = R.raw.sfx_win_wochaoma
        ),
        TacticTopic(
            id = "guajiaoma",
            name = "挂角马",
            keyPiece = KeyPiece.HORSE,
            rhymeRes = R.string.tactic_rhyme_corner,
            descriptionRes = R.string.tactic_desc_corner,
            puzzleId = 19,
            voiceRes = R.raw.sfx_win_guajiaoma
        ),
        TacticTopic(
            id = "bailianjiang",
            name = "白脸将",
            keyPiece = KeyPiece.KING,
            rhymeRes = R.string.tactic_rhyme_face,
            descriptionRes = R.string.tactic_desc_face,
            puzzleId = 4,
            voiceRes = R.raw.sfx_win_bailianjiang
        ),
        TacticTopic(
            id = "tiandipao",
            name = "天地炮",
            keyPiece = KeyPiece.CANNON,
            rhymeRes = R.string.tactic_rhyme_heaven,
            descriptionRes = R.string.tactic_desc_heaven,
            puzzleId = 22,
            voiceRes = R.raw.sfx_win_tiandipao
        ),
        TacticTopic(
            id = "diaoyuma",
            name = "钓鱼马",
            keyPiece = KeyPiece.HORSE,
            rhymeRes = R.string.tactic_rhyme_fishing,
            descriptionRes = R.string.tactic_desc_fishing,
            puzzleId = 21,
            voiceRes = R.raw.sfx_win_diaoyuma
        ),
        TacticTopic(
            id = "dadaowanxin",
            name = "大刀剜心",
            keyPiece = KeyPiece.ROOK,
            rhymeRes = R.string.tactic_rhyme_heart,
            descriptionRes = R.string.tactic_desc_heart,
            puzzleId = 27,
            voiceRes = R.raw.sfx_win_dadaowanxin
        ),
        TacticTopic(
            id = "mengong",
            name = "闷宫",
            keyPiece = KeyPiece.CANNON,
            rhymeRes = R.string.tactic_rhyme_smothered,
            descriptionRes = R.string.tactic_desc_smothered,
            puzzleId = 1,
            voiceRes = R.raw.sfx_win_mengong
        )
    )
}
