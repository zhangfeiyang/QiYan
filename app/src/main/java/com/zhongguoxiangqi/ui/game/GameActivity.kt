package com.zhongguoxiangqi.ui.game

import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import com.zhongguoxiangqi.ui.settings.AppSettings
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
    private var gameOverDialog: AlertDialog? = null

    /** 棋盘上已经渲染到第几步,用来判断是"又走了一步"还是悔棋/重开 */
    private var renderedMoveCount = 0
    private lateinit var feedback: GameFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // 引擎思考和玩家看棋期间不要熄屏(无需任何权限)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        userProfile = UserProfile(this)
        feedback = GameFeedback(this)
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
        applyDisplaySettings()
    }

    /** 把设置界面里的显示选项真正落到棋盘上 */
    private fun applyDisplaySettings() {
        val settings = AppSettings(this)
        binding.boardView.setShowCoordinates(settings.showCoordinates)
        binding.boardView.setAnimationEnabled(settings.animationEnabled)
        // 执黑时默认翻转到黑方视角,设置里的翻转开关在此基础上再取反
        val defaultFlip = lastPlayerSide == Color.BLACK
        binding.boardView.setFlipped(defaultFlip != settings.boardFlipped)
        binding.winrateBar.visibility = if (settings.showWinRate) View.VISIBLE else View.GONE
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
        // 引擎错误等一次性事件, 否则这些提示会被静默丢掉
        viewModel.events.observe(this) { event ->
            when (event) {
                is GameViewModel.GameEvent.ShowToast -> {
                    Toast.makeText(this, event.msg, Toast.LENGTH_SHORT).show()
                    viewModel.consumeEvent()
                }
                is GameViewModel.GameEvent.ShowDialog -> {
                    AlertDialog.Builder(this)
                        .setTitle(event.title)
                        .setMessage(event.message)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show()
                    viewModel.consumeEvent()
                }
                // 终局对话框由 state.isGameOver 驱动
                else -> Unit
            }
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                // 引擎名/对手评分
                binding.opponentName.text = if (state.playerSide == Color.RED) "引擎 (执黑)" else "引擎 (执红)"
                // 显示真实棋力; 降级时标出来,避免"Elo 3300"名不副实
                binding.opponentRating.text =
                    if (state.engineDowngraded) "Elo ${state.engineElo} (已降级)"
                    else "Elo ${state.engineElo}"
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
                updateBoard(state)

                // 游戏结束(状态会多次发射,对话框只弹一次)
                if (state.isGameOver) {
                    showGameOverDialog(state.gameOverReason)
                } else {
                    gameOverDialog?.dismiss()
                    gameOverDialog = null
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

    private fun updateBoard(state: GameViewModel.GameState) {
        val position = viewModel.currentPosition
        val history = viewModel.moveHistory

        // 只有"恰好新增一步"才播动画; 悔棋、开新局、跳步都直接刷新
        val advancedByOne = history.size == renderedMoveCount + 1
        val animateMove = if (advancedByOne) history.lastOrNull() else null
        binding.boardView.setPosition(position, animateMove)

        // 检测将军
        val inCheck = Rules.isInCheck(position, position.sideToMove)
        binding.boardView.setCheckedKing(
            if (inCheck) findKing(position, position.sideToMove) else -1
        )

        // 上一步走法
        binding.boardView.setLastMove(history.lastOrNull())

        // 落子反馈. 只在真正新增一步时触发,避免状态重复发射时反复响
        if (advancedByOne) {
            if (inCheck) feedback.onCheck() else feedback.onMove(state.lastMoveCaptured)
        }
        renderedMoveCount = history.size
    }

    private fun findKing(position: com.zhongguoxiangqi.game.Position, color: Int): Int {
        val kingCode = if (color == Color.RED) com.zhongguoxiangqi.game.Piece.R_KING.code
        else com.zhongguoxiangqi.game.Piece.B_KING.code
        for (sq in 0 until com.zhongguoxiangqi.game.Square.COUNT) {
            if (position.pieceAt(sq) == kingCode) return sq
        }
        return -1
    }

    private fun moveToChinese(move: Move): String =
        Move.toChineseString(move, viewModel.currentPosition)

    private fun showGameOverDialog(reason: String) {
        if (gameOverDialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return
        val state = viewModel.state.value
        val message = buildString {
            append("对局结束\n")
            append("原因: $reason\n")
            append("评分变化: ${if (state.ratingDelta >= 0) "+" else ""}${state.ratingDelta}\n")
            append("当前评分: ${state.playerRating}  ${state.playerRank}")
        }
        gameOverDialog = AlertDialog.Builder(this)
            .setTitle("对局结束")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_new_game) { _, _ ->
                gameOverDialog = null
                viewModel.startNewGame(lastDifficulty, lastPlayerSide)
            }
            .setNegativeButton(R.string.action_back) { _, _ ->
                gameOverDialog = null
                finish()
            }
            .show()
    }

    override fun onDestroy() {
        // 界面销毁时必须关掉对话框,否则会泄漏 Activity
        gameOverDialog?.dismiss()
        gameOverDialog = null
        feedback.release()
        super.onDestroy()
        // 引擎在 VM.onCleared 中关闭
    }

    companion object {
        const val EXTRA_DIFFICULTY = "difficulty"
        const val EXTRA_PLAYER_SIDE = "player_side"
    }
}