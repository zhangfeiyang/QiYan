package com.zhongguoxiangqi.engine

import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Square
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 校验开局库**数据本身**是合法的.
 *
 * 之前库里最后一步是"炮吃自己的马", 而且文件以 `//` 注释开头导致 JSON
 * 根本解析不了(静默变成空库). 这里直接读 assets 里的文件做把关.
 */
class OpeningBookLineTest {

    private val bookFile = File("src/main/assets/book/opening_book.json")

    @Test
    fun `book file is valid json`() {
        assertTrue("找不到开局库文件: ${bookFile.absolutePath}", bookFile.exists())
        // 解析失败会直接抛异常
        JSONObject(bookFile.readText())
    }

    @Test
    fun `every book line is playable from the start position`() {
        val root = JSONObject(bookFile.readText())
        var checkedLines = 0

        for (key in root.keys()) {
            val entry = root.optJSONObject(key) ?: continue
            val lines = entry.optJSONArray("lines") ?: continue

            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                val name = line.optString("name", "第${i + 1}条")
                val moves = line.getJSONArray("moves")

                val pos = Position()
                assertTrue("变例[$name] 的起始局面无法载入", pos.setFromFen(key))

                for (j in 0 until moves.length()) {
                    val uci = moves.getString(j)
                    val move = parseUci(uci)
                    assertNotNull("变例[$name] 第${j + 1}步 '$uci' 无法解析", move)
                    assertTrue(
                        "变例[$name] 第${j + 1}步 '$uci' 在该局面下不合法\n${pos.prettyPrint()}",
                        pos.isLegalMove(move!!)
                    )
                    pos.doMove(move)
                }
                checkedLines++
            }
        }
        assertTrue("开局库里应至少有一条变例", checkedLines > 0)
    }

    private fun parseUci(uci: String): Move? {
        if (uci.length < 4) return null
        val fromCol = uci[0] - 'a'
        val fromRow = 9 - uci[1].digitToInt()
        val toCol = uci[2] - 'a'
        val toRow = 9 - uci[3].digitToInt()
        if (!Square.valid(fromRow, fromCol) || !Square.valid(toRow, toCol)) return null
        return Move.make(Square.make(fromRow, fromCol), Square.make(toRow, toCol))
    }
}
