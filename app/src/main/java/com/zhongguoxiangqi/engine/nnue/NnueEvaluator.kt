package com.zhongguoxiangqi.engine.nnue

import com.zhongguoxiangqi.game.Position

/**
 * NNUE 整数推理. **必须与 nnue/export.py 的 int_infer 逐步对应** ——
 * 那边是训练侧的参考实现, 两边算出来不一样的话, 网络训得再好也没用.
 *
 * 每个搜索线程一个实例 (内部有可变缓冲区), 权重从 [NnueNetwork] 共享.
 *
 * 尺度约定:
 *   累加器 / 各层激活值    尺度 QA   (即 1.0 对应 127)
 *   权重                  尺度 QW   (即 1.0 对应 256)
 *   乘积                  尺度 QA*QW, 除以 QW 回到 QA, 再 clamp(0, QA)
 *   输出层不 clamp, 且要除以 QA*QW 才回到厘兵分
 *
 * 除法一律用 Kotlin 的 `/` (截断向零), Python 侧用 np.fix 对齐. 用向下取整
 * 会在负数上差 1 —— 隐藏层因为紧接着 clamp 到 0 看不出来, 输出层会直接差一分.
 */
class NnueEvaluator(private val net: NnueNetwork) : AutoCloseable {

    private val scratchPieces = IntArray(NnueFeatures.MAX_PIECES * 2)

    /**
     * native 句柄; 0 表示走 Kotlin 实现.
     *
     * 拿到句柄之后立刻做一次 NEON vs 标量自检, 不过就退回 Kotlin —— 开发机上
     * 没有 ARM 硬件能验 NEON, 只能让它在真机上自证. 算错的代价不是崩溃, 是
     * 棋力莫名其妙地差, 所以宁可慢也不能带着可疑的实现跑.
     */
    private var handle: Long = 0L
    private var pieceBuf: java.nio.ByteBuffer? = null

    // 增量累加器的状态. 声明必须在 init 之前 —— Kotlin 不允许初始化块引用
    // 后面才声明的属性. 语义说明见下面的"增量累加器"一节.
    private var accCapacity = 0
    private var accDepth = 0
    private var accRooted = false

    init {
        if (NnueNative.available) {
            val h = runCatching { NnueNative.nativeCreate(net.raw) }.getOrDefault(0L)
            if (h == 0L) warnFallback("nativeCreate 返回 0 (多半是 .so 与当前权重格式版本对不上, 忘了跑 build_host.sh?)")
            if (h != 0L) {
                val bad = runCatching { NnueNative.nativeSelfCheck(h, SELF_CHECK_SAMPLES) }
                    .getOrDefault(-1)
                if (bad == 0) {
                    handle = h
                    pieceBuf = NnueNative.nativePieceBuffer(h)
                    if (pieceBuf == null) {
                        warnFallback("拿不到 pieceBuffer")
                        NnueNative.nativeDestroy(h)
                        handle = 0L
                    } else {
                        // 增量路径单独自检一遍. 不过就只关掉增量, 全量 native
                        // 求值照常用 —— 慢一点总比算错强.
                        val accBad = runCatching {
                            NnueNative.nativeAccSelfCheck(h, SELF_CHECK_SAMPLES)
                        }.getOrDefault(-1)
                        if (accBad == 0) {
                            accCapacity = runCatching { NnueNative.nativeAccCapacity() }
                                .getOrDefault(0)
                        } else {
                            runCatching {
                                android.util.Log.e(
                                    "NnueEvaluator",
                                    "增量累加器自检未通过 ($accBad/$SELF_CHECK_SAMPLES 条不一致), " +
                                        "只用全量求值"
                                )
                            }
                        }
                    }
                } else {
                    warnFallback("native 自检未通过 ($bad/$SELF_CHECK_SAMPLES 条不一致)")
                    NnueNative.nativeDestroy(h)
                }
            }
        }
    }

    /** 当前真的在走 native 吗. 给基准测试和诊断用. */
    val usingNative: Boolean get() = handle != 0L

    override fun close() {
        if (handle != 0L) {
            NnueNative.nativeDestroy(handle)
            handle = 0L
            pieceBuf = null
            accCapacity = 0
            accRooted = false
        }
    }

    // ------------------------------------------------------------ 增量累加器
    //
    // 求值时间的 65% 花在累加器上 (32 子 × 256 维 × 2 视角), 而走一步棋只
    // 改动 2~3 个特征. 搜索在 make/unmake 处调 [accPush] / [accPop], 求值
    // 就只剩 fc1 那一半开销.
    //
    // **只在 native 路径上提供**. 纯 Kotlin 那条是正确性退路, 本来就慢 20 倍,
    // 再抄一份增量实现只是多一处能写错的地方 —— 而增量写错的症状是累加器
    // 随搜索慢慢漂移, 最难查的那一类.

