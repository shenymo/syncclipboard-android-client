package com.example.syncclipboard

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 快捷设置磁贴：上传当前系统剪贴板。
 * 悬浮窗模式下：启动短暂透明 Activity 读取剪贴板，再交给前台 Service 执行上传并展示悬浮窗。
 * 非悬浮窗模式下：继续使用 ProgressActivity 展示应用内界面。
 */
class UploadClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val progressStyle = UiStyleStorage.loadProgressStyle(this)
        if (
            progressStyle == UiStyleStorage.STYLE_NOTIFICATION &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
            return
        }
        val intent =
            when (progressStyle) {
                UiStyleStorage.STYLE_FLOATING_WINDOW -> Intent(this, ClipboardBridgeActivity::class.java)
                UiStyleStorage.STYLE_NOTIFICATION -> Intent(this, ClipboardBridgeActivity::class.java).apply {
                    putExtra(ClipboardBridgeActivity.EXTRA_MODE, ClipboardBridgeActivity.MODE_NOTIFICATION_PREPARE_UPLOAD)
                }
                else -> Intent(this, ProgressActivity::class.java).apply {
                    putExtra(ProgressActivity.EXTRA_OPERATION, ProgressActivity.OP_UPLOAD_CLIPBOARD)
                }
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 及以上要求使用 PendingIntent 版本
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            // 旧版本仍然可以直接使用 Intent
            startActivityAndCollapse(intent)
        }
    }
}
