package com.zhongguoxiangqi.ui.puzzle

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityPuzzleBinding
import com.zhongguoxiangqi.data.ProgressStore
import com.zhongguoxiangqi.game.CampaignStars
import com.zhongguoxiangqi.game.HistoryCampaign
import com.zhongguoxiangqi.game.PuzzleCatalog
import com.zhongguoxiangqi.game.Puzzles
import com.zhongguoxiangqi.game.TacticNames
import com.zhongguoxiangqi.ui.board.BoardView
import com.zhongguoxiangqi.ui.common.Insets
import com.zhongguoxiangqi.ui.game.GameFeedback
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 残局破解对局页.
 *
 * 顶部: 关卡名 + 步数/悔棋余量; 中间: 棋盘; 底部: 悔棋 / 重新开始。
 * 玩家(红)先行, 引擎执黑最佳防守; 将死黑方即破解成功。
 */
class PuzzleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PUZZLE_ID = "puzzle_id"
        const val EXTRA_DAILY = "daily_puzzle"
        const val EXTRA_BATTLE = "battle_id"
    }

    private var battleId = 0
    private var clueUsed = false

    private lateinit var binding: ActivityPuzzleBinding
    private val viewModel: PuzzleViewModel by viewModels()
    private var resultDialog: AlertDialog? = null
    private lateinit var feedback: GameFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPuzzleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.bottomBar, extraBottomDp = 8f)

        val puzzleId = intent.getIntExtra(EXTRA_PUZZLE_ID, 1)
        battleId = intent.getIntExtra(EXTRA_BATTLE, 0)
        val puzzle = resolvePuzzle(puzzleId)

        feedback = GameFeedback(this)
        binding.boardView.editMode = false
        binding.boardView.setPosition(viewModel.currentPosition)
        binding.boardView.onMoveListener = { move ->
            val isCapture = viewModel.currentPosition.pieceAt(move.to) != 0
            if (viewModel.onPlayerMove(move)) {
                feedback.onMove(captured = isCapture)
            } else {
                Toast.makeText(this, R.string.game_illegal_move, Toast.LENGTH_SHORT).show()
            }
        }
        binding.boardView.onSelectHaptic = { feedback.onSelectPiece() }

        if (viewModel.state.value.puzzle == null) {
            if (intent.getBooleanExtra(EXTRA_DAILY, false)) viewModel.loadDailyPuzzle()
            else viewModel.loadPuzzle(puzzle)
        }

        setupControls()
        observe()
    }

    private fun setupControls() {
        val clue = layoutInflater.inflate(R.layout.view_puzzle_clue, binding.bottomBar, false)
        binding.bottomBar.addView(clue, 0)
        clue.setOnClickListener {
            val puzzle = viewModel.state.value.puzzle ?: return@setOnClickListener
            clueUsed = true
            AlertDialog.Builder(this)
                .setTitle(R.string.puzzle_clue)
                .setMessage(puzzle.hintOverride ?: getString(puzzle.hintRes))
                .setPositiveButton(R.string.dialog_ok, null).show()
        }
        binding.btnUndo.setOnClickListener {
            if (viewModel.undo()) {
                feedback.onUndo()
            } else {
                val res = if (viewModel.state.value.undosLeft <= 0) R.string.puzzle_undo_exhausted
                else R.string.puzzle_undo_unavailable
                Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
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
                binding.toolbar.setTitle(if (s.dailyDate != null) R.string.daily_puzzle_title else R.string.puzzle_title)
                val puzzleName = puzzleTitle(puzzle)
                binding.puzzleTitle.text = if (s.dailyDate != null) getString(
                    R.string.daily_puzzle_date,
                    s.dailyDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    puzzleName,
                ) else puzzleName
                binding.puzzleMeta.text = getString(
                    R.string.puzzle_meta_format,
                    getString(R.string.puzzle_red_to_move, puzzle.maxMoves),
                    s.playerMoves, puzzle.maxMoves, s.undosLeft, s.attempts,
                )
                binding.puzzleDeviated.visibility =
                    if (s.deviated && !s.finished) View.VISIBLE else View.GONE
                binding.thinking.visibility = if (s.thinking) View.VISIBLE else View.GONE
                binding.btnUndo.isEnabled = s.engineReady && !s.thinking && !s.finished && s.playerMoves > 0 && s.undosLeft > 0

                binding.boardView.setPosition(viewModel.currentPosition)
            }
        }

        viewModel.toastEvent.observe(this) { res ->
            if (res != null) {
                // 走错一步 (偏离正解) 配一声短促闷音, 和引擎报错的 Toast 区分开
                if (res == R.string.puzzle_deviated) feedback.onPuzzleWrong()
                Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
                viewModel.toastEvent.value = null
            }
        }

        viewModel.finishedEvent.observe(this) { won ->
            if (won != null) {
                viewModel.finishedEvent.value = null
                val puzzle = viewModel.state.value.puzzle
                val detectedTactic = if (won) com.zhongguoxiangqi.game.Tactics.detect(viewModel.currentPosition, com.zhongguoxiangqi.game.Color.RED) else null
                val tactic = detectedTactic ?: puzzle?.tactic
                // 残局用轻快的「妙手！」/「再来一次。」, 不搬对局那套大凯旋凯歌 ——
                // 残局是一题一题刷的, 每次都奏凯歌太重
                if (won) feedback.onPuzzleSolved() else feedback.onPuzzleFailed()
                showResultDialog(won, tactic)
            }
        }
    }

    private fun showResultDialog(won: Boolean, tactic: String? = null) {
        if (resultDialog?.isShowing == true || isFinishing || isDestroyed) return
        val puzzle = viewModel.state.value.puzzle ?: return
        val dailyDate = viewModel.state.value.dailyDate
        if (won && battleId != 0) {
            val undosUsed = PuzzleViewModel.UNDO_LIMIT - viewModel.state.value.undosLeft
            val stars = CampaignStars.award(true, if (clueUsed) 1 else 0, undosUsed)
            ProgressStore(this).recordChronicleStars(battleId, stars)
        }
        val next = if (puzzle.id >= PuzzleCatalog.FIRST_ID) PuzzleCatalog.next(this, puzzle.id)
            else Puzzles.next(puzzle.id)
        val practice = viewModel.store.dailyPractice()
        val tacticLabel = if (won && !tactic.isNullOrBlank()) TacticNames.label(this, tactic) else null
        val message = when {
            tacticLabel != null -> getString(R.string.puzzle_solved_tactic_body, tacticLabel)
            won && dailyDate == LocalDate.now() -> getString(R.string.daily_puzzle_success, practice.completedDays, practice.currentStreak)
            won && dailyDate != null -> getString(R.string.daily_puzzle_expired)
            won -> getString(R.string.puzzle_solved_body)
            else -> getString(R.string.puzzle_fail_moves)
        }
        val dialogTitle = when {
            tacticLabel != null -> getString(R.string.puzzle_solved_tactic_title, tacticLabel)
            won -> getString(R.string.puzzle_solved_title) + " " + getString(R.string.campaign_level_format, puzzle.id)
            else -> getString(R.string.puzzle_fail_title) + " " + getString(R.string.campaign_level_format, puzzle.id)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setMessage(message)
            .setCancelable(false)
        if (won && dailyDate != null) {
            builder.setPositiveButton(R.string.daily_puzzle_more) { _, _ ->
                startActivity(Intent(this, PuzzleListActivity::class.java))
                finish()
            }
        } else if (won && next != null) {
            builder.setPositiveButton(R.string.puzzle_next_puzzle) { _, _ ->
                battleId = 0
                clueUsed = false
                viewModel.loadPuzzle(next.id)
            }
        } else {
            builder.setPositiveButton(R.string.puzzle_retry) { _, _ ->
                viewModel.reset()
            }
        }
        builder.setNegativeButton(R.string.action_back) { _, _ -> finish() }
        resultDialog = builder.show()
    }

    private fun resolvePuzzle(id: Int): com.zhongguoxiangqi.game.Puzzle {
        val battle = HistoryCampaign.battle(battleId)
        if (battle != null) {
            val fromBattle = HistoryCampaign.resolve(battle, PuzzleCatalog.all(this))
            if (fromBattle != null) {
                return fromBattle.copy(
                    titleOverride = getString(battle.nameRes),
                    hintOverride = getString(battle.briefRes),
                )
            }
        }
        return Puzzles.byId(id) ?: PuzzleCatalog.byId(this, id) ?: Puzzles.all.first()
    }

    private fun puzzleTitle(puzzle: com.zhongguoxiangqi.game.Puzzle): String {
        puzzle.titleOverride?.let { return it }
        if (puzzle.id >= PuzzleCatalog.FIRST_ID) {
            val index = puzzle.id - PuzzleCatalog.FIRST_ID + 1
            return getString(R.string.catalog_item_title, index, TacticNames.label(this, puzzle.tactic))
        }
        return getString(R.string.puzzle_stage_name, puzzle.id, getString(puzzle.nameRes))
    }

    override fun onDestroy() {
        resultDialog?.dismiss()
        resultDialog = null
        feedback.release()
        super.onDestroy()
    }
}
