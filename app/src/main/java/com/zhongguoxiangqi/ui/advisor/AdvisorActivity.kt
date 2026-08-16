package com.zhongguoxiangqi.ui.advisor

import com.zhongguoxiangqi.ui.common.Insets

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
import com.zhongguoxiangqi.game.UciCoords
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

    /** 上半屏展示用的局面, 由 EXTRA_FEN 还原 */
    private val boardPosition = Position()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvisorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.apply(binding.root, top = binding.toolbar, bottom = binding.inputBar, extraBottomDp = 4f)
        // 不设 setSupportActionBar: 一旦交给 ActionBar 管, XML 里 app:menu
        // 声明的菜单项就不会渲染到 Toolbar 上. 返回键这里是自己接的.
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

        // 建议问题: 点一下直接当成用户输入发出去, 省掉"想 + 打字"两步
        listOf(
            binding.suggestAnalyze,
            binding.suggestNext,
            binding.suggestReview,
        ).forEach { chip ->
            chip.setOnClickListener { viewModel.send(chip.text.toString()) }
        }

        // 启动时塞入 context
        val ctx = intent.getStringExtra(EXTRA_CONTEXT) ?: ""
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: ""
        viewModel.attach(ctx)
        binding.contextSummary.text = summary
        binding.contextSummary.visibility = if (summary.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        setupBoard()

        // 流式收集
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { msgs ->
                        adapter.submit(msgs)
                        // 空状态与消息列表互斥: 有第一条消息就永久让位
                        val hasMessages = msgs.isNotEmpty()
                        binding.emptyState.visibility =
                            if (hasMessages) android.view.View.GONE else android.view.View.VISIBLE
                        binding.messages.visibility =
                            if (hasMessages) android.view.View.VISIBLE else android.view.View.GONE
                        if (hasMessages) binding.messages.scrollToPosition(msgs.size - 1)
                    }
                }
                launch {
                    viewModel.busy.collect { busy ->
                        binding.btnSend.isEnabled =
                            !binding.input.text.isNullOrBlank() && !busy
                    }
                }
                launch {
                    viewModel.suggestedMove.collect { mv ->
                        showSuggestedMove(mv?.first, mv?.second)
                    }
                }
            }
        }
    }

    /** 上半屏的棋局. 只读 —— 这里是"看 AI 讲棋", 不是下棋的地方. */
    private fun setupBoard() {
        val fen = intent.getStringExtra(EXTRA_FEN)
        if (fen.isNullOrBlank() || !boardPosition.setFromFen(fen)) {
            binding.boardView.visibility = android.view.View.GONE
            return
        }
        binding.boardView.editMode = true          // 吃掉点击, 不让用户在这儿走子
        binding.boardView.onCellTapListener = { }
        binding.boardView.setFlipped(
            intent.getIntExtra(EXTRA_SIDE, Color.RED) == Color.BLACK
        )
        binding.boardView.setPosition(boardPosition)
    }

    /**
     * AI 讲到具体一步时, 在盘上把它标出来.
     *
     * 复用棋盘原本给"提示"用的那套箭头 + 虚影渲染 —— 用户在对局界面按提示
     * 看到的就是这个样子, 这里再学一遍新语汇没有必要.
     */
    private fun showSuggestedMove(fromUci: String?, toUci: String?) {
        if (fromUci == null || toUci == null) {
            binding.boardView.clearHint()
            binding.moveBanner.visibility = android.view.View.GONE
            return
        }
        // 模型给的坐标不一定落在盘内 (它也会写错), parse 返回 null 就当没讲
        val move = UciCoords.parse(fromUci + toUci)
        if (move == null) {
            binding.boardView.clearHint()
            binding.moveBanner.visibility = android.view.View.GONE
            return
        }
        binding.boardView.setHint(move)
        binding.moveBanner.text = getString(R.string.advisor_move_banner, fromUci, toUci)
        binding.moveBanner.visibility = android.view.View.VISIBLE
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
        /** 当前局面, 用来在上半屏画棋盘 */
        private const val EXTRA_FEN = "fen"

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
                activity.getString(if (currentPosition.sideToMove == Color.RED) R.string.red else R.string.black)
            )
            activity.startActivity(Intent(activity, AdvisorActivity::class.java).apply {
                putExtra(EXTRA_CONTEXT, ctx)
                putExtra(EXTRA_SUMMARY, summary)
                putExtra(EXTRA_FEN, currentPosition.toFen())
                putExtra(EXTRA_SIDE, playerSide)
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
                putExtra(EXTRA_FEN, currentPosition.toFen())
                putExtra(EXTRA_SIDE, playerSide)
            })
        }
    }
}
