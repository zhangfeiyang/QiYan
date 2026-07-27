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
}
