package com.ven.assists.simple.quickball

import android.content.Context
import com.blankj.utilcode.util.Utils

/** 悬浮小球显示开关，默认关闭 */
object FloatingQuickBallPrefs {
    private const val PREFS_NAME = "floating_quick_ball"
    private const val KEY_ENABLED = "quick_ball_enabled"

    fun isQuickBallEnabled(): Boolean =
        Utils.getApp().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setQuickBallEnabled(enabled: Boolean) {
        Utils.getApp().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
