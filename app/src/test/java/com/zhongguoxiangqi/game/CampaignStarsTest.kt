package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignStarsTest {
    @Test fun aWinWithNoHelpIsThreeStars() {
        assertEquals(3, CampaignStars.award(won = true, hintsUsed = 0, undosUsed = 0))
    }

    @Test fun aHintOrATakebackEachCostsOneStar() {
        assertEquals(2, CampaignStars.award(won = true, hintsUsed = 1, undosUsed = 0))
        assertEquals(2, CampaignStars.award(won = true, hintsUsed = 0, undosUsed = 2))
        assertEquals(1, CampaignStars.award(won = true, hintsUsed = 1, undosUsed = 1))
    }

    @Test fun aLossAwardsNothing() {
        assertEquals(0, CampaignStars.award(won = false, hintsUsed = 0, undosUsed = 0))
    }

    @Test fun replayKeepsTheBetterResult() {
        assertEquals(3, CampaignStars.merge(previous = 1, earned = 3))
        assertEquals(3, CampaignStars.merge(previous = 3, earned = 1))
    }

    @Test fun oldClearsWithoutAStarRecordCountAsOne() {
        assertEquals(1, CampaignStars.display(stored = null, passed = true))
        assertEquals(0, CampaignStars.display(stored = null, passed = false))
        assertEquals(2, CampaignStars.display(stored = 2, passed = true))
    }
}
