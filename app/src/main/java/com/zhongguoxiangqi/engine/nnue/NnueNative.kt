package com.zhongguoxiangqi.engine.nnue

import android.util.Log
import java.nio.ByteBuffer

/**
 * C++/NEON 求值核心的入口.
 *
 * 为什么要有 native: 纯 Kotlin 标量实现实测比手写评估慢 20~60 倍, 折合少搜
 * 三四层, 网络再准也补不回来. 而 fc1 那一层是 int8 点积, 正是 SIMD 最擅长
 * 的形状 —— NEON 一条 smull 顶 8 路乘加.
 *
 * 这是本项目自己的 C++ 代码, 不涉及任何 GPL 依赖.
 */
internal object NnueNative {

    private const val TAG = "NnueNative"

    /**
     * 库能不能用. 单元测试跑在 JVM 上, 没有 .so, 这里会是 false,
     * 调用方自动退回 Kotlin 实现 —— 所以单测照样能覆盖到求值逻辑.
     */
    val available: Boolean = run {
        val ok = try {
            System.loadLibrary("xqnnue")
            true
        } catch (t: Throwable) {
            false
        }
        // Log 也要包起来: 单元测试跑在 JVM 上, android.util.Log 是没实现的桩,
        // 调用它会抛异常. 类初始化里抛出去就是 ExceptionInInitializerError,
        // 整个类从此不可用 —— 一句日志把退路本身给砸了.
        if (!ok) runCatching { Log.w(TAG, "libxqnnue 不可用, 退回 Kotlin 求值") }
        ok
    }

    external fun nativeCreate(weights: ByteArray): Long
    external fun nativeDestroy(handle: Long)
    external fun nativePieceBuffer(handle: Long): ByteBuffer?
    external fun nativeEval(handle: Long, n: Int, black: Boolean): Int
    external fun nativeHasSimd(): Boolean

    /**
     * NEON 与标量两条路径跑同一批随机局面, 返回不一致的条数.
     *
     * 存在的理由很实在: 开发机上没有 ARM 硬件也没有模拟器, NEON 那段代码
     * 在推上真机之前没有任何办法验证. 与其赌它写对了, 不如让它到了真机自己
     * 证明 —— 对不上就退回标量, 宁可慢也不能算错. NNUE 算错不会崩溃, 只会
     * 让棋力莫名其妙地差.
     */
    external fun nativeSelfCheck(handle: Long, samples: Int): Int

    // ---- 增量累加器 ----
    //
    // 求值时间的 65% 花在累加器上, 而走一步棋只改动 2~3 个特征. 搜索在
    // make/unmake 处调 push/pop, 求值就只剩 fc1 那部分.

    /** 累加器栈能容纳的层数. 超出之后 push 只计数不写栈, 调用方要退回全量求值. */
    external fun nativeAccCapacity(): Int

    /** 用 pieceBuffer 里的局面全量重建, 并把栈深度归零. */
    external fun nativeAccRefresh(handle: Long, n: Int)

    /** 走一步. [capIdx] < 0 表示不吃子. 被吃的子就在 [toSq] 上. */
    external fun nativeAccPush(handle: Long, moverIdx: Int, fromSq: Int, toSq: Int, capIdx: Int)

    /** 走将之后用: 对当前 pieceBuffer 里的局面全量重算并压栈 */
    external fun nativeAccPushRefresh(handle: Long, n: Int)

    external fun nativeAccPop(handle: Long)

    /** 从栈顶累加器求值. 调用前必须确认深度未超容量. */
    external fun nativeAccEval(handle: Long, black: Boolean): Int

    /**
     * 增量路径自检, 返回不一致条数. 验两条不变式:
     * 1. `acc_refresh` + `eval_acc` == `eval_scalar`
     * 2. `acc_apply(父, 一步棋)` == `acc_refresh(走完之后)`
     *
     * 第 2 条才是增量真正的风险: 错了不会崩, 只会让累加器随着搜索慢慢漂移.
     */
    external fun nativeAccSelfCheck(handle: Long, samples: Int): Int
}
