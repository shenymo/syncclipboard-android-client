package com.example.syncclipboard

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 应用入口，用于启用基于系统莫奈取色的动态配色（Dynamic Color）。
 *
 * DynamicColors.applyToActivitiesIfAvailable(this) 会在支持的设备上，
 * 自动根据壁纸等生成一套颜色，并覆盖当前主题中的主色，保持整体风格一致。
 */
class SyncClipboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 如果系统支持莫奈取色，则对所有 Activity 应用动态配色主题覆盖。
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

