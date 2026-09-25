package com.zhongguoxiangqi.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zhongguoxiangqi.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhongguoxiangqi.analysis.WinRateAnalyzer
import com.zhongguoxiangqi.analysis.WinRateSnapshot
import com.zhongguoxiangqi.data.GameRecord
import com.zhongguoxiangqi.data.GameRecordDao
import com.zhongguoxiangqi.engine.DifficultySettings
import com.zhongguoxiangqi.engine.EngineInfo
import com.zhongguoxiangqi.engine.EngineListener
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.engine.PikafishEngine
import com.zhongguoxiangqi.engine.PlayStyle
import com.zhongguoxiangqi.engine.SearchParams
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.See
import com.zhongguoxiangqi.ui.common.Notation
import com.zhongguoxiangqi.game.OpeningDetector
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.Tactics
import com.zhongguoxiangqi.game.Threats
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.rating.GameResult
import com.zhongguoxiangqi.rating.RankLadder
import com.zhongguoxiangqi.rating.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 游戏 ViewModel.
 *
 * 持有:
 * - 棋盘局面
 * - 引擎实例
 * - 用户档案
 * - 实时胜率状态
 *
 * 提供:
 * - 玩家走子接口
 * - 引擎走子
 * - 提示
 * - 悔棋
 * - 认输
 * - 实时胜率更新
 */
class GameViewModel(app: Application) : AndroidViewModel(app), EngineListener {

    // ====== 状态 ======
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _winRateSnapshot = MutableStateFlow(WinRateSnapshot(redWinRate = 50.0))
    val winRateSnapshot: StateFlow<WinRateSnapshot> = _winRateSnapshot.asStateFlow()

    private val _hintState = MutableStateFlow<HintState?>(null)
    val hintState: StateFlow<HintState?> = _hintState.asStateFlow()

    /**
     * 引擎是否还在准备(读 50MB 权重).
     *
     * 为真时界面要挡住落子: 这段时间棋盘已经画出来了, 看上去完全可以下,
     * 但引擎还没起来 —— 玩家走完一步会发现对方"不动", 那比等一个明确的
     * 进度提示更让人困惑。
     */
    private val _enginePreparing = MutableStateFlow(false)
    val enginePreparing: StateFlow<Boolean> = _enginePreparing.asStateFlow()

    private val _events = MutableLiveData<GameEvent?>()
    /** 一次性事件(Toast/对话框). 只读暴露给 UI, 消费后调用 [consumeEvent] */
    val events: LiveData<GameEvent?> = _events

    /** UI 处理完事件后清空,避免旋转屏幕等重建时重复弹出 */
    fun consumeEvent() {
        _events.value = null
    }

    // ====== 数据 ======
    private val position = Position()
    val currentPosition: Position get() = position
    val moveHistory = mutableListOf<Move>()

    private val userProfile: UserProfile = UserProfile(getApplication())
    private val _engineManager: EngineManager = EngineManager(getApplication())
    /** 暴露给 Activity 用, 获取实际对手棋力 */
    val engineManager: EngineManager get() = _engineManager
    private val gameDao: GameRecordDao = com.zhongguoxiangqi.data.AppDatabase.get(getApplication()).gameRecordDao()

    private var gameStartTime = 0L
    val hasStarted: Boolean get() = gameStartTime != 0L
    /**
     * 本局的积分/战绩是否已经结算过.
     *
     * 结算只做一次, 但"终局"可以有多次: 结算页上「悔棋继续」之后还能再下完.
     * 以前两件事共用这一个开关, 第二次终局直接 return, 状态停在"未结束" ——
     * 认输按了没反应, 将死了也不弹结算, 引擎还被要求在无子可走的局面里出招.
     */
    private var resultRecorded = false
    /** 第一次终局结算出的积分变化, 续下去再存棋谱时沿用 */
    private var settledRatingDelta = 0
    /** 本局第一次终局时计过分 —— 棋谱的"练习"标注按它来, 不按续下之后的状态 */
    private var settledRated = false
    private var gameStartRating = 0
    private var initialFen = ""
    /** 本局是从拍照/摆棋给的局面开的(不计分) */
    private var startedFromFen = false
    /**
     * 本局对手出现过的最低档位.
     *
     * 积分按它换算: 否则中途把对手调低、赢面到手再调回大师, 就能按大师的分值结算.
     */
    private var lowestLevelFaced = DifficultySettings.DEFAULT_LEVEL

    /**
     * 本局在对局库里的那一条.
     *
     * 每开一局换一个新槽位: 上一局的写入若在开新局之后才落地, 写的也是旧槽位,
     * 不会把新局的 id 顶成旧局的 —— 那样新局一存就会覆盖掉上一盘棋谱.
     */
    private class SavedGame {
        @Volatile var id: Long = 0L
        var job: Job? = null
    }
    private var savedGame = SavedGame()
    /** 起始局面 FEN, 给 AI 分析 / 棋谱回放用 */
    val initialFenSnapshot: String get() = initialFen

    /**
     * 当前搜索的用途.
     *
     * 引擎只有一个实例, 对局搜索/提示/后台分析共用它. 必须区分用途, 否则
     * 后台分析结束时吐出的 bestmove 会被当成引擎着法直接落子 —— 玩家还没走,
     * 引擎就替玩家把棋走了.
     */
    @Volatile private var searchPurpose = SearchPurpose.NONE
    private val searchGeneration = AtomicLong()

