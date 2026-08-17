package com.zhongguoxiangqi.ui.game

import com.zhongguoxiangqi.ui.common.Notation

import android.Manifest
import android.os.Bundle
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.ai.AdvisorAgent
import com.zhongguoxiangqi.ai.AdvisorConfig
import com.zhongguoxiangqi.ai.AdvisorSession
import com.zhongguoxiangqi.ai.BoardContext
import com.zhongguoxiangqi.databinding.ActivityGameBinding
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Move
import com.zhongguoxiangqi.game.Rules
import com.zhongguoxiangqi.game.VoiceCommands
import com.zhongguoxiangqi.rating.GameResult
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

    /** 语音指挥下棋: 麦克风 -> 中文文本 -> 解析 -> 自动走子 */
    private lateinit var voiceInput: VoiceInputController

    /** 语音播报: 吃子/将军/绝杀/战术杀法 */
    private lateinit var voiceEffects: VoiceEffects

    /** AI 分析会话(懒建, 退出对局时关闭) */
    private var advisorSession: AdvisorSession? = null
    private var advisorAgent: AdvisorAgent? = null

    /** AI 分析全屏动效层(非空 = 正在分析) */
    private var analysisOverlay: View? = null
    private var analysisDialog: GameAnalysisDialog? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // Android 15 起 targetSdk 35+ 强制 edge-to-edge, 底部工具条会被系统
        // 导航栏(三键/手势条)压住。这里把导航栏高度加到工具条底部 padding,
        // 让按钮整体上移、和导航栏错开。
        // 同样顶部 statusBars 加到 Toolbar 顶部, 不然状态栏会盖在标题上.
        // WindowInsets 只分发给根视图, 挂到子 View 上永远不会触发。
        val baseBottomPad = binding.toolbarRow.paddingBottom
        val baseTopPad = binding.toolbar.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val status = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            // 底部额外加 12dp 呼吸空间, 让按钮不贴住系统三键
            binding.toolbarRow.setPadding(
                binding.toolbarRow.paddingLeft,
                binding.toolbarRow.paddingTop,
                binding.toolbarRow.paddingRight,
                baseBottomPad + nav.bottom + (12f * resources.displayMetrics.density).toInt()
            )
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                baseTopPad + status.top,
                binding.toolbar.paddingRight,
                binding.toolbar.paddingBottom
            )
            insets
        }
        // 引擎思考和玩家看棋期间不要熄屏(无需任何权限)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        userProfile = UserProfile(this)
        feedback = GameFeedback(this)
        voiceEffects = VoiceEffects(this)
        voiceInput = VoiceInputController(this, ::onVoiceText, ::onVoiceError)
        lastDifficulty = intent.getIntExtra(EXTRA_DIFFICULTY, 5)
        lastPlayerSide = intent.getIntExtra(EXTRA_PLAYER_SIDE, Color.RED)

        setupBoard()
        setupControls()
        observeState()

        // 启动新对局. startFen 非空时是从拍照识别摆好的局面接着下
        startFen = intent.getStringExtra(EXTRA_START_FEN)
        viewModel.startNewGame(lastDifficulty, lastPlayerSide, startFen)
        // 开局这一刻就要记住设定, 而不是等用户去改难度或翻转才记.
        // 否则从「新对局」页挑完对手打进来, 退回首页会发现对手卡还是上一档,
        // 按"开始对局"也照旧用老档位 —— 挑对手这件事等于没发生.
        persistGameSetup(lastDifficulty, lastPlayerSide)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.game_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_voice -> {
                requestVoiceInput()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 点麦克风: 先查权限, 已授权就直接听, 没授权走 Activity Result 申请 */
    private fun requestVoiceInput() {
        if (!voiceInput.available) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startVoiceInput() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        Toast.makeText(this, R.string.voice_listening, Toast.LENGTH_SHORT).show()
        voiceInput.start()
    }

    /** 识别到一段话, 解析成着法并自动执行 */
    private fun onVoiceText(text: String) {
        val position = viewModel.currentPosition
        val moves = VoiceCommands.parse(position, text)
        when {
            moves.isEmpty() ->
                Toast.makeText(this, getString(R.string.voice_no_match, text), Toast.LENGTH_SHORT).show()

            moves.size > 1 ->
                Toast.makeText(this, R.string.voice_ambiguous, Toast.LENGTH_SHORT).show()

            else -> {
                val move = moves[0]
                // 记谱要按"走之前"的局面算, 执行完棋盘就变了
                val notation = Notation.format(this, move, position)
                if (viewModel.playerMove(move)) {
                    Toast.makeText(this, getString(R.string.voice_played, notation), Toast.LENGTH_SHORT).show()
                } else {
                    val st = viewModel.state.value
                    val msgRes = when {
                        st.isGameOver -> R.string.voice_game_over
                        st.isThinking -> R.string.voice_wait_engine
                        st.sideToMove != st.playerSide -> R.string.voice_not_your_turn
                        else -> R.string.game_illegal_move
                    }
                    Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onVoiceError(code: Int) {
        val res = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                R.string.voice_no_speech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                R.string.voice_permission_denied
            else ->
                R.string.voice_error
        }
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun setupBoard() {
        binding.boardView.onMoveListener = { move ->
            // 玩家走子
            val success = viewModel.playerMove(move)
            if (!success) {
                Toast.makeText(this, R.string.game_illegal_move, Toast.LENGTH_SHORT).show()
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
                    if (!ok) Toast.makeText(this, R.string.game_undo_failed, Toast.LENGTH_SHORT).show()
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
                if (nowBlack) R.string.game_switched_to_black else R.string.game_switched_to_red,
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
            startGameAnalysis()
        }
    }

    // ====== AI 分析(全屏动效 + 透明对话框) ======

    /** 懒建 AI 会话/Agent(整个对局共享一个, 退出时关闭) */
    private fun advisorAgent(): AdvisorAgent? {
        advisorAgent?.let { return it }
        val config = runCatching { AdvisorConfig.fromBuildConfig() }.getOrNull()
            ?: return null
        val session = AdvisorSession(config)
        advisorSession = session
        return AdvisorAgent(clientProvider = { session.open() }, model = session.model)
            .also { advisorAgent = it }
    }

    /**
     * 点「AI 分析」: 先铺全屏分析动效(用引擎真实深度/胜率), 扫几秒后淡出,
     * 弹出透明对话框展示 AI 对当前局面的总结, 之后可继续追问。
     */
    private fun startGameAnalysis() {
        if (analysisOverlay != null || analysisDialog != null) return
        val s = viewModel.state.value
        if (s.isGameOver) return

        // 触发一次后台引擎分析, 让动效数字是真实搜索数据
        viewModel.runAnalysisNow()

        val overlay = layoutInflater.inflate(R.layout.view_game_analysis_overlay, null, false)
        analysisOverlay = overlay
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        content.addView(
            overlay,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(220L).start()

        // 轻触跳过动效, 直接进对话框
        overlay.setOnClickListener { finishAnalysisOverlay() }

        // 动效至少跑 3.4 秒, 看起来"真的分析了", 再弹总结对话框
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3400)
            if (analysisOverlay === overlay) finishAnalysisOverlay()
        }
    }

    private fun finishAnalysisOverlay() {
        val overlay = analysisOverlay ?: return
        analysisOverlay = null
        overlay.animate().alpha(0f).setDuration(220L).withEndAction {
            (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
            openAnalysisDialog()
        }.start()
    }

    private fun openAnalysisDialog() {
        if (analysisDialog != null || isFinishing) return
        val s = viewModel.state.value
        val preamble = BoardContext.build(
            initialFen = viewModel.initialFenSnapshot,
            currentPosition = viewModel.currentPosition,
            moveHistory = viewModel.moveHistory.toList(),
            playerSide = s.playerSide,
            difficulty = s.difficulty,
            engineElo = viewModel.engineManager.effectiveElo(),
        )
        val config = runCatching { AdvisorConfig.fromBuildConfig() }.getOrNull()
        val dialog = GameAnalysisDialog(
            context = this,
            agent = advisorAgent(),
            configured = config?.baseUrl?.isNotBlank() == true && config.proxyToken.isNotBlank(),
            contextPreamble = preamble,
            scope = lifecycleScope,
        )
        analysisDialog = dialog
        dialog.setOnDismissListener { analysisDialog = null }
        dialog.show()
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
                    voiceEffects.onCheck()
                    viewModel.consumeEvent()
                }
                is GameViewModel.GameEvent.TacticCheck -> {
                    feedback.onCheck()
                    voiceEffects.onTactic(event.tactic)
                    viewModel.consumeEvent()
                }
                is GameViewModel.GameEvent.Checkmate -> {
                    feedback.onCheckmate()
                    voiceEffects.onCheckmate()
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
                    voiceEffects.onCapture(event.piece, event.notation)
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
                // 变暗只施加在"身份"上(执子圆点 + 名字), 不施加在整行 ——
                // 难度胶囊是对局中随时可点的入口, 跟着整行淡到 50% 会让它
                // 看起来是个禁用控件, 而它恰恰是这一行里唯一能按的东西.
                // (子 View 的 alpha 与父级相乘且被夹在 [0,1], 所以父级一旦
                // 变暗就没法在子级把它抬回来, 只能从一开始就不暗父级.)
                val myDim = if (myTurn) 1f else DIMMED
                val oppDim = if (myTurn) DIMMED else 1f
                binding.playerDot.alpha = myDim
                binding.playerName.alpha = myDim
                binding.playerRating.alpha = myDim
                binding.moveCount.alpha = myDim
                binding.opponentDot.alpha = oppDim
                binding.opponentName.alpha = oppDim

                // 换边后两边的执子圆点必须跟着换, 否则颜色和文字对不上
                val playerRed = state.playerSide == Color.RED
                binding.playerDot.setBackgroundResource(
                    if (playerRed) R.drawable.dot_side_red else R.drawable.dot_side_black
                )
                binding.opponentDot.setBackgroundResource(
                    if (playerRed) R.drawable.dot_side_black else R.drawable.dot_side_red
                )

                binding.opponentName.text = getString(if (state.playerSide == Color.RED) R.string.game_opponent_black else R.string.game_opponent_red)
                // 对手这一行原来同时摆了"档位名"和"Lv.N 档位名"两处, 说的是
                // 同一件事, 却挤掉了名字的宽度 —— 结果 "AI (Black)" 被截成
                // "AI (B…"。档位只由右边那颗可点的胶囊负责, 这里不再重复。
                binding.playerName.text = getString(if (state.playerSide == Color.RED) R.string.game_player_red else R.string.game_player_black)
                binding.playerRating.text = state.playerRank
                binding.moveCount.text = getString(R.string.game_move_count, state.moveCount / 2 + 1)

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

        // AI 分析动效期间, 把引擎真实的搜索深度/胜率喂给扫描屏
        lifecycleScope.launch {
            viewModel.winRateSnapshot.collect { snapshot ->
                (analysisOverlay?.findViewById<AnalysisScanView>(R.id.scan_view))?.let { scan ->
                    scan.updateInfo(snapshot.depth, snapshot.redWinRate)
                }
            }
        }

        // 引擎启动遮罩: enginePreparing=true 时挡住棋盘, 防止玩家走子后
        // 引擎还没起来对方不回招. 用 GONE 而不是 INVISIBLE 是因为棋盘本来
        // 就铺满整个 weight=1 的 FrameLayout, 看不见 overlay 和看不见
        // 棋盘在视觉上无差 —— 但 GONE 不占 hit-test, 棋盘立刻就能点.
        lifecycleScope.launch {
            viewModel.enginePreparing.collect { preparing ->
                binding.engineLoading.visibility = if (preparing) View.VISIBLE else View.GONE
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

    /** 按当前记谱设置把一步棋写成字符串 (中文记谱 / WXF) */
    private fun moveToChinese(move: Move): String =
        Notation.format(this, move, viewModel.currentPosition)

    /**
     * 对局结束弹窗.
     *
     * 标题报的是**结果**, 不是"对局结束" —— 旧版标题写"对局结束", 正文第一行
     * 又是"对局结束", 同一句话说两遍; 而"我到底赢没赢"整个弹窗一个字都没提,
     * 只能从"评分变化: -15"倒推. 结果还按复盘列表那套语义色上色(胜=affirm、
     * 负=danger), 让"胜/负"在全 App 是同一种颜色语言.
     */
    private fun showGameOverDialog(reason: String) {
        if (gameOverDialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return
        val state = viewModel.state.value
        val deltaText = (if (state.ratingDelta >= 0) "+" else "") + state.ratingDelta
        val message = getString(R.string.game_over_body, reason, deltaText, state.playerRank)
        // 用 gameResult 而不是 gameOverWinner: 后者只在将死时才有值,
        // 认输走的是 endGame(), 那条路上 winner 一直是 NONE —— 拿它判会把
        // "我刚认输"显示成"和棋".
        val (titleRes, colorRes) = when (state.gameResult) {
            GameResult.WIN -> R.string.game_over_win to R.color.affirm
            GameResult.LOSS -> R.string.game_over_loss to R.color.danger
            else -> R.string.game_over_draw to R.color.text_primary
        }
        val title = SpannableString(getString(titleRes)).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@GameActivity, colorRes)),
                0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        gameOverDialog = AlertDialog.Builder(this)
            .setTitle(title)
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
        rv.adapter = DifficultyAdapter(levels, viewModel.state.value.difficulty) { level ->
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

    /**
     * 记住当前设定, 让主菜单的"直接开局"和对手卡接着用.
     *
     * 参数默认取 viewModel 的当前状态(改难度、换边之后调用就是这个语义);
     * onCreate 里要显式传 intent 带来的那一组 —— 那时 startNewGame 还没把
     * 状态推到 state 里, 读 state 会拿到上一局的值.
     */
    private fun persistGameSetup(
        difficulty: Int = viewModel.state.value.difficulty,
        side: Int = viewModel.state.value.playerSide
    ) {
        getSharedPreferences(PREFS_GAME, MODE_PRIVATE).edit()
            .putInt(KEY_LAST_DIFFICULTY, difficulty)
            .putInt(KEY_LAST_SIDE, side)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        bgm.resume()
    }

    override fun onPause() {
        super.onPause()
        bgm.pause()
        // 切后台/锁屏时取消正在听的语音, 免得识别服务在后台继续占着麦克风
        voiceInput.cancel()
    }

    override fun onDestroy() {
        bgm.release()
        voiceInput.destroy()
        voiceEffects.shutdown()
        // 界面销毁时必须关掉对话框,否则会泄漏 Activity
        gameOverDialog?.dismiss()
        gameOverDialog = null
        analysisDialog?.dismiss()
        analysisDialog = null
        (analysisOverlay?.parent as? android.view.ViewGroup)?.removeView(analysisOverlay)
        analysisOverlay = null
        runCatching { advisorSession?.close() }
        advisorSession = null
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
