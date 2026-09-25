package com.zhongguoxiangqi.ui.puzzle

import android.app.Application
import android.os.CountDownTimer
import androidx.lifecycle.AndroidViewModel
import com.zhongguoxiangqi.R
import androidx.lifecycle.viewModelScope
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.engine.EngineListener
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.engine.SearchParams
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Puzzle
import com.zhongguoxiangqi.game.Puzzles
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.UciCoords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 残局速拼 (Puzzle Rush) ViewModel.
 *
 * 3分钟倒计时，3条生命，连击与动态难度阶梯.
 */
class PuzzleRushViewModel(app: Application) : AndroidViewModel(app), EngineListener {

    sealed class RushEvent {
        data class Solved(val score: Int, val combo: Int) : RushEvent()
        data class Strike(val livesLeft: Int) : RushEvent()
        object Finished : RushEvent()
        object MoveAccepted : RushEvent()
    }

    data class UiState(
        val currentPuzzle: Puzzle? = null,
        val score: Int = 0,
        val combo: Int = 0,
        val maxCombo: Int = 0,
        val lives: Int = 3,
        val secondsLeft: Int = 180,
        val isFinished: Boolean = false,
        val finishReason: String = "",
        val isNewRecord: Boolean = false,
        val highScore: Int = 0,
        val honorTitle: String = "",
        val isThinking: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<RushEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    val store = ProgressStore(app)
    private val engineManager = EngineManager(app)
    private val position = Position()
    val currentPosition: Position get() = position
    val moveHistory = mutableListOf<Move>()

    private var countDownTimer: CountDownTimer? = null
    private val usedPuzzleIds = mutableSetOf<Int>()
    private var initialFen = ""
    private var playerMovesCount = 0
    private val searchGeneration = AtomicLong()

    init {
        val initialBest = store.puzzleRushHighScore
        _state.value = _state.value.copy(highScore = initialBest)
        viewModelScope.launch(Dispatchers.IO) {
            engineManager.start(this@PuzzleRushViewModel)
        }
    }

    fun startRush() {
        countDownTimer?.cancel()
        usedPuzzleIds.clear()
        _state.value = UiState(
            highScore = store.puzzleRushHighScore,
            secondsLeft = RUSH_DURATION_SECONDS,
            lives = 3,
            score = 0,
            combo = 0,
            maxCombo = 0,
            isFinished = false
        )

        loadNextPuzzle()

        countDownTimer = object : CountDownTimer(RUSH_DURATION_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (_state.value.isFinished) return
                val secs = (millisUntilFinished / 1000L).toInt()
                _state.value = _state.value.copy(secondsLeft = secs)
            }

            override fun onFinish() {
                if (!_state.value.isFinished) {
                    _state.value = _state.value.copy(secondsLeft = 0)
                    finishRush(getApplication<android.app.Application>().getString(R.string.puzzle_rush_time_up))
                }
            }
        }.start()
    }

    fun skipCurrentPuzzle() {
        if (_state.value.isFinished) return
        onStrike()
    }

    fun onPlayerMove(move: Move): Boolean {
        val p = _state.value.currentPuzzle ?: return false
        if (_state.value.isFinished || _state.value.isThinking) return false
        if (position.sideToMove != Color.RED) return false
        if (!position.isLegalMove(move)) return false

        position.doMove(move)
        moveHistory.add(move)
        playerMovesCount++
        _events.tryEmit(RushEvent.MoveAccepted)

        val over = Rules.gameOver(position)
        val isMate = over == Rules.GameOver.CHECKMATE_BLACK || over == Rules.GameOver.STALEMATE || Rules.isCheckmate(position)

        if (isMate) {
            onPuzzleSolved()
            return true
        }

        // 超出允许步数或走入死胡同 (红方被将死/非将军等)
        if (playerMovesCount >= p.maxMoves) {
            onStrike()
            return true
        }

        // 需要黑方引擎应手
        engineDefend()
        return true
    }

    private fun onPuzzleSolved() {
        val nextScore = _state.value.score + 1
        val nextCombo = _state.value.combo + 1
        val nextMaxCombo = maxOf(_state.value.maxCombo, nextCombo)
        _state.value = _state.value.copy(
            score = nextScore,
            combo = nextCombo,
            maxCombo = nextMaxCombo
        )
        _events.tryEmit(RushEvent.Solved(nextScore, nextCombo))

        viewModelScope.launch {
            delay(380)
            if (!_state.value.isFinished) {
                loadNextPuzzle()
            }
        }
    }

