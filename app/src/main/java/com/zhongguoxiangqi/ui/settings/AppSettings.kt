package com.zhongguoxiangqi.ui.settings

import android.content.Context

/**
 * 应用设置的读取入口.
 *
 * [SettingsActivity] 负责写, 其余界面通过这里读 —— 否则设置项只是存进
 * SharedPreferences 就没人用了(开关拨了也不起作用).
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val threads: Int get() = prefs.getInt(SettingsActivity.KEY_THREADS, 2).coerceIn(1, 8)
    val hashMb: Int get() = prefs.getInt(SettingsActivity.KEY_HASH, 64).coerceIn(16, 1024)
    val soundEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_SOUND, true)
    val vibrateEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_VIBRATE, true)
    val showCoordinates: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_SHOW_COORDS, true)
    val showWinRate: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_SHOW_WINRATE, true)
    val animationEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_ANIMATION, true)
    val boardFlipped: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_FLIPPED, false)

    /** 背景音乐. 默认开 —— 氛围是这次特意加的, 但随时能关 */
    val bgmEnabled: Boolean get() = prefs.getBoolean(SettingsActivity.KEY_BGM, true)

    /**
     * 主题模式: 0=跟随系统 1=深色 2=浅色.
     *
     * 默认 2 而不是 0 —— 这个 App 的配色是照着浅色暖木设计的 (棋盘木色、
     * 红黑双方、胜率条的对比度都按米白底调过), 深色是备选. 跟随系统会让
     * 一部分人第一次打开时看到的是备选方案.
     */
    val themeMode: Int get() = prefs.getInt(SettingsActivity.KEY_THEME, 2)

    /**
     * 棋子字符集: 0=跟随界面语言 1=汉字 2=拉丁字母.
     *
     * 默认跟随语言而不是写死汉字: 把界面翻成英文却在盘上摆七种汉字, 等于
     * 只翻译了外壳 —— 用户读得懂"Hint"却认不出被提示的那枚子是什么。
     */
    val pieceSetMode: Int get() = prefs.getInt(SettingsActivity.KEY_PIECE_SET, 0)

    /**
     * 记谱法: 0=跟随界面语言 1=中文记谱 2=WXF.
     *
     * 同上. "炮二平五"对不读汉字的人不是"另一种写法", 而是没有信息。
     */
    val notationMode: Int get() = prefs.getInt(SettingsActivity.KEY_NOTATION, 0)

    companion object {
        const val PREFS_NAME = "settings"
    }
}
