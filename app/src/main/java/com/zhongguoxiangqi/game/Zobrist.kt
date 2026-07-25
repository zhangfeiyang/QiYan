package com.zhongguoxiangqi.game

import kotlin.random.Random

/**
 * Zobrist 哈希键.
 *
 * 用于:
 * - 置换表 (Transposition Table) 键
 * - 重复局面检测
 * - 开局库查询
 *
 * 设计:
 * - 每种棋子 × 每个格子 一个 64 位随机数
 * - 走子方单独一个 64 位随机数(用于区分红走和黑走)
 */
object Zobrist {

    /** pieceSquare[piece][square] = 64-bit 随机数 */
    val pieceSquare: Array<LongArray> = Array(16) { LongArray(Square.COUNT) }
    val side: Long = Random.nextLong()

    init {
        val rng = Random(0xCAFEBABEL)
        for (p in 1..15) {
            for (sq in 0 until Square.COUNT) {
                pieceSquare[p][sq] = rng.nextLong()
            }
        }
    }

    /** 自定义初始化(测试时使用固定种子) */
    fun reseed(seed: Long) {
        val rng = Random(seed)
        for (p in 1..15) {
            for (sq in 0 until Square.COUNT) {
                pieceSquare[p][sq] = rng.nextLong()
            }
        }
    }
}