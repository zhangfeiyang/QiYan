package com.zhongguoxiangqi.game

import org.junit.Test
import java.io.File

/**
 * 手工跑一次, 把千题写进 assets. 日常测试走 [CatalogVerificationTest], 不重新生成.
 */
class CatalogGenerateTest {
    @Test fun writeCatalog() {
        if (System.getProperty("generate.catalog") != "true") return
        val started = System.currentTimeMillis()
        val entries = CatalogGenerator.generate(1000)
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        println("generated ${entries.size} in $seconds s")
        println(entries.groupingBy { it.tactic }.eachCount().entries.sortedByDescending { it.value })
        val out = File("src/main/assets/puzzles/catalog.txt")
        out.parentFile.mkdirs()
        out.writeText(entries.mapIndexed { index, entry ->
            "${index + 1001}\t${entry.tactic}\t${entry.fen}"
        }.joinToString("\n") + "\n")
        check(entries.size == 1000) { "only ${entries.size}" }
    }
}
