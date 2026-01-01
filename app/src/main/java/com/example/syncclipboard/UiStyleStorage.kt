package com.example.syncclipboard

import android.content.Context

/**
 * 用于存储 ProgressActivity 的界面样式选择：
 * - 对话框样式（默认）
 * - BottomSheet 底部弹出样式
 */
object UiStyleStorage {

    private const val KEY_PROGRESS_STYLE = "progress_style"
    private const val KEY_BOTTOM_SHEET_CANCEL_OUTSIDE = "bottom_sheet_cancel_outside"
    private const val KEY_LONG_PRESS_CLOSE_SECONDS = "long_press_close_seconds"
    private const val KEY_AUTO_CLOSE_DELAY_SECONDS = "auto_close_delay_seconds"

    const val STYLE_DIALOG = "dialog"
    const val STYLE_BOTTOM_SHEET = "bottom_sheet"
    const val STYLE_FLOATING_WINDOW = "floating_window"

    fun saveProgressStyle(context: Context, style: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PROGRESS_STYLE, style).apply()
    }

    fun loadProgressStyle(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PROGRESS_STYLE, STYLE_DIALOG) ?: STYLE_DIALOG
    }

    fun saveBottomSheetCancelOnTouchOutside(context: Context, allow: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BOTTOM_SHEET_CANCEL_OUTSIDE, allow).apply()
    }

    fun loadBottomSheetCancelOnTouchOutside(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BOTTOM_SHEET_CANCEL_OUTSIDE, false)
    }

    fun saveLongPressCloseSeconds(context: Context, seconds: Float) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_LONG_PRESS_CLOSE_SECONDS, seconds).apply()
    }

    fun loadLongPressCloseSeconds(context: Context): Float {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // Default 2.0 seconds
        return prefs.getFloat(KEY_LONG_PRESS_CLOSE_SECONDS, 2.0f)
    }

    fun saveAutoCloseDelaySeconds(context: Context, seconds: Float) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_AUTO_CLOSE_DELAY_SECONDS, seconds).apply()
    }

    fun loadAutoCloseDelaySeconds(context: Context): Float {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // Default 3.0 seconds
        return prefs.getFloat(KEY_AUTO_CLOSE_DELAY_SECONDS, 3.0f)
    }
}
