package com.example.syncclipboard

import android.content.Intent
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：上传当前系统剪贴板。
 * 这里只负责启动前台 ProgressActivity，具体逻辑在 Activity 中实现。
 */
class UploadClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ProgressActivity::class.java).apply {
            putExtra(ProgressActivity.EXTRA_OPERATION, ProgressActivity.OP_UPLOAD_CLIPBOARD)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndCollapse(intent)
    }
}

