package com.example.syncclipboard

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity

/**
 * 系统分享入口：
 * - 接收 text/plain 作为文本上传
 * - 接收文件/图片（EXTRA_STREAM）作为文件上传
 * - 立即启动 ProgressActivity 显示上传进度和结果，然后结束自己。
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
        val progressIntent = Intent(this, ProgressActivity::class.java).apply {
            putExtra(ProgressActivity.EXTRA_OPERATION, ProgressActivity.OP_UPLOAD_SHARED_TEXT)
            putExtra(ProgressActivity.EXTRA_SHARED_TEXT, sharedText)
            // 在独立任务中显示对话框，避免唤起设置主界面
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(progressIntent)
    }

    private fun handleSendFile() {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val fileName = resolveFileName(uri) ?: uri.lastPathSegment ?: "shared_file"

        val progressIntent = Intent(this, ProgressActivity::class.java).apply {
            putExtra(ProgressActivity.EXTRA_OPERATION, ProgressActivity.OP_UPLOAD_FILE)
            putExtra(ProgressActivity.EXTRA_FILE_URI, uri.toString())
            putExtra(ProgressActivity.EXTRA_FILE_NAME, fileName)
            // 在独立任务中显示对话框，避免唤起设置主界面
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(progressIntent)
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
