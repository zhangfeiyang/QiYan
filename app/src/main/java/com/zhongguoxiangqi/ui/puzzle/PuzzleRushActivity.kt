package com.zhongguoxiangqi.ui.puzzle

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityPuzzleRushBinding
import com.zhongguoxiangqi.databinding.DialogPuzzleRushResultBinding
import com.zhongguoxiangqi.ui.common.Insets
import com.zhongguoxiangqi.ui.game.GameFeedback
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 残局速拼 (Puzzle Rush) 界面.
 *
 * 3分钟极速冲榜模式，连续解答、动态难度阶梯、3条命、连击得分与称号战报.
 */
class PuzzleRushActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPuzzleRushBinding
    private val viewModel: PuzzleRushViewModel by viewModels()
    private lateinit var feedback: GameFeedback
    private var resultDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPuzzleRushBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.bottomBar, extraBottomDp = 8f)
        binding.toolbar.setNavigationOnClickListener { confirmExit() }
        // 返回键同样先确认: 速拼进行中误触返回就丢掉整局成绩.
        // 走 OnBackPressedDispatcher 而不是覆写已废弃的 onBackPressed (lint: MissingSuperCall).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        feedback = GameFeedback(this)
        binding.boardView.editMode = false
        binding.boardView.onMoveListener = { move ->
            val isCapture = viewModel.currentPosition.pieceAt(move.to) != 0
            if (viewModel.onPlayerMove(move)) {
                feedback.onMove(captured = isCapture)
            }
        }

        binding.btnSkip.setOnClickListener {
            viewModel.skipCurrentPuzzle()
        }
        binding.btnRushStart.setOnClickListener {
            viewModel.startRush()
        }

        observeState()
        observeEvents()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    binding.rushScoreText.text = getString(R.string.puzzle_rush_score_line, state.score)
                    binding.rushBestBadge.text = getString(R.string.puzzle_rush_best_badge, state.highScore)
                    binding.rushIntro.visibility = if (state.isStarted) View.GONE else View.VISIBLE
                    binding.rushIntroBest.text = if (state.highScore > 0)
                        getString(R.string.puzzle_rush_intro_best, state.highScore)
                    else getString(R.string.puzzle_rush_intro_first)
                    binding.btnSkip.isEnabled = state.isStarted && !state.isFinished

                    // 倒计时显示 (mm:ss)
                    val m = state.secondsLeft / 60
                    val s = state.secondsLeft % 60
                    binding.rushTimerText.text = "%02d:%02d".format(m, s)
                    if (state.secondsLeft <= 30) {
                        binding.rushTimerText.setTextColor(0xFFE53935.toInt()) // 紧迫红
                    } else {
                        binding.rushTimerText.setTextColor(getColor(R.color.text_primary))
                    }

                    // 生命值显示
                    val hearts = buildString {
                        for (i in 1..3) {
                            if (i <= state.lives) append("❤️") else append("🖤")
                        }
                    }
                    binding.rushLivesText.text = hearts

                    // 连击显示
                    if (state.combo >= 2) {
                        binding.rushComboText.visibility = View.VISIBLE
                        binding.rushComboText.text = getString(R.string.puzzle_rush_combo, state.combo)
                    } else {
                        binding.rushComboText.visibility = View.GONE
                    }

                    val p = state.currentPuzzle
                    if (p != null) {
                        // 速拼里卷名和题号没有意义, 还把"一步杀"说了两遍; 只留走棋方和题名
                        binding.puzzlePrompt.text = getString(R.string.puzzle_rush_prompt_short, getString(p.nameRes))
                        binding.boardView.setPosition(viewModel.currentPosition)
                    }

                    if (state.isFinished && resultDialog == null) {
                        showResultDialog(state)
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is PuzzleRushViewModel.RushEvent.Solved -> {
                            flashFeedback(isCorrect = true, combo = event.combo)
                        }
                        is PuzzleRushViewModel.RushEvent.Strike -> {
                            flashFeedback(isCorrect = false, combo = 0)
                        }
                        is PuzzleRushViewModel.RushEvent.MoveAccepted -> {
                            binding.boardView.setPosition(viewModel.currentPosition)
                        }
                        is PuzzleRushViewModel.RushEvent.Finished -> {
                            // Handled by state
                        }
                    }
                }
            }
        }
    }

    private fun flashFeedback(isCorrect: Boolean, combo: Int) {
        val color = if (isCorrect) 0x444CAF50.toInt() else 0x55E53935.toInt()
        binding.feedbackFlash.setBackgroundColor(color)
        binding.feedbackFlash.visibility = View.VISIBLE

        if (isCorrect) {
            val tactic = com.zhongguoxiangqi.game.Tactics.detect(viewModel.currentPosition, com.zhongguoxiangqi.game.Color.RED)
            if (tactic != null) {
                feedback.onVictory(tactic)
                binding.comboPopup.text = getString(R.string.puzzle_rush_mate_line, tactic) +
                    if (combo >= 2) " " + getString(R.string.puzzle_rush_combo, combo) else ""
            } else {
                feedback.onCheckmate()
                binding.comboPopup.text = if (combo >= 2) {
                    getString(R.string.puzzle_rush_combo, combo)
                } else {
                    getString(R.string.puzzle_rush_solved_flash)
                }
            }
            binding.comboPopup.visibility = View.VISIBLE
            val scaleX = ObjectAnimator.ofFloat(binding.comboPopup, "scaleX", 0.6f, 1.2f, 1.0f)
            val scaleY = ObjectAnimator.ofFloat(binding.comboPopup, "scaleY", 0.6f, 1.2f, 1.0f)
            val alpha = ObjectAnimator.ofFloat(binding.comboPopup, "alpha", 1f, 0f)
            alpha.startDelay = 250
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 450
                start()
            }
        } else {
            feedback.onDefeat() // 触发出错提示音与震动
        }

        binding.feedbackFlash.postDelayed({
            binding.feedbackFlash.visibility = View.GONE
            binding.comboPopup.visibility = View.GONE
        }, 320)
    }

    private fun showResultDialog(state: PuzzleRushViewModel.UiState) {
        val dialogBinding = DialogPuzzleRushResultBinding.inflate(layoutInflater)
        dialogBinding.resultScore.text = "${state.score}"
        dialogBinding.resultReason.text = state.finishReason
        dialogBinding.resultHonor.text = getString(R.string.puzzle_rush_honor_line, state.honorTitle)

        if (state.isNewRecord) {
            dialogBinding.resultHighScoreNote.text = getString(R.string.puzzle_rush_record_line, state.score)
            dialogBinding.resultHighScoreNote.setTextColor(getColor(R.color.action))
        } else {
            dialogBinding.resultHighScoreNote.text = getString(R.string.puzzle_rush_previous_best, state.highScore)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        // 结算卡自带圆角纸底 (bg_result_dialog); 弹窗窗口底要透明,
        // 否则四个圆角外会露出一圈窗口色 (夜间是深色的方角).
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        dialogBinding.btnRetry.setOnClickListener {
            dialog.dismiss()
            resultDialog = null
            viewModel.startRush()
        }

        dialogBinding.btnShare.setOnClickListener {
            shareRushReport(state)
        }

        dialogBinding.btnExit.setOnClickListener {
            dialog.dismiss()
            resultDialog = null
            finish()
        }

        resultDialog = dialog
        dialog.show()
    }

    private fun shareRushReport(state: PuzzleRushViewModel.UiState) {
        val text = getString(
            R.string.puzzle_rush_share_body,
            getString(R.string.puzzle_rush_report_title),
            state.score,
            state.maxCombo,
            getString(R.string.puzzle_rush_honor_line, state.honorTitle),
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.puzzle_rush_report_title))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.puzzle_rush_share)))
    }

    private fun confirmExit() {
        if (viewModel.state.value.isFinished || !viewModel.state.value.isStarted) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.puzzle_rush_title)
            .setMessage(R.string.puzzle_rush_leave)
            .setPositiveButton(R.string.puzzle_rush_leave_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        resultDialog?.dismiss()
        resultDialog = null
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, PuzzleRushActivity::class.java))
        }
    }
}