    /**
     * 当前这轮搜索是**谁**在走棋.
     *
     * 引擎的分数永远是走棋方视角, 换算成红方视角必须用搜索启动那一刻的
     * 走棋方. 搜索结果回调时棋盘可能已经翻面了, 那时再去读就会反号.
     */
    @Volatile private var searchSideToMove = Color.RED

    private enum class SearchPurpose { NONE, ENGINE_MOVE, HINT, ANALYSIS }

    // 提示状态
    data class HintState(
        val primaryMove: Move?,
        val secondaryMoves: List<Move>,
        val scoreCp: Int,
        val scoreMate: Int,
        val depth: Int,
        val pv: List<String>,
        /** 推荐着法的记谱(按当前记谱设置), 给提示条和读屏用 —— 箭头看不见的人也要知道走哪 */
        val notation: String = "",
    )

    data class GameState(
        val difficulty: Int = 1,
        /** Companion games remain unrated even if the difficulty is raised mid-game. */
        val isPractice: Boolean = true,
        /** 对手的棋路风格(攻杀/稳健...), 与难度正交 —— 见 [PlayStyle] */
        val style: PlayStyle = PlayStyle.BALANCED,
        /** 大于 0 = 对弈闯关模式, 值即当前关卡(对手强度 = 关卡难度) */
        val campaignLevel: Int = 0,
        val playerSide: Int = Color.RED,
        val sideToMove: Int = Color.RED,
        val isThinking: Boolean = false,
        val isCheck: Boolean = false,
        val isGameOver: Boolean = false,
        val gameOverReason: String = "",
        val gameOverWinner: Int = Color.NONE,
        /**
         * 本局对**玩家**而言的胜负, 未结束为 null.
         *
         * 不要用 [gameOverWinner] 判胜负 —— 它只在将死时才有值, 认输/和棋
         * 走的是 endGame(), 那条路上它一直是 NONE, 拿它判会把"认输"显示成
         * "和棋". 这里存的是 endGame 收到的那个结果, 也正是写进战绩和棋力
         * 分的同一个值.
         */
        val gameResult: GameResult? = null,
        val moveCount: Int = 0,
        /** 最近一步是否吃子(用于落子音效/震动强度) */
        val lastMoveCaptured: Boolean = false,
        val playerRating: Int = 1500,
        /** 本局结束后的评分变化, 对局中为 0 */
        val ratingDelta: Int = 0,
        val playerRank: String = "",
        val engineName: String = "",
        /** 对手实际棋力(降级时低于难度标称值) */
        val engineElo: Int = 1500,
        /** 绝杀时的战术杀法定式(马后炮/铁门栓/重炮/双车错等) */
        val winningTactic: String? = null,
        /** 开局定式(中炮对屏风马/顺手炮/仙人指路等) */
        val openingName: String? = null,
        /** 本局玩家用过的提示次数, 闯关三星用 */
        val hintsUsed: Int = 0,
        /** 本局玩家悔棋次数, 闯关三星用 */
        val undosUsed: Int = 0,
        /** 本局排位跨过了新段位时的段位名, 否则空 */
        val promotedRank: String = "",
        /** 这盘计分局下完正好定级结束, [promotedRank] 是揭晓的首枚印 */
        val placementComplete: Boolean = false,
        /** 这盘是定级赛且下完仍在定级: 已下的计分局数; 否则 null */
        val placementPlayed: Int? = null,
        /** 本次终局是玩家认输(局面本身没结束, 「继续」就是撤回认输) */
        val endedByResign: Boolean = false,
        /** 结算过一次后又「悔棋继续」下完的部分: 不再计分 */
        val isContinuation: Boolean = false,
        /** 续下之前的那次终局计过分 —— 结算页要说明"成绩以第一次为准" */
        val continuesRatedGame: Boolean = false,
    )

    sealed class GameEvent {
        data class ShowToast(val msg: String) : GameEvent()
        data class ShowDialog(val title: String, val message: String) : GameEvent()
        object GameOver : GameEvent()
        /** 推送威胁光晕给 BoardView: 红方子被瞄的格子 / 黑方子被瞄的格子 */
        data class ThreatsUpdate(val redSquares: List<Int>, val blackSquares: List<Int>) : GameEvent()
        object PlayerInCheck : GameEvent()
        /** 将军且命中战术结构(铁门栓/马后炮等), 携带战术名给语音播报 */
        data class TacticCheck(val tactic: String) : GameEvent()
        data class Checkmate(val tactic: String? = null) : GameEvent()
        /** [byPlayer] 区分敌我走子: 音效音色与落子涟漪颜色都靠它 */
        data class NormalMove(val byPlayer: Boolean = true) : GameEvent()
        /** [square] 是被吃子所在的格子, 特效要画在那儿; 攻方信息让特效"突进"有来向; [notation] 是本步中文记谱 */
        data class CapturedPiece(
            val piece: Piece,
            val square: Int,
            val attacker: Piece? = null,
            val attackerSquare: Int = -1,
            val notation: String? = null,
            val byPlayer: Boolean = true,
        ) : GameEvent()
    }

    // ====== 初始化 ======

