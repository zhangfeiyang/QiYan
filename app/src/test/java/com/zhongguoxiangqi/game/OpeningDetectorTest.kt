package com.zhongguoxiangqi.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningDetectorTest {

    @Test
    fun `central cannon opening is detected`() {
        assertEquals("当头炮", OpeningDetector.detect(listOf("h2e2")))
        assertEquals("当头炮", OpeningDetector.detect(listOf("b2e2")))
    }

    @Test
    fun `screen horse defense is detected`() {
        assertEquals("中炮对屏风马", OpeningDetector.detect(listOf("h2e2", "h9g7")))
        assertEquals("中炮对屏风马", OpeningDetector.detect(listOf("h2e2", "h9g7", "h0g2", "i9h9", "i0h0")))
    }

    @Test
    fun `same direction cannon is detected`() {
        assertEquals("顺手炮", OpeningDetector.detect(listOf("h2e2", "h7e7")))
    }

    @Test
    fun `opposite direction cannon is detected`() {
        assertEquals("列手炮", OpeningDetector.detect(listOf("h2e2", "b7e7")))
    }

    @Test
    fun `fairy pointing road variations are detected`() {
        assertEquals("仙人指路", OpeningDetector.detect(listOf("c3c4")))
        assertEquals("仙人指路对卒底炮", OpeningDetector.detect(listOf("c3c4", "h7g7")))
        assertEquals("仙人指路互进卒", OpeningDetector.detect(listOf("c3c4", "c6c5")))
    }

    @Test
    fun `flying elephant opening is detected`() {
        assertEquals("飞相局", OpeningDetector.detect(listOf("g0e2")))
        assertEquals("飞相局对左中炮", OpeningDetector.detect(listOf("g0e2", "h7e7")))
    }

    @Test
    fun `jumping horse opening is detected`() {
        assertEquals("起马局", OpeningDetector.detect(listOf("h0g2")))
        assertEquals("起马局对挺卒", OpeningDetector.detect(listOf("h0g2", "c6c5")))
    }

    @Test
    fun `empty moves returns null`() {
        assertNull(OpeningDetector.detect(emptyList()))
    }
}
