package com.zhongguoxiangqi.ui.board

import androidx.annotation.StringRes
import com.zhongguoxiangqi.R

/**
 * 棋盘材质.
 *
 * 黄花梨走主题色, 这样浅色/深色仍由 colors.xml 管.
 * 另外三套是盘面自己的颜色: 像一块放在桌上的实物棋盘, 不跟夜间界面一起变黑.
 * 棋子保持象牙面加大字, 字和盘面分开上色, 避免整盘变成看不清字的色块.
 */
enum class BoardSkin(val id: Int, @StringRes val nameRes: Int) {
    WOOD(0, R.string.skin_wood),
    BLACK_GOLD(1, R.string.skin_black_gold),
    PORCELAIN(2, R.string.skin_porcelain),
    JADE(3, R.string.skin_jade);

    companion object {
        fun fromId(id: Int): BoardSkin = entries.firstOrNull { it.id == id } ?: WOOD
    }
}

class BoardPalette(
    val frameTop: Int,
    val frameEdge: Int,
    val grain: Int,
    val line: Int,
    val palace: Int,
    val river: Int,
    val border: Int,
    val coord: Int,
    val boardGrad: IntArray,
    val pieceFace: Int,
    val pieceGrad: IntArray,
    val pieceShade: Int,
    val pieceRed: Int,
    val pieceBlack: Int,
    val pieceRedEdge: Int,
    val pieceBlackEdge: Int,
)

object BoardPalettes {
    fun of(skin: BoardSkin): BoardPalette? = when (skin) {
        BoardSkin.WOOD -> null
        BoardSkin.BLACK_GOLD -> BLACK_GOLD
        BoardSkin.PORCELAIN -> PORCELAIN
        BoardSkin.JADE -> JADE
    }

    private val BLACK_GOLD = BoardPalette(
        frameTop = 0xFF4A3020.toInt(),
        frameEdge = 0xFF140C08.toInt(),
        grain = 0x33E7C27A.toInt(),
        line = 0xFFE7C27A.toInt(),
        palace = 0xFFD4B483.toInt(),
        river = 0xFFF0D7A0.toInt(),
        border = 0xFFC6A15A.toInt(),
        coord = 0xFFE8D2A2.toInt(),
        boardGrad = intArrayOf(0xFF3A2618.toInt(), 0xFF2C1B12.toInt(), 0xFF24150E.toInt(), 0xFF1A100C.toInt()),
        pieceFace = 0xFFF6E7C8.toInt(),
        pieceGrad = intArrayOf(0xFFFFF6E4.toInt(), 0xFFF3E0BE.toInt(), 0xFFE4C99A.toInt()),
        pieceShade = 0x66A67C52.toInt(),
        pieceRed = 0xFFC23A2E.toInt(),
        pieceBlack = 0xFF1A120C.toInt(),
        pieceRedEdge = 0xFF8E241C.toInt(),
        pieceBlackEdge = 0xFF000000.toInt(),
    )

    private val PORCELAIN = BoardPalette(
        frameTop = 0xFF2A4E78.toInt(),
        frameEdge = 0xFF0E2240.toInt(),
        grain = 0x223A6EA5.toInt(),
        line = 0xFF1A3E66.toInt(),
        palace = 0xFF1A3E66.toInt(),
        river = 0xFF16365C.toInt(),
        border = 0xFF16365C.toInt(),
        coord = 0xFF1A3E66.toInt(),
        boardGrad = intArrayOf(0xFFF7F9FC.toInt(), 0xFFE7EEF6.toInt(), 0xFFF4F1E8.toInt(), 0xFFE3EAF3.toInt()),
        pieceFace = 0xFFFBF8F1.toInt(),
        pieceGrad = intArrayOf(0xFFFFFFFF.toInt(), 0xFFF4EFE4.toInt(), 0xFFE7DDD0.toInt()),
        pieceShade = 0x5590A4B8.toInt(),
        pieceRed = 0xFFA32020.toInt(),
        pieceBlack = 0xFF12304F.toInt(),
        pieceRedEdge = 0xFF7A1E18.toInt(),
        pieceBlackEdge = 0xFF0B2038.toInt(),
    )

    private val JADE = BoardPalette(
        frameTop = 0xFFE7E2D6.toInt(),
        frameEdge = 0xFFB7AD9C.toInt(),
        grain = 0x226E624F.toInt(),
        line = 0xFF6B5E4A.toInt(),
        palace = 0xFF5C5344.toInt(),
        river = 0xFF5C5344.toInt(),
        border = 0xFF8A7B66.toInt(),
        coord = 0xFF5C5344.toInt(),
        boardGrad = intArrayOf(0xFFFBF8F1.toInt(), 0xFFF3EBDD.toInt(), 0xFFEFE6D6.toInt(), 0xFFE6DCC8.toInt()),
        pieceFace = 0xFFFFFCF6.toInt(),
        pieceGrad = intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F1E6.toInt(), 0xFFEADFCB.toInt()),
        pieceShade = 0x44C4B59A.toInt(),
        pieceRed = 0xFFB42318.toInt(),
        pieceBlack = 0xFF241C16.toInt(),
        pieceRedEdge = 0xFF7E1812.toInt(),
        pieceBlackEdge = 0xFF120E0C.toInt(),
    )
}
