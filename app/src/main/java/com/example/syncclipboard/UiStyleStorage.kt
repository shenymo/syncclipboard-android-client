package com.example.syncclipboard

import android.content.Context

/**
 * 用于存储 ProgressActivity 的界面样式选择：
 * - 对话框样式（默认）
 * - BottomSheet 底部弹出样式
 */
object UiStyleStorage {

    private const val PREF_NAME = "syncclipboard_ui"
    private const val KEY_PROGRESS_STYLE = "progress_style"
    private const val KEY_BOTTOM_SHEET_CANCEL_ON_TOUCH_OUTSIDE = "bottom_sheet_cancel_outside"

    const val STYLE_DIALOG = "dialog"
    const val STYLE_BOTTOM_SHEET = "bottom_sheet"

    fun saveProgressStyle(context: Context, style: String) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_PROGRESS_STYLE, style)
            .apply()
    }

    fun loadProgressStyle(context: Context): String {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_PROGRESS_STYLE, STYLE_DIALOG) ?: STYLE_DIALOG
    }

    /**
     * 是否允许点击 BottomSheet 外部空白区域关闭弹窗。
     * 默认不允许（false），以避免误触。
     */
    fun saveBottomSheetCancelOnTouchOutside(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_BOTTOM_SHEET_CANCEL_ON_TOUCH_OUTSIDE, enabled)
            .apply()
    }

    fun loadBottomSheetCancelOnTouchOutside(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_BOTTOM_SHEET_CANCEL_ON_TOUCH_OUTSIDE, false)
    }
}
