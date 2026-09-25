package com.zhongguoxiangqi.rating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
