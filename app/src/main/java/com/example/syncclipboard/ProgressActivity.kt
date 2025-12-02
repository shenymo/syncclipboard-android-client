package com.example.syncclipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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

    private lateinit var textStatus: TextView
    private lateinit var textContent: TextView
    private lateinit var buttonAction: Button

    /**
     * 简单封装一次操作的结果：
     * - success 表示是否成功
     * - message 为要显示在界面上的文案
     * - content 为要展示在对话框中的文本内容（上传/下载的剪贴板）
     */
    data class OperationResult(
        val success: Boolean,
        val message: String,
        val content: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 使用对话框主题时，允许点击对话框外部区域自动关闭 Activity
        setFinishOnTouchOutside(true)

        setContentView(R.layout.activity_progress)

        textStatus = findViewById(R.id.textStatus)
        textContent = findViewById(R.id.textContent)
        buttonAction = findViewById(R.id.buttonAction)
        val operation = intent.getStringExtra(EXTRA_OPERATION) ?: OP_UPLOAD_CLIPBOARD

        currentOperation = operation

        if (operation == OP_UPLOAD_CLIPBOARD) {
            // 上传剪贴板：上传前不显示内容预览，只在按钮下方显示结果文字
            textStatus.text = ""
            // 上传本机剪贴板必须由用户在本应用内点击触发，
            // 否则 Android 会认为是后台读剪贴板而拒绝访问。
            requireUserTap = true
            buttonAction.visibility = View.VISIBLE
            buttonAction.text = getString(R.string.button_start)
            buttonAction.setOnClickListener {
                if (started) return@setOnClickListener
                started = true

                // 点击上传后，不再显示按钮，改为显示上传进度文字
                buttonAction.visibility = View.GONE
                textStatus.text = getString(R.string.progress_upload_clipboard)

                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        performOperation(OP_UPLOAD_CLIPBOARD)
                    }

                    // 上传完成后只显示结果文字，不展示剪贴板内容
                    textStatus.text = result.message
                    textContent.visibility = View.GONE

                    Toast.makeText(
                        this@ProgressActivity,
                        result.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            textStatus.text = when (operation) {
                OP_DOWNLOAD_CLIPBOARD -> getString(R.string.progress_download_clipboard)
                OP_UPLOAD_SHARED_TEXT -> getString(R.string.progress_upload_shared)
                OP_UPLOAD_FILE -> getString(R.string.progress_upload_file)
                OP_TEST_CONNECTION -> getString(R.string.progress_test_connection)
                else -> getString(R.string.progress_upload_clipboard)
            }
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

                // 下载剪贴板：标题为“已写入剪贴板”或“写入剪贴板失败”
                if (operation == OP_DOWNLOAD_CLIPBOARD) {
                    if (result.success) {
                        textStatus.text = getString(R.string.toast_download_success)
                        if (!result.content.isNullOrEmpty()) {
                            textContent.visibility = View.VISIBLE
                            textContent.text = result.content
                        } else {
                            textContent.visibility = View.GONE
                        }
                    } else {
                        textStatus.text = getString(R.string.dialog_download_failed_title)
                        textContent.visibility = View.VISIBLE
                        textContent.text = result.message
                    }
                } else {
                    // 分享上传 / 测试连接：沿用通用文案，显示结果和内容
                    // 上传剪贴板则只显示结果，不显示内容
                    textStatus.text = result.message
                    if (operation == OP_UPLOAD_CLIPBOARD) {
                        textContent.visibility = View.GONE
                    } else {
                        if (!result.content.isNullOrEmpty()) {
                            textContent.visibility = View.VISIBLE
                            textContent.text = result.content
                        } else {
                            textContent.visibility = View.GONE
                        }
                    }
                }

                Toast.makeText(
                    this@ProgressActivity,
                    result.message,
                    Toast.LENGTH_SHORT
                ).show()
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
                ),
                content = null
            )

        return when (operation) {
            OP_UPLOAD_CLIPBOARD -> uploadClipboard(config)
            OP_DOWNLOAD_CLIPBOARD -> downloadClipboard(config)
            OP_UPLOAD_SHARED_TEXT -> uploadSharedText(config)
            OP_UPLOAD_FILE -> uploadSharedFile(config)
            OP_TEST_CONNECTION -> testConnection(config)
            else -> OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_config_missing)
                ),
                content = null
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
                ),
                content = null
            )
        }

        val result = SyncClipboardApi.uploadText(config, text)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.toast_upload_success),
                content = text
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    result.errorMessage ?: "未知错误"
                ),
                content = text
            )
        }
    }

    private fun downloadClipboard(config: ServerConfig): OperationResult {
        // 先获取完整 Profile，根据 Type 决定是文本还是文件
        val profileResult = SyncClipboardApi.getClipboardProfile(config)
        if (!profileResult.success || profileResult.data == null) {
            return OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    profileResult.errorMessage ?: getString(R.string.error_server_not_text)
                ),
                content = null
            )
        }

        val profile = profileResult.data
        return when (profile.type) {
            "Text" -> {
                val text = profile.clipboard ?: ""
                if (text.isEmpty()) {
                    return OperationResult(
                        success = false,
                        message = getString(
                            R.string.toast_error_prefix,
                            getString(R.string.error_server_not_text)
                        ),
                        content = null
                    )
                }
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SyncClipboard", text)
                clipboardManager.setPrimaryClip(clip)
                OperationResult(
                    success = true,
                    message = getString(R.string.toast_download_success),
                    content = text
                )
            }
            "File" -> {
                val fileName = profile.file ?: return OperationResult(
                    success = false,
                    message = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_server_not_text)
                    ),
                    content = null
                )
                downloadFileToDownloadDir(config, fileName)
            }
            else -> {
                OperationResult(
                    success = false,
                    message = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_server_not_text)
                    ),
                    content = null
                )
            }
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
                ),
                content = null
            )
        }
        val result = SyncClipboardApi.uploadText(config, sharedText)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.toast_upload_success),
                content = sharedText
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    result.errorMessage ?: "未知错误"
                ),
                content = sharedText
            )
        }
    }

    private fun uploadSharedFile(config: ServerConfig): OperationResult {
        val uriString = intent.getStringExtra(EXTRA_FILE_URI) ?: return OperationResult(
            success = false,
            message = getString(
                R.string.toast_error_prefix,
                getString(R.string.error_file_missing)
            ),
            content = null
        )
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "shared_file"

        return try {
            val uri = Uri.parse(uriString)
            val input = contentResolver.openInputStream(uri)
                ?: return OperationResult(
                    success = false,
                    message = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_file_open_failed)
                    ),
                    content = null
                )

            input.use { stream ->
                val result = SyncClipboardApi.uploadFile(config, fileName, stream)
                if (result.success) {
                    OperationResult(
                        success = true,
                        message = getString(R.string.toast_upload_file_success),
                        content = fileName
                    )
                } else {
                    OperationResult(
                        success = false,
                        message = getString(
                            R.string.toast_error_prefix,
                            result.errorMessage ?: getString(R.string.error_file_upload_failed)
                        ),
                        content = fileName
                    )
                }
            }
        } catch (e: Exception) {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    e.message ?: getString(R.string.error_file_upload_failed)
                ),
                content = fileName
            )
        }
    }

    /**
     * 将服务器上的文件下载到系统公用 Download 目录。
     */
    private fun downloadFileToDownloadDir(config: ServerConfig, fileName: String): OperationResult {
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return OperationResult(
                    success = false,
                    message = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_file_create_failed)
                    ),
                    content = null
                )

            contentResolver.openOutputStream(uri)?.use { out ->
                val result = SyncClipboardApi.downloadFileToStream(config, fileName, out)
                if (!result.success) {
                    return OperationResult(
                        success = false,
                        message = getString(
                            R.string.toast_error_prefix,
                            result.errorMessage ?: getString(R.string.error_file_download_failed)
                        ),
                        content = null
                    )
                }
            } ?: return OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_file_create_failed)
                ),
                content = null
            )

            // 使用简短提示：文件名
            OperationResult(
                success = true,
                message = getString(R.string.toast_download_file_success),
                content = fileName
            )
        } catch (e: Exception) {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    e.message ?: getString(R.string.error_file_download_failed)
                ),
                content = null
            )
        }
    }

    private fun testConnection(config: ServerConfig): OperationResult {
        val result = SyncClipboardApi.testConnection(config)
        return if (result.success) {
            OperationResult(
                success = true,
                message = getString(R.string.result_success),
                content = null
            )
        } else {
            OperationResult(
                success = false,
                message = getString(
                    R.string.toast_error_prefix,
                    result.errorMessage ?: "未知错误"
                ),
                content = null
            )
        }
    }

    companion object {
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_SHARED_TEXT = "shared_text"
        const val EXTRA_FILE_URI = "file_uri"
        const val EXTRA_FILE_NAME = "file_name"

        const val OP_UPLOAD_CLIPBOARD = "upload_clipboard"
        const val OP_DOWNLOAD_CLIPBOARD = "download_clipboard"
        const val OP_UPLOAD_SHARED_TEXT = "upload_shared_text"
        const val OP_UPLOAD_FILE = "upload_file"
        const val OP_TEST_CONNECTION = "test_connection"
    }
}
