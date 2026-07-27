package com.zhongguoxiangqi.ui.recognize

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityRecognizeBinding
import com.zhongguoxiangqi.engine.EngineInfo
import com.zhongguoxiangqi.engine.EngineListener
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.Piece
import com.zhongguoxiangqi.game.Position
import com.zhongguoxiangqi.game.Square
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.vision.BoardLayout
import com.zhongguoxiangqi.vision.BoardRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 拍照/选图识别棋局, 然后让引擎支招.
 *
 * 识别模型整盘全对约 83%, 也就是**每 6 张图就有 1 张至少错一个子**, 而错一个
 * 子引擎给的就是完全错误的建议. 所以这里的流程刻意做成"识别 → 人工确认 →
 * 才允许求招": 可疑格子高亮出来, 点一下就能改, 校验不过不让求招.
 */
class RecognizeActivity : AppCompatActivity(), EngineListener {

    private lateinit var binding: ActivityRecognizeBinding

    private var recognizer: BoardRecognizer? = null
    private var engineManager: EngineManager? = null

    /** 当前(可能已被用户修改过的) 棋盘排布 */
    private var layout: Array<CharArray> = BoardLayout.emptyLayout()
    private var confidence: Array<FloatArray>? = null
    private var cameraUri: Uri? = null
    private var busy = false

    /**
     * 显示时是否上下翻转(红在上).
     *
     * 只影响显示 —— 局面数据始终是 row 0 = 黑方底线的规范形式, 否则引擎
     * 那边兵的走向、九宫位置全都要跟着反。识别后按原照片的上下关系自动设置,
     * 用户也可以手动改。
     */
    private var boardFlipped = false

    /** 已经识别过一次: 决定回到这一屏时要不要再自动开相机 */
    private var hasRecognized = false

    /** 阻止求招的硬伤(缺帅/多子等). 点"支招"时才提示, 不常驻界面 */
    private var blockingIssues: List<String> = emptyList()