    /**
     * 开始新对局.
     * @param difficulty 难度 1-5
     * @param playerSide 玩家执子 Color.RED / Color.BLACK
     * @param style 对手风格
     */
    /**
     * @param startFen 非空时从这个局面开局 (拍照识别摆好的残局), 否则标准开局.
     *                 解析失败就退回标准开局 —— 宁可开一盘正常的棋, 也不要
     *                 让用户对着一个空棋盘不知道发生了什么.
     */
    fun startNewGame(
        difficulty: Int,
        playerSide: Int,
        startFen: String? = null,
        style: PlayStyle = PlayStyle.BALANCED,
        campaignLevel: Int = 0,
    ) {
        if (_enginePreparing.value) return
        stopEngine()
        engineManager.shutdown()

        moveHistory.clear()
        // setFromFen 返回 Boolean 而不是抛异常, 所以判断要看返回值
        val loaded = startFen != null &&
            runCatching { position.setFromFen(startFen) }.getOrDefault(false)
        if (!loaded) position.setupStartPosition()
        initialFen = position.toFen()
        startedFromFen = startFen != null
        gameStartTime = System.currentTimeMillis()
        resultRecorded = false
        settledRatingDelta = 0
        settledRated = false
        savedGame = SavedGame()
        gameStartRating = userProfile.displayedRating
        _winRateSnapshot.value = WinRateSnapshot(redWinRate = 50.0)
        _hintState.value = null

        // 引擎启动放到后台线程.
        //
        // PikafishEngine.start() 要把 50MB 的 NNUE 权重从 assets 拷到 cache
        // (首次), 再把网络读进内存. 这件事原来是在 GameActivity.onCreate 里
        // 同步做的 —— 实测点"开始对弈"后主线程被占住 9.2 秒, 系统直接弹了
        // "QiYan isn't responding" 的 ANR 框, 而这段时间里屏幕上没有任何提示。
        //
        // 现在: 立刻把 GameState 发出去(棋盘先画出来), 引擎在 IO 线程起,
        // 起好之前 isEnginePreparing=true, 界面据此显示"正在摆棋…"并挡住落子。
        val level = difficulty.coerceIn(1, EngineManager.MAX_LEVEL)
        engineManager.difficultyLevel = level
        engineManager.playStyle = style
        lowestLevelFaced = level
        _enginePreparing.value = true

        val isPractice = level == 1 || startedFromFen
        _state.value = GameState(
            difficulty = level,
            isPractice = isPractice,
            style = style,
            campaignLevel = campaignLevel,
            playerSide = playerSide,
            sideToMove = position.sideToMove,
            isThinking = false,
            isCheck = false,
            isGameOver = false,
            gameOverReason = "",
            gameOverWinner = Color.NONE,
            gameResult = null,
            moveCount = 0,
            playerRating = userProfile.displayedRating,
            playerRank = rankLabel(countsTowardPlacement = campaignLevel == 0 && !isPractice),
            engineName = engineManager.currentEngine?.name() ?: getApplication<Application>().getString(R.string.unknown_label),
            engineElo = engineManager.effectiveElo(),
        )

        viewModelScope.launch {
            val started = kotlinx.coroutines.withContext(Dispatchers.IO) {
                engineManager.start(this@GameViewModel)
            }
            _enginePreparing.value = false
            if (!started) {
                _events.value = GameEvent.ShowToast(
                    getApplication<Application>().getString(R.string.game_engine_start_failed)
                )
                return@launch
            }
            // engineName/engineElo 要等引擎真的起来才知道, 所以这里补一次.
            _state.value = _state.value.copy(
                engineName = engineManager.currentEngine?.name()
                    ?: getApplication<Application>().getString(R.string.unknown_label),
                engineElo = engineManager.effectiveElo(),
            )
            // 玩家执黑时引擎先走 —— 必须等引擎起来之后才发得出这一步
            if (position.sideToMove != _state.value.playerSide) {
                makeEngineMove()
            }
        }
    }

    /** 引擎就绪 */
    override fun onReady() { /* 不需要做特殊处理 */ }

    /** 引擎搜索信息 */
    override fun onInfo(info: EngineInfo) {
        if (searchPurpose == SearchPurpose.NONE) return
        val generation = searchGeneration.get()
        val scoreCp = info.scoreCp
        val scoreMate = info.scoreMate
        // 引擎给的分数是**走棋方**视角的, 显示要换算成红方视角.
        // 用搜索启动时记下的走棋方, 不能读 _state —— 回调在工作线程,
        // 期间 _state 可能已经因为落子翻面, 一翻就把胜率整个照镜子.
        val stm = searchSideToMove
        val scoreRed = if (stm == Color.RED) scoreCp else -scoreCp
        val mateRed  = if (stm == Color.RED) scoreMate else -scoreMate
        // 用 tanh 平滑版本: 胜率不会在小分差下剧烈摆动.
        val redWR = WinRateAnalyzer.smoothWinRate(scoreRed, mateRed)
        val snapshot = WinRateSnapshot(
            redWinRate = redWR,
            scoreCp = scoreCp,
            scoreMate = scoreMate,
            depth = info.depth,
            timeMs = info.timeMs,
            nodes = info.nodes,
            nps = info.nps,
            pv = info.pv,
            engineName = engineManager.currentEngine?.name() ?: ""
        )
        viewModelScope.launch {
            if (searchGeneration.get() == generation && searchPurpose != SearchPurpose.NONE) {
                _winRateSnapshot.value = snapshot
            }
        }
    }