    // accCapacity: 累加器栈容量; 0 表示增量不可用.
    // accDepth:    当前栈深度, 由 accPush / accPop 维护, 与 native 侧计数同步.
    // accRooted:   栈底是不是已经用某个真实局面填过. 没有这一条的话, 忘了调
    //              accRefresh 就会拿一整块未初始化的累加器去求值 —— 不崩, 只是
    //              分数全是垃圾, 正是最难查的那种错.
    // 三者都声明在文件上方(init 之前), Kotlin 不允许初始化块引用后面的属性.

    /** 增量求值是否可用 (native 可用 + 自检通过). */
    val incrementalAvailable: Boolean get() = accCapacity > 0

    /**
     * 栈顶累加器现在能不能直接用来求值.
     *
     * 深度超出栈容量时为 false —— 此时 native 侧只增计数不写栈, 调用方要
     * 退回 [evaluate]. 弹回容量以内之后又会变回 true, 不需要重新 [accRefresh].
     */
    val accUsable: Boolean get() = accRooted && accDepth < accCapacity

    /** 进搜索前调一次: 用 [pos] 全量重建累加器, 栈深度归零. */
    fun accRefresh(pos: Position) {
        if (accCapacity == 0) return
        val buf = pieceBuf ?: return
        val n = NnueFeatures.extract(pos, scratchPieces)
        buf.clear()
        for (i in 0 until n) {
            buf.put(scratchPieces[i * 2].toByte())
            buf.put(scratchPieces[i * 2 + 1].toByte())
        }
        accDepth = 0
        NnueNative.nativeAccRefresh(handle, n)
        accRooted = true
    }

    /**
     * 走一步. 必须与搜索的 `doMove` **一一配对**, 且传的是走子**之前**读到的
     * 棋子编码 —— 走完之后起点已经空了, 落点上的被吃子也没了.
     *
     * [capIdx] 传 -1 表示不吃子.
     */
    fun accPush(moverIdx: Int, fromSq: Int, toSq: Int, capIdx: Int) {
        if (accCapacity == 0) return
        NnueNative.nativeAccPush(handle, moverIdx, fromSq, toSq, capIdx)
        accDepth++
    }

    /** 与 [accPush] 配对, 对应搜索的 `undoMove`. */
    fun accPop() {
        if (accCapacity == 0) return
        NnueNative.nativeAccPop(handle)
        if (accDepth > 0) accDepth--
    }

    /** 从栈顶累加器求值. 调用前必须先看 [accUsable]. */
    fun accEval(blackToMove: Boolean): Int =
        NnueNative.nativeAccEval(handle, blackToMove)

    /**
     * 基准测试用: 关掉增量, 强制退回全量重算.
     *
     * 增量 vs 全量的对比必须在**同一个进程、同一个网络**里做, 否则量到的是
     * JIT 预热和睿频的差别 —— 上一轮的 SIMD 实验就是这么被假的 1.22x 骗过一次.
     */
    internal fun disableIncrementalForBench() {
        accCapacity = 0
        accRooted = false
    }

    /** 两个视角的累加器拼在一起, 正好是 fc1 的输入 */
    private val acc = IntArray(net.l1 * 2)
    private val h1 = IntArray(net.l2)
    private val h2 = IntArray(net.l3)

    /**
     * 返回**走子方视角**的评估值, 单位厘兵. 正数表示走子方占优.
     *
     * 这条是**全量重算**路径 (32 个子 × 256 维 × 2 视角): 给搜索之外的调用方
     * (数据生成的标注、单测、诊断) 用, 也是增量路径的正确性基准.
     *
     * 搜索里走的是上面那套增量累加器, 只改动到的那两三个特征. 两者必须给出
     * **完全相同**的分数 —— `SimpleEngine.accVerify` 打开后会逐次比对.
     */
    fun evaluate(pos: Position): Int {
        val n = NnueFeatures.extract(pos, scratchPieces)
        return evaluatePieces(scratchPieces, n, NnueFeatures.sideToMoveIsBlack(pos))
    }

    /**
     * 直接吃 (pieceIdx, sq) 列表的版本. 存在的理由是可测:
     * 训练数据的 72 字节记录里存的就是这个形式, 于是可以拿同一批记录喂给
     * Python 的 int_infer 和这里, 逐条比对输出是否**完全相同**.
     *
     * [pieces] 每子两项, 长度至少 2*[n]. 允许传入外部数组, 不做拷贝.
     */
    fun evaluatePieces(pieces: IntArray, n: Int, blackToMove: Boolean): Int {
        val buf = pieceBuf
        if (handle != 0L && buf != null) {
            // 直接往 native 的缓冲里写, 免掉每次求值一次数组拷贝 ——
            // JNI 调用本身才十几纳秒, 拷贝反而是大头
            buf.clear()
            for (i in 0 until n) {
                buf.put(pieces[i * 2].toByte())
                buf.put(pieces[i * 2 + 1].toByte())
            }
            return NnueNative.nativeEval(handle, n, blackToMove)
        }
        return evaluateKotlin(pieces, n, blackToMove)
    }

