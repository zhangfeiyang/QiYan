package com.zhongguoxiangqi.ui.puzzle

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.engine.EngineListener
import com.zhongguoxiangqi.engine.SearchParams
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Puzzle
import com.zhongguoxiangqi.game.Puzzles
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.UciCoords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 残局破解 ViewModel.
 *
 * 一道残局 = 玩家(红)先行, 在谜题限定的步数内将死黑方; 引擎执黑**最佳防守**。
 * 规则:
 * - 每次尝试最多悔棋 3 次(一次悔掉"玩家+黑方"一整回合);
 * - 超出步数限制仍未将死 = 本局失败;
 * - 每次黑方防守后引擎做一次快速校验, 玩家若已错失杀棋会立即得到提示。
 */
class PuzzleViewModel(app: Application) : AndroidViewModel(app), EngineListener {

    data class UiState(
        val puzzle: Puzzle? = null,
        val attempts: Int = 0,
        val solvedBefore: Boolean = false,
        val playerMoves: Int = 0,
        val undosLeft: Int = UNDO_LIMIT,
        val thinking: Boolean = false,
        val engineReady: Boolean = false,
        /** 本局已结束(won 区分成败) */
        val finished: Boolean = false,
        val won: Boolean = false,
        /** 走错导致错失杀机 */
        val deviated: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** 一次性事件: 本局结束弹窗 */
    private val _finishedEvent = MutableLiveData<Boolean?>()
    val finishedEvent = _finishedEvent

    /** 轻提示(偏离解法等), 消费后置空 */
    private val _toastEvent = MutableLiveData<Int?>()
    val toastEvent = _toastEvent

    val store = ProgressStore(app)

    private val engineManager = EngineManager(app)
    private val position = Position()
    val currentPosition: Position get() = position
    val moveHistory = mutableListOf<Move>()

    private var puzzle: Puzzle? = null
    private var initialFen = ""

    /** 当前引擎搜索的用途; 引擎单实例, 必须区分"黑方防守"与"杀机校验" */
    @Volatile private var purpose = Purpose.NONE
    @Volatile private var lastCheckMateScore = 0

    private enum class Purpose { NONE, DEFENSE, CHECK }

    init {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { engineManager.start(this@PuzzleViewModel) }
            _state.value = _state.value.copy(engineReady = ok)
        }
    }

    fun loadPuzzle(id: Int) {
        val p = Puzzles.byId(id) ?: return
        puzzle = p
        initialFen = p.fen
        resetBoard(startNewAttempt = true)
    }

    /** 玩家(红方)走子入口, 返回是否接受 */
    fun onPlayerMove(move: Move): Boolean {
        val p = puzzle ?: return false
        val s = _state.value
        if (s.finished || s.thinking || !s.engineReady) return false
        if (position.sideToMove != Color.RED) return false
        if (!position.isLegalMove(move)) return false

        stopEngine()
        position.doMove(move)
        moveHistory.add(move)
        val playerMoves = s.playerMoves + 1
        _state.value = s.copy(playerMoves = playerMoves, deviated = false)

        val over = Rules.gameOver(position)
        if (over == Rules.GameOver.CHECKMATE_BLACK || over == Rules.GameOver.STALEMATE) {
            finish(won = true)
            return true
        }
        if (over != Rules.GameOver.NONE) {
            // 红方被将死/困毙: 残局里基本不可能, 但要接住
            finish(won = false)
            return true
        }
        if (playerMoves >= p.maxMoves) {
            finish(won = false)
            return true
        }

        engineDefend()
        return true
    }

    /** 悔棋: 悔掉"玩家+黑方"一整回合 */
    fun undo(): Boolean {
        val s = _state.value
        if (s.finished || s.thinking || s.undosLeft <= 0) return false
        stopEngine()
        var undone = 0
        while (moveHistory.isNotEmpty() && (undone == 0 || position.sideToMove != Color.RED)) {
            position.undoMove()
            moveHistory.removeAt(moveHistory.size - 1)
            undone++
            if (position.sideToMove == Color.RED && undone % 2 == 0) break
        }
        if (undone == 0) return false
        _state.value = s.copy(
            undosLeft = s.undosLeft - 1,
            playerMoves = (moveHistory.size + 1) / 2,
            deviated = false,
        )
        return true
    }

