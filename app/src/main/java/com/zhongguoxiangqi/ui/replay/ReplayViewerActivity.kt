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
import com.zhongguoxiangqi.game.Square
import kotlinx.coroutines.launch

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
    private val handler = Handler(Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null
    private var isAutoPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplayViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

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
            binding.titleText.text = "${record.startTime}  ${if (record.result == 1) "胜" else if (record.result == -1) "负" else "和"}"
            binding.playerInfo.text = "Lv.${record.difficulty}  Elo ${record.playerRating}  ${record.moves.size}手"

            replayPosition.setupStartPosition()
            currentMoveIndex = 0
            updateBoard()
            updateMoveInfo()
        }
    }

    private fun gotoStep(step: Int) {
        val target = step.coerceIn(0, moves.size)
        if (target == currentMoveIndex) return

        // 撤销到目标
        while (currentMoveIndex > target) {
            replayPosition.undoMove()
            currentMoveIndex--
        }
        // 应用到目标
        while (currentMoveIndex < target) {
            val move = parseMove(replayPosition, moves[currentMoveIndex])
            if (move != null && replayPosition.isLegalMove(move)) {
                replayPosition.doMove(move)
            }
            currentMoveIndex++
        }
        updateBoard()
        updateMoveInfo()
    }

    private fun parseMove(pos: Position, uci: String): Move? {
        if (uci.length < 4) return null
        return try {
            val fromCol = uci[0] - 'a'
            val fromRow = uci[1].digitToInt()
            val toCol = uci[2] - 'a'
            val toRow = uci[3].digitToInt()
            Move.make(Square.make(9 - fromRow, fromCol), Square.make(9 - toRow, toCol))
        } catch (_: Exception) { null }
    }

    private fun updateBoard() {
        binding.boardView.setPosition(replayPosition.copy())
        binding.boardView.setLastMove(if (currentMoveIndex > 0) parseMove(replayPosition.copy().apply {
            repeat(currentMoveIndex - 1) { undoMove() }
        }, moves[currentMoveIndex - 1]) else null)
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
            autoPlayRunnable?.let { handler.removeCallbacks(it) }
            autoPlayRunnable = null
            isAutoPlaying = false
            binding.btnAuto.text = getString(R.string.replay_play)
        } else {
            isAutoPlaying = true
            binding.btnAuto.text = "暂停"
            autoPlayRunnable = object : Runnable {
                override fun run() {
                    if (!isAutoPlaying) return
                    if (currentMoveIndex >= moves.size) {
                        toggleAutoPlay()
                        return
                    }
                    gotoStep(currentMoveIndex + 1)
                    handler.postDelayed(this, 1000L)
                }
            }
            handler.post(autoPlayRunnable!!)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autoPlayRunnable?.let { handler.removeCallbacks(it) }
    }

    companion object {
        const val EXTRA_GAME_ID = "game_id"
    }
}