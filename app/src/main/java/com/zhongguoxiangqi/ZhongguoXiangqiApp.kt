package com.zhongguoxiangqi

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 应用 Application 类.
 */
class ZhongguoXiangqiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 不再调用 DynamicColors.applyToActivitiesIfAvailable():
        // 那会用用户壁纸推导出的 Material You 配色去覆盖 colorPrimary 一类
        // 角色, 于是同一个 App 在不同手机上按钮是不同颜色的, 我们精心定的
        // 行动色绿反而说了不算. 品牌配色要可控, 就不能把它交给壁纸.
        AppCompatDelegate.setDefaultNightMode(
            when (AppSettings(this).themeMode) {
                1 -> AppCompatDelegate.MODE_NIGHT_YES
                2 -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}