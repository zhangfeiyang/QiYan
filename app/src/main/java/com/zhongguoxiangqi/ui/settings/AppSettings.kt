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

    /** 主题模式: 0=跟随系统 1=深色 2=浅色 */
    val themeMode: Int get() = prefs.getInt(SettingsActivity.KEY_THEME, 0)

    companion object {
        const val PREFS_NAME = "settings"
    }
}
