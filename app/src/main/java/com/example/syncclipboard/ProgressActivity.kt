package com.example.syncclipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用于在前台执行一次上传/下载/测试操作，并显示进度和结果。
 */
class ProgressActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress)

        val textStatus = findViewById<TextView>(R.id.textStatus)
        val buttonAction = findViewById<Button>(R.id.buttonAction)
        val operation = intent.getStringExtra(EXTRA_OPERATION) ?: OP_UPLOAD_CLIPBOARD

        textStatus.text = when (operation) {
            OP_UPLOAD_CLIPBOARD -> getString(R.string.progress_upload_clipboard)
            OP_DOWNLOAD_CLIPBOARD -> getString(R.string.progress_download_clipboard)
            OP_UPLOAD_SHARED_TEXT -> getString(R.string.progress_upload_shared)
            OP_TEST_CONNECTION -> getString(R.string.progress_test_connection)
            else -> getString(R.string.progress_upload_clipboard)
        }

        if (operation == OP_UPLOAD_CLIPBOARD) {
            // 从磁贴上传剪贴板时，为了满足系统“前台 + 用户交互”要求，
            // 等用户点击按钮后再访问剪贴板。
            buttonAction.visibility = View.VISIBLE
            buttonAction.setOnClickListener {
                buttonAction.isEnabled = false
                lifecycleScope.launch {
                    val message = withContext(Dispatchers.IO) {
                        performOperation(operation)
                    }
                    textStatus.text = message
                    delay(1500)
                    finish()
                }
            }
        } else {
            buttonAction.visibility = View.GONE
            lifecycleScope.launch {
                val message = withContext(Dispatchers.IO) {
                    performOperation(operation)
                }
                textStatus.text = message
                delay(1500)
                finish()
            }
        }
    }

    private fun performOperation(operation: String): String {
        val config = ConfigStorage.loadConfig(this)
            ?: return getString(R.string.error_config_missing)

        return when (operation) {
            OP_UPLOAD_CLIPBOARD -> uploadClipboard(config)
            OP_DOWNLOAD_CLIPBOARD -> downloadClipboard(config)
            OP_UPLOAD_SHARED_TEXT -> uploadSharedText(config)
            OP_TEST_CONNECTION -> testConnection(config)
            else -> getString(R.string.error_config_missing)
        }
    }

    private fun uploadClipboard(config: ServerConfig): String {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboardManager.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }
        if (text.isEmpty()) {
            return getString(R.string.error_clipboard_empty)
        }

        val result = SyncClipboardApi.uploadText(config, text)
        return if (result.success) {
            getString(R.string.result_success)
        } else {
            getString(R.string.result_failed, result.errorMessage ?: "未知错误")
        }
    }

    private fun downloadClipboard(config: ServerConfig): String {
        val result = SyncClipboardApi.downloadText(config)
        return if (result.success && result.data != null) {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncClipboard", result.data)
            clipboardManager.setPrimaryClip(clip)
            getString(R.string.result_success)
        } else {
            val message = result.errorMessage ?: getString(R.string.error_server_not_text)
            getString(R.string.result_failed, message)
        }
    }

    private fun uploadSharedText(config: ServerConfig): String {
        val sharedText = intent.getStringExtra(EXTRA_SHARED_TEXT) ?: ""
        if (sharedText.isEmpty()) {
            return getString(R.string.error_clipboard_empty)
        }
        val result = SyncClipboardApi.uploadText(config, sharedText)
        return if (result.success) {
            getString(R.string.result_success)
        } else {
            getString(R.string.result_failed, result.errorMessage ?: "未知错误")
        }
    }

    private fun testConnection(config: ServerConfig): String {
        val result = SyncClipboardApi.testConnection(config)
        return if (result.success) {
            getString(R.string.result_success)
        } else {
            getString(R.string.result_failed, result.errorMessage ?: "未知错误")
        }
    }

    companion object {
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_SHARED_TEXT = "shared_text"

        const val OP_UPLOAD_CLIPBOARD = "upload_clipboard"
        const val OP_DOWNLOAD_CLIPBOARD = "download_clipboard"
        const val OP_UPLOAD_SHARED_TEXT = "upload_shared_text"
        const val OP_TEST_CONNECTION = "test_connection"
    }
}
