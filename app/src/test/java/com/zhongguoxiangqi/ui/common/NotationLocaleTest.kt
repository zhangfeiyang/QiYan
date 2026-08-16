package com.zhongguoxiangqi.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记谱语言必须跟着**界面**语言走, 而不是设备系统语言.
 *
 * 这个 App 的语言一律走 per-app locale(Application.onCreate 里默认设成 "en"),
 * 但 AppCompat 在 API 33 以下只改写 Activity 的 Configuration —— Application
 * 的 Configuration 还是系统语言。GameViewModel 拿的正是 getApplication(),
 * 于是"界面英文 + 棋谱炮二平五"这种分裂只在旧系统上出现, 在 API 35 的模拟器上
 * 怎么点都复现不了。所以把判断逻辑拆出来直接测。
 */
class NotationLocaleTest {

    @Test
    fun `per-app 语言优先于 configuration`() {
        // 中文系统 + App 切成英文: 记谱必须是英文那套(WXF), 不能跟着系统走
        assertFalse(Notation.isChineseUi(perAppLanguage = "en", configLanguage = "zh"))
        // 英文系统 + App 切成中文: 反过来同理
        assertTrue(Notation.isChineseUi(perAppLanguage = "zh", configLanguage = "en"))
    }

    @Test
    fun `没有 per-app 语言时退回 configuration`() {
        assertTrue(Notation.isChineseUi(perAppLanguage = null, configLanguage = "zh"))
        assertFalse(Notation.isChineseUi(perAppLanguage = null, configLanguage = "en"))
    }

    @Test
    fun `两者都拿不到时当成非中文`() {
        // 非中文一律走 WXF: 读不了汉字的人看"炮二平五"等于没有信息,
        // 而中文用户看 "C2.5" 至少还能对上盘上那枚 C.
        assertFalse(Notation.isChineseUi(perAppLanguage = null, configLanguage = null))
    }

    @Test
    fun `地区变体不影响判断`() {
        // zh-Hans-CN / zh-TW 传进来的都是 language 部分 "zh"
        assertTrue(Notation.isChineseUi(perAppLanguage = "zh", configLanguage = null))
    }
}
