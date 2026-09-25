package com.zhongguoxiangqi.rating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankLadderTest {
    @Test fun crossingAThresholdPromotes() {
        assertEquals(4, RankLadder.promoted(1399, 1400)?.id)
        assertNull(RankLadder.promoted(1400, 1490))
        assertNull(RankLadder.promoted(1500, 1499))
    }

    @Test fun progressMeasuresTheGapToTheNextSeal() {
        val at = RankLadder.progress(1500)
        assertEquals(4, at.current.id)
        assertEquals(5, at.next?.id)
        assertEquals(100, at.pointsToNext)
        assertEquals(0.5f, at.fraction, 0.001f)
    }

    @Test fun theTopSealHasNoNextRank() {
        val top = RankLadder.progress(2600)
        assertEquals(10, top.current.id)
        assertNull(top.next)
        assertEquals(0, top.pointsToNext)
        assertEquals(1f, top.fraction, 0.001f)
    }

    @Test fun placementHidesSealsUntilTheThirdRatedGame() {
        assertTrue(RankLadder.inPlacement(0))
        assertTrue(RankLadder.inPlacement(2))
        assertFalse(RankLadder.inPlacement(3))
        // 定级中的一盘即使跨过门槛也不宣布晋升
        assertNull(RankLadder.earnedSeal(1399, 1600, ratedGamesBefore = 0, ratedGamesAfter = 1))
        assertNull(RankLadder.earnedSeal(1500, 1300, ratedGamesBefore = 1, ratedGamesAfter = 2))
    }

    @Test fun theThirdRatedGameRevealsTheFirstSealEvenAfterALoss() {
        assertTrue(RankLadder.completesPlacement(2, 3))
        assertFalse(RankLadder.completesPlacement(3, 4))
        assertEquals(3, RankLadder.earnedSeal(1450, 1305, ratedGamesBefore = 2, ratedGamesAfter = 3)?.id)
    }

    @Test fun afterPlacementOnlyCrossingAThresholdAwardsASeal() {
        assertEquals(5, RankLadder.earnedSeal(1590, 1605, ratedGamesBefore = 5, ratedGamesAfter = 6)?.id)
        assertNull(RankLadder.earnedSeal(1605, 1590, ratedGamesBefore = 6, ratedGamesAfter = 7))
    }
}
