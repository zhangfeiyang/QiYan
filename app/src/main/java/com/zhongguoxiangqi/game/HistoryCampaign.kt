package com.zhongguoxiangqi.game

import androidx.annotation.StringRes
import com.zhongguoxiangqi.R

/**
 * 历史战役.
 *
 * 每一战用千题库里一道已经验过的一步杀来收束, 局面是练习题, 不是那一仗的棋谱复原。
 * 说明文字只讲战史, 杀法名告诉你这道题在练什么。
 */
data class HistoryChapter(
    val id: Int,
    @StringRes val titleRes: Int,
    val battles: List<HistoryBattle>,
)

data class HistoryBattle(
    val id: Int,
    val chapterId: Int,
    @StringRes val nameRes: Int,
    @StringRes val briefRes: Int,
    val tactic: String,
    /** 该杀法在题库中的序号, 从 0 起 */
    val occurrence: Int,
)

object HistoryCampaign {
    val chapters: List<HistoryChapter> = listOf(
        HistoryChapter(1, R.string.history_ch_1, listOf(
            battle(1, 1, R.string.battle_1_name, R.string.battle_1_brief, "铁门栓", 0),
            battle(2, 1, R.string.battle_2_name, R.string.battle_2_brief, "双车错", 0),
            battle(3, 1, R.string.battle_3_name, R.string.battle_3_brief, "马后炮", 0),
            battle(4, 1, R.string.battle_4_name, R.string.battle_4_brief, "卧槽马", 0),
        )),
        HistoryChapter(2, R.string.history_ch_2, listOf(
            battle(5, 2, R.string.battle_5_name, R.string.battle_5_brief, "重炮", 0),
            battle(6, 2, R.string.battle_6_name, R.string.battle_6_brief, "钓鱼马", 0),
            battle(7, 2, R.string.battle_7_name, R.string.battle_7_brief, "双车错", 1),
            battle(8, 2, R.string.battle_8_name, R.string.battle_8_brief, "卧槽马", 1),
        )),
        HistoryChapter(3, R.string.history_ch_3, listOf(
            battle(9, 3, R.string.battle_9_name, R.string.battle_9_brief, "白脸将", 0),
            battle(10, 3, R.string.battle_10_name, R.string.battle_10_brief, "重炮", 1),
            battle(11, 3, R.string.battle_11_name, R.string.battle_11_brief, "马后炮", 1),
            battle(12, 3, R.string.battle_12_name, R.string.battle_12_brief, "双车错", 2),
        )),
        HistoryChapter(4, R.string.history_ch_4, listOf(
            battle(13, 4, R.string.battle_13_name, R.string.battle_13_brief, "铁门栓", 1),
            battle(14, 4, R.string.battle_14_name, R.string.battle_14_brief, "重炮", 2),
            battle(15, 4, R.string.battle_15_name, R.string.battle_15_brief, "钓鱼马", 1),
            battle(16, 4, R.string.battle_16_name, R.string.battle_16_brief, "卧槽马", 2),
        )),
        HistoryChapter(5, R.string.history_ch_5, listOf(
            battle(17, 5, R.string.battle_17_name, R.string.battle_17_brief, "白脸将", 1),
            battle(18, 5, R.string.battle_18_name, R.string.battle_18_brief, "挂角马", 0),
            battle(19, 5, R.string.battle_19_name, R.string.battle_19_brief, "马后炮", 2),
            battle(20, 5, R.string.battle_20_name, R.string.battle_20_brief, "天地炮", 0),
        )),
    )

    val battles: List<HistoryBattle> = chapters.flatMap { it.battles }

    fun battle(id: Int): HistoryBattle? = battles.firstOrNull { it.id == id }

    fun resolve(battle: HistoryBattle, catalog: List<Puzzle>): Puzzle? =
        catalog.filter { it.tactic == battle.tactic }.getOrNull(battle.occurrence)

    private fun battle(
        id: Int,
        chapterId: Int,
        @StringRes nameRes: Int,
        @StringRes briefRes: Int,
        tactic: String,
        occurrence: Int,
    ) = HistoryBattle(id, chapterId, nameRes, briefRes, tactic, occurrence)
}