    /** 引擎返回最佳走法 */
    override fun onBestMove(bestMove: String, ponderMove: String?) {
        val purpose = searchPurpose
        val generation = searchGeneration.get()
        if (purpose == SearchPurpose.NONE || purpose == SearchPurpose.ANALYSIS) return

        // 引擎回调在工作线程, position 只能在主线程上改
        viewModelScope.launch {
            if (searchGeneration.get() != generation || searchPurpose != purpose || _state.value.isGameOver) return@launch
            if (bestMove == "0000" || bestMove.length < 4) {
                stopEngine()
                _events.value = GameEvent.ShowToast(getApplication<Application>().getString(R.string.game_search_no_move))
                return@launch
            }
            if (purpose == SearchPurpose.HINT) {
                searchPurpose = SearchPurpose.NONE
                onHintBestMove(bestMove)
                return@launch
            }

            if (_state.value.isGameOver) return@launch
            // 只有轮到引擎时才落子, 防止把玩家的棋替玩家走了
            if (position.sideToMove == _state.value.playerSide) return@launch

            val bestMoveParsed = uciToMove(bestMove)
            if (bestMoveParsed == null || !position.isLegalMove(bestMoveParsed)) {
                _events.value = GameEvent.ShowToast(
                    getApplication<Application>().getString(R.string.engine_illegal_move, bestMove)
                )
                _state.value = _state.value.copy(isThinking = false)
                return@launch
            }

            searchPurpose = SearchPurpose.NONE
            // 风格挑选: 与最佳着法分差足够小的候选里按棋路挑一步.
            // 均衡风格(以及候选拿不到的任何退路)就是引擎最佳本身.
            val move = if (engineManager.playStyle == PlayStyle.BALANCED) bestMoveParsed
                else pickStyledMove(bestMoveParsed)
            val capturedPiece = if (position.pieceAt(move.to) != Piece.NONE.code)
                Piece.fromCode(position.pieceAt(move.to)) else null
            val captured = capturedPiece != null
            val attackerPiece = Piece.fromCode(position.pieceAt(move.from))
            val notation = Notation.format(getApplication(), move, position)
            position.doMove(move)
            moveHistory.add(move)
            updateAfterMove(captured, capturedPiece, move.to, notation, attackerPiece, move.from)
        }
    }

    override fun onError(message: String) {
        val generation = searchGeneration.get()
        viewModelScope.launch(Dispatchers.Main) {
            if (searchGeneration.get() != generation) return@launch
            stopEngine()
            _events.value = GameEvent.ShowToast(
                getApplication<Application>().getString(R.string.engine_error, message)
            )
        }
    }

    // ====== 玩家接口 ======

    /** 玩家走子 */
    fun playerMove(move: Move): Boolean {
        if (_enginePreparing.value || _state.value.isThinking || _state.value.isGameOver) return false
        if (position.sideToMove != _state.value.playerSide) return false
        if (!position.isLegalMove(move)) return false

        stopEngine()
        _hintState.value = null

        // 截胡: 玩家在被将军时必须应招(消将/挡将/避将). 走非应招的子
        // 看似合法(它本来就是合法着法), 但下一步仍会被将军, 引擎必吃.
        // 这种情况**拦截** —— 弹 Toast, 不真落子. 物理上不撤销"已被合法接受
        // 的点击"也没问题, 玩家立刻再选一个子就继续.
        val wasInCheck = Rules.isInCheck(position, _state.value.playerSide)
        val capturedPiece = if (position.pieceAt(move.to) != Piece.NONE.code)
            Piece.fromCode(position.pieceAt(move.to)) else null
        val captured = capturedPiece != null
        val attackerPiece = Piece.fromCode(position.pieceAt(move.from))
        val notation = Notation.format(getApplication(), move, position)
        position.doMove(move)
        moveHistory.add(move)
        // 检查"我走完这一手, 是不是还吃着将军": 我(玩家)走子之后, 仍是我(玩家)
        // 处于被将状态 —— 用我方颜色作为参数. (position.sideToMove 已是引擎, 查它
        // 反而是问"我是否把将送给了引擎", 完全反了.)
        val stillInCheck = Rules.isInCheck(position, _state.value.playerSide)
        if (wasInCheck && stillInCheck) {
            // 不应招 —— 回退, 只发 Toast. 之前我连发两条事件, 后一条会覆盖前一条,
            // 结果 Toast 都不显示.
            position.undoMove()
            moveHistory.removeAt(moveHistory.size - 1)
            _events.value = GameEvent.ShowToast(
                getApplication<Application>().getString(R.string.must_answer_check)
            )
            return false
        }
        updateAfterMove(captured, capturedPiece, move.to, notation, attackerPiece, move.from)

        // 如果游戏未结束,让引擎走
        if (!_state.value.isGameOver) {
            makeEngineMove()
        }
        return true
    }

    /** 悔棋 */
    fun undoMove(): Boolean {
        if (_enginePreparing.value || _state.value.isThinking) return false
        if (moveHistory.isEmpty()) return false

        // 目标: 退回到轮玩家走的局面.
        // 轮到玩家 => 引擎刚走完, 要退引擎那步和玩家上一步(2 步);
        // 轮到引擎 => 玩家刚走完, 只退玩家这一步(1 步).
        // 执黑时盘上只有引擎的第一手, 玩家还没走过 —— 没有可悔的棋. 以前这里
        // 照样退掉引擎那一手, 结果轮到引擎却没人让它走, 整盘棋卡住.
        val playerToMove = position.sideToMove == _state.value.playerSide
        val toUndo = if (playerToMove) 2 else 1
        if (toUndo > moveHistory.size) return false
        stopEngine()
        repeat(toUndo) {
            position.undoMove()
            moveHistory.removeAt(moveHistory.size - 1)
        }

        val uciList = moveHistory.map { UciCoords.toUci(it) }
        val opening = if (moveHistory.isNotEmpty()) OpeningDetector.detect(uciList) else null
        _state.value = _state.value.copy(
            sideToMove = position.sideToMove,
            isCheck = Rules.isInCheck(position, position.sideToMove),
            isGameOver = false,
            gameOverReason = "",
            gameOverWinner = Color.NONE,
            gameResult = null,
            winningTactic = null,
            endedByResign = false,
            moveCount = moveHistory.size,
            openingName = opening,
            undosUsed = _state.value.undosUsed + 1,
        )
        _winRateSnapshot.value = WinRateSnapshot(redWinRate = 50.0)
        _hintState.value = null
        return true
    }

