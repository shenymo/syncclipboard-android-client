package com.example.syncclipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private var useBottomSheet = false
    private var bottomSheetDialog: BottomSheetDialog? = null
    private var lastDownloadedFileUri: Uri? = null
    private var lastDownloadedFileName: String? = null

    private lateinit var textStatus: TextView
    private lateinit var textContent: TextView
    private lateinit var buttonAction: Button
    private lateinit var buttonReplace: Button
    private lateinit var buttonKeepBoth: Button

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
        // 根据设置选择使用对话框样式还是 BottomSheet 样式
        useBottomSheet = UiStyleStorage.loadProgressStyle(this) == UiStyleStorage.STYLE_BOTTOM_SHEET
        if (useBottomSheet) {
            // BottomSheet 模式使用全屏透明宿主 Activity，只承载底部弹窗，不再单独显示对话框窗口。
            setTheme(R.style.Theme_SyncClipboard_BottomSheetHost)
        } else {
            // 对话框模式使用原来的半透明对话框主题。
            setTheme(R.style.Theme_SyncClipboard_Dialog)
        }

        super.onCreate(savedInstanceState)

        val operation = intent.getStringExtra(EXTRA_OPERATION) ?: OP_UPLOAD_CLIPBOARD

        if (useBottomSheet) {
            // 使用 BottomSheet 弹出样式
            val dialog = BottomSheetDialog(this)
            // 略微压暗下方界面（具体强度由主题中的 backgroundDimAmount 控制，这里保持默认）
            // 可以按需调用 dialog.window?.setDimAmount(0.2f) 进一步覆盖
            // 根据设置决定是否允许点击外部空白区域关闭 BottomSheet
            val cancelOnOutside = UiStyleStorage.loadBottomSheetCancelOnTouchOutside(this)
            dialog.setCanceledOnTouchOutside(cancelOnOutside)
            val view = layoutInflater.inflate(R.layout.activity_progress, null)
            dialog.setContentView(view)

            textStatus = view.findViewById(R.id.textStatus)
            textContent = view.findViewById(R.id.textContent)
            buttonAction = view.findViewById(R.id.buttonAction)
            buttonReplace = view.findViewById(R.id.buttonReplace)
            buttonKeepBoth = view.findViewById(R.id.buttonKeepBoth)

            dialog.setOnDismissListener {
                // 底部弹窗关闭时结束 Activity，行为类似对话框
                finish()
            }
            dialog.show()
            bottomSheetDialog = dialog
        } else {
            // 使用对话框主题时，允许点击对话框外部区域自动关闭 Activity
            setFinishOnTouchOutside(true)

            setContentView(R.layout.activity_progress)

            textStatus = findViewById(R.id.textStatus)
            textContent = findViewById(R.id.textContent)
            buttonAction = findViewById(R.id.buttonAction)
            buttonReplace = findViewById(R.id.buttonReplace)
            buttonKeepBoth = findViewById(R.id.buttonKeepBoth)
        }

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

                    // 对话框样式下可以额外弹出一条短 Toast；BottomSheet 已在界面内展示结果，无需 Toast
                    if (!useBottomSheet) {
                        Toast.makeText(
                            this@ProgressActivity,
                            result.message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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

    override fun onDestroy() {
        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null
        super.onDestroy()
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

                // 下载剪贴板 / 文件：根据结果显示“已写入剪贴板”或“文件已下载”等文案
                if (operation == OP_DOWNLOAD_CLIPBOARD) {
                    if (result.success) {
                        // 如果存在 lastDownloadedFileUri，说明本次是“文件下载”场景
                        if (lastDownloadedFileUri != null) {
                            // 标题显示“已保存至 路径…”
                            textStatus.text = result.message
                            // 内容区域只展示文件名
                            val fileName = lastDownloadedFileName ?: result.content ?: ""
                            if (fileName.isNotEmpty()) {
                                textContent.visibility = View.VISIBLE
                                textContent.text = fileName
                            } else {
                                textContent.visibility = View.GONE
                            }
                            // 显示“打开”按钮，点击后调用系统根据类型选择可打开的应用
                            buttonAction.visibility = View.VISIBLE
                            buttonAction.text = getString(R.string.button_open_file)
                            buttonAction.setOnClickListener {
                                openDownloadedFile()
                            }
                        } else {
                            // 文本剪贴板下载：沿用原有显示逻辑
                            textStatus.text = result.message
                            if (!result.content.isNullOrEmpty()) {
                                textContent.visibility = View.VISIBLE
                                textContent.text = result.content
                            } else {
                                textContent.visibility = View.GONE
                            }
                            buttonAction.visibility = View.GONE
                        }
                    } else {
                        textStatus.text = getString(R.string.dialog_download_failed_title)
                        textContent.visibility = View.VISIBLE
                        textContent.text = result.message
                        buttonAction.visibility = View.GONE
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

                // 对话框样式下可以弹出 Toast；BottomSheet 模式下不再弹 Toast，避免遮挡底部内容
                if (!useBottomSheet) {
                    Toast.makeText(
                        this@ProgressActivity,
                        result.message,
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
        // 先获取完整 Profile，根据 File / Type 决定是文件还是文本。
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
        // 优先根据 File 字段判断是否为文件模式：只要服务器返回了 File 名，就按文件处理，
        // 避免 Type 值不规范（例如 Image、自定义字符串）导致误判。
        if (!profile.file.isNullOrEmpty()) {
            return downloadFileToDownloadDir(config, profile.file)
        }

        // 其次再看 Text 模式
        if (profile.type == "Text") {
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
            return OperationResult(
                success = true,
                message = getString(R.string.toast_download_success),
                content = text
            )
        }

        // 既不是 File，也不是 Text，则提示不支持
        return OperationResult(
            success = false,
            message = getString(
                R.string.toast_error_prefix,
                getString(R.string.error_server_not_text)
            ),
            content = null
        )
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

            // 步骤 1：在 UI 上提示“准备上传文件”
            runOnUiThread {
                textStatus.text = "正在准备上传文件…"
                textContent.visibility = View.GONE
            }

            // 尝试获取文件大小，用于更准确显示上传进度；失败则记为未知大小
            var totalBytes: Long = -1L
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (index >= 0) {
                            totalBytes = it.getLong(index)
                        }
                    }
                }
            }

            val startTime = System.currentTimeMillis()

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
                val result = SyncClipboardApi.uploadFile(
                    config,
                    fileName,
                    stream,
                    totalBytes
                ) { uploaded, total ->
                    // 上传进度回调：在 UI 线程上更新文案
                    runOnUiThread {
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val elapsedSec = if (elapsedMs <= 0) 1 else elapsedMs / 1000
                        val speedBytesPerSec = if (elapsedSec <= 0) uploaded else uploaded / elapsedSec
                        val progressText = buildFileUploadProgressText(
                            uploadedBytes = uploaded,
                            totalBytes = total,
                            speedBytesPerSec = speedBytesPerSec
                        )
                        textStatus.text = progressText
                        textContent.visibility = View.GONE
                    }
                }
                if (result.success) {
                    // 步骤 3：文件上传完毕，正在等待服务器确认剪贴板状态更新
                    runOnUiThread {
                        textStatus.text = "上传完成，正在更新服务器状态…"
                        textContent.visibility = View.GONE
                    }
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
            val startTime = System.currentTimeMillis()

            // 步骤 1：在 UI 上提示正在准备下载文件
            runOnUiThread {
                textStatus.text = "正在准备下载文件…"
                textContent.visibility = View.GONE
            }

            // 下载目录绝对路径，用于提示“已保存至 …”
            val downloadDirPath = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .absolutePath

            // 先检查是否存在同名文件
            val existingUri = findExistingDownloadEntry(fileName)

            // 根据是否有同名文件以及用户选择，确定最终要使用的文件名和目标 Uri
            val (targetUri, finalFileName) = if (existingUri == null) {
                // 无同名文件，直接按原文件名创建新条目
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
                uri to fileName
            } else {
                // 已存在同名文件，询问用户是“替换”还是“保留”
                val decision = waitUserDecisionForFileConflict(fileName)
                if (decision == FileConflictDecision.REPLACE) {
                    // 替换：直接覆盖已有条目内容
                    existingUri to fileName
                } else {
                    // 保留：生成一个不修改后缀的新文件名，例如 "name (2).ext"
                    val newName = generateNonConflictingDownloadName(fileName)
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, newName)
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
                    uri to newName
                }
            }

            contentResolver.openOutputStream(targetUri, "w")?.use { out ->
                val result = SyncClipboardApi.downloadFileToStream(
                    config,
                    finalFileName,
                    out
                ) { downloaded, total ->
                    // 下载进度回调：在 UI 线程上更新文案
                    runOnUiThread {
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val elapsedSec = if (elapsedMs <= 0) 1 else elapsedMs / 1000
                        val speedBytesPerSec = if (elapsedSec <= 0) downloaded else downloaded / elapsedSec
                        val progressText = buildFileDownloadProgressText(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            speedBytesPerSec = speedBytesPerSec
                        )
                        textStatus.text = progressText
                        textContent.visibility = View.GONE
                    }
                }
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

            // 记录本次下载的文件信息，便于在界面上展示文件名并支持“打开”按钮
            lastDownloadedFileUri = targetUri
            lastDownloadedFileName = finalFileName

            OperationResult(
                success = true,
                // 标题：已保存至 "路径"
                message = "已保存至 \"$downloadDirPath\"",
                // 内容：只显示文件名
                content = finalFileName
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

    /**
     * 将字节数格式化为 B / KB / MB 文本，用于显示下载进度和速度。
     */
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    private fun buildFileDownloadProgressText(
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ): String {
        val downloaded = formatSize(downloadedBytes)
        val total = if (totalBytes > 0) formatSize(totalBytes) else "未知大小"
        val speed = formatSize(speedBytesPerSec) + "/s"
        return "正在下载文件… $downloaded / $total ($speed)"
    }

    private fun buildFileUploadProgressText(
        uploadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ): String {
        val uploaded = formatSize(uploadedBytes)
        val total = if (totalBytes > 0) formatSize(totalBytes) else "未知大小"
        val speed = formatSize(speedBytesPerSec) + "/s"
        return "正在上传文件… $uploaded / $total ($speed)"
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

    /**
     * 文件名冲突时用户的选择：替换或保留。
     */
    private enum class FileConflictDecision {
        REPLACE,
        KEEP_BOTH
    }

    /**
     * 在 Download 媒体库中查找给定文件名的现有条目（若存在）。
     */
    private fun findExistingDownloadEntry(fileName: String): Uri? {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(MediaStore.Downloads._ID)
                if (idIndex >= 0) {
                    val id = cursor.getLong(idIndex)
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }
        }
        return null
    }

    /**
     * 生成一个不会与现有文件冲突的文件名，只在原始文件名基础上追加 "(2)"、"(3)" 等，
     * 不修改后缀。
     */
    private fun generateNonConflictingDownloadName(originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        val base = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val ext = if (dotIndex > 0) originalName.substring(dotIndex) else ""

        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (findExistingDownloadEntry(candidate) == null) {
                return candidate
            }
            index++
        }
    }

    /**
     * 在 UI 上询问用户对于同名文件是“替换”还是“保留”。
     * 该函数会阻塞当前后台线程，直到用户做出选择。
     */
    private fun waitUserDecisionForFileConflict(fileName: String): FileConflictDecision {
        val latch = java.util.concurrent.CountDownLatch(1)
        var decision = FileConflictDecision.REPLACE

        runOnUiThread {
            // 标题显示文件名，正文询问是否保留已存在的同名文件
            textStatus.text = fileName
            textContent.visibility = View.VISIBLE
            textContent.text = getString(R.string.file_conflict_message)

            // 显示“替换”和“保留”两个按钮，隐藏单一操作按钮
            buttonAction.visibility = View.GONE
            buttonReplace.visibility = View.VISIBLE
            buttonKeepBoth.visibility = View.VISIBLE

            buttonReplace.text = getString(R.string.button_replace)
            buttonKeepBoth.text = getString(R.string.button_keep_both)

            buttonReplace.setOnClickListener {
                decision = FileConflictDecision.REPLACE
                latch.countDown()
            }
            buttonKeepBoth.setOnClickListener {
                decision = FileConflictDecision.KEEP_BOTH
                latch.countDown()
            }
        }

        try {
            latch.await()
        } catch (_: InterruptedException) {
        }

        // 用户已选择，隐藏两个按钮，避免影响后续界面
        runOnUiThread {
            buttonReplace.visibility = View.GONE
            buttonKeepBoth.visibility = View.GONE
        }

        return decision
    }

    /**
     * 打开刚刚下载的文件：交给系统根据 MIME 类型选择可用的应用。
     */
    private fun openDownloadedFile() {
        val uri = lastDownloadedFileUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "没有可用于打开该文件的应用",
                Toast.LENGTH_SHORT
            ).show()
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
