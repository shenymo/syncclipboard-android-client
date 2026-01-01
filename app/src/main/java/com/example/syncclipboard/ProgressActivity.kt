package com.example.syncclipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import android.webkit.MimeTypeMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.syncclipboard.ui.theme.SyncClipboardTheme
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.os.Looper
import java.util.Locale

/**
 * 用于在前台执行一次上传/下载/测试操作，并显示进度和结果。
 */
class ProgressActivity : AppCompatActivity() {

    // 记录当前要执行的操作类型，以及是否已经启动过，避免同一个实例多次执行。
    private var started = false
    private var currentOperation: String = OP_UPLOAD_CLIPBOARD
    private var requireUserTap = false
    private var useBottomSheet = false
    private var useFloatingWindow = false
    private var cancelOnOutside = false
    private var lastDownloadedFileUri: Uri? = null
    private var lastDownloadedFileName: String? = null
    private var overlayController: FloatingOverlayController? = null

    private val statusTextState = mutableStateOf("")
    private val contentTextState = mutableStateOf<String?>(null)
    private val isSuccessState = mutableStateOf(false)
    private val isErrorState = mutableStateOf(false)
    private val actionButtonState = mutableStateOf<UiButton?>(null)
    private val replaceButtonState = mutableStateOf<UiButton?>(null)
    private val keepBothButtonState = mutableStateOf<UiButton?>(null)

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

    private data class UiButton(
        val text: String,
        val onClick: () -> Unit
    )

