package com.zhongguoxiangqi.ui.recognize

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.boardView.editMode = true
        binding.boardView.onCellTapListener = { sq -> showPiecePicker(sq) }

        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnAdvise.setOnClickListener { requestAdvice() }
        binding.sideGroup.setOnCheckedChangeListener { _, _ -> refreshBoard() }
        binding.btnFlip.setOnClickListener {
            boardFlipped = !boardFlipped
            refreshBoard()
        }

        // 模型有 40MB, 加载要几百毫秒, 不能卡住界面
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { BoardRecognizer.create(this@RecognizeActivity) }
            recognizer = r
            if (r == null) {
                binding.status.text = getString(R.string.recognize_model_failed)
                binding.btnCamera.isEnabled = false
                binding.btnGallery.isEnabled = false
            }
        }
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
        binding.status.text = getString(R.string.recognize_working)
        binding.btnAdvise.isEnabled = false
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
                binding.status.text = getString(R.string.recognize_failed)
                return@launch
            }
            layout = result.layout
            confidence = result.confidence
            // 按原照片的上下关系显示: 拍的时候红方在上, 屏幕上也让红方在上,
            // 用户才对得上自己拍的那张图
            boardFlipped = result.redOnTop
            binding.preview.setImageBitmap(
                if (boardFlipped) rotate180(result.warped) else result.warped
            )
            binding.preview.visibility = View.VISIBLE

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

        val blocking = issues.filter { it.blocking }
        binding.btnAdvise.isEnabled = blocking.isEmpty()
        binding.status.text = buildString {
            if (blocking.isNotEmpty()) {
                append(getString(R.string.recognize_must_fix)).append('\n')
                blocking.forEach { append("• ").append(it.message).append('\n') }
            }
            issues.filterNot { it.blocking }.forEach {
                append("• ").append(it.message).append('\n')
            }
            if (blocking.isEmpty()) {
                append(getString(R.string.recognize_ready, suspect.size))
            }
        }.trim()
    }

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
        val issues = BoardLayout.validate(layout, sideToMove)
        if (issues.any { it.blocking }) { refreshBoard(); return }
        val fen = BoardLayout.toFen(layout, sideToMove)

        binding.advice.visibility = View.VISIBLE
        binding.advice.text = getString(R.string.recognize_thinking)
        binding.btnAdvise.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val mgr = engineManager ?: EngineManager(this@RecognizeActivity).also {
                it.difficultyLevel = ADVICE_LEVEL
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
        /** 支招用的难度: Pikafish 全力, 但时间压到提示搜索的预算 */
        private const val ADVICE_LEVEL = 10
        /** 选子对话框里的候选, 空格排第一 */
        private val PICKER_CHARS =
            listOf('.', 'K', 'A', 'B', 'N', 'R', 'C', 'P', 'k', 'a', 'b', 'n', 'r', 'c', 'p')
    }
}
