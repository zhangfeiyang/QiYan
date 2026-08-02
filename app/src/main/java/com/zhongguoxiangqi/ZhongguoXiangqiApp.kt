package com.zhongguoxiangqi

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.zhongguoxiangqi.ui.settings.AppSettings

/**
 * 应用 Application 类.
 */
class ZhongguoXiangqiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 默认英文, 除非用户自己在首页切过语言.
        //
        // getApplicationLocales() 返回空列表当且仅当从没设置过 per-app
        // locale —— 包括全新安装、以及用户从未点过语言切换按钮的情况。
        // 一旦用户点过(哪怕切回了跟系统一致的语言), AppCompat 会把选择
        // 持久化, 这里就不会再是空列表, 也就不会覆盖用户的选择。不依赖
        // 设备系统语言, 因为需求是"默认英文", 不是"跟随系统"。
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
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