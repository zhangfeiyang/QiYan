package com.zhongguoxiangqi.data

import org.junit.Assert.*
import org.junit.Test

class CampaignProgressMigrationTest {
    @Test fun newPlayerStartsWithoutCompletedStages() {
        assertEquals(CampaignProgressMigration.Result(1, emptySet()), CampaignProgressMigration.migrate(1, emptySet()))
    }

    @Test fun partialOldStageDoesNotBecomeANewWin() {
        val result = CampaignProgressMigration.migrate(3, setOf(1, 2))
        assertEquals(1, result.unlocked)
        assertTrue(result.passed.isEmpty())
    }

    @Test fun completingThreeOldStagesUnlocksTheNextTier() {
        assertEquals(CampaignProgressMigration.Result(2, setOf(1)), CampaignProgressMigration.migrate(4, setOf(1, 2, 3)))
    }

    @Test fun migratedProgressRequiresEveryStageInAGroup() {
        val result = CampaignProgressMigration.migrate(7, setOf(1, 3, 4, 5, 6))
        assertEquals(3, result.unlocked)
        assertEquals(setOf(2), result.passed)
    }

    @Test fun legacyCompletionStaysWithinFiveStages() {
        assertEquals(CampaignProgressMigration.Result(5, (1..5).toSet()), CampaignProgressMigration.migrate(16, (1..15).toSet()))
    }
}
