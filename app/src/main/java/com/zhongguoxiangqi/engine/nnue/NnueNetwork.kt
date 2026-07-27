package com.zhongguoxiangqi.engine.nnue

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 量化后的 NNUE 权重. 由 nnue/export.py 写出, 格式见 nnue/FORMAT.md.
 *
 * 权重是只读的, 六百多 KB, 全进程共享一份; 推理时的临时缓冲放在
 * [NnueEvaluator] 里, 每个搜索线程一个. 分成两个类就是为了这件事 ——
 * 合在一起的话, 要么多线程搜索时互相踩缓冲区, 要么每个线程复制一份权重.
 */
class NnueNetwork private constructor(
    val l1: Int,
    val l2: Int,
    val l3: Int,
    val ftWeight: ShortArray,   // [FEATURES * l1] 行主序
    val ftBias: ShortArray,     // [l1]
    val fc1Weight: ByteArray,   // [l2 * (l1*2)] 行主序
    val fc1Bias: IntArray,      // [l2]
    val fc2Weight: ByteArray,   // [l3 * l2]
    val fc2Bias: IntArray,      // [l3]
    val outWeight: ShortArray,  // [l3]
    val outBias: Int,
) {

    companion object {
        /**
         * 层宽从文件头读, 不写死在代码里.
         *
         * 因为它是要**调**的: 求值速度基本正比于 l1, 而棋力随 l1 递增,
         * 中间那个甜点只能实测出来. 写死的话每试一个宽度都要改代码重编,
         * 而且端上还可能装着按另一个宽度导出的文件.
         *
         * FEATURES 相反, 它由数据格式定死 (见 FORMAT.md), 改了等于作废
         * 已经生成的全部样本, 所以必须严格校验.
         */
        private const val MAX_LAYER = 4096

        /** 激活值尺度: ClippedReLU 的上界 1.0 对应 QA */
        const val QA = 127

        /** 权重尺度 */
        const val QW = 64

        /** 逐字节比对而不是拼成 int 比: 拼 int 要考虑字节序, 很容易写反 */
        private val MAGIC = byteArrayOf('X'.code.toByte(), 'Q'.code.toByte(),
            'N'.code.toByte(), 'N'.code.toByte())
        private const val VERSION = 1

        /**
         * 从流里加载. 结构对不上就抛异常, 绝不"尽力而为地读一读".
         *
         * 尺寸读错的后果不是崩溃而是**静默地变弱** —— 权重错位之后网络照样
         * 输出数字, 引擎照样下棋, 只是棋力莫名其妙地差. 那种问题几乎查不出来,
         * 所以宁可在加载时就炸掉.
         */
        @Throws(IOException::class)
        fun load(input: InputStream): NnueNetwork {
            val data = DataInputStream(input.buffered())

            val magic = ByteArray(4)
            data.readFully(magic)
            if (!magic.contentEquals(MAGIC)) {
                throw IOException(
                    "不是 NNUE 文件 (magic=${String(magic, Charsets.ISO_8859_1)})"
                )
            }

            val header = ByteArray(7 * 4)
            data.readFully(header)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val version = hb.int
            if (version != VERSION) {
                throw IOException("NNUE 版本不支持: $version, 期望 $VERSION")
            }
            check(hb, NnueFeatures.FEATURES, "FEATURES")
            val l1 = dim(hb, "L1")
            val l2 = dim(hb, "L2")
            val l3 = dim(hb, "L3")
            check(hb, QA, "QA")
            check(hb, QW, "QW")

            val ftWeight = readShorts(data, NnueFeatures.FEATURES * l1)
            val ftBias = readShorts(data, l1)
            val fc1Weight = readBytes(data, l2 * (l1 * 2))
            val fc1Bias = readInts(data, l2)
            val fc2Weight = readBytes(data, l3 * l2)
            val fc2Bias = readInts(data, l3)
            val outWeight = readShorts(data, l3)
            val outBias = readInts(data, 1)[0]

            // 文件应当到此为止. 还有剩余说明导出脚本和这里对格式的理解不一致,
            // 前面读到的东西很可能已经错位了.
            if (data.read() != -1) {
                throw IOException("NNUE 文件尾部有多余数据, 格式不一致")
            }

            return NnueNetwork(
                l1, l2, l3, ftWeight, ftBias, fc1Weight, fc1Bias,
                fc2Weight, fc2Bias, outWeight, outBias,
            )
        }

        /**
         * 读一个层宽. 上界检查不是防手误, 是防**恶意或损坏的文件**:
         * 这里读到的数字直接决定后面要分配多大的数组, 一个 20 亿的层宽会
         * 让 App 在加载网络时直接 OOM 崩掉.
         */
        private fun dim(hb: ByteBuffer, name: String): Int {
            val v = hb.int
            if (v <= 0 || v > MAX_LAYER) {
                throw IOException("NNUE $name 不合理: $v")
            }
            return v
        }

        private fun check(hb: ByteBuffer, expected: Int, name: String) {
            val got = hb.int
            if (got != expected) {
                throw IOException("NNUE $name 不匹配: 文件是 $got, 端上是 $expected")
            }
        }

        private fun readBuf(data: DataInputStream, bytes: Int): ByteBuffer {
            val raw = ByteArray(bytes)
            data.readFully(raw)
            return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun readShorts(data: DataInputStream, n: Int): ShortArray {
            val buf = readBuf(data, n * 2)
            return ShortArray(n) { buf.short }
        }

        private fun readInts(data: DataInputStream, n: Int): IntArray {
            val buf = readBuf(data, n * 4)
            return IntArray(n) { buf.int }
        }

        private fun readBytes(data: DataInputStream, n: Int): ByteArray {
            val raw = ByteArray(n)
            data.readFully(raw)
            return raw
        }
    }
}