    /** 重新开始本局(计一次新尝试) */
    fun reset() {
        _finishedEvent.value = null
        resetBoard(startNewAttempt = true)
    }

    // ====== 内部 ======

    private fun resetBoard(startNewAttempt: Boolean) {
        val p = puzzle ?: return
        stopEngine()
        position.setFromFen(initialFen)
        moveHistory.clear()
        _state.value = _state.value.copy(
            puzzle = p,
            playerMoves = 0,
            undosLeft = UNDO_LIMIT,
            finished = false,
            won = false,
            deviated = false,
            thinking = false,
            solvedBefore = p.id in store.puzzleSolvedIds(),
            attempts = if (startNewAttempt) store.incrementPuzzleAttempts(p.id)
                       else _state.value.attempts,
        )
    }

    private fun engineDefend() {
        val p = puzzle ?: return
        engineManager.setPosition(initialFen, moveHistory)
        purpose = Purpose.DEFENSE
        _state.value = _state.value.copy(thinking = true)
        engineManager.startSearch(
            SearchParams(movetime = DEFEND_MOVETIME, threads = 1, hashMb = 16, multiPv = 1, strictMovetime = true)
        )
    }

    /** 黑方防守后, 从玩家视角快速校验杀机是否还在 */
    private fun checkMateStillOn() {
        val p = puzzle ?: return
        engineManager.setPosition(initialFen, moveHistory)
        purpose = Purpose.CHECK
        lastCheckMateScore = 0
        engineManager.startSearch(
            SearchParams(movetime = CHECK_MOVETIME, threads = 1, hashMb = 16, multiPv = 1, strictMovetime = true)
        )
    }

    private fun finish(won: Boolean) {
        val p = puzzle ?: return
        stopEngine()
        if (won) store.markPuzzleSolved(p.id)
        _state.value = _state.value.copy(finished = true, won = won, thinking = false)
        _finishedEvent.value = won
    }

    private fun stopEngine() {
        purpose = Purpose.NONE
        engineManager.stopSearch()
        _state.value = _state.value.copy(thinking = false)
    }

    // ====== EngineListener ======

    override fun onReady() {}

    override fun onInfo(info: com.zhongguoxiangqi.engine.EngineInfo) {
        // 杀机校验的评分是玩家(红)视角: searchSideToMove 就是红方
        if (purpose == Purpose.CHECK && info.scoreMate != 0) {
            lastCheckMateScore = info.scoreMate
        }
    }

    override fun onBestMove(bestMove: String, ponderMove: String?) {
        val purposeSnap = purpose
        viewModelScope.launch {
            when (purposeSnap) {
                Purpose.DEFENSE -> {
                    val p = puzzle ?: return@launch
                    if (_state.value.finished) return@launch
                    val move = UciCoords.parse(bestMove)
                    if (move == null || !position.isLegalMove(move)) {
                        // 黑方无好棋可走(将死/困毙边缘) —— 视作玩家得手
                        finish(won = true)
                        return@launch
                    }
                    position.doMove(move)
                    moveHistory.add(move)
                    _state.value = _state.value.copy(thinking = false)
                    val over = Rules.gameOver(position)
                    // 黑方把红方将死/困毙: 本局失败
                    if (over == Rules.GameOver.CHECKMATE_RED || over == Rules.GameOver.STALEMATE) {
                        finish(won = false)
                        return@launch
                    }
                    // 防守完校验杀机: 不再是杀 = 玩家走错了
                    checkMateStillOn()
                }
                Purpose.CHECK -> {
                    purpose = Purpose.NONE
                    if (lastCheckMateScore <= 0 && !_state.value.finished) {
                        _state.value = _state.value.copy(deviated = true)
                        _toastEvent.value = com.zhongguoxiangqi.R.string.puzzle_deviated
                    }
                }
                Purpose.NONE -> {}
            }
        }
    }

    override fun onError(message: String) {
        // 引擎错误静默: 残局里引擎起不来时棋盘仍可摆, 但不给防守
    }

    override fun onCleared() {
        super.onCleared()
        engineManager.shutdown()
    }

    companion object {
        /** 每次尝试的悔棋上限 */
        const val UNDO_LIMIT = 3
        private const val DEFEND_MOVETIME = 900L
        private const val CHECK_MOVETIME = 600L
    }
}
