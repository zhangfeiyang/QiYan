package com.zhongguoxiangqi

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 应用 Application 类.
 */
class ZhongguoXiangqiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(
            when (AppSettings(this).themeMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        // 提前加载 Pikafish JNI .so, 避免在引擎初始化时与 UI 线程竞争
    try { System.loadLibrary("pikafish") } catch (_: Exception) {}
    }
}