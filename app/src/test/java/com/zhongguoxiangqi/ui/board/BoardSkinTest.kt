package com.zhongguoxiangqi.ui.board

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BoardSkinTest {
    @Test fun unknownIdsStayOnWarmWood() {
        assertSame(BoardSkin.WOOD, BoardSkin.fromId(-1))
        assertSame(BoardSkin.WOOD, BoardSkin.fromId(99))
    }

    @Test fun warmWoodFollowsTheThemeInsteadOfAFixedPalette() {
        assertNull(BoardPalettes.of(BoardSkin.WOOD))
    }

    @Test fun eachMaterialKeepsIvoryFacesAndDistinctSideColors() {
        for (skin in listOf(BoardSkin.BLACK_GOLD, BoardSkin.PORCELAIN, BoardSkin.JADE)) {
            val palette = BoardPalettes.of(skin)!!
            assertNotEquals(skin.name, palette.pieceFace, palette.pieceRed)
            assertNotEquals(skin.name, palette.pieceFace, palette.pieceBlack)
            assertNotEquals(skin.name, palette.pieceRed, palette.pieceBlack)
            assertNotEquals(skin.name, palette.line, palette.boardGrad[1])
        }
    }
}