    /** 纯 Kotlin 实现. 既是 native 不可用时的退路, 也是它的正确性基准. */
    fun evaluateKotlin(pieces: IntArray, n: Int, blackToMove: Boolean): Int {
        // 权重数组一律先取到局部变量再进循环.
        //
        // net.ftWeight 是 by lazy 的 (为了让走 native 的设备不去分配这几百 KB),
        // 而 lazy 的每次读取都要过一遍委托的同步/volatile 检查. 写成
        // net.ftWeight[i] 放在最内层循环里, 一次求值就是一万六千多次委托访问 ——
        // 实测真机上 Kotlin 路径因此慢了约 50 倍. 取到局部变量之后就只剩一次.
        val fc1W = net.fc1Weight
        val fc1B = net.fc1Bias
        val fc2W = net.fc2Weight
        val fc2B = net.fc2Bias
        val outW = net.outWeight

        refresh(pieces, n, blackToMove)

        // fc1: (L2 × L1*2) @ acc
        val inDim = net.l1 * 2
        for (o in 0 until net.l2) {
            var sum = fc1B[o]
            val row = o * inDim
            for (i in 0 until inDim) {
                sum += fc1W[row + i].toInt() * acc[i]
            }
            h1[o] = clipped(sum / NnueNetwork.QW)
        }

        // fc2: (L3 × L2) @ h1
        for (o in 0 until net.l3) {
            var sum = fc2B[o]
            val row = o * net.l2
            for (i in 0 until net.l2) {
                sum += fc2W[row + i].toInt() * h1[i]
            }
            h2[o] = clipped(sum / NnueNetwork.QW)
        }

        // 输出层: h2 带着 QA 的尺度, 权重带着 QW 的尺度, 所以除 QA*QW
        var out = net.outBias
        for (i in 0 until net.l3) {
            out += outW[i] * h2[i]
        }
        return out / (NnueNetwork.QA * NnueNetwork.QW)
    }

    /**
     * 重算两个视角的累加器.
     *
     * "我方"永远是走子方: 黑走棋时把棋盘翻过来看. 网络因此只需要学
     * "我方/敌方"的概念, 不必分别学一遍红方和黑方 —— 参数量减半, 而且
     * 天然带上了红黑对称的先验.
     */
    private fun refresh(pieces: IntArray, n: Int, blackToMove: Boolean) {
        val l1 = net.l1
        val ftW = net.ftWeight      // 同上: lazy 属性绝不能出现在内层循环里
        val ftB = net.ftBias
        for (i in 0 until l1) {
            val b = ftB[i].toInt()
            acc[i] = b
            acc[l1 + i] = b
        }

        for (p in 0 until n) {
            val idx = pieces[p * 2]
            val sq = pieces[p * 2 + 1]

            // 红走棋时"我方"就是原样看棋盘, 黑走棋时两个视角对调
            val raw = NnueFeatures.ownFeature(idx, sq)
            val flipped = NnueFeatures.oppFeature(idx, sq)
            val ownRow = (if (blackToMove) flipped else raw) * l1
            val oppRow = (if (blackToMove) raw else flipped) * l1

            for (i in 0 until l1) {
                acc[i] += ftW[ownRow + i].toInt()
                acc[l1 + i] += ftW[oppRow + i].toInt()
            }
        }

        for (i in acc.indices) {
            acc[i] = clipped(acc[i])
        }
    }

    /**
     * native 不可用时必须**吵**一声.
     *
     * 原来只有 `runCatching { android.util.Log.e(...) }`, 而单元测试跑在桌面
     * JVM 上, android.util.Log 是空壳 —— 日志被 runCatching 一并吞掉, 于是
     * 悄悄退回慢 20 倍的 Kotlin 路径, 表现只是"测试变慢", 不报任何错。
     *
     * 改格式版本却忘了重编 .so 时就是这样: 编译通过、测试不失败, 只是全量
     * 套件从 30 秒变成十几分钟, 而我先后归因到置换表冲突和 CPU 争抢, 两次
     * 都错, 最后靠 jstack 看到栈里是 evaluateKotlin 才定位。
     */
    private fun warnFallback(why: String) {
        System.err.println("[NnueEvaluator] !! 退回 Kotlin 求值(慢约 20 倍): $why")
        runCatching { android.util.Log.e("NnueEvaluator", why) }
    }

    private companion object {
        /**
         * 自检样本数. 几百条就足以逼出下标算错、通道错位这类系统性错误
         * (它们会让几乎每条都对不上); 而加载只做一次, 这点开销无所谓.
         */
        const val SELF_CHECK_SAMPLES = 256
    }

    /** ClippedReLU 的定点版本 */
    private fun clipped(v: Int): Int =
        if (v < 0) 0 else if (v > NnueNetwork.QA) NnueNetwork.QA else v
}
