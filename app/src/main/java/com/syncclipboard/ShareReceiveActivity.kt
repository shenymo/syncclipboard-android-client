package com.syncclipboard

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 系统分享入口：
 * - 接收 text/plain 作为文本上传
 * - 接收文件/图片（EXTRA_STREAM）作为文件上传
 * - 立即启动 FloatingOverlayService 显示悬浮窗进度，然后结束自己。
 */
class ShareReceiveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val type = intent?.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("text/")) {
                handleSendText()
            } else {
                handleSendFile()
            }
        }

        finish()
    }

    private fun handleSendText() {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        if (!PermissionUtils.checkOverlayPermission(this)) {
            PermissionUtils.requestOverlayPermission(this)
            return
        }

        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_UPLOAD_TEXT
            putExtra(FloatingOverlayService.EXTRA_TEXT, sharedText)
        }
        startService(intent)
    }

    private fun handleSendFile() {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val fileName = resolveFileName(uri) ?: uri.lastPathSegment ?: "shared_file"

        if (!PermissionUtils.checkOverlayPermission(this)) {
            PermissionUtils.requestOverlayPermission(this)
            return
        }

        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_UPLOAD_FILE
            putExtra(FloatingOverlayService.EXTRA_FILE_URI, uri.toString())
            putExtra(FloatingOverlayService.EXTRA_FILE_NAME, fileName)
        }
        startService(intent)
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
