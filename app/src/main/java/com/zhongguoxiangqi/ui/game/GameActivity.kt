package com.zhongguoxiangqi.ui.game

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityGameBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.rating.UserProfile
import kotlinx.coroutines.launch

/**
 * 主对局 Activity.
 *
 * 布局:
 * - 顶部:对手信息(引擎名、评分、是否在思考)
 * - 胜率条
 * - 棋盘
 * - 提示信息
 * - 玩家信息(评分、当前手数)
 * - 控制按钮(提示、悔棋、翻转、认输)
 */
class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private val viewModel: GameViewModel by viewModels()
    private lateinit var userProfile: UserProfile

    private var lastPlayerSide = Color.RED
    private var lastDifficulty = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

        userProfile = UserProfile(this)
        lastDifficulty = intent.getIntExtra(EXTRA_DIFFICULTY, 5)
        lastPlayerSide = intent.getIntExtra(EXTRA_PLAYER_SIDE, Color.RED)

        setupBoard()
        setupControls()
        observeState()

        // 启动新对局
        viewModel.startNewGame(lastDifficulty, lastPlayerSide)
    }

    private fun setupBoard() {
        binding.boardView.onMoveListener = { move ->
            // 玩家走子
            val success = viewModel.playerMove(move)
            if (!success) {
                Toast.makeText(this, "非法走子", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupControls() {
        binding.btnHint.setOnClickListener {
            if (viewModel.state.value.isThinking) return@setOnClickListener
            viewModel.requestHint()
            Toast.makeText(this, R.string.action_hint, Toast.LENGTH_SHORT).show()
        }

        binding.btnUndo.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_undo_title)
                .setMessage(R.string.dialog_undo_message)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    val ok = viewModel.undoMove()
                    if (!ok) Toast.makeText(this, "无法悔棋", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }

        binding.btnFlip.setOnClickListener {
            binding.boardView.setFlipped(!binding.boardView.isFlipped())
        }

        binding.btnResign.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_resign_title)
                .setMessage(R.string.dialog_resign_message)
                .setPositiveButton(R.string.dialog_yes) { _, _ ->
                    viewModel.resign()
                }
                .setNegativeButton(R.string.dialog_no, null)
                .show()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                // 引擎名/对手评分
                binding.opponentName.text = if (state.playerSide == Color.RED) "引擎 (执黑)" else "引擎 (执红)"
                val elo = engineEloFor(state.difficulty)
                binding.opponentRating.text = "Elo $elo"
                binding.engineName.text = state.engineName
                binding.playerName.text = if (state.playerSide == Color.RED) "我 (执红)" else "我 (执黑)"
                binding.playerRating.text = "Elo ${state.playerRating} ${state.playerRank}"
                binding.moveCount.text = "第${state.moveCount / 2 + 1}手"

                binding.thinkIndicator.visibility = if (state.isThinking) View.VISIBLE else View.GONE

                // 引擎信息
                val wr = viewModel.winRateSnapshot.value
                binding.depthLabel.text = "D${wr.depth}"
                binding.npsLabel.text = if (wr.nps > 1000) "${wr.nps / 1000}k NPS" else "${wr.nps} NPS"

                // 棋盘更新
                updateBoard()

                // 游戏结束
                if (state.isGameOver) {
                    showGameOverDialog(state.gameOverReason)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.winRateSnapshot.collect { snapshot ->
                binding.winrateBar.update(snapshot)
                binding.depthLabel.text = "D${snapshot.depth}"
                binding.npsLabel.text = if (snapshot.nps > 1000) "${snapshot.nps / 1000}k NPS" else "${snapshot.nps} NPS"
            }
        }

        lifecycleScope.launch {
            viewModel.hintState.collect { hint ->
                if (hint != null && hint.primaryMove != null) {
                    binding.boardView.setHint(hint.primaryMove, hint.secondaryMoves)
                    binding.hintRow.visibility = View.VISIBLE
                    val mainMv = moveToChinese(hint.primaryMove)
                    val secMv = hint.secondaryMoves.map { moveToChinese(it) }.joinToString(", ")
                    binding.hintText.text = "推荐: $mainMv${if (secMv.isNotEmpty()) "  备选: $secMv" else ""}"
                    val evalStr = if (hint.scoreMate != 0) {
                        if (hint.scoreMate > 0) "${hint.scoreMate}步杀" else "${-hint.scoreMate}步被杀"
                    } else {
                        "%.1f 兵".format(hint.scoreCp / 100.0)
                    }
                    binding.hintScore.text = "评估: $evalStr   深度: ${hint.depth}"
                } else {
                    binding.boardView.clearHint()
                    binding.hintRow.visibility = View.GONE
                }
            }
        }
    }

    private fun updateBoard() {
        val position = viewModel.currentPosition
        binding.boardView.setPosition(position)

        // 检测将军
        val kingSq = if (position.sideToMove == Color.RED) {
            findKing(position, Color.RED)
        } else {
            findKing(position, Color.BLACK)
        }
        if (kingSq != -1 && Rules.isInCheck(position, position.sideToMove)) {
            binding.boardView.setCheckedKing(kingSq)
        } else {
            binding.boardView.setCheckedKing(-1)
        }

        // 上一步走法
        if (viewModel.moveHistory.isNotEmpty()) {
            binding.boardView.setLastMove(viewModel.moveHistory.last())
        }
    }

    private fun findKing(position: com.zhongguoxiangqi.game.Position, color: Int): Int {
        val kingCode = if (color == Color.RED) com.zhongguoxiangqi.game.Piece.R_KING.code
        else com.zhongguoxiangqi.game.Piece.B_KING.code
        for (sq in 0 until com.zhongguoxiangqi.game.Square.COUNT) {
            if (position.pieceAt(sq) == kingCode) return sq
        }
        return -1
    }

    private fun moveToChinese(move: Move): String {
        val pos = viewModel.currentPosition
        return Move.toChineseString(move, pos.sideToMove, pos)
    }

    private fun showGameOverDialog(reason: String) {
        val state = viewModel.state.value
        val message = buildString {
            append("对局结束\n")
            append("原因: $reason\n")
            val ratingDelta = state.playerRating - intent.getIntExtra(EXTRA_INITIAL_RATING, state.playerRating)
            append("评分变化: ${if (ratingDelta >= 0) "+" else ""}$ratingDelta\n")
            append("当前评分: ${state.playerRating}  ${state.playerRank}")
        }
        AlertDialog.Builder(this)
            .setTitle("对局结束")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_new_game) { _, _ ->
                viewModel.startNewGame(lastDifficulty, lastPlayerSide)
            }
            .setNegativeButton(R.string.action_back) { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 引擎在 VM.onCleared 中关闭
    }

    private fun engineEloFor(difficulty: Int): Int = when (difficulty) {
        1 -> 600; 2 -> 900; 3 -> 1200; 4 -> 1500
        5 -> 1700; 6 -> 1900; 7 -> 2200; 8 -> 2500
        9 -> 2900; 10 -> 3300
        else -> 1500
    }

    companion object {
        const val EXTRA_DIFFICULTY = "difficulty"
        const val EXTRA_PLAYER_SIDE = "player_side"
        const val EXTRA_INITIAL_RATING = "initial_rating"
    }
}