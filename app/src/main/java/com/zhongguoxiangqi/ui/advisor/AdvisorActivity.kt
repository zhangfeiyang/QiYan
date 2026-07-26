package com.zhongguoxiangqi.ui.advisor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ai.BoardContext
import com.zhongguoxiangqi.databinding.ActivityAdvisorBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Position
import kotlinx.coroutines.launch

/**
 * AI 分析聊天界面.
 *
 * 启动方式:
 * 1. **对弈中**: [Companion.launchFromGame] —— 传 (initialFen, currentPosition,
 *    moveHistory, playerSide, difficulty, engineElo, "对弈第 X 手 · 轮到 Y").
 * 2. **复盘中**: [Companion.launchFromReplay] —— 传 (initialFen, currentPosition,
 *    moveHistory, playerSide, difficulty, engineElo, "复盘第 X 手").
 */
class AdvisorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvisorBinding
    private val viewModel: AdvisorViewModel by viewModels()

    private val adapter = MessageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvisorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setOnMenuItemClickListener { onToolbarMenu(it) }

        binding.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messages.adapter = adapter

        binding.btnSend.setOnClickListener { sendCurrentInput() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput(); true
            } else false
        }
        // 输入框空/忙时禁用发送按钮
        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.btnSend.isEnabled = !s.isNullOrBlank() && !viewModel.busy.value
            }
        }
        binding.input.addTextChangedListener(textWatcher)

        // 启动时塞入 context
        val ctx = intent.getStringExtra(EXTRA_CONTEXT) ?: ""
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: ""
        viewModel.attach(ctx)
        binding.contextSummary.text = summary
        binding.contextSummary.visibility = if (summary.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        // 流式收集
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { msgs ->
                        adapter.submit(msgs)
                        if (msgs.isNotEmpty()) binding.messages.scrollToPosition(msgs.size - 1)
                    }
                }
                launch {
                    viewModel.busy.collect { busy ->
                        binding.btnSend.isEnabled =
                            !binding.input.text.isNullOrBlank() && !busy
                    }
                }
            }
        }
    }

    private fun onToolbarMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear -> { viewModel.clear(); true }
        else -> false
    }

    private fun sendCurrentInput() {
        val text = binding.input.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return
        binding.input.setText("")
        // 收键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.input.windowToken, 0)
        viewModel.send(text)
    }

    companion object {
        private const val EXTRA_INITIAL_FEN = "initialFen"
        private const val EXTRA_MOVES_UCI = "movesUci"
        private const val EXTRA_SIDE = "side"
        private const val EXTRA_DIFFICULTY = "difficulty"
        private const val EXTRA_ELO = "elo"
        private const val EXTRA_CONTEXT = "context"
        private const val EXTRA_SUMMARY = "summary"

        /**
         * 从对弈界面启动. 上下文是当前局面 + 全走法.
         */
        fun launchFromGame(
            activity: AppCompatActivity,
            initialFen: String,
            currentPosition: Position,
            moveHistory: List<Move>,
            playerSide: Int,
            difficulty: Int,
            engineElo: Int,
        ) {
            val ctx = BoardContext.build(
                initialFen = initialFen,
                currentPosition = currentPosition,
                moveHistory = moveHistory,
                playerSide = playerSide,
                difficulty = difficulty,
                engineElo = engineElo,
            )
            val summary = activity.getString(
                R.string.advisor_context_game,
                moveHistory.size,
                if (currentPosition.sideToMove == Color.RED) "红" else "黑"
            )
            activity.startActivity(Intent(activity, AdvisorActivity::class.java).apply {
                putExtra(EXTRA_CONTEXT, ctx)
                putExtra(EXTRA_SUMMARY, summary)
            })
        }

        /**
         * 从复盘界面启动. 上下文是当前复盘到的局面 + 全走法 (包含复盘到的位置).
         */
        fun launchFromReplay(
            activity: AppCompatActivity,
            initialFen: String,
            currentPosition: Position,
            moveHistory: List<Move>,
            playerSide: Int,
            difficulty: Int,
            engineElo: Int,
        ) {
            val ctx = BoardContext.build(
                initialFen = initialFen,
                currentPosition = currentPosition,
                moveHistory = moveHistory,
                playerSide = playerSide,
                difficulty = difficulty,
                engineElo = engineElo,
            )
            val summary = activity.getString(R.string.advisor_context_replay, moveHistory.size)
            activity.startActivity(Intent(activity, AdvisorActivity::class.java).apply {
                putExtra(EXTRA_CONTEXT, ctx)
                putExtra(EXTRA_SUMMARY, summary)
            })
        }
    }
}
