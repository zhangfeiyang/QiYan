package com.zhongguoxiangqi.engine

import android.content.Context
import org.json.JSONObject
import kotlin.random.Random

/**
 * 开局库.
 *
 * 数据格式: 起始局面 FEN 的棋盘部分 -> 若干条变例, 每条是一串 UCI 着法.
 *
 * 查询规则: 只有当**实际走过的着法**恰好是某条变例的前缀时, 才给出该变例的
 * 下一步. 否则一旦对手离谱, 继续照着谱走会走出昏招甚至非法着法.
 */
class OpeningBook(private val context: Context) {

    private val book: Map<String, List<Line>> by lazy { loadBook() }

    data class Line(val name: String, val moves: List<String>)

    /**
     * 查开局库.
     *
     * @param startFen 对局起始局面的 FEN
     * @param movesPlayed 已经走过的着法(UCI)
     * @return 下一步的 UCI 着法; 不在库中或已出谱时返回 null
     */
    fun lookup(startFen: String, movesPlayed: List<String>): String? {
        if (movesPlayed.size >= MAX_BOOK_PLIES) return null
        val lines = book[startFen.substringBefore(' ')] ?: return null

        // 仍然吻合的变例: 已走着法是它的前缀, 且它还有后续着法
        val candidates = lines.filter { line ->
            line.moves.size > movesPlayed.size &&
                movesPlayed.indices.all { i -> line.moves[i].equals(movesPlayed[i], ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null

        // 多条可选时随机挑一条, 开局才不会每盘都一模一样
        return candidates[Random.nextInt(candidates.size)].moves[movesPlayed.size]
    }

    private fun loadBook(): Map<String, List<Line>> = try {
        val json = context.assets.open(BOOK_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(json)
        buildMap {
            for (key in root.keys()) {
                // 跳过 _comment 这类非对象字段
                val entry = root.optJSONObject(key) ?: continue
                val linesArr = entry.optJSONArray("lines") ?: continue
                val lines = (0 until linesArr.length()).mapNotNull { i ->
                    val obj = linesArr.optJSONObject(i) ?: return@mapNotNull null
                    val movesArr = obj.optJSONArray("moves") ?: return@mapNotNull null
                    val moves = (0 until movesArr.length()).map { movesArr.getString(it) }
                    if (moves.isEmpty()) null else Line(obj.optString("name", ""), moves)
                }
                if (lines.isNotEmpty()) put(key, lines)
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("OpeningBook", "开局库加载失败,将完全依赖搜索: ${e.message}")
        emptyMap()
    }

    private companion object {
        const val BOOK_ASSET = "book/opening_book.json"
        /** 超过这个手数就不再用开局库 */
        const val MAX_BOOK_PLIES = 12
    }
}
