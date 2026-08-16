package com.zhongguoxiangqi.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏内边距.
 *
 * targetSdk 35 起 edge-to-edge 是强制的, 没有 opt-out: 内容默认铺满整块屏,
 * 状态栏和导航栏直接压在上面。此前只有 GameActivity 自己处理了这件事, 另外
 * 8 个界面的标题和返回箭头都和系统时钟叠在一起 —— 这是全 App 一致的缺陷,
 * 所以修法也应当是一处共享实现, 而不是抄 8 遍。
 *
 * 只加 padding、不改 margin: padding 让背景色继续延伸到状态栏底下, 屏幕顶端
 * 才是连续的一块; 用 margin 会在上方留一条露出 windowBackground 的空隙。
 *
 * 每个 View 的初始 padding 会被记下来当基线。listener 可能被重复分发
 * (旋转、键盘弹出、手势导航切换), 每次都在**基线**上加 inset, 而不是在上一次
 * 的结果上继续累加 —— 否则转几次屏 padding 就会滚雪球。
 */
object Insets {

    private val basePadding = HashMap<Int, Int>()

    /**
     * 把状态栏高度加到 [top] 的上边距, 导航栏高度加到 [bottom] 的下边距.
     *
     * 两个参数都可以为 null: 只有顶部工具栏的界面传 top, 只有底部按钮条的传
     * bottom。[root] 必须是 Activity 的内容根视图 —— WindowInsets 只分发给
     * 根视图, 挂到子 View 上的 listener 永远不会触发。
     *
     * [extraBottomDp] 是给底部再留的呼吸空间, 让按钮不贴死在手势条上。
     */
    fun apply(root: View, top: View? = null, bottom: View? = null, extraBottomDp: Float = 0f) {
        val density = root.resources.displayMetrics.density
        top?.let { basePadding.getOrPut(it.hashCode()) { it.paddingTop } }
        bottom?.let { basePadding.getOrPut(it.hashCode()) { it.paddingBottom } }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top?.let {
                val base = basePadding[it.hashCode()] ?: it.paddingTop
                it.setPadding(it.paddingLeft, base + bars.top, it.paddingRight, it.paddingBottom)
            }
            bottom?.let {
                val base = basePadding[it.hashCode()] ?: it.paddingBottom
                it.setPadding(
                    it.paddingLeft,
                    it.paddingTop,
                    it.paddingRight,
                    base + bars.bottom + (extraBottomDp * density).toInt()
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
