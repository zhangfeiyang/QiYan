package com.zhongguoxiangqi.ui.game

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityGameBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.rating.UserProfile
import com.zhongguoxiangqi.ui.menu.DifficultyAdapter
import com.zhongguoxiangqi.ui.menu.DifficultyLevel
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

    /** 非空 = 这盘是从拍照识别摆好的局面开的, "再来一局"要接着用同一个局面 */
    private var startFen: String? = null
    private var gameOverDialog: AlertDialog? = null

    /** 棋盘上已经渲染到第几步,用来判断是"又走了一步"还是悔棋/重开 */
    private var renderedMoveCount = 0
    private lateinit var feedback: GameFeedback

    /** 背景音乐. 跟着界面走: 切后台就停, 免得用户在别的 App 里还听见 */
    private val bgm by lazy { BgmPlayer(this) }

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

        // 启动新对局. startFen 非空时是从拍照识别摆好的局面接着下
        startFen = intent.getStringExtra(EXTRA_START_FEN)
        viewModel.startNewGame(lastDifficulty, lastPlayerSide, startFen)
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

        // 「翻转」以前只是换个看棋的视角, 盘还是自己那盘. 现在它真的换边:
        // 我方红黑对调, 引擎接手原来我这边. 视角跟着走, 否则换完自己的子
        // 跑到对面去了, 更难认.
        binding.btnFlip.setOnClickListener {
            viewModel.swapSides()
            val nowBlack = viewModel.state.value.playerSide == Color.BLACK
            binding.boardView.setFlipped(nowBlack)
            persistGameSetup()
            Toast.makeText(
                this,
                if (nowBlack) "已换为执黑" else "已换为执红",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 对手信息整行可点 -> 改 AI 等级
        binding.opponentRow.setOnClickListener { showDifficultyPicker() }

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

        binding.btnAi.setOnClickListener {
            val s = viewModel.state.value
            com.zhongguoxiangqi.ui.advisor.AdvisorActivity.launchFromGame(
                this,
                initialFen = viewModel.initialFenSnapshot,
                currentPosition = viewModel.currentPosition,
                moveHistory = viewModel.moveHistory.toList(),
                playerSide = s.playerSide,
                difficulty = s.difficulty,
                engineElo = viewModel.engineManager.effectiveElo(),
            )
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
                is GameViewModel.GameEvent.ThreatsUpdate -> {
                    binding.boardView.setThreats(event.redSquares, event.blackSquares)
                    viewModel.consumeEvent()
                }
                is GameViewModel.GameEvent.PlayerInCheck -> {
                    feedback.onCheck()
                    viewModel.consumeEvent()
                }
                is GameViewModel.GameEvent.Checkmate -> {
                    feedback.onCheckmate()
                    viewModel.consumeEvent()
                }
                is GameViewModel.GameEvent.NormalMove -> {
                    feedback.onMove(captured = false)
                    viewModel.consumeEvent()
                }
                is GameViewModel.GameEvent.CapturedPiece -> {
                    binding.boardView.playCaptureEffect(event.square, event.piece.code)
                    // 走子音效+振动 + 弹 "吃X" 的提示. 这是用户对局中**唯一**会看
                    // 到的吃子提示位置, 必须有可识别的文字, 不然车被吃也不知道.
                    val msg = getString(R.string.captured_piece_format,
                        getString(pieceNameRes(event.piece)))
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    feedback.onCapturePiece(event.piece)
                    viewModel.consumeEvent()
                }
                // 终局对话框由 state.isGameOver 驱动
                else -> Unit
            }
        }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                // 引擎名/对手评分
                // 轮到谁走谁就亮起来. 在此之前, 玩家判断"是不是该我了"只能靠
                // 那个转瞬即逝的"引擎思考中"胶囊 —— 它消失之后两行长得一模一样.
                val myTurn = state.sideToMove == state.playerSide && !state.isGameOver
                binding.playerRow.alpha = if (myTurn) 1f else DIMMED
                binding.opponentRow.alpha = if (myTurn) DIMMED else 1f

                // 换边后两边的执子圆点必须跟着换, 否则颜色和文字对不上
                val playerRed = state.playerSide == Color.RED
                binding.playerDot.setBackgroundResource(
                    if (playerRed) R.drawable.dot_side_red else R.drawable.dot_side_black
                )
                binding.opponentDot.setBackgroundResource(
                    if (playerRed) R.drawable.dot_side_black else R.drawable.dot_side_red
                )

                binding.opponentName.text = if (state.playerSide == Color.RED) "AI (执黑)" else "AI (执红)"
                // 显示档位名而不是分数: 那个分数是估计值, 没有已知等级分的
                // 对手可标定, 摆出来是给用户一个并不存在的精确度。
                binding.opponentRating.text = getString(
                    com.zhongguoxiangqi.ui.menu.DifficultyLevel.values()
                        .firstOrNull { it.id == state.difficulty }?.nameRes
                        ?: com.zhongguoxiangqi.R.string.difficulty_intermediate
                )
                binding.playerName.text = if (state.playerSide == Color.RED) "我 (执红)" else "我 (执黑)"
                binding.playerRating.text = "Elo ${state.playerRating} ${state.playerRank}"
                binding.moveCount.text = "第${state.moveCount / 2 + 1}手"

                val level = DifficultyLevel.entries.firstOrNull { it.id == state.difficulty }
                binding.levelChip.text = level
                    ?.let { "Lv.${it.id} ${getString(it.nameRes)}" }
                    ?: "Lv.${state.difficulty}"

                // INVISIBLE 而不是 GONE: 这个指示器每走一步都要出现/消失一次,
                // 用 GONE 会让它所在的信息行时高时矮, 而棋盘是 weight=1 吃剩余
                // 空间 —— 于是每走一步棋盘就被挤扁再弹回, 看起来就是上下抖动.
                binding.thinkIndicator.visibility =
                    if (state.isThinking) View.VISIBLE else View.INVISIBLE

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
            }
        }

        lifecycleScope.launch {
            viewModel.hintState.collect { hint ->
                // 只在棋盘上画箭头, 不再弹"推荐 X / 备选 Y / 评估 Z / 深度 N"
                // 那张卡片: 用户按提示是想知道"走哪儿", 箭头已经把这件事说完了,
                // 那串引擎术语既占地方又没人读.
                if (hint?.primaryMove != null) {
                    binding.boardView.setHint(hint.primaryMove, hint.secondaryMoves)
                } else {
                    binding.boardView.clearHint()
                }
            }
        }
    }

    /** 把被吃的棋子映射到 R.string.* 名, 用于 "吃X" 的 Toast */
    private fun pieceNameRes(piece: com.zhongguoxiangqi.game.Piece): Int = when (piece) {
        com.zhongguoxiangqi.game.Piece.R_KING, com.zhongguoxiangqi.game.Piece.B_KING -> R.string.piece_king
        com.zhongguoxiangqi.game.Piece.R_ADVISOR, com.zhongguoxiangqi.game.Piece.B_ADVISOR -> R.string.piece_advisor
        com.zhongguoxiangqi.game.Piece.R_ELEPHANT, com.zhongguoxiangqi.game.Piece.B_ELEPHANT -> R.string.piece_elephant
        com.zhongguoxiangqi.game.Piece.R_HORSE, com.zhongguoxiangqi.game.Piece.B_HORSE -> R.string.piece_horse
        com.zhongguoxiangqi.game.Piece.R_ROOK, com.zhongguoxiangqi.game.Piece.B_ROOK -> R.string.piece_rook
        com.zhongguoxiangqi.game.Piece.R_CANNON, com.zhongguoxiangqi.game.Piece.B_CANNON -> R.string.piece_cannon
        com.zhongguoxiangqi.game.Piece.R_PAWN, com.zhongguoxiangqi.game.Piece.B_PAWN -> R.string.piece_pawn
        else -> R.string.piece_pawn
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

        // 走子音效/震动由 viewModel 走 GameEvent 路径触发, 不在这里重复.
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
                viewModel.startNewGame(lastDifficulty, lastPlayerSide, startFen)
            }
            .setNegativeButton(R.string.action_back) { _, _ ->
                gameOverDialog = null
                finish()
            }
            .show()
    }

    /**
     * 对局中改 AI 等级.
     *
     * 复用主菜单原来那套难度列表 —— 它已经带了每档的说明和 Elo, 正是"我该
     * 选哪档"需要的信息, 没必要在这里另做一份简化版.
     */
    private fun showDifficultyPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_difficulty, null)
        val rv = view.findViewById<RecyclerView>(R.id.difficulty_list)
        rv.layoutManager = LinearLayoutManager(this)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_difficulty)
            .setView(view)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        val levels = DifficultyLevel.entries.toList()
        rv.adapter = DifficultyAdapter(levels) { level ->
            dialog.dismiss()
            viewModel.setDifficulty(level.id)
            lastDifficulty = level.id
            persistGameSetup()
        }
        dialog.show()
        // 滚到当前档位, 免得每次都从第一档开始找
        levels.indexOfFirst { it.id == viewModel.state.value.difficulty }
            .takeIf { it >= 0 }
            ?.let { rv.scrollToPosition(it) }
    }

    /** 记住当前设定, 让主菜单的"直接开局"接着用 */
    private fun persistGameSetup() {
        getSharedPreferences(PREFS_GAME, MODE_PRIVATE).edit()
            .putInt(KEY_LAST_DIFFICULTY, viewModel.state.value.difficulty)
            .putInt(KEY_LAST_SIDE, viewModel.state.value.playerSide)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        bgm.resume()
    }

    override fun onPause() {
        super.onPause()
        bgm.pause()
    }

    override fun onDestroy() {
        bgm.release()
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

        /** 自定义起始局面 (拍照识别摆好后交过来的 FEN) */
        const val EXTRA_START_FEN = "start_fen"

        /** 非当前走子方那一行的淡出程度. 太淡会看不清对手信息, 0.45 刚好 */
        const val DIMMED = 0.45f

        /** 上次对局的设定, 供主菜单"直接开局"复用 */
        const val PREFS_GAME = "game_setup"
        const val KEY_LAST_DIFFICULTY = "last_difficulty"
        const val KEY_LAST_SIDE = "last_side"
    }
}