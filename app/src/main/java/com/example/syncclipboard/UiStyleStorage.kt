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
}

