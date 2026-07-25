package com.zhongguoxiangqi

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 应用 Application 类.
 */
class ZhongguoXiangqiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 启用 Material You 动态色彩(Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}