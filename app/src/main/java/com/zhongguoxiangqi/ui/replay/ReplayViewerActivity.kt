package com.zhongguoxiangqi.ui.replay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.data.AppDatabase
import com.zhongguoxiangqi.databinding.ActivityReplayViewerBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.UciCoords
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对局回放界面.
 *
 * 显示棋盘 + 走法控制(前进/后退/自动播放).
 * 玩家可逐步查看对局进展.
 */
class ReplayViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplayViewerBinding
    private var initialFen = ""
    private var moves: List<String> = emptyList()
    private var currentMoveIndex = 0  // 已走的步数
    private var recordId: Long = 0
    private var playerSide = Color.RED

    private val replayPosition = Position()
    /** 已实际落到棋盘上的着法,与 currentMoveIndex 保持一致 */
    private val appliedMoves = mutableListOf<Move>()
    private val handler = Handler(Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null
    private var isAutoPlaying = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplayViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        recordId = intent.getLongExtra(EXTRA_GAME_ID, 0L)
        loadRecord()

        binding.btnFirst.setOnClickListener { gotoStep(0) }
        binding.btnPrev.setOnClickListener { gotoStep(currentMoveIndex - 1) }
        binding.btnNext.setOnClickListener { gotoStep(currentMoveIndex + 1) }
        binding.btnLast.setOnClickListener { gotoStep(moves.size) }
        binding.btnAuto.setOnClickListener { toggleAutoPlay() }
    }

    private fun loadRecord() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ReplayViewerActivity).gameRecordDao()
            val record = dao.getById(recordId)
            if (record == null) {
                Toast.makeText(this@ReplayViewerActivity, "对局记录不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            initialFen = record.initialFen
            moves = record.moves
            playerSide = record.playerSide
            val resultText = when (record.result) {
                1 -> "胜"
                -1 -> "负"
                else -> "和"
            }
            binding.titleText.text = "${dateFormat.format(Date(record.startTime))}  $resultText"
            binding.playerInfo.text = "Lv.${record.difficulty}  Elo ${record.playerRating}  ${record.moves.size}手"

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
                Toast.makeText(this, "棋谱第${currentMoveIndex + 1}手无法还原", Toast.LENGTH_SHORT).show()
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

    private fun updateMoveInfo() {
        binding.moveInfo.text = getString(R.string.replay_move_number, currentMoveIndex)
        binding.btnPrev.isEnabled = currentMoveIndex > 0
        binding.btnNext.isEnabled = currentMoveIndex < moves.size
        binding.btnFirst.isEnabled = currentMoveIndex > 0
        binding.btnLast.isEnabled = currentMoveIndex < moves.size
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