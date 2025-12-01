package com.example.syncclipboard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 系统分享入口：
 * - 接收 text/plain
 * - 立即启动 ProgressActivity 执行上传，然后结束自己。
 */
class ShareReceiveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val type = intent?.type
        if (Intent.ACTION_SEND == action && type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val progressIntent = Intent(this, ProgressActivity::class.java).apply {
                putExtra(ProgressActivity.EXTRA_OPERATION, ProgressActivity.OP_UPLOAD_SHARED_TEXT)
                putExtra(ProgressActivity.EXTRA_SHARED_TEXT, sharedText)
            }
            startActivity(progressIntent)
        }

        finish()
    }
}