    /**
     * 结算页上的「继续」.
     *
     * 认输时局面本身没有结束, 继续就是撤回认输、原样接着下; 将死/困毙/重复这类
     * 终局要退回到轮玩家走的那一手. 本局的积分和战绩在第一次终局时已经结算,
     * 续下去的部分一律不计分 —— 否则"输了悔棋、赢了再结算"就能刷分.
     */
    fun continueAfterGameOver(): Boolean {
        val s = _state.value
        if (!s.isGameOver || _enginePreparing.value) return false
        val resumed = if (s.endedByResign) {
            _state.value = s.copy(
                isGameOver = false,
                gameOverReason = "",
                gameOverWinner = Color.NONE,
                gameResult = null,
                winningTactic = null,
                endedByResign = false,
            )
            true
        } else {
            undoMove()
        }
        if (!resumed) return false
        _state.value = _state.value.copy(
            isPractice = true,
            isContinuation = true,
            continuesRatedGame = settledRated,
            ratingDelta = 0,
            promotedRank = "",
        )
        if (position.sideToMove != _state.value.playerSide) makeEngineMove()
        return true
    }

    /** 认输 —— 认输方永远是玩家自己,与执红执黑无关 */
    fun resign(): GameResult {
        if (_enginePreparing.value) return GameResult.LOSS
        if (_state.value.isGameOver) return _state.value.gameResult ?: GameResult.LOSS
        stopEngine()
        endGame(GameResult.LOSS, getApplication<Application>().getString(R.string.status_resign), resigned = true)
        return GameResult.LOSS
    }

    /** 求和 */
    fun offerDraw(): Boolean {
        // 简化:对局超过 60 手且无子和棋分差 < 30,接受;否则拒绝
        if (moveHistory.size < 60) return false
        val currentCp = _winRateSnapshot.value.scoreCp
        return kotlin.math.abs(currentCp) < 30
    }

    /** 请求提示 */
    fun requestHint() {
        if (_enginePreparing.value || _state.value.isGameOver || _state.value.isThinking) return
        if (position.sideToMove != _state.value.playerSide) return
        stopEngine()

        engineManager.setPosition(initialFen, moveHistory)
        val params = engineManager.requestHint()
        searchSideToMove = position.sideToMove
        searchPurpose = SearchPurpose.HINT
        _state.value = _state.value.copy(isThinking = true, hintsUsed = _state.value.hintsUsed + 1)
        _hintState.value = null
        engineManager.startSearch(params)
    }

    /** 后台实时分析(只更新胜率条,不落子) */
    private fun startAnalysis() {
        if (_enginePreparing.value || _state.value.isGameOver || _state.value.isThinking) return
        stopEngine()
        engineManager.setPosition(initialFen, moveHistory)
        searchSideToMove = position.sideToMove
        searchPurpose = SearchPurpose.ANALYSIS
        engineManager.startSearch(engineManager.requestAnalysis())
    }

    /**
     * AI 分析入口: 主动触发一次后台分析, 让全屏分析动效拿到真实的
     * 深度/胜率数据。只在轮到玩家思考时跑, 不抢引擎的对局搜索。
     */
    fun runAnalysisNow() {
        if (_enginePreparing.value || _state.value.isGameOver || _state.value.isThinking) return
        if (position.sideToMove != _state.value.playerSide) return
        startAnalysis()
    }

    /** 设置玩家执子 */
    fun setPlayerSide(side: Int) {
        _state.value = _state.value.copy(playerSide = side)
    }

    /**
     * 对局中途改 AI 等级, 立即生效.
     *
     * 不重开局: 用户想在被虐时调低、觉得太easy时调高, 重开就等于让这个功能
     * 没意义. 引擎的搜索参数是每次 [makeEngineMove] 现算的, 所以只要改掉
     * state.difficulty, 下一手就是新等级; 唯一要处理的是它正在思考 —— 那就
     * 打断重下, 否则这一手仍按旧等级出.
     */
    fun setDifficulty(level: Int) {
        val clamped = level.coerceIn(1, com.zhongguoxiangqi.engine.EngineManager.MAX_LEVEL)
        if (clamped == _state.value.difficulty) return
        if (_state.value.isGameOver) return
        stopEngine()
        engineManager.difficultyLevel = clamped
        // 还没落子时改档就是"换个对手", 按新档位重新判定计不计分;
        // 下到一半再改, 积分只按这盘出现过的最低档结算, 练习局也不会因此变成计分局.
        val notStarted = moveHistory.isEmpty() && !_state.value.isContinuation
        lowestLevelFaced = if (notStarted) clamped else minOf(lowestLevelFaced, clamped)
        val isPractice = if (notStarted) clamped == 1 || startedFromFen
            else _state.value.isPractice || clamped == 1
        _state.value = _state.value.copy(
            difficulty = clamped,
            isPractice = isPractice,
            engineElo = engineManager.effectiveElo(),
            playerRank = rankLabel(countsTowardPlacement = _state.value.campaignLevel == 0 && !isPractice),
        )
        if (!_enginePreparing.value && position.sideToMove != _state.value.playerSide) makeEngineMove()
    }