    private val sideToMove: Int
        get() = if (binding.sideRed.isChecked) Color.RED else Color.BLACK

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { runRecognition(it) } }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { runRecognition(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 不设 setSupportActionBar: 一旦交给 ActionBar 管, XML 里 app:menu
        // 声明的菜单项就不会渲染到 Toolbar 上. 返回键这里是自己接的.
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.boardView.editMode = true
        binding.boardView.onCellTapListener = { sq -> onBoardTap(sq) }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_retake -> { launchCamera(); true }
                R.id.action_gallery -> { pickImage.launch("image/*"); true }
                else -> false
            }
        }
        binding.btnAdvise.setOnClickListener { requestAdvice() }
        binding.btnAdviceLevel.setOnClickListener { showAdviceLevelPicker() }
        // 模型没加载完之前先禁用, 免得点了没反应
        binding.btnStartCamera.isEnabled = false
        binding.btnStartGallery.isEnabled = false
        binding.btnStartCamera.setOnClickListener { launchCamera() }
        binding.btnStartGallery.setOnClickListener { pickImage.launch("image/*") }
        updateAdviceLevelLabel()
        binding.sideGroup.setOnCheckedChangeListener { _, _ -> refreshBoard() }
        binding.btnFlip.setOnClickListener {
            boardFlipped = !boardFlipped
            refreshBoard()
        }

        buildTrays()
        refreshBoard()

        // 模型有 40MB, 加载要几百毫秒, 不能卡住界面
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { BoardRecognizer.create(this@RecognizeActivity) }
            recognizer = r
            if (r == null) {
                Toast.makeText(
                    this@RecognizeActivity,
                    R.string.recognize_model_failed, Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // 模型就绪后**不再自动开相机**。
            //
            // 原来是进来直接弹相机, 理由是"用户点进来就是想拍"。但想从相册
            // 选的人得先退出相机再去翻 toolbar; 而且"识别准不准全看拍得正
            // 不正"这件事没有任何地方告诉过他 —— 整盘全对只有约 83%, 拍歪了
            // 就是白拍。现在先给一屏引导, 两条路并列, 附拍摄要点。
            binding.btnStartCamera.isEnabled = true
            binding.btnStartGallery.isEnabled = true
        }
    }

    /** 取到图并识别成功之后, 引导让位给棋盘 */
    private fun showBoard() {
        binding.startPanel.visibility = View.GONE
        binding.contentGroup.visibility = View.VISIBLE
    }

    // ====== 支招水平 ======

    /**
     * 支招水平。用「快速/标准/深思」而不是"搜索深度""毫秒" —— 用户关心的是
     * 想得快还是想得好, 不是内部参数。三档映射到不同的难度等级。
     */
    private enum class AdviceLevel(
        val level: Int,
        val labelRes: Int,
        val descRes: Int,
    ) {
        FAST(6, R.string.advice_level_fast, R.string.advice_level_fast_desc),
        NORMAL(10, R.string.advice_level_normal, R.string.advice_level_normal_desc),
        DEEP(14, R.string.advice_level_deep, R.string.advice_level_deep_desc),
    }

    private var adviceLevel = AdviceLevel.NORMAL

    private fun updateAdviceLevelLabel() {
        binding.btnAdviceLevel.text =
            getString(R.string.advice_level_current, getString(adviceLevel.labelRes))
    }

    private fun showAdviceLevelPicker() {
        val items = AdviceLevel.values().map {
            getString(it.labelRes) + " · " + getString(it.descRes)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.advice_level_title)
            .setSingleChoiceItems(items, adviceLevel.ordinal) { dlg, which ->
                adviceLevel = AdviceLevel.values()[which]
                updateAdviceLevelLabel()
                // 难度变了, 已建好的引擎要跟着改, 否则下一次求招还是旧档位
                engineManager?.difficultyLevel = adviceLevel.level
                dlg.dismiss()
            }
            .show()
    }

    // ====== 取图 ======

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "board.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraUri = uri
        try {
            takePhoto.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.recognize_no_camera, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 读图并按长边限制降采样.
     *
     * 手机原图动辄 4000x3000(约 48MB ARGB), 直接解出来既慢又容易 OOM;
     * 识别只需要看清棋子, 1920 够用了.
     */
    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    // ====== 识别 ======

    private fun runRecognition(uri: Uri) {
        val r = recognizer ?: return
        if (busy) return
        busy = true
        Toast.makeText(this, R.string.recognize_working, Toast.LENGTH_SHORT).show()
        binding.advice.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bmp = loadBitmap(uri) ?: return@withContext null
                try { r.recognize(bmp) } catch (e: Throwable) {
                    android.util.Log.e(TAG, "识别失败", e); null
                } finally { bmp.recycle() }
            }
            busy = false
            if (result == null) {
                Toast.makeText(this@RecognizeActivity,
                    R.string.recognize_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            layout = result.layout
            confidence = result.confidence
            hasRecognized = true
            showBoard()
            // 按原照片的上下关系显示: 拍的时候红方在上, 屏幕上也让红方在上,
            // 用户才对得上自己拍的那张图
            boardFlipped = result.redOnTop
            // 不再显示校正后的原图: 用户要核对的是"识别出来的这盘棋对不对",
            // 那盘棋就在下面的棋盘上, 再摆一张照片只是占地方.
            held = null
            syncTraySelection()
            binding.boardView.setSelectedSquare(-1)

            // 四角没框住时先说清楚, 否则用户会以为是模型不认识棋子
            if (result.meanCornerScore < BoardLayout.LOW_CORNER_SCORE || result.offBoardCount > 0) {
                Toast.makeText(this@RecognizeActivity,
                    R.string.recognize_corner_warning, Toast.LENGTH_LONG).show()
            }
            refreshBoard()
        }
    }

    // ====== 棋盘显示与编辑 ======

    private fun refreshBoard() {
        val pos = BoardLayout.toPosition(layout, sideToMove) ?: Position().also { p ->
            // FEN 解析不了(比如一行超过 9 格), 至少把棋子摆上去让用户能改
            for (i in 0 until Square.COUNT) p.squares[i] = Piece.NONE.code
        }
        binding.boardView.setFlipped(boardFlipped)
        binding.boardView.setPosition(pos)

        val issues = BoardLayout.validate(layout, sideToMove)
        val suspect = mutableSetOf<Int>()
        confidence?.let { conf ->
            BoardLayout.lowConfidenceCells(conf).forEach { suspect += Square.make(it.row, it.col) }
        }
        issues.forEach { issue ->
            issue.cells.forEach { suspect += Square.make(it.row, it.col) }
        }
        binding.boardView.setSuspectSquares(suspect)

        // 局面有硬伤时不常驻一段说明文字占地方, 改成点"支招"那一刻再讲
        // (见 requestAdvice). 按钮始终可点, 否则用户只看到一个灰按钮,
        // 连"为什么不能点"都不知道.
        blockingIssues = issues.filter { it.blocking }.map { it.message }
    }

    // ====== 摆子托盘 ======

    /**
     * 当前"手里拿着"的东西. 三种状态互斥:
     *  - null            什么都没选
     *  - FromTray(ch)    从托盘拿起一个兵种, 点棋盘就落子
     *  - FromBoard(sq)   选中棋盘上的一个子, 点空点=移动, 点虚线圈=拿掉
     */
    private sealed class Held {
        data class FromTray(val fen: Char) : Held()
        data class FromBoard(val square: Int) : Held()
    }

    private var held: Held? = null
    private val trayViews = mutableListOf<TextView>()

    /** 七个兵种, 红黑各一排; 末尾额外一个虚线圈作"拿掉" */
    private fun buildTrays() {
        val red = listOf('K', 'A', 'B', 'N', 'R', 'C', 'P')
        val black = red.map { it.lowercaseChar() }
        fill(binding.trayBlack, black, isRed = false)
        fill(binding.trayRed, red, isRed = true)
    }

    private fun fill(row: LinearLayout, chars: List<Char>, isRed: Boolean) {
        row.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.space_1)
        // 槽位直径 = 棋盘一格的宽度, 让托盘里的子和盘上的子一样大 —— 托盘
        // 是"还没摆上去的同一副棋子", 大小不一致会让人以为是两种东西.
        // 棋盘横向 9 路, 所以按屏宽 / 9 算; 8 个槽自然放得下.
        val boardMargin = resources.getDimensionPixelSize(R.dimen.space_1) * 2
        val cell = (resources.displayMetrics.widthPixels - boardMargin) / 9
        val size = (cell - gap * 2).coerceAtLeast(resources.getDimensionPixelSize(R.dimen.space_5))
        chars.forEach { ch ->
            val piece = Piece.entries.first { it.fen == ch }
            val tv = TextView(this).apply {
                text = piece.chinese
                gravity = Gravity.CENTER
                // 字随圆走, 固定 16sp 在小屏上会顶破圆圈
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * 0.5f)
                setTypeface(Typeface.SERIF, Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        this@RecognizeActivity,
                        if (isRed) R.color.piece_red else R.color.piece_black
                    )
                )
                setBackgroundResource(
                    if (isRed) R.drawable.bg_tray_piece_red else R.drawable.bg_tray_piece_black
                )
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = gap; marginEnd = gap
                }
                setOnClickListener { onTrayTap(ch) }
            }
            row.addView(tv)
            trayViews += tv
        }
        // "拿掉"槽: 只有手里握着棋盘上的子时才有意义
        val trash = TextView(this).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_tray_trash)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = gap * 2; marginEnd = gap
            }
            setOnClickListener { onTrashTap() }
        }
        row.addView(trash)
    }

    private fun onTrayTap(fen: Char) {
        // 再点一次同一个兵种 = 放下
        held = if ((held as? Held.FromTray)?.fen == fen) null else Held.FromTray(fen)
        syncTraySelection()
        binding.boardView.setSelectedSquare(-1)
        refreshBoard()
    }

    /** 点虚线圈: 把选中的子从盘上拿掉 */
    private fun onTrashTap() {
        val h = held as? Held.FromBoard ?: return
        setCell(h.square, BoardRecognizer.CHAR_EMPTY)
        held = null
        syncTraySelection()
        binding.boardView.setSelectedSquare(-1)
        refreshBoard()
    }

    /**
     * 点棋盘.
     *
     * 手里有托盘子 → 落在这一格;
     * 手里有盘上的子 → 移过去 (点自己 = 取消);
     * 手里空着 → 这一格有子就拿起它, 空格则无事发生.
     */
    private fun onBoardTap(sq: Int) {
        when (val h = held) {
            is Held.FromTray -> {
                setCell(sq, h.fen)
                held = null
            }
            is Held.FromBoard -> {
                if (h.square == sq) {
                    held = null
                } else {
                    val moving = cellOf(h.square)
                    setCell(h.square, BoardRecognizer.CHAR_EMPTY)
                    setCell(sq, moving)
                    held = null
                }
            }
            null -> {
                held = if (cellOf(sq) != BoardRecognizer.CHAR_EMPTY) Held.FromBoard(sq) else null
            }
        }
        syncTraySelection()
        binding.boardView.setSelectedSquare((held as? Held.FromBoard)?.square ?: -1)
        refreshBoard()
    }

    private fun cellOf(sq: Int): Char = layout[Square.row(sq)][Square.col(sq)]

    private fun setCell(sq: Int, ch: Char) {
        val r = Square.row(sq)
        val c = Square.col(sq)
        layout[r][c] = ch
        // 用户亲手摆的, 不该再被标成"识别可疑"
        confidence?.get(r)?.set(c, 1f)
    }

    private fun syncTraySelection() {
        val heldFen = (held as? Held.FromTray)?.fen
        trayViews.forEach { tv ->
            tv.isSelected = heldFen != null && tv.text == pieceChinese(heldFen)
        }
    }

    private fun pieceChinese(fen: Char): String =
        Piece.entries.first { it.fen == fen }.chinese

    /** 点格子改子 */
    private fun showPiecePicker(sq: Int) {
        val row = Square.row(sq)
        val col = Square.col(sq)
        val chars = PICKER_CHARS
        val names = chars.map { ch ->
            if (ch == BoardRecognizer.CHAR_EMPTY) getString(R.string.recognize_empty)
            else {
                val piece = Piece.entries.first { it.fen == ch }
                (if (piece.isRed) "红 " else "黑 ") + piece.chinese
            }
        }.toTypedArray()

        val current = chars.indexOf(layout[row][col]).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recognize_pick_piece, 'a' + col, UciCoords.flipRow(row)))
            .setSingleChoiceItems(names, current) { dialog, which ->
                layout[row][col] = chars[which]
                // 用户已经确认过这一格, 不该再标成可疑
                confidence?.get(row)?.set(col, 1f)
                dialog.dismiss()
                refreshBoard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ====== 支招 ======

    private fun requestAdvice() {
        if (blockingIssues.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.recognize_must_fix) + " " + blockingIssues.joinToString("; "),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val issues = BoardLayout.validate(layout, sideToMove)
        if (issues.any { it.blocking }) { refreshBoard(); return }
        val fen = BoardLayout.toFen(layout, sideToMove)

        binding.advice.visibility = View.VISIBLE
        binding.advice.text = getString(R.string.recognize_thinking)
        binding.btnAdvise.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val mgr = engineManager ?: EngineManager(this@RecognizeActivity).also {
                it.difficultyLevel = adviceLevel.level
                engineManager = it
            }
            if (mgr.currentEngine == null && !mgr.start(this@RecognizeActivity)) {
                withContext(Dispatchers.Main) {
                    binding.advice.text = getString(R.string.recognize_engine_failed)
                    binding.btnAdvise.isEnabled = true
                }
                return@launch
            }
            mgr.setListener(this@RecognizeActivity)
            mgr.setPosition(fen, emptyList())
            mgr.startSearch(mgr.requestHint())
        }
    }

    override fun onReady() {}
    override fun onInfo(info: EngineInfo) {}
    override fun onError(message: String) {
        runOnUiThread {
            binding.advice.text = getString(R.string.recognize_engine_error, message)
            binding.btnAdvise.isEnabled = true
        }
    }

    override fun onBestMove(bestMove: String, ponderMove: String?) {
        val move = UciCoords.parse(bestMove)
        runOnUiThread {
            binding.btnAdvise.isEnabled = true
            if (move == null) {
                binding.advice.text = getString(R.string.recognize_no_move)
                return@runOnUiThread
            }
            val pos = BoardLayout.toPosition(layout, sideToMove)
            val piece = pos?.pieceAt(move.from)?.let { Piece.fromCode(it) }
            binding.advice.text = getString(
                R.string.recognize_advice,
                piece?.chinese ?: "",
                UciCoords.squareToUci(move.from),
                UciCoords.squareToUci(move.to)
            )
            binding.boardView.setHint(move)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engineManager?.shutdown()
        recognizer?.close()
    }

    /** 把校正后的正视图也转成与照片相同的朝向 */
    private fun rotate180(src: Bitmap): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    companion object {
        private const val TAG = "RecognizeActivity"
        private const val MAX_IMAGE_SIDE = 1920
        /** 选子对话框里的候选, 空格排第一 */
        private val PICKER_CHARS =
            listOf('.', 'K', 'A', 'B', 'N', 'R', 'C', 'P', 'k', 'a', 'b', 'n', 'r', 'c', 'p')
    }
}
