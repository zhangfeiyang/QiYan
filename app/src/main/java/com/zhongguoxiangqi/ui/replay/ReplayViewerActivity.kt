package com.zhongguoxiangqi.ui.replay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.AppDatabase
import com.zhongguoxiangqi.data.GameRecord
import com.zhongguoxiangqi.databinding.ActivityReplayViewerBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.OpeningNames
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.ui.common.Insets
import com.zhongguoxiangqi.ui.common.Notation
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 专业级对局复盘界面.
 *
 * 集成：
 * 1. 局势胜率折线图 (AdvantageGraphView) + 触控手势随选寻步
 * 2. 走法六级质量评估 (💎妙着/🟢正着/🟡疑问/🟠失误/🔴漏着/📖定式)
 * 3. 统计计数指示栏 (妙着/正着/疑问/失误/漏着)
 * 4. 中文记谱与开局定式名牌
 * 5. PGN 标准格式导出、FEN 复制与战报社交分享
 */
class ReplayViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplayViewerBinding
    private var initialFen = ""
    private var moves: List<String> = emptyList()
    private var currentMoveIndex = 0  // 已走的步数
    private var recordId: Long = 0
    private var recordDifficulty = 5
    private var recordEngineElo = 1700
    private var playerSide = Color.RED

    private var loadedRecord: GameRecord? = null
    private var analysisResult: ReplayAnalysisResult? = null

    private val replayPosition = Position()
    /** 已实际落到棋盘上的着法,与 currentMoveIndex 保持一致 */
    private val appliedMoves = mutableListOf<Move>()
    private val handler = Handler(Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null
    private var isAutoPlaying = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    // 变着推演
    private var isVariationMode = false
    private var variationOriginStep = 0
    private val variationMoves = mutableListOf<Move>()
    private lateinit var feedback: com.zhongguoxiangqi.ui.game.GameFeedback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplayViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.controlRow, extraBottomDp = 8f)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            if (isVariationMode) {
                exitVariationMode()
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isVariationMode) {
                    exitVariationMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        feedback = com.zhongguoxiangqi.ui.game.GameFeedback(this)
        recordId = intent.getLongExtra(EXTRA_GAME_ID, 0L)
        loadRecord()

        binding.btnFirst.setOnClickListener { gotoStep(0) }
        binding.btnPrev.setOnClickListener { gotoStep(currentMoveIndex - 1) }
        binding.btnNext.setOnClickListener { gotoStep(currentMoveIndex + 1) }
        binding.btnLast.setOnClickListener { gotoStep(moves.size) }
        binding.btnAuto.setOnClickListener { toggleAutoPlay() }
        binding.btnAi.setOnClickListener { launchAdvisor() }

        binding.btnTryVariation.setOnClickListener { enterVariationMode() }
        binding.btnVariationUndo.setOnClickListener { undoVariationMove() }
        binding.btnVariationExit.setOnClickListener { exitVariationMode() }

        binding.advantageGraph.onStepSelectedListener = { targetStep ->
            gotoStep(targetStep)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.replay_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val record = loadedRecord ?: return super.onOptionsItemSelected(item)
        val chineseMoves = analysisResult?.evaluations?.map { it.chineseNotation } ?: emptyList()
        val detectedOpening = analysisResult?.evaluations?.lastOrNull { it.openingName != null }?.openingName

        return when (item.itemId) {
            R.id.action_share -> {
                val reportText = PgnExporter.exportShareText(record, chineseMoves, detectedOpening)
                shareText(getString(R.string.replay_share_report), reportText)
                true
            }
            R.id.action_export_pgn -> {
                val pgnText = PgnExporter.exportPgn(record, chineseMoves, detectedOpening)
                copyToClipboard("PGN", pgnText)
                shareText(getString(R.string.replay_export_pgn), pgnText)
                true
            }
            R.id.action_copy_fen -> {
                val fen = replayPosition.toFen()
                copyToClipboard("FEN", fen)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.replay_copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, title))
    }

    private fun launchAdvisor() {
        com.zhongguoxiangqi.ui.advisor.AdvisorActivity.launchFromReplay(
            this,
            initialFen = initialFen,
            currentPosition = replayPosition,
            moveHistory = appliedMoves.toList(),
            playerSide = playerSide,
            difficulty = recordDifficulty,
            engineElo = recordEngineElo,
        )
    }

    private fun loadRecord() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ReplayViewerActivity).gameRecordDao()
            val record = dao.getById(recordId)
            if (record == null) {
                Toast.makeText(this@ReplayViewerActivity, getString(R.string.replay_record_missing), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            loadedRecord = record
            initialFen = record.initialFen
            moves = record.moves
            playerSide = record.playerSide
            recordDifficulty = record.difficulty
            recordEngineElo = record.engineElo

            val resultText = when (record.result) {
                1 -> getString(R.string.result_win_short)
                -1 -> getString(R.string.result_loss_short)
                else -> getString(R.string.result_draw_short)
            }
            binding.titleText.text = "${dateFormat.format(Date(record.startTime))}  $resultText"
            val rank = getString(com.zhongguoxiangqi.rating.UserRank.fromRating(record.playerRating).nameRes)
            binding.playerInfo.text = getString(
                R.string.replay_player_info, record.difficulty, rank, record.moves.size
            )

            // 运行快速复盘评估引擎 (在后台线程执行以避免 ANR)
            val analysis = withContext(Dispatchers.Default) {
                ReplayEvaluator.analyze(initialFen, moves)
            }
            analysisResult = analysis

            // 更新走法品质统计
            binding.statBrilliant.text = "💎 ${analysis.brilliantCount}"
            binding.statBest.text = "🟢 ${analysis.bestCount}"
            binding.statInaccuracy.text = "🟡 ${analysis.inaccuracyCount}"
            binding.statMistake.text = "🟠 ${analysis.mistakeCount}"
            binding.statBlunder.text = "🔴 ${analysis.blunderCount}"

            // 初始化折线图
            binding.advantageGraph.setData(analysis.winRates, analysis.evaluations, 0)

            resetToStart()
            updateBoard()
            updateMoveInfo()
        }
    }

    /** 回到起始局面(优先用记录中的 FEN,缺失或损坏时退回标准开局) */
    private fun resetToStart() {
        if (initialFen.isBlank() || !replayPosition.setFromFen(initialFen)) {
            replayPosition.setupStartPosition()
        }
        currentMoveIndex = 0
        appliedMoves.clear()
    }

    private fun gotoStep(step: Int) {
        if (isVariationMode) {
            exitVariationMode()
        }
        val target = step.coerceIn(0, moves.size)
        if (target == currentMoveIndex) return
        val before = currentMoveIndex

        // 撤销到目标: 只回退真正走过的着法,索引才不会和棋盘脱节
        while (currentMoveIndex > target) {
            replayPosition.undoMove()
            appliedMoves.removeAt(appliedMoves.size - 1)
            currentMoveIndex--
        }
        // 应用到目标
        while (currentMoveIndex < target) {
            val move = parseMove(moves[currentMoveIndex])
            if (move == null || !replayPosition.isLegalMove(move)) {
                // 棋谱损坏: 停在能走到的最后一步,而不是继续错位地走下去
                Toast.makeText(this, getString(R.string.replay_move_undo_failed, currentMoveIndex + 1), Toast.LENGTH_SHORT).show()
                stopAutoPlay()
                break
            }
            replayPosition.doMove(move)
            appliedMoves.add(move)
            currentMoveIndex++
        }
        // 单步前进才播动画; 跳到首/末或后退直接刷新
        updateBoard(animate = target == before + 1)
        updateMoveInfo()
    }

    private fun parseMove(uci: String): Move? = UciCoords.parse(uci)

    private fun updateBoard(animate: Boolean = false) {
        binding.boardView.setPosition(
            replayPosition,
            if (animate) appliedMoves.lastOrNull() else null
        )
        binding.boardView.setLastMove(appliedMoves.lastOrNull())
    }

    /** 当前步的记谱按界面语言来。评估结果里留的是中文记谱，只给战报导出用。 */
    private fun notationForCurrentMove(fallback: String): String {
        val move = appliedMoves.lastOrNull() ?: return fallback
        if (replayPosition.undoMove() == null) return fallback
        val text = Notation.format(this, move, replayPosition)
        replayPosition.doMove(move)
        return text
    }

    private fun updateMoveInfo() {
        // 同步走势图光标
        binding.advantageGraph.setCurrentStep(currentMoveIndex)

        val analysis = analysisResult
        if (currentMoveIndex == 0) {
            binding.moveInfo.text = getString(R.string.replay_start_position)
            binding.moveQualityChip.visibility = View.GONE
            binding.moveOpeningChip.visibility = View.GONE
            val initialWr = analysis?.winRates?.getOrNull(0) ?: 50f
            binding.moveWinrateText.text = getString(R.string.replay_red_winrate, initialWr.roundToInt())
        } else {
            val eval = analysis?.evaluations?.getOrNull(currentMoveIndex - 1)
            if (eval != null) {
                val notation = notationForCurrentMove(eval.chineseNotation)
                binding.moveInfo.text = getString(R.string.replay_move_line, currentMoveIndex, notation)
                binding.moveQualityChip.text = "${eval.quality.symbol} ${getString(eval.quality.labelRes)}"
                binding.moveQualityChip.setTextColor(eval.quality.colorHex)
                binding.moveQualityChip.visibility = View.VISIBLE

                if (!eval.openingName.isNullOrBlank()) {
                    val opening = OpeningNames.label(this, eval.openingName)
                    binding.moveOpeningChip.text = getString(R.string.opening_chip, opening)
                    binding.moveOpeningChip.visibility = View.VISIBLE
                } else {
                    binding.moveOpeningChip.visibility = View.GONE
                }

                binding.moveWinrateText.text = getString(R.string.replay_red_winrate, eval.redWinRate.roundToInt())
            } else {
                binding.moveInfo.text = getString(R.string.replay_move_number, currentMoveIndex)
                binding.moveQualityChip.visibility = View.GONE
                binding.moveOpeningChip.visibility = View.GONE
                binding.moveWinrateText.text = ""
            }
        }

        binding.btnPrev.isEnabled = currentMoveIndex > 0
        binding.btnNext.isEnabled = currentMoveIndex < moves.size
        binding.btnFirst.isEnabled = currentMoveIndex > 0
        binding.btnLast.isEnabled = currentMoveIndex < moves.size
    }

    private fun enterVariationMode() {
        if (isVariationMode) return
        stopAutoPlay()
        isVariationMode = true
        variationOriginStep = currentMoveIndex
        variationMoves.clear()

        binding.moveDetailsContainer.visibility = View.GONE
        binding.variationPanel.visibility = View.VISIBLE
        binding.controlRow.alpha = 0.35f
        setControlsEnabled(false)

        binding.boardView.editMode = false
        binding.boardView.onMoveListener = { move ->
            if (replayPosition.isLegalMove(move)) {
                val isCapture = replayPosition.pieceAt(move.to) != 0
                val notation = Notation.format(this, move, replayPosition)
                replayPosition.doMove(move)
                variationMoves.add(move)
                feedback.onMove(captured = isCapture)
                updateBoard(animate = true)
                updateVariationView(notation)
            } else {
                Toast.makeText(this, R.string.game_illegal_move, Toast.LENGTH_SHORT).show()
            }
        }
        updateVariationView(null)
    }

    private fun undoVariationMove() {
        if (!isVariationMode || variationMoves.isEmpty()) return
        replayPosition.undoMove()
        variationMoves.removeAt(variationMoves.size - 1)
        updateBoard(animate = false)
        val lastNotation = if (variationMoves.isNotEmpty()) {
            getString(R.string.replay_variation_step, variationMoves.size)
        } else null
        updateVariationView(lastNotation)
    }

    private fun exitVariationMode() {
        if (!isVariationMode) return
        isVariationMode = false
        binding.boardView.onMoveListener = null
        binding.moveDetailsContainer.visibility = View.VISIBLE
        binding.variationPanel.visibility = View.GONE
        binding.controlRow.alpha = 1.0f
        setControlsEnabled(true)

        // 撤销所有分支走子，恢复到推演发起点
        while (variationMoves.isNotEmpty()) {
            replayPosition.undoMove()
            variationMoves.removeAt(variationMoves.size - 1)
        }
        updateBoard(animate = false)
        updateMoveInfo()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnFirst.isEnabled = enabled && currentMoveIndex > 0
        binding.btnPrev.isEnabled = enabled && currentMoveIndex > 0
        binding.btnAuto.isEnabled = enabled
        binding.btnNext.isEnabled = enabled && currentMoveIndex < moves.size
        binding.btnLast.isEnabled = enabled && currentMoveIndex < moves.size
        binding.btnAi.isEnabled = enabled
    }

    private fun updateVariationView(lastMoveNotation: String?) {
        val count = variationMoves.size
        val (_, wr) = ReplayEvaluator.evaluatePosition(replayPosition)
        val rate = wr.roundToInt()
        binding.variationInfoText.text = if (lastMoveNotation != null) {
            getString(R.string.replay_variation_status_move, count, lastMoveNotation, rate)
        } else {
            getString(R.string.replay_variation_status, count, rate)
        }
        binding.btnVariationUndo.isEnabled = count > 0
    }


    private fun toggleAutoPlay() {
        if (isAutoPlaying) {
            stopAutoPlay()
            return
        }
        if (currentMoveIndex >= moves.size) return  // 已到末尾,无需播放
        isAutoPlaying = true
        binding.btnAuto.text = getString(R.string.replay_pause)
        val runnable = object : Runnable {
            override fun run() {
                if (!isAutoPlaying) return
                if (currentMoveIndex >= moves.size) {
                    stopAutoPlay()
                    return
                }
                gotoStep(currentMoveIndex + 1)
                if (isAutoPlaying) handler.postDelayed(this, AUTO_PLAY_INTERVAL_MS)
            }
        }
        autoPlayRunnable = runnable
        handler.post(runnable)
    }

    private fun stopAutoPlay() {
        autoPlayRunnable?.let { handler.removeCallbacks(it) }
        autoPlayRunnable = null
        isAutoPlaying = false
        binding.btnAuto.text = getString(R.string.replay_play)
    }

    override fun onPause() {
        super.onPause()
        // 界面不可见时继续自动播放没有意义,还会持有 handler 回调
        stopAutoPlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val EXTRA_GAME_ID = "game_id"
        private const val AUTO_PLAY_INTERVAL_MS = 1000L
    }
}