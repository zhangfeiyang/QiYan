package com.zhongguoxiangqi.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import com.zhongguoxiangqi.rating.GameResult
import com.zhongguoxiangqi.rating.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val events: MutableLiveData<GameEvent?> = _events

    // ====== 数据 ======
    private val position = Position()
    val currentPosition: Position get() = position
    val moveHistory = mutableListOf<Move>()

    private val userProfile: UserProfile = UserProfile(getApplication())
    private val engineManager: EngineManager = EngineManager(getApplication())
    private val gameDao: GameRecordDao = com.zhongguoxiangqi.data.AppDatabase.get(getApplication()).gameRecordDao()

    private var gameStartTime = 0L
    private var gameStartRating = 0
    private var initialFen = ""
    private var thinkingJob: Job? = null

    /** 当前是否为提示搜索(影响 onBestMove 处理) */
    @Volatile private var isHintSearchActive = false

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
        val gameOverWinner: Int = 0,
        val moveCount: Int = 0,
        val playerRating: Int = 1500,
        val playerRank: String = "初学者",
        val engineName: String = ""
    )

    sealed class GameEvent {
        object ShowHint { /* has data in hintState */ }
        data class ShowToast(val msg: String) : GameEvent()
        data class ShowDialog(val title: String, val message: String) : GameEvent()
        object GameOver : GameEvent()
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
        engineManager.setListener(this)
        val started = engineManager.start(this)

        val rank = userProfile.rank()
        _state.value = GameState(
            difficulty = difficulty,
            playerSide = playerSide,
            sideToMove = Color.RED,
            isThinking = false,
            isCheck = false,
            isGameOver = false,
            gameOverReason = "",
            gameOverWinner = 0,
            moveCount = 0,
            playerRating = userProfile.displayedRating,
            playerRank = rank.chineseTitle,
            engineName = engineManager.currentEngine?.name() ?: "未知"
        )

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
        val redWR = WinRateAnalyzer.redWinRate(scoreCp, scoreMate)
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
        if (isHintSearchActive && info.pv.isNotEmpty()) {
            processHintInfo(info)
        }
    }

    /** 引擎返回最佳走法 */
    override fun onBestMove(bestMove: String, ponderMove: String?) {
        if (bestMove == "0000" || bestMove.length < 4) return

        // 如果是提示搜索,转给提示状态
        if (isHintSearchActive) {
            isHintSearchActive = false
            onHintBestMove(bestMove)
            return
        }

        val move = uciToMove(bestMove) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            // 应用引擎走法到 position
            if (position.isLegalMove(move)) {
                withContext(Dispatchers.Main) {
                    position.doMove(move)
                    moveHistory.add(move)
                    // 更新状态
                    updateAfterMove(move)
                }
            }
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
        position.doMove(move)
        moveHistory.add(move)
        updateAfterMove(move)

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

        // 撤销最后两步(玩家 + 引擎) 或 玩家走子前的一步
        val toUndo = if (moveHistory.size >= 2 &&
            position.sideToMove != _state.value.playerSide) 2 else 1
        repeat(toUndo) {
            position.undoMove()
            if (moveHistory.isNotEmpty()) {
                moveHistory.removeAt(moveHistory.size - 1)
            }
        }

        _state.value = _state.value.copy(
            sideToMove = position.sideToMove,
            isCheck = Rules.isInCheck(position, position.sideToMove),
            isGameOver = false,
            gameOverReason = "",
            gameOverWinner = 0,
            moveCount = moveHistory.size
        )
        _winRateSnapshot.value = WinRateSnapshot(redWinRate = 50.0)
        _hintState.value = null
        return true
    }

    /** 认输 */
    fun resign(): GameResult {
        stopEngine()
        val playerColor = _state.value.playerSide
        val result = if (playerColor == Color.RED) GameResult.LOSS else GameResult.WIN
        endGame(result, "认输")
        return result
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
        if (_state.value.isThinking) return
        stopEngine()

        engineManager.setPosition(position, moveHistory)
        val params = engineManager.requestHint(moveHistory)
        isHintSearchActive = true
        engineManager.startSearch(params)
        _state.value = _state.value.copy(isThinking = true)
        _hintState.value = null
    }

    /** 切换实时分析开关 */
    fun toggleAnalysis(enabled: Boolean) {
        if (enabled) {
            engineManager.setPosition(position, moveHistory)
            val params = engineManager.requestAnalysis()
            engineManager.startSearch(params)
        } else {
            engineManager.stopSearch()
            // 不显示 isThinking
        }
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
        // 设置局面,启动引擎
        engineManager.setPosition(position, moveHistory)
        val params = SearchParams(
            movetime = estimateThinkTime(_state.value.difficulty),
            threads = estimateThreads(_state.value.difficulty),
            hashMb = estimateHash(_state.value.difficulty),
            multiPv = 3
        )
        engineManager.startSearch(params)
        _state.value = _state.value.copy(isThinking = true)
    }

    private fun estimateThinkTime(difficulty: Int): Long = when (difficulty) {
        in 1..3 -> 500L
        in 4..5 -> 1500L
        in 6..7 -> 3000L
        in 8..9 -> 5000L
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
        thinkingJob?.cancel()
        engineManager.stopSearch()
        _state.value = _state.value.copy(isThinking = false)
    }

    private fun updateAfterMove(move: Move) {
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
            else -> 0
        }

        _state.value = _state.value.copy(
            sideToMove = position.sideToMove,
            isCheck = inCheck,
            isGameOver = isOver,
            gameOverReason = gameOverReason,
            gameOverWinner = winner,
            moveCount = moveHistory.size,
            isThinking = false
        )

        if (isOver) {
            // 确定玩家结果
            val playerResult = if (winner == _state.value.playerSide) GameResult.WIN
                else if (winner == 0) GameResult.DRAW else GameResult.LOSS
            endGame(playerResult, gameOverReason)
        } else {
            // 启动后台分析(实时胜率)
            if (moveHistory.size > 5) {
                toggleAnalysis(true)
            }
        }
    }

    private fun endGame(result: GameResult, reason: String) {
        stopEngine()
        val difficulty = _state.value.difficulty
        val engineElo = engineEloFor(difficulty)
        userProfile.recordResult(result, engineElo)
        val rank = userProfile.rank()

        _state.value = _state.value.copy(
            isGameOver = true,
            gameOverReason = reason,
            isThinking = false,
            playerRating = userProfile.displayedRating,
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

    private fun engineEloFor(difficulty: Int): Int = when (difficulty) {
        1 -> 600; 2 -> 900; 3 -> 1200; 4 -> 1500
        5 -> 1700; 6 -> 1900; 7 -> 2200; 8 -> 2500
        9 -> 2900; 10 -> 3300
        else -> 1500
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

    private fun uciToMove(uci: String): Move? {
        if (uci.length < 4) return null
        return try {
            val fromCol = uci[0] - 'a'
            val fromRow = uci[1].digitToInt()
            val toCol = uci[2] - 'a'
            val toRow = uci[3].digitToInt()
            val from = com.zhongguoxiangqi.game.Square.make(9 - fromRow, fromCol)
            val to = com.zhongguoxiangqi.game.Square.make(9 - toRow, toCol)
            Move.make(from, to)
        } catch (_: Exception) {
            null
        }
    }

    private fun moveToUci(move: Move): String {
        val fc = com.zhongguoxiangqi.game.Square.col(move.from)
        val fr = 9 - com.zhongguoxiangqi.game.Square.row(move.from)
        val tc = com.zhongguoxiangqi.game.Square.col(move.to)
        val tr = 9 - com.zhongguoxiangqi.game.Square.row(move.to)
        return "${(fc + 'a'.code).toChar()}$fr${(tc + 'a'.code).toChar()}$tr"
    }

    /** 设置提示回调监听(由 UI 调用) */
    fun overrideHintProcessing() {
        // 由 GameActivity 接管 hint 流程
    }

    /** Activity 直接调用以更新提示状态 */
    fun onHintInfo(info: EngineInfo) = processHintInfo(info)

    /** Activity 直接处理最佳走法(用于提示) */
    fun onHintBestMove(bestMove: String) {
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