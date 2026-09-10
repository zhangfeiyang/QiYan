package com.zhongguoxiangqi.engine

/**
 * PikaFish（GPL-3）引擎的 JNI 入口。
 *
 * 对应 C++ 侧 `third_party/Pikafish/src/pikafish_jni.cpp`。所有调用在 C++
 * 里经 g_mutex 串行化 —— PikaFish 引擎不保证并发安全，App 侧必须保证
 * 同一时刻只有一个搜索在进行（EngineManager 已保证）。
 */
object PikafishNative {

    init {
        System.loadLibrary("pikafish")
    }

    /** 一次性初始化（攻击表、开局库）。返回 0 成功，非 0 失败。 */
    external fun initGlobal(nnuePath: String?): Int

    /** 设置 UCI 选项（如 Hash、Threads）。 */
    external fun setoption(name: String, value: String)

    /**
     * 设置局面。
     * @return 0 成功，-1 失败
     */
    external fun setposition(fen: String, movesUci: Array<String>, movesCount: Int): Int

    /**
     * 异步开始搜索。
     * @param movetimeMs 限时毫秒（0 = 不限时）
     * @param depth 最大深度（0 = 不限）
     * @param infinite 1 = 无限思考直到 stop
     */
    external fun go(movetimeMs: Long, depth: Int, infinite: Int)

    /** 请求停止当前搜索。 */
    external fun stop()

    /** 阻塞直到搜索结束（不能持锁，搜索线程需要运行）。 */
    external fun waitForSearch()

    /**
     * 读取最佳着法（UCI 字符串写入 buf）。
     * @return 写入的字节数
     */
    external fun getBestmove(buf: ByteArray): Int

    /** 读取思考着法（可能为空）。 */
    external fun getPondermove(buf: ByteArray): Int

    /** 清空搜索状态（置换表等）。 */
    external fun searchClear()

    /** 是否正在搜索。 */
    external fun isSearching(): Int

    /**
     * 读取最近一次搜索信息。
     * out[0]=depth, out[1]=scoreCp, out[2]=scoreMate,
     * out[3]=timeMs(低32), out[4]=nodes(低32), out[5]=nps(低32)
     */
    external fun getLastInfo(out: IntArray)

    /**
     * 读取本次搜索的 MultiPV 候选着法, 供"棋手风格"挑选用.
     *
     * 格式: "move:cp:mate;move:cp:mate;..." — 第 1 条是引擎的最佳着法,
     * 评分都是走子方视角. 只有以 MultiPV>1 搜索时才有内容.
     *
     * @return 写入的字节数
     */
    external fun getMultiPvCandidates(buf: ByteArray): Int

    /** 关闭引擎。 */
    external fun shutdown()
}
