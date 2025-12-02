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

    // 记录当前要执行的操作类型，以及是否已经启动过，避免同一个实例多次执行。
    private var started = false
    private var currentOperation: String = OP_UPLOAD_CLIPBOARD
    private var requireUserTap = false

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

        currentOperation = operation

        if (operation == OP_UPLOAD_CLIPBOARD) {
            // 上传本机剪贴板必须由用户在本应用内点击触发，
            // 否则 Android 会认为是后台读剪贴板而拒绝访问。
            requireUserTap = true
            buttonAction.visibility = View.VISIBLE
            buttonAction.text = getString(R.string.button_start)
            buttonAction.setOnClickListener {
                if (started) return@setOnClickListener
                started = true

                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        performOperation(OP_UPLOAD_CLIPBOARD)
                    }
                    Toast.makeText(
                        this@ProgressActivity,
                        result.message,
                        Toast.LENGTH_SHORT
                    ).show()
                    finishAffinity()
                }
            }
        } else {
            // 下载剪贴板 / 分享上传 / 测试连接 不需要访问本机剪贴板，
            // 可以在 onResume 中自动执行。
            requireUserTap = false
            buttonAction.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()

        // 对于上传剪贴板的场景，必须等用户点击按钮才执行，
        // 避免在应用未获得聚焦或无用户手势时访问剪贴板被系统拒绝。
        if (requireUserTap) return

        // 只在第一次恢复时启动一次操作，避免因重建/旋转等重复执行
        if (started) return
        started = true

        // 使用 post 把执行逻辑放到界面绘制之后，进一步确保已获得窗口焦点
        window.decorView.post {
            val operation = currentOperation
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
