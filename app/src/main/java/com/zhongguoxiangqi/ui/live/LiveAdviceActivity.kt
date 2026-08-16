package com.zhongguoxiangqi.ui.live

import com.zhongguoxiangqi.ui.common.Insets

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zhongguoxiangqi.R
import com.zhongguoxiangqi.databinding.ActivityLiveAdviceBinding
import com.zhongguoxiangqi.engine.EngineInfo
import com.zhongguoxiangqi.engine.EngineListener
import com.zhongguoxiangqi.engine.EngineManager
import com.zhongguoxiangqi.engine.SearchParams
import com.zhongguoxiangqi.game.Color
import com.zhongguoxiangqi.game.UciCoords
import com.zhongguoxiangqi.vision.BoardLayout
import com.zhongguoxiangqi.vision.BoardRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时支招: 摄像头一直对着棋盘, 局面一变就重算最佳着法, 箭头直接画在预览画面上.
 *
 * 三个刻意的设计取舍:
 *
 * 1. **不是每帧都识别**. 识别是两个 ONNX 模型 (11MB + 30MB) 串起来跑, 每帧都跑
 *    只会让手机发烫、帧率崩掉, 而棋局本来也不是每帧都变. 用
 *    STRATEGY_KEEP_ONLY_LATEST 天然背压 + 一个"上一帧还没算完就丢"的开关.
 *
 * 2. **连续两次识别一致才认**. 整盘全对约 83%, 也就是每 6 帧就有 1 帧至少错一个子.
 *    直接拿单帧结果去驱动引擎, 箭头会疯狂乱跳. 要求连续 [STABLE_FRAMES] 帧结果
 *    完全一样才当成"局面变了", 用一点延迟换稳定.
 *
 * 3. **搜索时间卡死 [MOVETIME_MS]**. 这是"实时"的硬约束 —— 宁可要一个 0.6 秒内
 *    算出的好棋, 不要一个 3 秒后算出的最佳棋.
 */
class LiveAdviceActivity : AppCompatActivity(), EngineListener {

    private lateinit var binding: ActivityLiveAdviceBinding

    private var recognizer: BoardRecognizer? = null
    private var engine: EngineManager? = null
    /** 当前相机句柄, 供 zoom 控制 (CameraX 的 CameraControl.zoomState) */
    private var camera: androidx.camera.core.Camera? = null

