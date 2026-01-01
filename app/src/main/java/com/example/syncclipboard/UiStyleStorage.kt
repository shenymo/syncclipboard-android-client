package com.example.syncclipboard

import android.content.Context

/**
 * 用于存储 ProgressActivity 的界面样式选择：
 * - 悬浮窗样式
 *
 * 说明：当前版本仅保留“悬浮窗样式”。历史的 BottomSheet/通知 值会被自动迁移为悬浮窗。
 */
object UiStyleStorage {

    private const val PREF_NAME = "ui_style_prefs"
    private const val KEY_PROGRESS_STYLE = "progress_style"
    private const val KEY_BOTTOM_SHEET_CANCEL_OUTSIDE = "bottom_sheet_cancel_outside"
    private const val KEY_LONG_PRESS_CLOSE_SECONDS = "long_press_close_seconds"
    private const val KEY_AUTO_CLOSE_DELAY_SECONDS = "auto_close_delay_seconds"

    const val STYLE_FLOATING_WINDOW = "floating_window"
    // legacy values (kept only for reading old preferences)
    const val STYLE_BOTTOM_SHEET = "bottom_sheet"
    const val STYLE_NOTIFICATION = "notification"

    fun saveProgressStyle(context: Context, style: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 强制仅保留悬浮窗样式，忽略传入值（避免外部仍然写入 legacy 样式）。
        prefs.edit().putString(KEY_PROGRESS_STYLE, STYLE_FLOATING_WINDOW).apply()
    }

    fun loadProgressStyle(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PROGRESS_STYLE, STYLE_FLOATING_WINDOW) ?: STYLE_FLOATING_WINDOW
        // 自动迁移：无论历史值是什么，统一返回悬浮窗样式。
        return when (stored) {
            STYLE_FLOATING_WINDOW -> STYLE_FLOATING_WINDOW
            STYLE_BOTTOM_SHEET,
            STYLE_NOTIFICATION,
            "dialog" -> STYLE_FLOATING_WINDOW
            else -> STYLE_FLOATING_WINDOW
        }
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
