package com.example.syncclipboard

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：从服务器下载剪贴板内容并设置到系统剪贴板。
 */
class DownloadClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val progressStyle = UiStyleStorage.loadProgressStyle(this)
        if (progressStyle != UiStyleStorage.STYLE_FLOATING_WINDOW) {
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

        // 悬浮窗模式下：通过透明桥接 Activity 触发，确保能收起 Quick Settings 面板。
        val intent = Intent(this, ClipboardBridgeActivity::class.java).apply {
            putExtra(ClipboardBridgeActivity.EXTRA_MODE, ClipboardBridgeActivity.MODE_DOWNLOAD)
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
