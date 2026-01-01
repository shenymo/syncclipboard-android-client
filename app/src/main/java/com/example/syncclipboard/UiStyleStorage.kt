package com.example.syncclipboard

import android.content.Context

/**
 * 用于存储悬浮窗相关的偏好设置。
 */
object UiStyleStorage {

    private const val PREF_NAME = "ui_style_prefs"
    private const val KEY_LONG_PRESS_CLOSE_SECONDS = "long_press_close_seconds"
    private const val KEY_AUTO_CLOSE_DELAY_SECONDS = "auto_close_delay_seconds"

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
