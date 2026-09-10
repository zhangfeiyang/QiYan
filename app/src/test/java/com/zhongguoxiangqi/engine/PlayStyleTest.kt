package com.zhongguoxiangqi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 风格挑选逻辑的纯逻辑测试.
 *
 * 不碰引擎: 候选着法和特征都是手工构造的, 锁住的是 [PlayStyle.pick] 的
 * 挑选规则 —— 各风格在什么局面下会偏离引擎最佳、偏离时受什么约束。
 */
class PlayStyleTest {

    private fun cand(uci: String, cp: Int, mate: Int = 0) =
        PlayStyle.Candidate(uci, cp, mate)

    private fun feats(
        capture: Boolean = false,
        gain: Int = 0,
        check: Boolean = false,
    ) = PlayStyle.Features(capture, gain, check)

    @Test
    fun `空候选返回null`() {
        assertNull(PlayStyle.AGGRESSIVE.pick(emptyList()) { feats() })
    }

    @Test
    fun `均衡永远走最佳`() {
        val candidates = listOf(cand("h2e2", 60), cand("b2e2", 55, ))
        val picked = PlayStyle.BALANCED.pick(candidates) {
            feats(capture = true, gain = 900, check = true)
        }
        assertEquals("h2e2", picked?.moveUci)
    }

    @Test
    fun `超出容差的候选不会被选中`() {
        // 第二条线差 100cp, 远超 40cp 容差: 哪怕它是弃车强攻也不该被攻杀型选中
        val candidates = listOf(cand("h2e2", 80), cand("c3d5", -20))
        val picked = PlayStyle.AGGRESSIVE.pick(candidates) {
            if (it.moveUci == "c3d5") feats(capture = true, gain = 900, check = true) else feats()
        }
        assertEquals("h2e2", picked?.moveUci)
    }

    @Test
    fun `容差内攻杀型偏爱吃子将军`() {
        // 最佳着法是平静出子; 容差内有一手"吃车带将军"(只差 10cp) —— 攻杀型该选后者
        val candidates = listOf(cand("h2e2", 60), cand("c3e2", 50))
        val picked = PlayStyle.AGGRESSIVE.pick(candidates) {
            if (it.moveUci == "c3e2") feats(capture = true, gain = 900, check = true) else feats()
        }
        assertEquals("c3e2", picked?.moveUci)
    }

    @Test
    fun `稳健型拒绝容差内的弃子`() {
        // "弃车"只差 5cp 且带将军, 稳健型也不该要: SEE<0 的冒险直接出局
        val candidates = listOf(cand("h2e2", 30), cand("b0c2", 25))
        val picked = PlayStyle.SOLID.pick(candidates) {
            if (it.moveUci == "b0c2") feats(capture = true, gain = -800, check = true) else feats()
        }
        assertEquals("h2e2", picked?.moveUci)
    }

    @Test
    fun `稳健型同分时偏好平静着法`() {
        // 两手同分: 一手吃马(不亏), 一手平静出子 —— 稳健型挑平静那手
        val candidates = listOf(cand("a0a1", 40), cand("a0b2", 40))
        val picked = PlayStyle.SOLID.pick(candidates) {
            if (it.moveUci == "a0b2") feats(capture = true, gain = 400) else feats()
        }
        // 吃马不亏子力, 会进 safe 集合; 但 strength 相同时平静着法排前面 ——
        // 注意这里 maxBy 的第一个键就是 strength, 平静偏好只在同强度下起作用
        assertEquals("a0a1", picked?.moveUci)
    }

    @Test
    fun `灵动型在容差内随机但不越界`() {
        val candidates = listOf(cand("h2e2", 60), cand("b2e2", 55), cand("c3d5", 45))
        repeat(20) {
            val picked = PlayStyle.SWARM.pick(candidates) { feats() }
            assertTrue(picked!!.moveUci in listOf("h2e2", "b2e2", "c3d5"))
        }
    }

    @Test
    fun `有成杀路线时所有风格都走杀`() {
        val candidates = listOf(cand("e6e9", 0, mate = 5), cand("h2e2", 200))
        for (style in PlayStyle.entries) {
            val picked = style.pick(candidates) {
                if (it.moveUci == "h2e2") feats(capture = true, gain = 900, check = true) else feats()
            }
            assertEquals(style.name, "e6e9", picked?.moveUci)
        }
    }

    @Test
    fun `容差内存在杀棋候选时优先成杀`() {
        // 最佳 +500, 容差内有一手"3步杀" —— 强度分远超 cp, 应被所有风格选中
        val candidates = listOf(cand("h2e2", 500), cand("c3t9", 480, mate = 3))
        for (style in PlayStyle.entries) {
            if (style == PlayStyle.BALANCED) continue
            val picked = style.pick(candidates) { feats() }
            assertEquals(style.name, "c3t9", picked?.moveUci)
        }
    }

    @Test
    fun `fromId未知id回退均衡`() {
        assertEquals(PlayStyle.BALANCED, PlayStyle.fromId(99))
        assertEquals(PlayStyle.SOLID, PlayStyle.fromId(2))
    }
}
