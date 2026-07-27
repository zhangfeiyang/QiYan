package com.zhongguoxiangqi.engine.nnue

import android.content.Context
import android.util.Log

/**
 * 全局唯一的 NNUE 权重来源.
 *
 * **更新网络只需要替换 assets 里的那一个文件**, 代码一行都不用改:
 * 层宽、量化尺度全部写在文件头里 (见 nnue/FORMAT.md), 加载时按文件说的来。
 * 换更宽的网络、换训练了更久的权重, 都只是换文件。
 *
 * 权重只读且几百 KB, 全进程共享一份; 每个搜索线程各自 new 一个
 * [NnueEvaluator] (那里面有可变缓冲和 native 句柄)。
 */
object NnueProvider {

    private const val TAG = "NnueProvider"

    /** 换网络就换这个文件, 不用动代码 */
    const val ASSET_PATH = "models/xiangqi.nnue"

    @Volatile
    private var cached: NnueNetwork? = null

    @Volatile
    private var tried = false

    /**
     * 拿到网络; 没有或加载失败返回 null, 调用方退回手写评估.
     *
     * 失败**不抛异常**: 网络只是让棋力更好的可选件, 没有它引擎照样能下。
     * 为一个可选件让整个 App 起不来是不划算的。但失败要留下日志, 否则
     * "悄悄退回手写评估"会被误当成"网络没效果"。
     */
    fun get(context: Context): NnueNetwork? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            if (tried) return null
            tried = true
            return try {
                val net = context.applicationContext.assets.open(ASSET_PATH).use {
                    NnueNetwork.load(it)
                }
                Log.i(TAG, "NNUE 已加载: l1=${net.l1} l2=${net.l2} l3=${net.l3}, " +
                    "native=${NnueNative.available}")
                cached = net
                net
            } catch (e: Exception) {
                // 文件不存在是正常情况(还没训练出可用的网络时), 不必吓人
                Log.i(TAG, "未启用 NNUE, 使用手写评估: ${e.message}")
                null
            }
        }
    }

    /** 造一个绑定当前网络的求值器; 没有网络时返回 null. */
    fun newEvaluator(context: Context): NnueEvaluator? =
        get(context)?.let { NnueEvaluator(it) }
}
