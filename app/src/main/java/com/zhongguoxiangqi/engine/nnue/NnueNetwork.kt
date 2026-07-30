package com.zhongguoxiangqi.engine.nnue

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 量化后的 NNUE 权重. 由 nnue/export.py 写出, 格式见 nnue/FORMAT.md.
 *
 * 持有的是**原始字节**, 各层权重按需解析.
 *
 * 这么做是因为端上真正干活的是 C++/NEON 实现, 它直接吃原始字节;
 * Kotlin 侧那份数组只在没有 native 库时(单元测试、native 自检不过)才用得上.
 * 两份都常驻就是白占 645KB —— 用 lazy 之后, 走 native 的设备上那些数组
 * 根本不会被分配.
 */
class NnueNetwork private constructor(
    val raw: ByteArray,
    val l1: Int,
    val l2: Int,
    val l3: Int,
) {

    val ftWeight: ShortArray by lazy { readAt(0) { b, n -> ShortArray(n) { b.short } } }
    val ftBias: ShortArray by lazy { readAt(1) { b, n -> ShortArray(n) { b.short } } }
    val fc1Weight: ByteArray by lazy { readAt(2) { b, n -> ByteArray(n).also(b::get) } }
    val fc1Bias: IntArray by lazy { readAt(3) { b, n -> IntArray(n) { b.int } } }
    val fc2Weight: ByteArray by lazy { readAt(4) { b, n -> ByteArray(n).also(b::get) } }
    val fc2Bias: IntArray by lazy { readAt(5) { b, n -> IntArray(n) { b.int } } }
    val outWeight: IntArray by lazy { readAt(6) { b, n -> IntArray(n) { b.int } } }
    val outBias: Int by lazy { readAt(7) { b, _ -> b.int } }

    /** 各段的 (元素数, 每元素字节数), 顺序即文件中的顺序 */
    private fun sections(): List<Pair<Int, Int>> = listOf(
        NnueFeatures.FEATURES * l1 to 2,   // ft.weight  i16
        l1 to 2,                           // ft_bias    i16
        l2 * (l1 * 2) to 1,                // fc1.weight i8
        l2 to 4,                           // fc1.bias   i32
        l3 * l2 to 1,                      // fc2.weight i8
        l3 to 4,                           // fc2.bias   i32
        l3 to 4,                           // out.weight i32
        1 to 4,                            // out.bias   i32
    )

    private fun <T> readAt(index: Int, reader: (ByteBuffer, Int) -> T): T {
        val secs = sections()
        var offset = HEADER_BYTES
        for (i in 0 until index) offset += secs[i].first * secs[i].second
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(offset)
        return reader(buf, secs[index].first)
    }

    companion object {
        const val HEADER_BYTES = 4 + 7 * 4

        /** 激活值尺度: ClippedReLU 的上界 1.0 对应 QA */
        const val QA = 127

        /**
         * fc1/fc2 的权重尺度. 必须与 C++ 侧 nnue_core.h 的 kQW 和
         * nnue/export.py 的 QW 一致 —— 三处对不上就是静默算错.
         *
         * 从 64 提到 256 的理由: 实测 fc1/fc2 的权重最大只到 0.15~0.35,
         * 乘 64 之后只占到 int8 的 9~22 (满量程 127), 二三十个档位, 四舍五入
         * 把精度磨掉了。这不是溢出(越界数一直是 0), 是分辨率浪费。逐层消融
         * 显示 98% 的量化误差出在这两层。
         */
        const val QW = 256

        /**
         * 层宽上限, 必须与 C++ 侧 nnue_core.h 的 kMaxL1 / kMaxL23 一致.
         *
         * 这不是"建议值": native 推理用的是栈上定长缓冲, 层宽超了会直接踩坏栈.
         * 所以拦在这里, 别让不可信的文件走到 native.
         */
        const val MAX_L1 = 1024
        const val MAX_L23 = 256

        /** 逐字节比对而不是拼成 int 比: 拼 int 要考虑字节序, 很容易写反 */
        private val MAGIC = byteArrayOf('X'.code.toByte(), 'Q'.code.toByte(),
            'N'.code.toByte(), 'N'.code.toByte())
        private const val VERSION = 2   // QW 64->256 且 out.weight i16->i32

        /**
         * 从流里加载. 结构对不上就抛异常, 绝不"尽力而为地读一读".
         *
         * 尺寸读错的后果不是崩溃而是**静默地变弱** —— 权重错位之后网络照样
         * 输出数字, 引擎照样下棋, 只是棋力莫名其妙地差. 那种问题几乎查不出来,
         * 所以宁可在加载时就炸掉.
         */
        @Throws(IOException::class)
        fun load(input: InputStream): NnueNetwork {
            val raw = input.readBytes()
            if (raw.size < HEADER_BYTES) throw IOException("NNUE 文件太短")
            if (!raw.copyOf(4).contentEquals(MAGIC)) {
                throw IOException(
                    "不是 NNUE 文件 (magic=${String(raw, 0, 4, Charsets.ISO_8859_1)})"
                )
            }

            val hb = ByteBuffer.wrap(raw, 4, 7 * 4).order(ByteOrder.LITTLE_ENDIAN)
            val version = hb.int
            if (version != VERSION) {
                throw IOException("NNUE 版本不支持: $version, 期望 $VERSION")
            }
            check(hb.int, NnueFeatures.FEATURES, "FEATURES")
            val l1 = dim(hb.int, "L1", MAX_L1)
            val l2 = dim(hb.int, "L2", MAX_L23)
            val l3 = dim(hb.int, "L3", MAX_L23)
            check(hb.int, QA, "QA")
            check(hb.int, QW, "QW")

            // native 的 NEON 路径按 16 个一组处理, 不写收尾循环
            if (l1 % 16 != 0) throw IOException("NNUE L1 必须是 16 的倍数: $l1")

            val net = NnueNetwork(raw, l1, l2, l3)
            val expected = HEADER_BYTES + net.sections().sumOf { it.first * it.second }
            if (raw.size != expected) {
                throw IOException(
                    "NNUE 文件长度不符: 实际 ${raw.size} 字节, 按结构应为 $expected"
                )
            }
            return net
        }

        /**
         * 层宽检查. 上界不是防手误, 是防**损坏或伪造的文件**: 这个数字直接
         * 决定 native 侧栈上缓冲的用量, 放过去就是踩栈.
         */
        private fun dim(v: Int, name: String, max: Int): Int {
            if (v <= 0 || v > max) throw IOException("NNUE $name 不合理: $v")
            return v
        }

        private fun check(got: Int, expected: Int, name: String) {
            if (got != expected) {
                throw IOException("NNUE $name 不匹配: 文件是 $got, 端上是 $expected")
            }
        }
    }
}
