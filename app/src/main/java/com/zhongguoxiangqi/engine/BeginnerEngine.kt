package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.MoveGenerator
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.UciCoords
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * A deliberately limited practice opponent, independent of JNI and opening books.
 * Level 1 mostly chooses randomly; level 2 notices captures and immediate hanging pieces,
 * but neither searches combinations or evaluates a strong engine's principal variation.
 */
internal class BeginnerEngine(
    private val difficulty: () -> Int,
    private val random: Random = Random.Default,
    private val style: () -> PlayStyle = { PlayStyle.BALANCED },
) : ChessEngine {
    @Volatile private var ready = false
    @Volatile private var listener: EngineListener? = null
    private var position: Position? = Position()
    private var worker: Thread? = null
    private val generation = AtomicLong()

    override fun isReady() = ready
    override fun name() = if (difficulty() <= 1) "轻松陪练" else "入门棋友"
    override fun maxStrength() = 2

    override fun start(): Boolean {
        ready = true
        listener?.onReady()
        return true
    }

    override fun shutdown() {
        ready = false
        stopSearch()
    }

    override fun setListener(listener: EngineListener?) { this.listener = listener }

    override fun setPosition(fen: String, moves: List<String>) {
        stopSearch()
        val next = Position()
        if (fen.isNotBlank() && !next.setFromFen(fen)) {
            position = null
            listener?.onError("局面 FEN 无效")
            return
        }
        for (uci in moves) {
            val move = UciCoords.parse(uci)
            if (move == null || !next.isLegalMove(move) || !next.doMove(move)) {
                position = null
                listener?.onError("局面着法无效: $uci")
                return
            }
        }
        position = next
    }

    override fun startSearch(params: SearchParams) {
        stopSearch()
        // Analysis must be explicitly routed through DifficultyEngine's strong engine.
        if (params.strictMovetime) {
            listener?.onError("提示和分析需要专业引擎")
            return
        }
        val snapshot = position?.copy()
        if (!ready || snapshot == null) {
            listener?.onError("陪练引擎或当前局面尚未就绪")
            return
        }
        val token = generation.get()
        val level = difficulty().coerceIn(1, 2)
        val thread = Thread({
            try {
                val move = chooseMove(snapshot, level, random, style())
                // Short, cancellable pause lets users follow each move without long waits.
                Thread.sleep(params.movetime.coerceIn(0L, 650L))
                if (ready && generation.get() == token) {
                    listener?.onBestMove(move?.let(UciCoords::toUci) ?: "0000", null)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "Xiangqi-practice").apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    override fun stopSearch() {
        generation.incrementAndGet()
        val thread = worker
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            try { thread.join() } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (worker === thread) worker = null
    }

    companion object {
        /** Never changes the caller's position, including hash and repetition history. */
        internal fun chooseMove(position: Position, level: Int, random: Random, style: PlayStyle = PlayStyle.BALANCED): Move? {
            val pos = position.copy()
            val legal = mutableListOf<Move>()
            pos.generateMoves(legal)
            if (legal.isEmpty()) return null
            // A beginner frequently overlooks a free capture. Level 2 still makes mistakes.
            val randomChance = (if (level <= 1) 85 else 25) + when (style) { PlayStyle.SWARM -> 10; PlayStyle.AGGRESSIVE -> -10; else -> 0 }
            if (random.nextInt(100) < randomChance) return legal[random.nextInt(legal.size)]

            val scored = legal.map { move ->
                var score = pieceValue(pos.pieceAt(move.to))
                if (level >= 2 || style == PlayStyle.SOLID) {
                    pos.doMove(move)
                    val captures = mutableListOf<Move>()
                    MoveGenerator.generateCaptures(pos, captures)
                    // Only sees the opponent's next capture; no minimax or opening theory.
                    score -= captures.maxOfOrNull { pieceValue(pos.pieceAt(it.to)) } ?: 0
                    pos.undoMove()
                }
                move to score
            }
            val best = scored.maxOf { it.second }
            val candidates = scored.filter { it.second == best }
            return candidates[random.nextInt(candidates.size)].first
        }

        private fun pieceValue(piece: Int): Int = when (Piece.fromCode(piece).type) {
            1 -> 10000
            5 -> 900
            4, 6 -> 400
            2, 3 -> 200
            7 -> 100
            else -> 0
        }
    }
}