    /**
     * 对局中途改对手风格, 立即生效.
     *
     * 与 [setDifficulty] 同一套节奏: 不重开, 正在思考就打断重下 —— 否则这一手
     * 仍按旧风格出, 用户会以为切换没起作用。
     */
    fun setStyle(style: PlayStyle) {
        if (style == _state.value.style || _state.value.isGameOver) return
        stopEngine()
        engineManager.playStyle = style
        _state.value = _state.value.copy(style = style)
        if (!_enginePreparing.value && position.sideToMove != _state.value.playerSide) makeEngineMove()
    }

    /**
     * 交换执子: 我方红黑对调, 引擎接手原来我方那一边.
     *
     * 「翻转」以前只是翻转视角, 盘还是自己那盘. 现在它真的换边 —— 换完如果
     * 轮到的是引擎那一方, 立刻让引擎接着走, 否则局面会卡住等一个永远不会
     * 落子的人.
     *
     * 下过棋之后再换边, 等于接手引擎经营出来的局面: 计分局就此转成练习
     * (界面会先征得同意, 见 [swapMakesPractice]); 闯关的执子固定, 不许换.
     */
    fun swapSides(): Boolean {
        if (_enginePreparing.value || _state.value.isGameOver) return false
        if (_state.value.campaignLevel > 0) return false
        val newSide = if (_state.value.playerSide == Color.RED) Color.BLACK else Color.RED
        val becomesPractice = swapMakesPractice()
        stopEngine()
        _hintState.value = null
        _state.value = _state.value.copy(
            playerSide = newSide,
            isThinking = false,
            isPractice = _state.value.isPractice || becomesPractice,
        )
        if (position.sideToMove != newSide) {
            makeEngineMove()
        }
        return true
    }

    /** 这一局此刻换边会不会让它不再计分 —— 开局前换边只是选先后手, 不算 */
    fun swapMakesPractice(): Boolean =
        !_state.value.isPractice && _state.value.campaignLevel == 0 && moveHistory.isNotEmpty()

    /** 重置胜率显示 */
    fun resetAnalysisDisplay() {
        _winRateSnapshot.value = WinRateSnapshot(redWinRate = 50.0)
    }

    // ====== 内部 ======

    private fun makeEngineMove() {
        if (_enginePreparing.value || _state.value.isGameOver || position.sideToMove == _state.value.playerSide) return
        stopEngine()
        // 设置局面,启动引擎
        engineManager.setPosition(initialFen, moveHistory)
        // 思考时长/线程/哈希**全部由 EngineManager.adaptParams 按难度算**.
        // 之前这里另起一套 estimateThinkTime/Threads/Hash, 但 adaptParams 会
        // 用 coerceAtLeast 静默抬高 —— 结果是 ViewModel 的 8000ms 在 Lv.10
        // 被抬到 14s, 在 Lv.15 被抬到 90s. 现在只传 MultiPV(风格要用候选),
        // 别的留给唯一来源. 均衡风格 MultiPV=1: 一条线都不用分, 棋力最足.
        val params = SearchParams(multiPv = engineManager.playStyle.multiPv)
        searchSideToMove = position.sideToMove
        searchPurpose = SearchPurpose.ENGINE_MOVE
        _state.value = _state.value.copy(isThinking = true)
        engineManager.startSearch(params)
    }

    /**
     * 按 [EngineManager.playStyle] 从本轮搜索的 MultiPV 候选里挑一步.
     *
     * 特征(吃子收益/是否将军)在**当前真实局面**上算, 算完立即还原 ——
     * 只在 onBestMove 回调的主线程协程里跑, 与其它局面读写天然串行.
     * 任何一步拿不到候选/候选全非法的退路都返回引擎最佳.
     */
    private fun pickStyledMove(bestMove: Move): Move {
        val engine = engineManager.currentEngine as? PikafishEngine ?: return bestMove
        val raw = engine.lastCandidates()
        if (raw.size <= 1) return bestMove

        class Entry(val candidate: PlayStyle.Candidate, val move: Move, val features: PlayStyle.Features)

        val entries = ArrayList<Entry>(raw.size)
        for (cand in raw) {
            val mv = uciToMove(cand.moveUci) ?: continue
            if (!position.isLegalMove(mv)) continue
            val victim = position.pieceAt(mv.to)
            val isCapture = victim != Piece.NONE.code
            val gain = if (isCapture) See.evaluate(position, mv) else 0
            val givesCheck = if (position.doMove(mv)) {
                val check = Rules.isInCheck(position, position.sideToMove)
                position.undoMove()
                check
            } else false
            entries += Entry(cand, mv, PlayStyle.Features(isCapture, gain, givesCheck))
        }
        if (entries.isEmpty()) return bestMove

        val picked = engineManager.playStyle.pick(entries.map { it.candidate }) { c ->
            entries.first { it.candidate == c }.features
        }
        return entries.firstOrNull { it.candidate == picked }?.move ?: bestMove
    }

    private fun stopEngine() {
        // 先清用途, 再停搜索: stop 会让引擎立刻吐一个 bestmove, 那个结果必须丢弃
        searchPurpose = SearchPurpose.NONE
        searchGeneration.incrementAndGet()
        engineManager.stopSearch()
        _state.value = _state.value.copy(isThinking = false)
    }

