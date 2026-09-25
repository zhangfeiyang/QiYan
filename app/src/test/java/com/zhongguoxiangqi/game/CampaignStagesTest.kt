package com.zhongguoxiangqi.game

import com.zhongguoxiangqi.engine.DifficultySettings
import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignStagesTest {
    @Test fun theMountainHasOneStagePerEngineLevel() {
        assertEquals(DifficultySettings.MAX_LEVEL, CampaignStages.all.size)
        assertEquals((1..DifficultySettings.MAX_LEVEL).toList(), CampaignStages.all.map { it.level })
    }

    @Test fun outOfRangeLevelsStayOnTheMountain() {
        assertEquals(1, CampaignStages.of(0).level)
        assertEquals(DifficultySettings.MAX_LEVEL, CampaignStages.of(99).level)
    }
}
