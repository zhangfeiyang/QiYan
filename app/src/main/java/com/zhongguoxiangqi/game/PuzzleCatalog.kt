package com.zhongguoxiangqi.game

import android.content.Context
import com.zhongguoxiangqi.R

/**
 * 千题库. 文件在 assets/puzzles/catalog.txt, 每行:
 * id \t 杀法 \t FEN
 *
 * 和 [Puzzles] 的 48 道名局分开: 名局仍用引擎复核, 这里每题都是红先一步将死,
 * 由 [MateSearch] 用棋规确认。
 */
object PuzzleCatalog {
    const val FIRST_ID = 1001

    @Volatile private var cached: List<Puzzle>? = null

    fun all(context: Context): List<Puzzle> {
        cached?.let { return it }
        val text = context.applicationContext.assets.open("puzzles/catalog.txt")
            .bufferedReader().use { it.readText() }
        return parse(text).also { cached = it }
    }

    fun byId(context: Context, id: Int): Puzzle? = all(context).firstOrNull { it.id == id }

    fun next(context: Context, id: Int): Puzzle? {
        val all = all(context)
        val index = all.indexOfFirst { it.id == id }
        return if (index in 0 until all.lastIndex) all[index + 1] else null
    }

    fun parse(text: String): List<Puzzle> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "坏题行: $line" }
            val tactic = parts[1]
            Puzzle(
                id = parts[0].toInt(),
                nameRes = R.string.catalog_mate_title,
                maxMoves = 1,
                fen = parts[2],
                hintRes = R.string.catalog_mate_hint,
                chapter = 0,
                tactic = tactic,
            )
        }.toList()
}