    private fun updateAfterMove(
        captured: Boolean,
        capturedPiece: Piece? = null,
        capturedSquare: Int = -1,
        notation: String? = null,
        attackerPiece: Piece? = null,
        attackerSquare: Int = -1,
    ) {
        // 检测将军 / 终局
        val inCheck = Rules.isInCheck(position, position.sideToMove)
        val gameOver = Rules.gameOver(position)
        val isOver = gameOver != Rules.GameOver.NONE

        val gameOverReason = when (gameOver) {
            Rules.GameOver.CHECKMATE_RED -> getApplication<Application>().getString(R.string.game_over_checkmate_red)
            Rules.GameOver.CHECKMATE_BLACK -> getApplication<Application>().getString(R.string.game_over_checkmate_black)
            Rules.GameOver.STALEMATE -> getApplication<Application>().getString(R.string.status_stalemate)
            Rules.GameOver.REPETITION -> getApplication<Application>().getString(R.string.game_over_repetition)
            Rules.GameOver.FIFTY_MOVES -> getApplication<Application>().getString(R.string.game_over_fifty_moves)
            else -> ""
        }
        val winner = when (gameOver) {
            Rules.GameOver.CHECKMATE_RED -> Color.BLACK
            Rules.GameOver.CHECKMATE_BLACK -> Color.RED
            else -> Color.NONE
        }
        val winningTactic = if (winner != Color.NONE) Tactics.detect(position, winner) else null

        // 重新算威胁光晕 + 触发音效
        emitThreats()
        emitFeedback(inCheck, isOver, gameOver, capturedPiece, capturedSquare, notation, attackerPiece, attackerSquare, winningTactic)

        val uciList = moveHistory.map { UciCoords.toUci(it) }
        val detectedOpening = if (moveHistory.size <= 16) OpeningDetector.detect(uciList) else _state.value.openingName
        val opening = detectedOpening ?: _state.value.openingName

        if (isOver) {
            // 确定玩家结果: 玩家执子如果与胜方一致则为 WIN
            val playerResult = when (winner) {
                Color.NONE -> GameResult.DRAW
                _state.value.playerSide -> GameResult.WIN
                else -> GameResult.LOSS
            }
            endGame(playerResult, gameOverReason, winner, winningTactic)
        } else {
            _state.value = _state.value.copy(
                sideToMove = position.sideToMove,
                isCheck = inCheck,
                isGameOver = false,
                gameOverReason = "",
                gameOverWinner = Color.NONE,
                winningTactic = null,
                openingName = opening,
                gameResult = null,
                moveCount = moveHistory.size,
                lastMoveCaptured = captured,
                isThinking = false
            )
            if (position.sideToMove == _state.value.playerSide) {
                // 轮到玩家思考时才后台分析, 避免和引擎的对局搜索抢同一个引擎实例
                if (_state.value.difficulty >= 3 && moveHistory.size > 5) startAnalysis()
            }
        }
    }

    /**
     * 把"对方下回合可能吃我方子"这件事发给界面层.
     * 颜色: 受害子的颜色, 不是当前走子方.
     */
    private fun emitThreats() {
        val threats = Threats.findThreats(position)
        val redSquares = threats.filter {
            Piece.fromCode(position.pieceAt(it.target)).isRed
        }.map { it.target }
        val blackSquares = threats.filter {
            !Piece.fromCode(position.pieceAt(it.target)).isRed
        }.map { it.target }
        _events.value = GameEvent.ThreatsUpdate(redSquares, blackSquares)
    }

    /** 走子后发出音效/震动事件, 由 Activity 转发给 GameFeedback */
    private fun emitFeedback(
        inCheck: Boolean, isOver: Boolean, gameOver: Rules.GameOver,
        capturedPiece: Piece?, capturedSquare: Int, notation: String? = null,
        attackerPiece: Piece? = null, attackerSquare: Int = -1,
        winningTactic: String? = null,
    ) {
        // 走子方 = 走完后轮到谁的反面 (updateAfterMove 时 position 已含新着法)
        val byPlayer = Color.opposite(position.sideToMove) == _state.value.playerSide
        _events.value = when {
            isOver && (gameOver == Rules.GameOver.CHECKMATE_RED || gameOver == Rules.GameOver.CHECKMATE_BLACK) ->
                GameEvent.Checkmate(winningTactic)
            inCheck -> {
                // 将军时先看是否命中战术结构(马后炮/铁门栓), 命中就播战术名
                val tactic = Tactics.detect(position, Color.opposite(position.sideToMove))
                if (tactic != null) GameEvent.TacticCheck(tactic) else GameEvent.PlayerInCheck
            }
            capturedPiece != null -> GameEvent.CapturedPiece(
                capturedPiece, capturedSquare, attackerPiece, attackerSquare, notation, byPlayer,
            )
            else -> GameEvent.NormalMove(byPlayer)
        }
    }

