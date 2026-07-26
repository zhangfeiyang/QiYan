package com.zhongguoxiangqi.engine

/**
 * Pikafish 引擎的 JNI 桥 (libpikafish.so).
 *
 * 每个引擎实例单线程: go / stop / set_position 必须串行.
 * C++ 端用 g_engine_mutex 保护.
 *
 * loadLibrary 在 ZhongguoXiangqiApp.onCreate() 中提前完成.
 */
object PikafishNative {

    /** 初始化引擎并加载 NNUE. nnuePath 是 pikafish.nnue 的绝对路径. */
    @JvmStatic external fun initGlobal(nnuePath: String): Int

    /** 修改 UCI 选项. 耗时约数毫秒. */
    @JvmStatic external fun setoption(name: String, value: String)

    /** 设局面. fen 是起始 FEN, movesUci 是后续 UCI 着法; movesCount 是其长度. */
    @JvmStatic external fun setposition(
        fen: String, movesUci: Array<String>, movesCount: Int
    ): Int

    /** 启动搜索 (异步). movetimeMs/depth 为 0 表示不限; infinite=1 表示不限时. */
    @JvmStatic external fun go(movetimeMs: Long, depth: Int, infinite: Int)

    /** 请求停止当前搜索 (不阻塞). */
    @JvmStatic external fun stop()

    /** 阻塞等待当前搜索完成. */
    @JvmStatic external fun waitForSearch()

    /** 把缓存的 bestmove UCI 字符串写入 buf, 返回写入字节数. */
    @JvmStatic external fun getBestmove(buf: ByteArray): Int

    /** 把缓存的 ponder UCI 字符串写入 buf, 返回写入字节数. */
    @JvmStatic external fun getPondermove(buf: ByteArray): Int

    /** 清空置换表 + 历史表 (开新局时调用). */
    @JvmStatic external fun searchClear()

    /** 当前是否在搜索中 (1 = 是). */
    @JvmStatic external fun isSearching(): Int

    /** 释放引擎. App 退出前调用. */
    @JvmStatic external fun shutdown()
}