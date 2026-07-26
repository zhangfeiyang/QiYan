package com.zhongguoxiangqi.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhongguoxiangqi.analysis.WinRateAnalyzer
import com.zhongguoxiangqi.analysis.WinRateSnapshot
import com.zhongguoxiangqi.data.GameRecord
import com.zhongguoxiangqi.data.GameRecordDao
import com.zhongguoxiangqi.engine.EngineInfo
import com.zhongguoxiangqi.engine.EngineListener
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.engine.SearchParams
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.Threats
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.rating.GameResult
import com.zhongguoxiangqi.rating.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private var gameStartRating = 0
    private var initialFen = ""
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
        val pv: List<String>
    )

    data class GameState(
        val difficulty: Int = 5,
        val playerSide: Int = Color.RED,
        val sideToMove: Int = Color.RED,
        val isThinking: Boolean = false,
        val isCheck: Boolean = false,
        val isGameOver: Boolean = false,
        val gameOverReason: String = "",
        val gameOverWinner: Int = Color.NONE,
        val moveCount: Int = 0,
        /** 最近一步是否吃子(用于落子音效/震动强度) */
        val lastMoveCaptured: Boolean = false,
        val playerRating: Int = 1500,
        /** 本局结束后的评分变化, 对局中为 0 */
        val ratingDelta: Int = 0,
        val playerRank: String = "初学者",
        val engineName: String = "",
        /** 对手实际棋力(降级时低于难度标称值) */
        val engineElo: Int = 1500,
        /** 是否因引擎缺失而降级 */
        val engineDowngraded: Boolean = false
    )

    sealed class GameEvent {
        data class ShowToast(val msg: String) : GameEvent()
        data class ShowDialog(val title: String, val message: String) : GameEvent()
        object GameOver : GameEvent()
        /** 推送威胁光晕给 BoardView: 红方子被瞄的格子 / 黑方子被瞄的格子 */
        data class ThreatsUpdate(val redSquares: List<Int>, val blackSquares: List<Int>) : GameEvent()
        object PlayerInCheck : GameEvent()
        object Checkmate : GameEvent()
        object NormalMove : GameEvent()
        data class CapturedPiece(val piece: Piece) : GameEvent()
    }

    // ====== 初始化 ======

    /**
     * 开始新对局.
     * @param difficulty 难度 1-10
     * @param playerSide 玩家执子 Color.RED / Color.BLACK
     */
    fun startNewGame(difficulty: Int, playerSide: Int) {
        stopEngine()
        engineManager.shutdown()

        moveHistory.clear()
        position.setupStartPosition()
        initialFen = position.toFen()
        gameStartTime = System.currentTimeMillis()
        gameStartRating = userProfile.displayedRating
        _winRateSnapshot.value = WinRateSnapshot(redWinRate = 50.0)
        _hintState.value = null

        // 启动引擎
        engineManager.difficultyLevel = difficulty
        if (!engineManager.start(this)) {
            _events.value = GameEvent.ShowToast("引擎启动失败")
        }

        val rank = userProfile.rank()
        _state.value = GameState(
            difficulty = difficulty,
            playerSide = playerSide,
            sideToMove = Color.RED,
            isThinking = false,
            isCheck = false,
            isGameOver = false,
            gameOverReason = "",
            gameOverWinner = Color.NONE,
            moveCount = 0,
            playerRating = userProfile.displayedRating,
            playerRank = rank.chineseTitle,
            engineName = engineManager.currentEngine?.name() ?: "未知",
            engineElo = engineManager.effectiveElo(),
            engineDowngraded = engineManager.isDowngraded
        )

        if (engineManager.isDowngraded) {
            _events.value = GameEvent.ShowToast(
                "未找到 Pikafish 引擎文件,已降级为内置引擎(约 ${engineManager.effectiveElo()} 分)"
            )
        }

        // 如果玩家执黑,引擎先走
        if (playerSide == Color.BLACK) {
            makeEngineMove()
        }
    }

    /** 引擎就绪 */
    override fun onReady() { /* 不需要做特殊处理 */ }

    /** 引擎搜索信息 */
    override fun onInfo(info: EngineInfo) {
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
        _winRateSnapshot.value = snapshot

        // 如果是提示搜索,累积 PV 信息
        if (searchPurpose == SearchPurpose.HINT && info.pv.isNotEmpty()) {
            processHintInfo(info)
        }
    }

    /** 引擎返回最佳走法 */
    override fun onBestMove(bestMove: String, ponderMove: String?) {
        val purpose = searchPurpose
        if (purpose == SearchPurpose.NONE || purpose == SearchPurpose.ANALYSIS) return
        if (bestMove == "0000" || bestMove.length < 4) return

        // 引擎回调在工作线程, position 只能在主线程上改
        viewModelScope.launch {
            if (purpose == SearchPurpose.HINT) {
                searchPurpose = SearchPurpose.NONE
                onHintBestMove(bestMove)
                return@launch
            }

            if (_state.value.isGameOver) return@launch
            // 只有轮到引擎时才落子, 防止把玩家的棋替玩家走了
            if (position.sideToMove == _state.value.playerSide) return@launch

            val move = uciToMove(bestMove)
            if (move == null || !position.isLegalMove(move)) {
                _events.value = GameEvent.ShowToast("引擎给出非法着法: $bestMove")
                _state.value = _state.value.copy(isThinking = false)
                return@launch
            }

            searchPurpose = SearchPurpose.NONE
            val capturedPiece = if (position.pieceAt(move.to) != Piece.NONE.code)
                Piece.fromCode(position.pieceAt(move.to)) else null
            val captured = capturedPiece != null
            position.doMove(move)
            moveHistory.add(move)
            updateAfterMove(captured, capturedPiece)
        }
    }

    override fun onError(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _events.value = GameEvent.ShowToast("引擎错误: $message")
        }
    }

    // ====== 玩家接口 ======

    /** 玩家走子 */
    fun playerMove(move: Move): Boolean {
        if (_state.value.isThinking || _state.value.isGameOver) return false
        if (position.sideToMove != _state.value.playerSide) return false
        if (!position.isLegalMove(move)) return false

        stopEngine()

        // 截胡: 玩家在被将军时必须应招(消将/挡将/避将). 走非应招的子
        // 看似合法(它本来就是合法着法), 但下一步仍会被将军, 引擎必吃.
        // 这种情况**拦截** —— 弹 Toast, 不真落子. 物理上不撤销"已被合法接受
        // 的点击"也没问题, 玩家立刻再选一个子就继续.
        val wasInCheck = Rules.isInCheck(position, _state.value.playerSide)
        val capturedPiece = if (position.pieceAt(move.to) != Piece.NONE.code)
            Piece.fromCode(position.pieceAt(move.to)) else null
        val captured = capturedPiece != null
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
            _events.value = GameEvent.ShowToast("被将军, 必须应招或消除威胁")
            return false
        }
        updateAfterMove(captured, capturedPiece)

        // 如果游戏未结束,让引擎走
        if (!_state.value.isGameOver) {
            makeEngineMove()
        }
        return true
    }

    /** 悔棋 */
    fun undoMove(): Boolean {
        if (_state.value.isThinking) return false
        if (moveHistory.isEmpty()) return false

        stopEngine()

        // 目标: 退回到轮玩家走的局面.
        // 轮到玩家 => 引擎刚走完, 要退引擎那步和玩家上一步(2 步);
        // 轮到引擎 => 玩家刚走完, 只退玩家这一步(1 步).
        val playerToMove = position.sideToMove == _state.value.playerSide
        val toUndo = if (playerToMove && moveHistory.size >= 2) 2 else 1
        repeat(toUndo.coerceAtMost(moveHistory.size)) {
            position.undoMove()
            moveHistory.removeAt(moveHistory.size - 1)
        }

        _state.value = _state.value.copy(
            sideToMove = position.sideToMove,
            isCheck = Rules.isInCheck(position, position.sideToMove),
            isGameOver = false,
            gameOverReason = "",
            gameOverWinner = Color.NONE,
            moveCount = moveHistory.size
        )
        _winRateSnapshot.value = WinRateSnapshot(redWinRate = 50.0)
        _hintState.value = null
        return true
    }

    /** 认输 —— 认输方永远是玩家自己,与执红执黑无关 */
    fun resign(): GameResult {
        stopEngine()
        endGame(GameResult.LOSS, "认输")
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
        if (_state.value.isGameOver) return
        if (position.sideToMove != _state.value.playerSide) return
        stopEngine()

        engineManager.setPosition(initialFen, moveHistory)
        val params = engineManager.requestHint()
        searchSideToMove = position.sideToMove
        searchPurpose = SearchPurpose.HINT
        engineManager.startSearch(params)
        _state.value = _state.value.copy(isThinking = true)
        _hintState.value = null
    }

    /** 后台实时分析(只更新胜率条,不落子) */
    private fun startAnalysis() {
        if (_state.value.isGameOver) return
        engineManager.setPosition(initialFen, moveHistory)
        searchSideToMove = position.sideToMove
        searchPurpose = SearchPurpose.ANALYSIS
        engineManager.startSearch(engineManager.requestAnalysis())
    }

    /** 设置玩家执子 */
    fun setPlayerSide(side: Int) {
        _state.value = _state.value.copy(playerSide = side)
    }

    /** 重置胜率显示 */
    fun resetAnalysisDisplay() {
        _winRateSnapshot.value = WinRateSnapshot(redWinRate = 50.0)
    }

    // ====== 内部 ======

    private fun makeEngineMove() {
        if (_state.value.isGameOver) return
        stopEngine()
        // 设置局面,启动引擎
        engineManager.setPosition(initialFen, moveHistory)
        val params = SearchParams(
            movetime = estimateThinkTime(_state.value.difficulty),
            threads = estimateThreads(_state.value.difficulty),
            hashMb = estimateHash(_state.value.difficulty),
            multiPv = 3
        )
        searchSideToMove = position.sideToMove
        searchPurpose = SearchPurpose.ENGINE_MOVE
        engineManager.startSearch(params)
        _state.value = _state.value.copy(isThinking = true)
    }

    private fun estimateThinkTime(difficulty: Int): Long = when (difficulty) {
        in 1..3 -> 500L
        in 4..5 -> 1500L
        6 -> 2000L
        7 -> 3000L
        8 -> 4000L
        9 -> 6000L
        10 -> 8000L
        else -> 3000L
    }

    private fun estimateThreads(difficulty: Int): Int = when (difficulty) {
        in 1..5 -> 1
        in 6..7 -> 2
        in 8..10 -> 4
        else -> 1
    }

    private fun estimateHash(difficulty: Int): Int = when (difficulty) {
        in 1..5 -> 16
        in 6..7 -> 64
        in 8..9 -> 128
        10 -> 256
        else -> 32
    }

    private fun stopEngine() {
        // 先清用途, 再停搜索: stop 会让引擎立刻吐一个 bestmove, 那个结果必须丢弃
        searchPurpose = SearchPurpose.NONE
        engineManager.stopSearch()
        _state.value = _state.value.copy(isThinking = false)
    }

    private fun updateAfterMove(captured: Boolean, capturedPiece: Piece? = null) {
        // 检测将军 / 终局
        val inCheck = Rules.isInCheck(position, position.sideToMove)
        val gameOver = Rules.gameOver(position)
        val isOver = gameOver != Rules.GameOver.NONE

        val gameOverReason = when (gameOver) {
            Rules.GameOver.CHECKMATE_RED -> "黑方将死红方"
            Rules.GameOver.CHECKMATE_BLACK -> "红方将死黑方"
            Rules.GameOver.STALEMATE -> "困毙"
            Rules.GameOver.REPETITION -> "重复局面"
            Rules.GameOver.FIFTY_MOVES -> "自然限着"
            else -> ""
        }
        val winner = when (gameOver) {
            Rules.GameOver.CHECKMATE_RED -> Color.BLACK
            Rules.GameOver.CHECKMATE_BLACK -> Color.RED
            else -> Color.NONE
        }

        _state.value = _state.value.copy(
            sideToMove = position.sideToMove,
            isCheck = inCheck,
            isGameOver = isOver,
            gameOverReason = gameOverReason,
            gameOverWinner = winner,
            moveCount = moveHistory.size,
            lastMoveCaptured = captured,
            isThinking = false
        )

        // 重新算威胁光晕 + 触发音效
        emitThreats()
        emitFeedback(inCheck, isOver, gameOver, capturedPiece)

        if (isOver) {
            // 确定玩家结果
            val playerResult = when (winner) {
                Color.NONE -> GameResult.DRAW
                _state.value.playerSide -> GameResult.WIN
                else -> GameResult.LOSS
            }
            endGame(playerResult, gameOverReason)
        } else if (position.sideToMove == _state.value.playerSide) {
            // 轮到玩家思考时才后台分析, 避免和引擎的对局搜索抢同一个引擎实例
            if (moveHistory.size > 5) startAnalysis()
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
    private fun emitFeedback(inCheck: Boolean, isOver: Boolean, gameOver: Rules.GameOver, capturedPiece: Piece?) {
        _events.value = when {
            isOver && (gameOver == Rules.GameOver.CHECKMATE_RED || gameOver == Rules.GameOver.CHECKMATE_BLACK) ->
                GameEvent.Checkmate
            inCheck -> GameEvent.PlayerInCheck
            capturedPiece != null -> GameEvent.CapturedPiece(capturedPiece)
            else -> GameEvent.NormalMove
        }
    }

    private fun endGame(result: GameResult, reason: String) {
        stopEngine()
        val difficulty = _state.value.difficulty
        // 用实际对手棋力算分,而不是难度标称值(降级时两者不同)
        val engineElo = engineManager.effectiveElo()
        userProfile.recordResult(result, engineElo)
        val rank = userProfile.rank()

        _state.value = _state.value.copy(
            isGameOver = true,
            gameOverReason = reason,
            isThinking = false,
            playerRating = userProfile.displayedRating,
            ratingDelta = userProfile.displayedRating - gameStartRating,
            playerRank = rank.chineseTitle
        )

        // 保存对局记录
        viewModelScope.launch(Dispatchers.IO) {
            val record = GameRecord(
                startTime = gameStartTime,
                endTime = System.currentTimeMillis(),
                playerSide = _state.value.playerSide,
                difficulty = difficulty,
                engineElo = engineElo,
                result = when (result) {
                    GameResult.WIN -> 1
                    GameResult.LOSS -> -1
                    GameResult.DRAW -> 0
                },
                ratingDelta = userProfile.displayedRating - gameStartRating,
                playerRating = userProfile.displayedRating,
                moves = moveHistory.map { moveToUci(it) },
                initialFen = initialFen,
                endReason = reason
            )
            gameDao.insert(record)
        }

        _events.value = GameEvent.GameOver
    }

    /** 处理提示搜索结果(从 onInfo 累积) */
    private fun processHintInfo(info: EngineInfo) {
        // 从 PV 提取第一条作为主提示
        val primaryUci = info.pv.firstOrNull() ?: return
        val primaryMove = uciToMove(primaryUci) ?: return
        val secondaryMoves = info.pv.drop(1).mapNotNull { uciToMove(it) }.take(2)

        _hintState.value = HintState(
            primaryMove = primaryMove,
            secondaryMoves = secondaryMoves,
            scoreCp = info.scoreCp,
            scoreMate = info.scoreMate,
            depth = info.depth,
            pv = info.pv
        )
    }

    private fun uciToMove(uci: String): Move? = UciCoords.parse(uci)

    private fun moveToUci(move: Move): String = UciCoords.toUci(move)

    /** 提示搜索结束: 用最终 bestmove 作为推荐着法 */
    private fun onHintBestMove(bestMove: String) {
        val move = uciToMove(bestMove)
        if (move != null) {
            _hintState.value = HintState(
                primaryMove = move,
                secondaryMoves = emptyList(),
                scoreCp = _winRateSnapshot.value.scoreCp,
                scoreMate = _winRateSnapshot.value.scoreMate,
                depth = _winRateSnapshot.value.depth,
                pv = listOf(bestMove)
            )
            _state.value = _state.value.copy(isThinking = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        engineManager.shutdown()
    }
}