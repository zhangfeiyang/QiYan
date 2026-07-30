package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Square

/**
 * NNUE 特征编码. 规范见 nnue/FORMAT.md.
 *
 * **这个文件和 Python 训练脚本必须逐位一致**. 编码错一位, 训练出来的网络在
 * 端上就是一堆噪声, 而且症状是"能跑但棋力莫名其妙地差", 极难排查. 所以这里
 * 的常量顺序一旦确定就不能改 —— 改了等于作废已经生成的全部数据.
 */
object NnueFeatures {

    const val PIECE_KINDS = 14
    const val SQUARES = Square.COUNT          // 90
    /** 将被困在九宫, 只有 9 个位置 —— 所以把将位编进特征只让维度涨 9 倍 */
    const val KING_BUCKETS = 9
    const val PIECE_SQ = PIECE_KINDS * SQUARES          // 1260, 一个桶内的特征数
    const val FEATURES = KING_BUCKETS * PIECE_SQ        // 11340

    /** 棋盘上最多同时存在 32 个子 */
    const val MAX_PIECES = 32

    /**
     * Piece 枚举 -> 稠密索引 0..13.
     *
     * 用显式 when 而不是 `piece.code`: code 是引擎内部编号, 中间有空洞
     * (红 1..7, 黑 9..15), 直接拿来当特征下标会浪费 2/16 的输入维度, 也让
     * Python 侧必须复刻那个空洞, 平白多一处能对错的地方.
     */
    fun pieceIndex(piece: Int): Int = when (Piece.fromCode(piece)) {
        Piece.R_KING -> 0
        Piece.R_ADVISOR -> 1
        Piece.R_ELEPHANT -> 2
        Piece.R_HORSE -> 3
        Piece.R_ROOK -> 4
        Piece.R_CANNON -> 5
        Piece.R_PAWN -> 6
        Piece.B_KING -> 7
        Piece.B_ADVISOR -> 8
        Piece.B_ELEPHANT -> 9
        Piece.B_HORSE -> 10
        Piece.B_ROOK -> 11
        Piece.B_CANNON -> 12
        Piece.B_PAWN -> 13
        Piece.NONE -> -1
    }

    /** 红黑互换: 0..6 <-> 7..13 */
    fun flipPieceIndex(idx: Int): Int = if (idx < 7) idx + 7 else idx - 7

    /** 上下翻转格子, 列不动 */
    fun flipSquare(sq: Int): Int {
        val row = Square.row(sq)
        val col = Square.col(sq)
        return Square.make(Square.ROWS - 1 - row, col)
    }

    /**
     * 九宫桶: 经过视角变换后"己方将"必落在 row7-9 col3-5, 两个视角共用这一个.
     *
     * 特征加入将位是因为象棋的评估极度依赖将的位置(士象是否成形、车炮是否对着
     * 将门), 而只按 (子类型,格子) 编码的话网络表达不出这层依赖.
     *
     * 结果**必须夹紧**: 真实局面里将一定在九宫内, 但自检/测试会造随机子力表,
     * 那里"将"可能在任意格 —— 不夹的话桶下标越界, 索引权重就是读越界内存.
     */
    fun kingBucket(sq: Int): Int =
        ((Square.row(sq) - 7) * 3 + (Square.col(sq) - 3)).coerceIn(0, KING_BUCKETS - 1)

    /** 我方视角的特征下标. [bucket] 来自我方将的位置 */
    fun ownFeature(bucket: Int, pieceIdx: Int, sq: Int): Int =
        bucket * PIECE_SQ + pieceIdx * SQUARES + sq

    /** 敌方视角: 翻棋盘 + 换颜色, 让网络学"我方/敌方"而不是"红方/黑方" */
    fun oppFeature(bucket: Int, pieceIdx: Int, sq: Int): Int =
        bucket * PIECE_SQ + flipPieceIndex(pieceIdx) * SQUARES + flipSquare(sq)

    /** 走的是不是将/帅 —— 将一动所有特征换桶, 增量累加器必须退回全量重算 */
    fun isKing(pieceIdx: Int): Boolean = pieceIdx == 0 || pieceIdx == 7

    /**
     * 把局面摊成 (pieceIdx, sq) 列表, 写进 [outPieces] (每子 2 项), 返回子数.
     *
     * 不返回新数组: 生成上千万条样本时, 每条都分配一个数组就是上千万次分配.
     */
    fun extract(pos: Position, outPieces: IntArray): Int {
        var n = 0
        for (sq in 0 until SQUARES) {
            val code = pos.squares[sq]
            if (code == Piece.NONE.code) continue
            val idx = pieceIndex(code)
            if (idx < 0) continue
            if (n >= MAX_PIECES) break
            outPieces[n * 2] = idx
            outPieces[n * 2 + 1] = sq
            n++
        }
        return n
    }

    /**
     * 走子方是否为黑.
     *
     * 训练侧要靠它决定哪个累加器算"我方" —— 存的是原始局面, 视角转换在
     * 读数据时做, 这样同一份数据换个视角约定还能重用.
     */
    fun sideToMoveIsBlack(pos: Position): Boolean = pos.sideToMove == Color.BLACK
}
