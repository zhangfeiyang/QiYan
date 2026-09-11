package com.zhongguoxiangqi.ui.puzzle

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityPuzzleBinding
import com.zhongguoxiangqi.game.Puzzles
import com.zhongguoxiangqi.ui.board.BoardView
import com.zhongguoxiangqi.ui.common.Insets
import kotlinx.coroutines.launch

/**
 * 残局破解对局页.
 *
 * 顶部: 关卡名 + 步数/悔棋余量; 中间: 棋盘; 底部: 悔棋 / 重新开始。
 * 玩家(红)先行, 引擎执黑最佳防守; 将死黑方即破解成功。
 */
class PuzzleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PUZZLE_ID = "puzzle_id"
    }

    private lateinit var binding: ActivityPuzzleBinding
    private val viewModel: PuzzleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPuzzleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.bottomBar, extraBottomDp = 8f)

        val puzzleId = intent.getIntExtra(EXTRA_PUZZLE_ID, 1)
        val puzzle = Puzzles.byId(puzzleId) ?: Puzzles.all.first()

        binding.boardView.editMode = false
        binding.boardView.setPosition(viewModel.currentPosition)
        binding.boardView.onMoveListener = { move ->
            if (!viewModel.onPlayerMove(move)) {
                Toast.makeText(this, R.string.game_illegal_move, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.loadPuzzle(puzzle.id)

        setupControls()
        observe()
    }

    private fun setupControls() {
        binding.btnUndo.setOnClickListener {
            if (!viewModel.undo()) {
                Toast.makeText(this, R.string.puzzle_undo_exhausted, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnRetry.setOnClickListener {
            val s = viewModel.state.value
            if (s.finished) {
                viewModel.reset()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.puzzle_retry)
                    .setMessage(getString(R.string.puzzle_retry_confirm, s.attempts))
                    .setPositiveButton(R.string.dialog_yes) { _, _ -> viewModel.reset() }
                    .setNegativeButton(R.string.dialog_no, null)
                    .show()
            }
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.state.collect { s ->
                val puzzle = s.puzzle ?: return@collect
                binding.puzzleTitle.text =
                    getString(R.string.puzzle_stage_name, puzzle.id, getString(puzzle.nameRes))
                binding.puzzleMeta.text = getString(
                    R.string.puzzle_meta_format,
                    getString(R.string.puzzle_red_to_move, puzzle.maxMoves),
                    s.playerMoves, puzzle.maxMoves, s.undosLeft, s.attempts,
                )
                binding.puzzleDeviated.visibility =
                    if (s.deviated && !s.finished) View.VISIBLE else View.GONE
                binding.thinking.visibility = if (s.thinking) View.VISIBLE else View.GONE

                binding.boardView.setPosition(viewModel.currentPosition)
            }
        }

        viewModel.toastEvent.observe(this) { res ->
            if (res != null) {
                Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
                viewModel.toastEvent.value = null
            }
        }

        viewModel.finishedEvent.observe(this) { won ->
            if (won == null) return@observe
            showResultDialog(won)
        }
    }

    private fun showResultDialog(won: Boolean) {
        val puzzle = viewModel.state.value.puzzle ?: return
        val next = Puzzles.next(puzzle.id)
        val builder = AlertDialog.Builder(this)
            .setTitle(
                getString(
                    if (won) R.string.puzzle_solved_title else R.string.puzzle_fail_title
                ) + " " + getString(R.string.campaign_level_format, puzzle.id)
            )
            .setMessage(
                getString(
                    if (won) R.string.puzzle_solved_body else R.string.puzzle_fail_moves
                )
            )
            .setCancelable(false)
        if (won && next != null) {
            builder.setPositiveButton(R.string.puzzle_next_puzzle) { _, _ ->
                viewModel.loadPuzzle(next.id)
            }
        } else {
            builder.setPositiveButton(R.string.puzzle_retry) { _, _ ->
                viewModel.reset()
            }
        }
        builder.setNegativeButton(R.string.action_back) { _, _ -> finish() }
        builder.show()
    }
}
