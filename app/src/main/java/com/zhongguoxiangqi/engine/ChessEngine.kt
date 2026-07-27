package com.zhongguoxiangqi.engine

/**
 * 引擎抽象接口.
 *
 * 所有引擎实现必须遵循此接口,以便 EngineManager 切换不同难度的引擎.
 */
interface ChessEngine {

    /** 引擎是否已就绪 */
    fun isReady(): Boolean

    /** 引擎名称(用于显示) */
    fun name(): String

    /** 引擎最大强度(1-10) */
    fun maxStrength(): Int

    /**
     * 启动引擎(加载 NNUE 文件,准备 UCI 环境).
     * @return 是否成功
     */
    fun start(): Boolean

    /** 关闭引擎进程 */
    fun shutdown()

    /**
     * 设置局面.
     * @param fen 起始 FEN
     * @param moves 后续走法(UCI 格式,如 "b2e2")
     */
    fun setPosition(fen: String, moves: List<String>)

    /**
     * 异步开始搜索.
     * 搜索结果通过 [setListener] 设置的监听器回调.
     *
     * @param params 搜索参数(深度、时间、节点等)
     */
    fun startSearch(params: SearchParams)

    /** 停止当前搜索(同步) */
    fun stopSearch()

    /**
     * 设置监听器.
     * 回调在引擎工作线程中触发,UI 层需自行切到主线程.
     */
    fun setListener(listener: EngineListener?)
}

/**
 * 搜索参数.
 */
data class SearchParams(
    /** 最大搜索深度 (ply). 0 表示无限制 */
    val depth: Int = 0,
    /** 思考时间(毫秒). 0 表示无限制 */
    val movetime: Long = 0,
    /** 最大搜索节点数. 0 表示无限制 */
    val nodes: Long = 0,
    /** 是否无限思考(直到收到 stop) */
    val infinite: Boolean = false,
    /** 多线程数 (1..8) */
    val threads: Int = 1,
    /** 哈希表大小 (MB) */
    val hashMb: Int = 16,
    /** 多 PV 行数 (分析时常用, 主对局一般 1) */
    val multiPv: Int = 1,
    /**
     * [movetime] 是**硬上限**, 不允许按难度往上抬.
     *
     * 默认 false 是给对局用的: 那边"难度高"就该"想得久", EngineManager
     * 会按等级把 movetime 抬到 2~8 秒. 但实时支招正相反 —— 延迟才是硬
     * 约束, 宁可要 0.6 秒算出的好棋, 也不要 4 秒后的最佳棋. 这个标志就是
     * 让调用方把这件事说清楚.
     */
    val strictMovetime: Boolean = false
) {
    companion object {
        val DEFAULT_GAME = SearchParams(depth = 0, movetime = 3000, threads = 2, hashMb = 64, multiPv = 1)
        val DEFAULT_ANALYSIS = SearchParams(infinite = true, threads = 2, hashMb = 64, multiPv = 3)
        val INSTANT_HINT = SearchParams(movetime = 1500, threads = 1, hashMb = 32, multiPv = 1)
    }
}

/**
 * 引擎事件监听器.
 */
interface EngineListener {
    /** 收到 UCI 握手完成 */
    fun onReady()
    /** 搜索信息更新 */
    fun onInfo(info: EngineInfo)
    /** 最佳走法确定 */
    fun onBestMove(bestMove: String, ponderMove: String?)
    /** 引擎出错(将触发降级到备用引擎) */
    fun onError(message: String)
}

/**
 * 搜索过程中引擎返回的信息.
 *
 * @param depth 当前搜索深度 (ply)
 * @param seldepth 选择性深度
 * @param timeMs 已用时间(毫秒)
 * @param nodes 已搜索节点数
 * @param nps 每秒搜索节点数
 * @param scoreCp 形势评估(厘兵). 正值=红方优势,负值=黑方优势
 * @param scoreMate 杀棋步数. 正值=红方 N 步杀,负值=黑方 N 步杀. 0 表示非杀棋
 * @param pv 主变例(走法列表,UCI 格式)
 * @param pvIndex 第几条 PV (用于多 PV)
 * @param hashfull 哈希表填充率 (0..1000)
 */
data class EngineInfo(
    val depth: Int = 0,
    val seldepth: Int = 0,
    val timeMs: Long = 0,
    val nodes: Long = 0,
    val nps: Long = 0,
    val scoreCp: Int = 0,
    val scoreMate: Int = 0,
    val pv: List<String> = emptyList(),
    val pvIndex: Int = 0,
    val hashfull: Int = 0
) {
    /** 优势评分(厘兵,从当前走子方角度) */
    fun advantageCp(sideToMove: Int): Int {
        val s = if (scoreMate != 0) {
            // 杀棋: 大数表示必胜
            if (scoreMate > 0) 30000 - scoreMate * 50 else -30000 - scoreMate * 50
        } else scoreCp
        // 如果轮到对手走, 评估值需要从对方角度翻转(信息约定为相对当前走子方)
        return s
    }
}