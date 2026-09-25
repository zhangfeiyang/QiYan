package com.zhongguoxiangqi.engine

import org.junit.Assert.*
import org.junit.Test

class DifficultySettingsTest {
    @Test
    fun `legacy fifteen levels migrate in five groups of three`() {
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5),
            (1..15).map(DifficultySettings::migrateLegacyLevel))
        assertEquals(1, DifficultySettings.migrateLegacyLevel(Int.MIN_VALUE))
        assertEquals(5, DifficultySettings.migrateLegacyLevel(Int.MAX_VALUE))
        assertEquals(1, DifficultySettings.DEFAULT_LEVEL)
    }

    @Test
    fun `all analysis and hints always select strong engine`() {
        for (level in 1..5) {
            assertTrue(DifficultyEngine.usesStrongEngine(level, SearchParams.INSTANT_HINT))
            assertTrue(DifficultyEngine.usesStrongEngine(level, SearchParams.DEFAULT_ANALYSIS))
            assertEquals(level >= 3, DifficultyEngine.usesStrongEngine(level, SearchParams()))
        }
    }
}
