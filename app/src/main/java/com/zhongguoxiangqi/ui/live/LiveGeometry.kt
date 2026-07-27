package com.zhongguoxiangqi.ui.live

/**
 * 实时支招里的纯几何 / 纯状态逻辑.
 *
 * 单独抽出来是为了能测: [BoardOverlayView] 依赖 android.graphics.Matrix,
 * 而 Matrix 在普通单元测试里是个空壳(一碰就抛 "not mocked"), 把这些算术
 * 关在 View 里就等于永远测不了. 而箭头指错格子恰恰是这个功能最容易犯、
 * 又最难靠肉眼发现的错误 —— 差半格看起来"好像也对".
 */
object LiveGeometry {

    /**
     * PreviewView 的 FILL_CENTER 映射: 分析图像坐标 → View 坐标.
     *
     * FILL_CENTER 的语义是"按较大的那个比例缩放, 多出来的部分居中裁掉".
     * 叠加层必须用**完全一样**的算法, 否则箭头会整体平移 —— 而且分析分辨率
     * 和预览分辨率通常不同, 差异只会在这一步暴露出来.
     */
    fun fillCenterScale(srcW: Int, srcH: Int, viewW: Int, viewH: Int): Float {
        if (srcW <= 0 || srcH <= 0) return 1f
        return maxOf(viewW.toFloat() / srcW, viewH.toFloat() / srcH)
    }

    /**
     * 写入式版本: 结果放进 [out], 不分配.
     *
     * onDraw 一帧要调七八次, 每次返回一个新 FloatArray 就是每帧七八次分配.
     */
    fun srcToViewInto(
        x: Float, y: Float,
        srcW: Int, srcH: Int, viewW: Int, viewH: Int,
        out: FloatArray,
    ) {
        val s = fillCenterScale(srcW, srcH, viewW, viewH)
        out[0] = x * s + (viewW - srcW * s) / 2f
        out[1] = y * s + (viewH - srcH * s) / 2f
    }

    /** 返回 [x, y] 的 View 坐标. 会分配, 测试和非热路径用. */
    fun srcToView(
        x: Float, y: Float,
        srcW: Int, srcH: Int, viewW: Int, viewH: Int,
    ): FloatArray = FloatArray(2).also {
        srcToViewInto(x, y, srcW, srcH, viewW, viewH, it)
    }
}

/**
 * "连续 N 帧认出同一个局面才算数" 的闸门.
 *
 * 整盘识别准确率约 83%, 单帧结果直接驱动引擎会让箭头疯狂乱跳. 这里用一点
 * 延迟换稳定 —— 宁可慢半拍, 不要指错.
 */
class StabilityGate(private val needed: Int = 2) {

    private var candidate: String? = null
    private var count = 0

    /** 已经被接受、正在生效的那个局面 */
    var accepted: String? = null
        private set

    /**
     * 喂一帧识别结果.
     *
     * @param fen 本帧识别出的局面; null 表示这帧不可用(棋盘没框住/局面非法)
     * @return true 表示"这是一个新的、稳定的局面", 调用方该重算着法了
     */
    fun offer(fen: String?): Boolean {
        if (fen == null) {
            // 不可用的帧不能清掉 accepted: 手抖一下挡住半个棋盘就把已经算好的
            // 着法丢掉, 用户会觉得箭头一直在闪.
            candidate = null
            count = 0
            return false
        }
        if (fen == candidate) count++ else { candidate = fen; count = 1 }
        if (count < needed) return false
        if (fen == accepted) return false
        accepted = fen
        return true
    }

    /** 走子方变了之类的外部原因, 需要强制重算 */
    fun invalidate() {
        accepted = null
        candidate = null
        count = 0
    }
}