    private fun setStatusText(text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { setStatusText(text) }
            return
        }
        statusTextState.value = text
        // 悬浮窗模式下强制刷新窗口内容，确保进度能实时更新（避免部分 ROM 下重组不触发）
        overlayController?.refresh()
    }

    private fun setContentText(text: String?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { setContentText(text) }
            return
        }
        contentTextState.value = text
        // 悬浮窗模式下强制刷新窗口内容，确保进度能实时更新（避免部分 ROM 下重组不触发）
        overlayController?.refresh()
    }

    private fun previewForOverlay(raw: String, maxChars: Int = 80): String {
        val normalized = raw.trim().replace("\r", " ").replace("\n", " ")
        if (normalized.isEmpty()) return ""
        return if (normalized.length > maxChars) normalized.take(maxChars) + "…" else normalized
    }

    private fun showActionButton(text: String, onClick: () -> Unit) {
        actionButtonState.value = UiButton(text = text, onClick = onClick)
    }

    private fun hideActionButton() {
        actionButtonState.value = null
    }

    private fun showConflictButtons(
        replaceText: String,
        onReplace: () -> Unit,
        keepBothText: String,
        onKeepBoth: () -> Unit
    ) {
        replaceButtonState.value = UiButton(text = replaceText, onClick = onReplace)
        keepBothButtonState.value = UiButton(text = keepBothText, onClick = onKeepBoth)
    }

    private fun hideConflictButtons() {
        replaceButtonState.value = null
        keepBothButtonState.value = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val progressStyle = UiStyleStorage.loadProgressStyle(this)
        // 根据设置选择使用 BottomSheet / 悬浮窗
        useBottomSheet =
            progressStyle == UiStyleStorage.STYLE_BOTTOM_SHEET ||
                progressStyle == UiStyleStorage.STYLE_NOTIFICATION
        useFloatingWindow = progressStyle == UiStyleStorage.STYLE_FLOATING_WINDOW
        cancelOnOutside = UiStyleStorage.loadBottomSheetCancelOnTouchOutside(this)
        if (useFloatingWindow) {
            setTheme(R.style.Theme_SyncClipboard_FloatingHost)
        } else {
            // BottomSheet 模式使用全屏透明宿主 Activity，只承载底部弹窗。
            setTheme(R.style.Theme_SyncClipboard_BottomSheetHost)
        }

        super.onCreate(savedInstanceState)

        val operation = intent.getStringExtra(EXTRA_OPERATION) ?: OP_UPLOAD_CLIPBOARD

        if (!useBottomSheet) {
            // 对话框样式：保持点击空白处关闭的行为
            setFinishOnTouchOutside(true)
        }

        if (useFloatingWindow) {
            if (!Settings.canDrawOverlays(this)) {
                setContent {
                    SyncClipboardTheme {
                        OverlayPermissionScreen(
                            onGrantPermission = { openOverlayPermissionSettings() },
                            onUseInApp = {
                                UiStyleStorage.saveProgressStyle(this, UiStyleStorage.STYLE_BOTTOM_SHEET)
                                recreate()
                            }
                        )
                    }
                }
                return
            }

            val originalParams = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
            try {
                // 保持 Activity 处于前台，但不接管触摸：触摸交给悬浮窗与底层应用
                // 将 Activity 窗口最小化，防止全屏透明遮罩阻挡点击
                val params = window.attributes
                params.width = 1
                params.height = 1
                params.alpha = 0f
                window.attributes = params

                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                // 确保不压暗底层界面
                window.setDimAmount(0f)
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

                overlayController = FloatingOverlayController(this).also { controller ->
                    controller.show(
                        operation = operation,
                        statusTextState = statusTextState,
                        contentTextState = contentTextState,
                        isSuccessState = isSuccessState,
                        isErrorState = isErrorState,
                        actionButtonState = actionButtonState,
                        replaceButtonState = replaceButtonState,
                        keepBothButtonState = keepBothButtonState,
                        onClose = { finish() }
                    )
                }

                setContent { SyncClipboardTheme { Box(modifier = Modifier.size(1.dp)) } }
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "悬浮窗创建失败，将回退到应用内界面：${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
                UiStyleStorage.saveProgressStyle(this, UiStyleStorage.STYLE_BOTTOM_SHEET)
                useFloatingWindow = false
                useBottomSheet = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                window.attributes = originalParams
                setContent {
                    SyncClipboardTheme {
                        ProgressOverlay(
                            useBottomSheet = true,
                            cancelOnOutside = cancelOnOutside,
                            statusText = statusTextState.value,
                            contentText = contentTextState.value,
                            actionButton = actionButtonState.value,
                            replaceButton = replaceButtonState.value,
                            keepBothButton = keepBothButtonState.value,
                            onDismissRequest = { finish() }
                        )
                    }
                }
            }
        } else {
            setContent {
                SyncClipboardTheme {
                    ProgressOverlay(
                        useBottomSheet = useBottomSheet,
                        cancelOnOutside = cancelOnOutside,
                        statusText = statusTextState.value,
                        contentText = contentTextState.value,
                        actionButton = actionButtonState.value,
                        replaceButton = replaceButtonState.value,
                        keepBothButton = keepBothButtonState.value,
                        onDismissRequest = { finish() }
                    )
                }
            }
        }

        currentOperation = operation

        if (operation == OP_UPLOAD_CLIPBOARD) {
            // 上传剪贴板：上传前不显示内容预览，只在按钮下方显示结果文字
            setStatusText("")
            setContentText(null)
            // 上传本机剪贴板必须由用户在本应用内点击触发，
            // 否则 Android 会认为是后台读剪贴板而拒绝访问。
            requireUserTap = true
            showActionButton(text = getString(R.string.button_start), onClick = onStartUpload@{
                if (started) return@onStartUpload
                started = true

                // 点击上传后，不再显示按钮，改为显示上传进度文字
                hideActionButton()
                setStatusText(getString(R.string.progress_upload_clipboard))
                setContentText(null)

                lifecycleScope.launch {
                    if (useFloatingWindow) {
                        // Temporarily gain focus to read clipboard on Android 10+
                        overlayController?.setFocusable(true)
                        delay(100)
                    }

                    val result = withContext(Dispatchers.IO) {
                        performOperation(OP_UPLOAD_CLIPBOARD)
                    }

                    if (useFloatingWindow) {
                        // Restore non-focusable state to allow clicking behind
                        overlayController?.setFocusable(false)
                    }

                    // 上传完成后只显示结果文字，不展示剪贴板内容
                    setStatusText(result.message)
                    setContentText(if (useFloatingWindow) result.content?.let { previewForOverlay(it) } else null)
                    if (result.success) isSuccessState.value = true else isErrorState.value = true

                    // 对话框样式下可以额外弹出一条短 Toast；BottomSheet 已在界面内展示结果，无需 Toast
                    if (!useBottomSheet) {
                        Toast.makeText(
                            this@ProgressActivity,
                            result.message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    if (useFloatingWindow) {
                        val delaySeconds = UiStyleStorage.loadAutoCloseDelaySeconds(this@ProgressActivity)
                        if (delaySeconds > 0) {
                            delay((delaySeconds * 1000).toLong())
                            finish()
                        }
                    }
                }
            })
        } else {
            setStatusText(
                when (operation) {
                OP_DOWNLOAD_CLIPBOARD -> getString(R.string.progress_download_clipboard)
                OP_UPLOAD_SHARED_TEXT -> getString(R.string.progress_upload_shared)
                OP_UPLOAD_FILE -> getString(R.string.progress_upload_file)
                OP_TEST_CONNECTION -> getString(R.string.progress_test_connection)
                else -> getString(R.string.progress_upload_clipboard)
                }
            )
            setContentText(
                when (operation) {
                    OP_UPLOAD_SHARED_TEXT -> intent.getStringExtra(EXTRA_SHARED_TEXT)?.let { previewForOverlay(it) }?.takeIf { it.isNotEmpty() }
                    OP_UPLOAD_FILE -> intent.getStringExtra(EXTRA_FILE_NAME)?.takeIf { it.isNotBlank() }
                    else -> null
                }
            )
            // 下载剪贴板 / 分享上传 / 测试连接 不需要访问本机剪贴板，
            // 可以在 onResume 中自动执行。
            requireUserTap = false
            hideActionButton()
        }
    }

    override fun onDestroy() {
        overlayController?.hide()
        overlayController = null
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

                hideConflictButtons()
                if (result.success) isSuccessState.value = true else isErrorState.value = true

                // 下载剪贴板 / 文件：根据结果显示“已写入剪贴板”或“文件已下载”等文案
                if (operation == OP_DOWNLOAD_CLIPBOARD) {
                    if (result.success) {
                        // 如果存在 lastDownloadedFileUri，说明本次是“文件下载”场景
                        if (lastDownloadedFileUri != null) {
                            // 标题显示“已保存至 路径…”
                            setStatusText(result.message)
                            // 内容区域只展示文件名
                            val fileName = lastDownloadedFileName ?: result.content ?: ""
                            setContentText(fileName.ifEmpty { null })
                            // 显示“打开”按钮，点击后调用系统根据类型选择可打开的应用
                            showActionButton(text = getString(R.string.button_open_file)) {
                                openDownloadedFile()
                            }
                        } else {
                            // 文本剪贴板下载：沿用原有显示逻辑
                            setStatusText(result.message)
                            setContentText(result.content?.takeIf { it.isNotEmpty() })
                            hideActionButton()
                        }
                    } else {
                        setStatusText(getString(R.string.dialog_download_failed_title))
                        setContentText(result.message)
                        hideActionButton()
                    }
                } else {
                    // 分享上传 / 测试连接：沿用通用文案，显示结果和内容
                    // 上传剪贴板则只显示结果，不显示内容
                    setStatusText(result.message)
                    if (operation == OP_UPLOAD_CLIPBOARD) {
                        setContentText(null)
                    } else {
                        setContentText(result.content?.takeIf { it.isNotEmpty() })
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

                if (useFloatingWindow) {
                    val delaySeconds = UiStyleStorage.loadAutoCloseDelaySeconds(this@ProgressActivity)
                    if (delaySeconds > 0) {
                        delay((delaySeconds * 1000).toLong())
                        finish()
                    }
                }
            }
        }
    }

    private fun performOperation(operation: String): OperationResult {
        runOnUiThread {
            if (statusTextState.value.isBlank()) {
                setStatusText("正在准备…")
            }
        }

        runOnUiThread { setStatusText("正在读取配置…") }
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
        runOnUiThread {
            setStatusText("正在读取剪贴板…")
            setContentText(null)
        }
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

        runOnUiThread {
            if (useFloatingWindow) {
                setContentText(previewForOverlay(text))
            }
            setStatusText("正在上传剪贴板…")
        }

        val result = SyncClipboardApi.uploadText(config, text)
        if (result.success) {
            return OperationResult(
                success = true,
                message = getString(R.string.toast_upload_success),
                content = text
            )
        }

        val errorMessage = result.errorMessage ?: "未知错误"
        // 兼容：请求已到达服务器但客户端等待响应超时的情况，尝试二次确认服务器状态
        if (errorMessage == "网络请求超时") {
            runOnUiThread { setStatusText("正在确认服务器状态…") }
            val confirmed = runCatching {
                val profile = SyncClipboardApi.getClipboardProfile(config, timeoutMs = 8_000)
                profile.success &&
                    profile.data?.type?.trim()?.lowercase(Locale.ROOT) == "text" &&
                    (profile.data?.clipboard ?: "") == text
            }.getOrNull() == true
            if (confirmed) {
                return OperationResult(
                    success = true,
                    message = getString(R.string.toast_upload_success),
                    content = text
                )
            }
        }

        return OperationResult(
            success = false,
            message = getString(R.string.toast_error_prefix, errorMessage),
            content = text
        )
    }

    private fun downloadClipboard(config: ServerConfig): OperationResult {
        runOnUiThread { setStatusText("正在获取服务器剪贴板…") }
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
        val normalizedType = profile.type.trim().lowercase(Locale.ROOT)

        // 优先根据 File 字段判断是否为文件模式：只要服务器返回了 File 名，就按文件处理，
        // 避免 Type 值不规范（例如 Image、自定义字符串）导致误判。
        val fileNameFromFileField = profile.file?.trim()?.takeIf { it.isNotEmpty() }
        if (fileNameFromFileField != null) {
            return downloadFileToDownloadDir(config, fileNameFromFileField)
        }

        // 兼容部分服务端把文件名塞到 Clipboard 字段的情况（尤其是 Type=File 但 File=""）
        val fileNameFromClipboardField =
            if (normalizedType == "file") profile.clipboard?.trim()?.takeIf { it.isNotEmpty() } else null
        if (fileNameFromClipboardField != null) {
            return downloadFileToDownloadDir(config, fileNameFromClipboardField)
        }

        // 其次再看 Text 模式（兼容大小写）
        if (normalizedType == "text") {
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
            runOnUiThread {
                setStatusText("正在写入本机剪贴板…")
                if (useFloatingWindow) {
                    setContentText(previewForOverlay(text))
                }
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

        runOnUiThread {
            setStatusText("正在上传分享文本…")
            if (useFloatingWindow) {
                setContentText(previewForOverlay(sharedText))
            }
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
                setStatusText("正在准备上传文件…")
                setContentText(fileName)
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
                        setStatusText(progressText)
                        setContentText(fileName)
                    }
                }
                if (result.success) {
                    // 步骤 3：文件上传完毕，正在等待服务器确认剪贴板状态更新
                    runOnUiThread {
                        setStatusText("上传完成，正在更新服务器状态…")
                        setContentText(fileName)
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
                setStatusText("正在准备下载文件…")
                setContentText(null)
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
                val mimeType = guessMimeTypeFromName(fileName) ?: "application/octet-stream"
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
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
                    val mimeType = guessMimeTypeFromName(newName) ?: "application/octet-stream"
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, newName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
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
                    // 服务器端始终使用原始文件名 fileName，本地实际保存名可以不同
                    fileName,
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
                        setStatusText(progressText)
                        setContentText(finalFileName)
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
        runOnUiThread { setStatusText(getString(R.string.progress_test_connection)) }
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
     * 根据文件名简单推断 MIME 类型，用于下载保存和打开时尽量选择更合适的应用。
     */
    private fun guessMimeTypeFromName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return null
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
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
            setStatusText(fileName)
            setContentText(getString(R.string.file_conflict_message))

            // 显示“替换”和“保留”两个按钮，隐藏单一操作按钮
            hideActionButton()
            showConflictButtons(
                replaceText = getString(R.string.button_replace),
                onReplace = {
                decision = FileConflictDecision.REPLACE
                latch.countDown()
                },
                keepBothText = getString(R.string.button_keep_both),
                onKeepBoth = {
                decision = FileConflictDecision.KEEP_BOTH
                latch.countDown()
                }
            )
        }

        try {
            latch.await()
        } catch (_: InterruptedException) {
        }

        // 用户已选择，隐藏两个按钮，避免影响后续界面
        runOnUiThread {
            hideConflictButtons()
        }

        return decision
    }

    /**
     * 打开刚刚下载的文件：交给系统根据 MIME 类型选择可用的应用。
     */
    private fun openDownloadedFile() {
        val uri = lastDownloadedFileUri ?: return
        val name = lastDownloadedFileName
        val resolvedType = contentResolver.getType(uri)
        val guessedType = if (name != null) guessMimeTypeFromName(name) else null
        val finalType = resolvedType ?: guessedType ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, finalType)
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

    @Composable
    private fun ProgressOverlay(
        useBottomSheet: Boolean,
        cancelOnOutside: Boolean,
        statusText: String,
        contentText: String?,
        actionButton: UiButton?,
        replaceButton: UiButton?,
        keepBothButton: UiButton?,
        onDismissRequest: () -> Unit
    ) {
        val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)
        val sheetAlignment = if (useBottomSheet) Alignment.BottomCenter else Alignment.Center

        Box(modifier = Modifier.fillMaxSize()) {
            val dismissOnScrimTap = !useBottomSheet || cancelOnOutside
            val scrimModifier =
                if (dismissOnScrimTap) {
                    Modifier
                        .fillMaxSize()
                        .background(scrimColor)
                        .clickable(onClick = onDismissRequest)
                } else {
                    Modifier
                        .fillMaxSize()
                        .background(scrimColor)
                }
            Box(modifier = scrimModifier)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = sheetAlignment
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher),
                            contentDescription = getString(R.string.app_name),
                            modifier = Modifier.size(40.dp)
                        )

                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!contentText.isNullOrEmpty()) {
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Spacer(modifier = Modifier.height(0.dp))
                        }

                        if (actionButton != null) {
                            Button(onClick = actionButton.onClick) {
                                Text(text = actionButton.text)
                            }
                        }

                        if (replaceButton != null || keepBothButton != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (replaceButton != null) {
                                    Button(onClick = replaceButton.onClick) {
                                        Text(text = replaceButton.text)
                                    }
                                }
                                if (keepBothButton != null) {
                                    OutlinedButton(onClick = keepBothButton.onClick) {
                                        Text(text = keepBothButton.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    @Composable
    private fun OverlayPermissionScreen(
        onGrantPermission: () -> Unit,
        onUseInApp: () -> Unit
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = getString(R.string.settings_overlay_permission_required),
                    style = MaterialTheme.typography.titleMedium
                )
                Button(onClick = onGrantPermission) {
                    Text(text = getString(R.string.button_grant_overlay_permission))
                }
                OutlinedButton(onClick = onUseInApp) {
                    Text(text = getString(R.string.settings_ui_style_bottom_sheet))
                }
            }
        }
    }

    private class FloatingOverlayController(
        private val activity: ProgressActivity
    ) {
        private val windowManager =
            activity.getSystemService(WINDOW_SERVICE) as WindowManager
        private var view: ComposeView? = null
        private var params: WindowManager.LayoutParams? = null
        private var contentOperation: String = OP_UPLOAD_CLIPBOARD
        private var contentStatusTextState: androidx.compose.runtime.State<String>? = null
        private var contentTextState: androidx.compose.runtime.State<String?>? = null
        private var contentIsSuccessState: androidx.compose.runtime.State<Boolean>? = null
        private var contentIsErrorState: androidx.compose.runtime.State<Boolean>? = null
        private var contentActionButtonState: androidx.compose.runtime.State<UiButton?>? = null
        private var contentReplaceButtonState: androidx.compose.runtime.State<UiButton?>? = null
        private var contentKeepBothButtonState: androidx.compose.runtime.State<UiButton?>? = null
        private var contentOnClose: (() -> Unit)? = null

        fun show(
            operation: String,
            statusTextState: androidx.compose.runtime.State<String>,
            contentTextState: androidx.compose.runtime.State<String?>,
            isSuccessState: androidx.compose.runtime.State<Boolean>,
            isErrorState: androidx.compose.runtime.State<Boolean>,
            actionButtonState: androidx.compose.runtime.State<UiButton?>,
            replaceButtonState: androidx.compose.runtime.State<UiButton?>,
            keepBothButtonState: androidx.compose.runtime.State<UiButton?>,
            onClose: () -> Unit
        ) {
            if (view != null) return

            contentOperation = operation
            contentStatusTextState = statusTextState
            this.contentTextState = contentTextState
            contentIsSuccessState = isSuccessState
            contentIsErrorState = isErrorState
            contentActionButtonState = actionButtonState
            contentReplaceButtonState = replaceButtonState
            contentKeepBothButtonState = keepBothButtonState
            contentOnClose = onClose

            // Default: NOT_FOCUSABLE (allows click-through to app behind)
            // We will temporarily remove this flag when reading clipboard.
            val overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 120
            }

            val composeView = ComposeView(activity).apply {
                setViewTreeLifecycleOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                // Consume Back key to close overlay
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_UP && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
            }

            windowManager.addView(composeView, overlayParams)
            view = composeView
            params = overlayParams
            refresh()
        }

        fun refresh() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                activity.runOnUiThread { refresh() }
                return
            }
            val currentView = view ?: return
            val statusTextState = contentStatusTextState ?: return
            val contentTextState = this.contentTextState ?: return
            val isSuccessState = contentIsSuccessState ?: return
            val isErrorState = contentIsErrorState ?: return
            val actionButtonState = contentActionButtonState ?: return
            val replaceButtonState = contentReplaceButtonState ?: return
            val keepBothButtonState = contentKeepBothButtonState ?: return
            val closeHandler = contentOnClose ?: return
            val operation = contentOperation

            // 强制重设 content，确保窗口内 UI 能刷新进度（部分 ROM/窗口组合下重组可能不触发）
            currentView.setContent {
                val moveHandler = remember {
                    { dx: Float, dy: Float -> moveBy(dx, dy) }
                }

                val longPressSeconds = remember {
                    UiStyleStorage.loadLongPressCloseSeconds(activity)
                }

                SyncClipboardTheme {
                    FloatingProgressCard(
                        operation = operation,
                        statusText = statusTextState.value,
                        contentText = contentTextState.value,
                        isSuccess = isSuccessState.value,
                        isError = isErrorState.value,
                        actionButton = actionButtonState.value,
                        replaceButton = replaceButtonState.value,
                        keepBothButton = keepBothButtonState.value,
                        longPressSeconds = longPressSeconds,
                        onMoveBy = moveHandler,
                        onClose = closeHandler
                    )
                }
            }
        }

        fun setFocusable(focusable: Boolean) {
            val currentView = view ?: return
            val currentParams = params ?: return
            if (focusable) {
                currentParams.flags = currentParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                currentParams.flags = currentParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            try {
                windowManager.updateViewLayout(currentView, currentParams)
            } catch (_: Exception) {
            }
        }

        fun hide() {
            val current = view ?: return
            view = null
            params = null
            contentOnClose = null
            contentStatusTextState = null
            contentTextState = null
            contentIsSuccessState = null
            contentIsErrorState = null
            contentActionButtonState = null
            contentReplaceButtonState = null
            contentKeepBothButtonState = null
            try {
                windowManager.removeView(current)
            } catch (_: Exception) {
            }
        }

        private fun moveBy(dx: Float, dy: Float) {
            val currentView = view ?: return
            val currentParams = params ?: return
            currentParams.x = (currentParams.x + dx.toInt()).coerceAtLeast(0)
            currentParams.y = (currentParams.y + dy.toInt()).coerceAtLeast(0)
            try {
                windowManager.updateViewLayout(currentView, currentParams)
            } catch (_: Exception) {
            }
        }

        @Composable
        private fun FloatingProgressCard(
            operation: String,
            statusText: String,
            contentText: String?,
            isSuccess: Boolean,
            isError: Boolean,
            actionButton: UiButton?,
            replaceButton: UiButton?,
            keepBothButton: UiButton?,
            longPressSeconds: Float,
            onMoveBy: (dx: Float, dy: Float) -> Unit,
            onClose: () -> Unit
        ) {
            // Determine Icon
            val iconVector = when {
                operation == OP_UPLOAD_CLIPBOARD || operation == OP_UPLOAD_FILE || operation == OP_UPLOAD_SHARED_TEXT -> Icons.Default.ArrowUpward
                else -> Icons.Default.ArrowDownward
            }

            val iconColor = when {
                isSuccess -> MaterialTheme.colorScheme.primary
                isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }

            val displayStatusText = statusText.ifBlank { "准备中…" }
            val displayContentText = contentText?.takeIf { it.isNotBlank() } ?: "—"

            Surface(
                modifier = Modifier
                    .widthIn(min = 200.dp, max = 320.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (longPressSeconds > 0) {
                                    try {
                                        withTimeout((longPressSeconds * 1000).toLong()) {
                                            awaitRelease()
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        onClose()
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onMoveBy(dragAmount.x, dragAmount.y)
                        }
                    },
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(24.dp)
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayStatusText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = displayContentText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (actionButton != null) {
                        Button(
                            onClick = actionButton.onClick,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 0.dp, horizontal = 12.dp)
                        ) {
                            Text(text = actionButton.text)
                        }
                    }

                    if (replaceButton != null || keepBothButton != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (replaceButton != null) {
                                Button(
                                    onClick = replaceButton.onClick,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text(text = replaceButton.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (keepBothButton != null) {
                                OutlinedButton(
                                    onClick = keepBothButton.onClick,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text(text = keepBothButton.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
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
