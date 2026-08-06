package com.zhongguoxiangqi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主机 PikaFish JNI 冒烟：验证 12 个原生函数签名与 C++ 侧一致，
 * 并实际完成一次搜索。
 *
 * 需要先运行 app/src/main/cpp/build_host_pikafish.sh 生成
 * host_lib/libpikafish.so，并把 java.library.path 指到该目录
 * （Gradle 测试配置已指向 app/src/main/cpp/host_lib）。
 */
class PikafishNativeSmokeTest {

    @Test
    fun searchReturnsMove() {
        // NNUE 路径：用 third_party 里的权重（测试机是宿主机）。
        val nnue = System.getProperty("pikafish.nnue")
            ?: "/home/zhangfy/zhongguoxiangqi/third_party/Pikafish/src/pikafish.nnue"
        assertEquals("initGlobal 应成功", 0, PikafishNative.initGlobal(nnue))

        try {
            PikafishNative.setoption("Threads", "1")
            PikafishNative.setoption("Hash", "16")
            val rc = PikafishNative.setposition(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
                emptyArray(), 0
            )
            assertEquals("setposition 应成功", 0, rc)

            PikafishNative.go(movetimeMs = 2000L, depth = 0, infinite = 0)
            PikafishNative.waitForSearch()

            val buf = ByteArray(8)
            val n = PikafishNative.getBestmove(buf)
            val move = String(buf, 0, n)
            assertTrue("应返回 4 字符 UCI 着法，实际: '$move'", move.length == 4)

            val info = IntArray(6)
            PikafishNative.getLastInfo(info)
            assertTrue("深度应 > 0，实际: ${info[0]}", info[0] > 0)
        } finally {
            PikafishNative.shutdown()
        }
    }
}
