package com.zhongguoxiangqi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标映射错了, 箭头就指错格子 —— 而差半格看起来"好像也对", 靠肉眼很难发现.
 * 这里用几个能手算的用例把它钉死.
 */
class LiveGeometryTest {

    private fun map(x: Float, y: Float, sw: Int, sh: Int, vw: Int, vh: Int) =
        LiveGeometry.srcToView(x, y, sw, sh, vw, vh)

    @Test
    fun `宽高比一致时就是纯缩放, 没有裁剪偏移`() {
        // 源 960x720, 视图 480x360: 比例相同, scale=0.5, 不该有偏移
        val p = map(100f, 200f, 960, 720, 480, 360)
        assertEquals(50f, p[0], 0.01f)
        assertEquals(100f, p[1], 0.01f)
    }

    @Test
    fun `源更宽时左右被裁掉, 中心点仍映射到视图中心`() {
        // 源 1000x500 (2:1), 视图 500x500 (1:1) -> scale = max(0.5, 1.0) = 1.0
        // 缩放后 1000x500, 宽超出 500 -> 左右各裁 250
        val c = map(500f, 250f, 1000, 500, 500, 500)
        assertEquals(250f, c[0], 0.01f)
        assertEquals(250f, c[1], 0.01f)
    }

    @Test
    fun `源更宽时左边缘被推到视图外`() {
        val left = map(0f, 0f, 1000, 500, 500, 500)
        assertEquals(-250f, left[0], 0.01f)
        assertEquals(0f, left[1], 0.01f)
    }

    @Test
    fun `源更高时上下被裁掉`() {
        // 源 500x1000, 视图 500x500 -> scale = max(1.0, 0.5) = 1.0, 上下各裁 250
        val p = map(250f, 500f, 500, 1000, 500, 500)
        assertEquals(250f, p[0], 0.01f)
        assertEquals(250f, p[1], 0.01f)
    }

    @Test
    fun `取较大的缩放比, 保证画面填满不留黑边`() {
        // 若错用 min 会得到 0.5, 那样视图上下会露黑边
        assertEquals(1.0f, LiveGeometry.fillCenterScale(1000, 500, 500, 500), 0.001f)
    }

    @Test
    fun `尺寸非法时不崩且退化为不缩放`() {
        assertEquals(1f, LiveGeometry.fillCenterScale(0, 0, 500, 500), 0.001f)
    }
}

/**
 * 稳定性闸门决定"什么时候才让引擎重算". 判错的后果是两个极端:
 * 太松 -> 箭头乱跳; 太紧 -> 棋盘变了却不重算.
 */
class StabilityGateTest {

    @Test
    fun `第一帧不算数, 连续两帧一致才接受`() {
        val g = StabilityGate(needed = 2)
        assertFalse(g.offer("fenA"))
        assertTrue(g.offer("fenA"))
        assertEquals("fenA", g.accepted)
    }

    @Test
    fun `同一个局面稳定后不重复触发`() {
        val g = StabilityGate(needed = 2)
        g.offer("fenA"); g.offer("fenA")
        assertFalse(g.offer("fenA"))
        assertFalse(g.offer("fenA"))
    }

    @Test
    fun `中间夹一帧误识别不会打断, 但要重新累计`() {
        val g = StabilityGate(needed = 2)
        assertFalse(g.offer("fenA"))
        assertFalse(g.offer("fenB"))   // 误识别
        assertFalse(g.offer("fenA"))   // 重新从 1 开始
        assertTrue(g.offer("fenA"))
    }

    @Test
    fun `局面真的变了要能触发重算`() {
        val g = StabilityGate(needed = 2)
        g.offer("fenA"); g.offer("fenA")
        assertFalse(g.offer("fenB"))
        assertTrue(g.offer("fenB"))
        assertEquals("fenB", g.accepted)
    }

    @Test
    fun `不可用的帧不清掉已接受的局面`() {
        val g = StabilityGate(needed = 2)
        g.offer("fenA"); g.offer("fenA")
        g.offer(null)                  // 手挡了一下
        assertEquals("fenA", g.accepted)
        // 挡完恢复同一个局面, 不该重复触发搜索
        assertFalse(g.offer("fenA"))
        assertFalse(g.offer("fenA"))
    }

    @Test
    fun `invalidate 后同一个局面要能重新触发`() {
        val g = StabilityGate(needed = 2)
        g.offer("fenA"); g.offer("fenA")
        g.invalidate()
        assertNull(g.accepted)
        assertFalse(g.offer("fenA"))
        assertTrue(g.offer("fenA"))
    }
}
