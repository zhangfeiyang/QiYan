package com.zhongguoxiangqi.engine

import android.content.Context
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 开局库(开局字典).
 *
 * 数据格式: fen -> { name, moves: List<String> }
 * 实际生产环境建议使用专业开局库,如 Pikafish 提供的开局文件.
 */
class OpeningBook(private val context: Context) {

    private val book: Map<String, BookEntry> by lazy { loadBook() }

    fun lookup(position: Position, movesPlayed: Int): Move? {
        if (movesPlayed > 12) return null  // 超过 12 手不用开局库
        val fen = position.toFen()
        // 取走子方归一化
        val entry = book.entries.firstOrNull {
            it.key.startsWith(fen.substringBefore(' '))
        } ?: return null

        val moveList = entry.value.moves
        if (movesPlayed >= moveList.size) return null

        val uci = moveList[movesPlayed]
        return uciToMove(uci)
    }

    private fun uciToMove(uci: String): Move? {
        if (uci.length < 4) return null
        return try {
            val fromCol = uci[0] - 'a'
            val fromRow = uci[1].digitToInt()
            val toCol = uci[2] - 'a'
            val toRow = uci[3].digitToInt()
            val from = com.zhongguoxiangqi.game.Square.make(9 - fromRow, fromCol)
            val to = com.zhongguoxiangqi.game.Square.make(9 - toRow, toCol)
            Move.make(from, to)
        } catch (_: Exception) { null }
    }

    private fun loadBook(): Map<String, BookEntry> {
        return try {
            val json = context.assets.open("book/opening_book.json").use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
            val obj = JSONObject(json)
            val map = mutableMapOf<String, BookEntry>()
            obj.keys().forEach { key ->
                val entry = obj.getJSONObject(key)
                val movesArr = entry.getJSONArray("moves")
                val moves = (0 until movesArr.length()).map { movesArr.getString(it) }
                map[key] = BookEntry(
                    name = entry.optString("name", ""),
                    moves = moves
                )
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    data class BookEntry(
        val name: String,
        val moves: List<String>
    )
}