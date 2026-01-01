package com.example.syncclipboard

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 快捷设置磁贴：从服务器下载剪贴板内容并设置到系统剪贴板。
 */
class DownloadClipboardTileService : TileService() {

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
        if (progressStyle != UiStyleStorage.STYLE_FLOATING_WINDOW && progressStyle != UiStyleStorage.STYLE_NOTIFICATION) {
            val intent = Intent(this, ProgressActivity::class.java).apply {
                putExtra(ProgressActivity.EXTRA_OPERATION, ProgressActivity.OP_DOWNLOAD_CLIPBOARD)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapse(intent)
            }
            return
        }

        // 悬浮窗/通知 模式下：通过透明桥接 Activity 触发，确保能收起 Quick Settings 面板。
        val intent = Intent(this, ClipboardBridgeActivity::class.java).apply {
            putExtra(
                ClipboardBridgeActivity.EXTRA_MODE,
                if (progressStyle == UiStyleStorage.STYLE_NOTIFICATION)
                    ClipboardBridgeActivity.MODE_NOTIFICATION_DOWNLOAD
                else
                    ClipboardBridgeActivity.MODE_DOWNLOAD
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