    // 这两个 Detector 不能在字段初始化时建: 那时 Activity 还没挂到窗口上,
    // ViewConfiguration.get() 会抛 IllegalStateException, 导致点击"实时支招"
    // 一进 Activity 就闪退(Android 15 上必现)。改 by lazy, 等 onCreate 之后
    // 真正用到了才构造。
    private val scaleDetector by lazy {
        ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val ratio = detector.scaleFactor
                    val newZoom = cam.cameraInfo.zoomState.value?.let {
                        (it.zoomRatio * ratio).coerceIn(it.minZoomRatio, it.maxZoomRatio)
                    } ?: return false
                    cam.cameraControl.setZoomRatio(newZoom)
                    updateZoomBadge()
                    return true
                }
            }
        )
    }
    private val tapDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val cam = camera ?: return false
                    cam.cameraControl.setZoomRatio(1f)
                    updateZoomBadge()
                    return true
                }
            }
        )
    }

    /** 预览上的捏合/双击手势交给缩放检测; 返回 true 表示已消费
     *
     *  用 runCatching 包住 detector 调用: tapDetector/scaleDetector 都是 by lazy,
     *  真正第一次进 onTouchEvent 才构造 GestureDetector, 万一构造里抛了异常
     *  (比如某些 ROM 上 ViewConfiguration.get 在 onResume 之前调), 不能让这
     *  个事件把整个 Activity 拽下去.
     */
    private val previewTouchListener = View.OnTouchListener { _, event ->
        runCatching { tapDetector.onTouchEvent(event) }
        runCatching { scaleDetector.onTouchEvent(event) }
        true
    }

    private fun updateZoomBadge() {
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        runOnUiThread {
            binding.zoomBadge.text = getString(R.string.live_zoom, z.zoomRatio)
            binding.zoomBadge.visibility = if (z.zoomRatio > 1.01f) View.VISIBLE else View.GONE
        }
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LiveBoardAnalysis").apply { isDaemon = true }
    }

    /** 正在识别 / 正在搜索: 两个阶段各自丢帧, 不排队 */
    private val recognizing = AtomicBoolean(false)
    private val searching = AtomicBoolean(false)

    /** 连续 N 帧一致才认的闸门, 逻辑见 [StabilityGate] (有单测) */
    private val gate = StabilityGate(STABLE_FRAMES)

    /** 当前帧的四角与尺寸, 供叠加层做坐标映射 */
    @Volatile private var lastCorners: FloatArray? = null
    @Volatile private var lastSrcW = 0
    @Volatile private var lastSrcH = 0

    private val sideToMove: Int
        get() = if (binding.sideRed.isChecked) Color.RED else Color.BLACK

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, R.string.live_need_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveAdviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.apply(binding.root, top = binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.preview.setOnTouchListener(previewTouchListener)

        // 换走子方要立刻重算: 同一个局面轮红走和轮黑走, 最佳着法完全不同
        binding.sideGroup.setOnCheckedChangeListener { _, _ ->
            gate.invalidate()
            currentMove = null
            binding.overlay.update(lastCorners, lastSrcW, lastSrcH, null)
        }

        // 启动流程用 runCatching 兜底: 之前 onCreate 任何一行抛了都会闪退
        // (Issue: 用户点击"实时支招"后 Activity 直接崩, 没有任何错误弹窗).
        // 现在即使模型加载失败 / 相机权限申请异常 / 相机绑定异常, 都能退回
        // 友好的错误提示, 而不是直接闪退.
        lifecycleScope.launch {
            runCatching {
                recognizer = withContext(Dispatchers.IO) {
                    BoardRecognizer.create(this@LiveAdviceActivity)
                }
            }.onFailure { t ->
                Log.e(TAG, "识别器初始化失败", t)
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@LiveAdviceActivity,
                        R.string.recognize_model_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@launch
            }
            if (recognizer == null) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@LiveAdviceActivity,
                        R.string.recognize_model_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@launch
            }
            // 模型加载完再问相机权限, 不然用户没看到模型加载失败就先被弹
            // 了一次权限框, 体验上像是"App 问完就崩".
            if (ContextCompat.checkSelfPermission(this@LiveAdviceActivity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { startCamera() }.onFailure {
                    Log.e(TAG, "启动相机失败", it)
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this@LiveAdviceActivity, R.string.live_camera_failed, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            } else {
                runCatching { requestCamera.launch(Manifest.permission.CAMERA) }
                    .onFailure {
                        Log.e(TAG, "申请相机权限失败", it)
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@LiveAdviceActivity, R.string.live_need_camera, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
            }
        }
    }

    // ====== 相机 ======

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }

            // 分析分辨率压到 720p 上下: 识别模型自己就要缩到 256/400, 再高的输入
            // 只是白白多花一次拷贝和缩放.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(960, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }

            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }.onFailure {
                Log.e(TAG, "绑定相机失败", it)
                Toast.makeText(this, R.string.live_camera_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 逐帧回调. 跑在 [analysisExecutor] 上, 不要碰 UI. */
    private fun analyze(proxy: ImageProxy) {
        // 上一帧还在识别就直接丢掉这一帧 —— 排队只会让画面越来越滞后
        if (!recognizing.compareAndSet(false, true)) {
            proxy.close(); return
        }
        val started = System.currentTimeMillis()
        try {
            val bmp = proxy.toUprightBitmap()
            // 尺寸必须在 recycle 之前取: 回收之后再读 width/height 是未定义行为.
            // 而且 toUprightBitmap 可能把画面转了 90°, 宽高会互换, 不能拿
            // proxy 的原始宽高顶替.
            val w = bmp.width
            val h = bmp.height
            val r = recognizer ?: return
            val result = runCatching { r.recognize(bmp) }.getOrNull()
            bmp.recycle()
            if (result == null) return

            // recognize() 每帧都会产出一张 400x450 的校正图供界面预览用, 这里
            // 用不到. 连续跑起来就是每秒好几 MB 的垃圾, 必须当场回收.
            runCatching { result.warped.recycle() }

            lastCorners = result.corners
            lastSrcW = w
            lastSrcH = h

            val cost = System.currentTimeMillis() - started
            onRecognized(result, cost)
        } catch (t: Throwable) {
            Log.e(TAG, "识别帧失败", t)
        } finally {
            recognizing.set(false)
            proxy.close()
        }
    }

    private fun onRecognized(result: com.zhongguoxiangqi.vision.RecognizedBoard, costMs: Long) {
        val corners = result.corners
        val srcW = lastSrcW
        val srcH = lastSrcH

        // 四角没框住时别画: 角点一飘, 箭头会指到棋盘外面去, 比不画更糟
        if (result.meanCornerScore < BoardLayout.LOW_CORNER_SCORE) {
            runOnUiThread {
                binding.overlay.clear()
                binding.status.text = getString(R.string.live_board_invalid)
            }
            gate.offer(null)
            return
        }

        val issues = BoardLayout.validate(result.layout, sideToMove)
        val ok = issues.none { it.blocking }
        val fen = if (ok) BoardLayout.toFen(result.layout, sideToMove) else null

        // 稳定性闸门: 连续几帧认出同一个局面才当真
        val changed = gate.offer(fen)
        val locked = gate.accepted != null

        runOnUiThread {
            binding.overlay.update(corners, srcW, srcH, currentMove)
            binding.status.text = when {
                !ok -> getString(R.string.live_board_invalid)
                !locked -> getString(R.string.live_locking)
                else -> getString(R.string.live_ready, costMs)
            }
        }

        if (changed && !searching.get()) requestMove(fen!!)
    }

    // ====== 引擎 ======

    private var currentMove: IntArray? = null

    private fun requestMove(fen: String) {
        if (!searching.compareAndSet(false, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val mgr = engine ?: EngineManager(this@LiveAdviceActivity).also {
                it.difficultyLevel = LIVE_LEVEL
                engine = it
            }
            if (mgr.currentEngine == null && !mgr.start(this@LiveAdviceActivity)) {
                searching.set(false)
                return@launch
            }
            mgr.setListener(this@LiveAdviceActivity)
            mgr.setPosition(fen, emptyList())
            // 卡死搜索时间: 这是"实时"的硬约束
            mgr.startSearch(
                SearchParams(
                    movetime = MOVETIME_MS, threads = 2, hashMb = 64, multiPv = 1,
                    // 关键: 不加这个, EngineManager 会按难度 8 把 600ms 抬到 4000ms
                    strictMovetime = true,
                )
            )
        }
    }

    override fun onBestMove(bestMove: String, ponderMove: String?) {
        searching.set(false)
        val mv = UciCoords.parse(bestMove) ?: return
        val fromSq = mv.from
        val toSq = mv.to
        currentMove = intArrayOf(
            com.zhongguoxiangqi.game.Square.col(fromSq),
            com.zhongguoxiangqi.game.Square.row(fromSq),
            com.zhongguoxiangqi.game.Square.col(toSq),
            com.zhongguoxiangqi.game.Square.row(toSq),
        )
        runOnUiThread {
            binding.overlay.update(lastCorners, lastSrcW, lastSrcH, currentMove)
        }
    }

    override fun onReady() {}
    override fun onInfo(info: EngineInfo) {}
    override fun onError(message: String) {
        searching.set(false)
        Log.e(TAG, "引擎错误: $message")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 顺序有讲究: 先停分析线程, 再关识别器.
        // 反过来的话正在跑的那一帧会拿着已经 close 掉的 OrtSession 继续算,
        // 直接崩在 native 层.
        analysisExecutor.shutdown()
        runCatching { analysisExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) }
        // BoardRecognizer 持有两个 ONNX 会话(11MB + 30MB 模型), 是 Closeable.
        // 不关的话每进一次这个界面就漏一份原生内存, 反复进出必然 OOM.
        runCatching { recognizer?.close() }
        recognizer = null
        engine?.shutdown()
    }

    /**
     * ImageProxy -> 正立的 Bitmap.
     *
     * 相机传感器几乎总是横着的, 拿到的帧带一个 rotationDegrees. 必须先转正再识别,
     * 否则四角检测出来的"左上角"根本不是棋盘的左上角, 后面整条坐标链全错.
     */
    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val buf = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buf)
        val cropped = if (rowPadding == 0) bmp else
            Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
        val deg = imageInfo.rotationDegrees
        if (deg == 0) return cropped
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
            .also { if (it !== cropped) cropped.recycle() }
    }

    private companion object {
        const val TAG = "LiveAdvice"
        /** 连续几帧一致才认: 单帧识别有 ~17% 概率至少错一个子 */
        const val STABLE_FRAMES = 2
        /** 搜索时间上限, 毫秒. 留出识别和绘制的余量, 保证整体感觉在 1 秒内 */
        const val MOVETIME_MS = 600L
        const val LIVE_LEVEL = 8
    }
}