    private fun onStrike() {
        val nextLives = _state.value.lives - 1
        _state.value = _state.value.copy(
            lives = nextLives,
            combo = 0
        )
        _events.tryEmit(RushEvent.Strike(nextLives))

        if (nextLives <= 0) {
            finishRush(getApplication<android.app.Application>().getString(R.string.puzzle_rush_lives_out))
        } else {
            viewModelScope.launch {
                delay(450)
                if (!_state.value.isFinished) {
                    loadNextPuzzle()
                }
            }
        }
    }

    private fun loadNextPuzzle() {
        val currentScore = _state.value.score
        // 根据得分动态阶梯选题:
        // 0~4分: 卷一 (1步杀，快速建立节奏)
        // 5~9分: 卷一与卷二混合 (1~2步杀)
        // 10分以上: 卷二与卷三混合 (2~4步杀)
        val candidatePool = when {
            currentScore < 5 -> Puzzles.all.filter { it.chapter == 1 }
            currentScore < 10 -> Puzzles.all.filter { it.chapter <= 2 }
            else -> Puzzles.all.filter { it.chapter >= 2 }
        }

        val available = candidatePool.filter { it.id !in usedPuzzleIds }
        val puzzle = if (available.isNotEmpty()) {
            available.random()
        } else {
            usedPuzzleIds.clear()
            candidatePool.random()
        }

        usedPuzzleIds.add(puzzle.id)
        initialFen = puzzle.fen
        position.setFromFen(initialFen)
        moveHistory.clear()
        playerMovesCount = 0

        _state.value = _state.value.copy(
            currentPuzzle = puzzle,
            isThinking = false
        )
    }

    private fun engineDefend() {
        _state.value = _state.value.copy(isThinking = true)
        searchGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            engineManager.setPosition(initialFen, moveHistory)
            engineManager.startSearch(
                SearchParams(movetime = 100, threads = 1, hashMb = 16, multiPv = 1, strictMovetime = true)
            )
        }
    }

    private fun finishRush(reason: String) {
        countDownTimer?.cancel()
        val finalScore = _state.value.score
        val isNew = store.recordPuzzleRushScore(finalScore)
        val app = getApplication<android.app.Application>()
        val title = app.getString(when {
            finalScore < 3 -> R.string.puzzle_rush_honor_1
            finalScore < 6 -> R.string.puzzle_rush_honor_2
            finalScore < 10 -> R.string.puzzle_rush_honor_3
            finalScore < 15 -> R.string.puzzle_rush_honor_4
            else -> R.string.puzzle_rush_honor_5
        })

        _state.value = _state.value.copy(
            isFinished = true,
            finishReason = reason,
            isNewRecord = isNew,
            highScore = maxOf(_state.value.highScore, finalScore),
            honorTitle = title,
            isThinking = false
        )
        _events.tryEmit(RushEvent.Finished)
    }

    override fun onCleared() {
        super.onCleared()
        countDownTimer?.cancel()
        engineManager.stopSearch()
        engineManager.shutdown()
    }

    // EngineListener
    override fun onReady() {}
    override fun onInfo(info: com.zhongguoxiangqi.engine.EngineInfo) {}
    override fun onError(message: String) {}

    override fun onBestMove(bestMove: String, ponderMove: String?) {
        viewModelScope.launch {
            if (_state.value.isFinished) return@launch
            val move = UciCoords.parse(bestMove)
            if (move != null && position.isLegalMove(move)) {
                position.doMove(move)
                moveHistory.add(move)
                val over = Rules.gameOver(position)
                if (over == Rules.GameOver.CHECKMATE_RED || over == Rules.GameOver.STALEMATE) {
                    onStrike()
                } else {
                    _state.value = _state.value.copy(isThinking = false)
                }
            } else {
                // 如果引擎无解或应手异常，判玩家速胜
                onPuzzleSolved()
            }
        }
    }

    companion object {
        const val RUSH_DURATION_SECONDS = 180 // 3 分钟
    }
}
