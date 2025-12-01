package com.example.syncclipboard

import android.content.Intent
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：从服务器下载剪贴板内容并设置到系统剪贴板。
 */
class DownloadClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ProgressActivity::class.java).apply {
            putExtra(ProgressActivity.EXTRA_OPERATION, ProgressActivity.OP_DOWNLOAD_CLIPBOARD)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndCollapse(intent)
    }
}