    private fun endGame(
        result: GameResult,
        reason: String,
        winner: Int = Color.NONE,
        winningTactic: String? = null,
        resigned: Boolean = false,
    ) {
        if (_state.value.isGameOver) return
        stopEngine()
        val difficulty = _state.value.difficulty
        val campaignLevel = _state.value.campaignLevel
        // 积分只在第一次终局结算; 结算后「继续」下完的部分只刷新终局状态和棋谱.
        val firstFinish = !resultRecorded
        resultRecorded = true
        // 闯关模式不动战绩积分: 重复刷低关卡会白送积分, 排行含金量就没了。
        // 棋谱也不入库(复盘列表里塞满刷关记录没有价值), 闯关进度记在 ProgressStore。
        val rated = firstFinish && campaignLevel == 0 && !_state.value.isPractice
        val ratedGamesBefore = userProfile.totalGames
        if (rated) {
            userProfile.recordResult(result, EngineManager.nominalElo(lowestLevelFaced))
            settledRatingDelta = userProfile.displayedRating - gameStartRating
            settledRated = true
        }
        val ratedGamesAfter = userProfile.totalGames
        val earned = if (rated) RankLadder.earnedSeal(
            gameStartRating, userProfile.displayedRating, ratedGamesBefore, ratedGamesAfter,
        ) else null
        val placementComplete = rated && RankLadder.completesPlacement(ratedGamesBefore, ratedGamesAfter)

        _state.value = _state.value.copy(
            sideToMove = position.sideToMove,
            isCheck = Rules.isInCheck(position, position.sideToMove),
            isGameOver = true,
            gameOverReason = reason,
            gameOverWinner = winner,
            winningTactic = winningTactic,
            gameResult = result,
            endedByResign = resigned,
            moveCount = moveHistory.size,
            isThinking = false,
            playerRating = userProfile.displayedRating,
            ratingDelta = if (rated) settledRatingDelta else 0,
            playerRank = rankLabel(countsTowardPlacement = false),
            promotedRank = earned?.let { getApplication<Application>().getString(it.nameRes) }.orEmpty(),
            placementComplete = placementComplete,
            placementPlayed = if (rated && userProfile.isInPlacement) ratedGamesAfter else null,
        )

        if (campaignLevel > 0) return

        // Snapshot before launching IO: starting another game must not change this record.
        val record = GameRecord(
                startTime = gameStartTime,
                endTime = System.currentTimeMillis(),
                playerSide = _state.value.playerSide,
                difficulty = difficulty,
                engineElo = engineManager.effectiveElo(),
                result = when (result) {
                    GameResult.WIN -> 1
                    GameResult.LOSS -> -1
                    GameResult.DRAW -> 0
                },
                ratingDelta = settledRatingDelta,
                playerRating = userProfile.displayedRating,
                moves = moveHistory.map { moveToUci(it) },
                initialFen = initialFen,
                endReason = if (!settledRated)
                    getApplication<Application>().getString(R.string.game_practice_record_reason, reason)
                else reason
            )
        saveRecord(record)

        _events.value = GameEvent.GameOver
    }

    /**
     * 玩家那一行显示的段位.
     *
     * 定级期间还没有印: 计分局写"定级赛 n/3"(这是第几盘), 其它场合只写"定级中" ——
     * 闯关、练习不计入定级, 写成第几盘反而误导.
     */
    private fun rankLabel(countsTowardPlacement: Boolean): String {
        val app = getApplication<Application>()
        return when {
            !userProfile.isInPlacement -> app.getString(userProfile.rank().nameRes)
            countsTowardPlacement -> app.getString(
                R.string.rank_placement_chip, userProfile.totalGames + 1, RankLadder.PLACEMENT_GAMES,
            )
            else -> app.getString(R.string.rank_placement_name)
        }
    }

    /**
     * 把本局写进对局库. 同一局再次终局(悔棋继续后下完)覆盖同一条记录,
     * 复盘里看到的永远是最后下完的那一版, 不会多出一盘重复的棋.
     *
     * 不跟 viewModelScope 一起取消: 结算页上点「返回」会立刻销毁 ViewModel,
     * 那一瞬间还没落盘的棋谱就丢了.
     */
    private fun saveRecord(record: GameRecord) {
        val slot = savedGame
        val previous = slot.job
        slot.job = viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            previous?.join()
            slot.id = gameDao.insert(record.copy(id = slot.id))
        }
    }

    /** 本局存进对局库后的 id; 还没终局或不入库(闯关)时为 null. 会等正在进行的写入落盘 */
    suspend fun awaitSavedRecordId(): Long? {
        val slot = savedGame
        slot.job?.join()
        return slot.id.takeIf { it > 0 }
    }

    private fun uciToMove(uci: String): Move? = UciCoords.parse(uci)

    private fun moveToUci(move: Move): String = UciCoords.toUci(move)

    /**
     * 提示搜索结束: 用最终 bestmove 作为推荐着法.
     *
     * 只在这里写一次 _hintState —— 以前 onInfo 每来一条 info(每加深一层)
     * 就更新一次, 迭代加深过程中 PV 会换来换去, 棋盘上的箭头跟着连续跳动,
     * 用户点一次"提示"却看到好几个不同的着法轮番被推荐, 像是弹出了好几种
     * 指引。备选着法(secondaryMoves)从 _winRateSnapshot 里取 ——
     * 那个状态本来就每条 info 都更新, 到 bestmove 这一刻已经是最深一层的 PV。
     */
    private fun onHintBestMove(bestMove: String) {
        val move = uciToMove(bestMove)
        _state.value = _state.value.copy(isThinking = false)
        if (move != null && position.isLegalMove(move)) {
            val finalPv = _winRateSnapshot.value.pv
            _hintState.value = HintState(
                primaryMove = move,
                // A principal variation contains subsequent turns, not alternative first moves.
                secondaryMoves = emptyList(),
                scoreCp = _winRateSnapshot.value.scoreCp,
                scoreMate = _winRateSnapshot.value.scoreMate,
                depth = _winRateSnapshot.value.depth,
                pv = finalPv.ifEmpty { listOf(bestMove) },
                notation = Notation.format(getApplication(), move, position),
            )
            _state.value = _state.value.copy(isThinking = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        engineManager.shutdown()
    }
}
