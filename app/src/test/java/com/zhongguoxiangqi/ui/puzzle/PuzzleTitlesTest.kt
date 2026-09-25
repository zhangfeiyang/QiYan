package com.zhongguoxiangqi.ui.puzzle

import org.junit.Assert.assertEquals
import org.junit.Test

class PuzzleTitlesTest {

    @Test
    fun keepsOnlyTheThemeAfterTheSeparator() {
        assertEquals("Smothered", PuzzleTitles.theme("Mate in 1 · Smothered"))
        assertEquals("闷宫", PuzzleTitles.theme("一步杀·闷宫"))
        assertEquals("Corner Horse & Rook", PuzzleTitles.theme("Mate in 4 · Corner Horse & Rook"))
    }

    @Test
    fun namesWithoutSeparatorOrThemeStayWhole() {
        assertEquals("Iron bolt", PuzzleTitles.theme("Iron bolt"))
        assertEquals("一步杀·", PuzzleTitles.theme("一步杀·"))
    }
}
