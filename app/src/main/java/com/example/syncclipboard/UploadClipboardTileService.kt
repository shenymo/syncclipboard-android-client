package com.example.syncclipboard

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：上传当前系统剪贴板。
 * 悬浮窗模式下：启动短暂透明 Activity 读取剪贴板，再交给前台 Service 执行上传并展示悬浮窗。
 */
class UploadClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardBridgeActivity::class.java).apply {
            putExtra(ClipboardBridgeActivity.EXTRA_MODE, ClipboardBridgeActivity.MODE_UPLOAD)
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
