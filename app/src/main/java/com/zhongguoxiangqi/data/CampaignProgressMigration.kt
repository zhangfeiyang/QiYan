package com.zhongguoxiangqi.data

import com.zhongguoxiangqi.engine.DifficultySettings

/** Preserve access while only awarding a new cleared stage for three cleared old stages. */
object CampaignProgressMigration {
    data class Result(val unlocked: Int, val passed: Set<Int>)

    fun migrate(oldUnlocked: Int, oldPassed: Set<Int>): Result = Result(
        unlocked = DifficultySettings.migrateLegacyLevel(oldUnlocked),
        passed = (1..DifficultySettings.MAX_LEVEL).filter { level ->
            ((level - 1) * 3 + 1..level * 3).all { it in oldPassed }
        }.toSet(),
    )
}
