package com.example.syncclipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用于在前台执行一次上传/下载/测试操作，并显示进度和结果。
 */
class ProgressActivity : AppCompatActivity() {

    /**
     * 简单封装一次操作的结果：
     * - success 表示是否成功
     * - message 为要显示在界面上的文案
     */
    data class OperationResult(
        val success: Boolean,
        val message: String
    )

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

        // 对于上传/下载/分享/测试，统一采用：
        // - 弹出一个小的对话框样式 Activity 作为前台界面
        // - 自动执行操作，不再需要额外点击
        // - 完成后只弹 Toast 提示结果，然后结束当前任务
        buttonAction.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                performOperation(operation)
            }

            Toast.makeText(
                this@ProgressActivity,
                result.message,
                Toast.LENGTH_SHORT
            ).show()
            finishAffinity()
        }
    }

    private fun performOperation(operation: String): OperationResult {
        val config = ConfigStorage.loadConfig(this)
            ?: return OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_config_missing)
                )
            )

        return when (operation) {
            OP_UPLOAD_CLIPBOARD -> uploadClipboard(config)
            OP_DOWNLOAD_CLIPBOARD -> downloadClipboard(config)
            OP_UPLOAD_SHARED_TEXT -> uploadSharedText(config)
            OP_TEST_CONNECTION -> testConnection(config)
            else -> OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_config_missing)
                )
            )
        }
    }

    private fun uploadClipboard(config: ServerConfig): OperationResult {
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboardManager.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }
        if (text.isEmpty()) {
            return OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_clipboard_empty)
                )
            )
        }

        val result = SyncClipboardApi.uploadText(config, text)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.toast_upload_success)
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    result.errorMessage ?: "未知错误"
                )
            )
        }
    }

    private fun downloadClipboard(config: ServerConfig): OperationResult {
        val result = SyncClipboardApi.downloadText(config)
        return if (result.success && result.data != null) {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncClipboard", result.data)
            clipboardManager.setPrimaryClip(clip)
            OperationResult(
                success = true,
                message = getString(R.string.toast_download_success)
            )
        } else {
            val message = result.errorMessage ?: getString(R.string.error_server_not_text)
            OperationResult(
                success = false,
                message = getString(R.string.toast_error_prefix, message)
            )
        }
    }

    private fun uploadSharedText(config: ServerConfig): OperationResult {
        val sharedText = intent.getStringExtra(EXTRA_SHARED_TEXT) ?: ""
        if (sharedText.isEmpty()) {
            return OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_clipboard_empty)
                )
            )
        }
        val result = SyncClipboardApi.uploadText(config, sharedText)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.toast_upload_success)
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    result.errorMessage ?: "未知错误"
                )
            )
        }
    }

    private fun testConnection(config: ServerConfig): OperationResult {
        val result = SyncClipboardApi.testConnection(config)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.result_success)
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    result.errorMessage ?: "未知错误"
                )
            )
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
