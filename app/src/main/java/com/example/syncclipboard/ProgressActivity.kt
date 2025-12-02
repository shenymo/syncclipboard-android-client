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

        if (operation == OP_UPLOAD_CLIPBOARD) {
            // 从磁贴上传剪贴板时，为了满足系统“前台 + 用户交互”要求，
            // 等用户点击按钮后再访问剪贴板。
            buttonAction.visibility = View.VISIBLE
            buttonAction.setOnClickListener {
                buttonAction.isEnabled = false
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        performOperation(operation)
                    }
                    textStatus.text = result.message

                    if (result.success) {
                        // 成功时自动退出整个应用任务，避免回到设置主界面
                        Toast.makeText(
                            this@ProgressActivity,
                            result.message,
                            Toast.LENGTH_SHORT
                        ).show()
                        finishAffinity()
                    } else {
                        // 失败时停留在当前界面，允许用户查看错误信息或重试
                        buttonAction.isEnabled = true
                    }
                }
            }
        } else {
            buttonAction.visibility = View.GONE
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    performOperation(operation)
                }
                textStatus.text = result.message

                // 从磁贴或分享入口触发时：
                // - 成功：自动退出应用任务，回到之前的应用/桌面
                // - 失败：停留在失败界面
                if (result.success) {
                    Toast.makeText(
                        this@ProgressActivity,
                        result.message,
                        Toast.LENGTH_SHORT
                    ).show()
                    finishAffinity()
                }
            }
        }
    }

    private fun performOperation(operation: String): OperationResult {
        val config = ConfigStorage.loadConfig(this)
            ?: return OperationResult(
                success = false,
                message = getString(
                    R.string.result_failed,
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
                    R.string.result_failed,
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
                    R.string.result_failed,
                    getString(R.string.error_clipboard_empty)
                )
            )
        }

        val result = SyncClipboardApi.uploadText(config, text)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.result_success)
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.result_failed,
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
                message = getString(R.string.result_success)
            )
        } else {
            val message = result.errorMessage ?: getString(R.string.error_server_not_text)
            OperationResult(
                success = false,
                message = getString(R.string.result_failed, message)
            )
        }
    }

    private fun uploadSharedText(config: ServerConfig): OperationResult {
        val sharedText = intent.getStringExtra(EXTRA_SHARED_TEXT) ?: ""
        if (sharedText.isEmpty()) {
            return OperationResult(
                success = false,
                message = getString(
                    R.string.result_failed,
                    getString(R.string.error_clipboard_empty)
                )
            )
        }
        val result = SyncClipboardApi.uploadText(config, sharedText)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.result_success)
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.result_failed,
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
                    R.string.result_failed,
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
