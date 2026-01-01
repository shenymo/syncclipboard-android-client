package com.example.syncclipboard

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.database.Cursor
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 悬浮窗进度展示 + 后台执行上传/下载任务。
 *
 * 说明：之前使用 ComposeView 在部分 ROM/窗口组合下会出现“进度已变但悬浮窗不刷新”的偶发问题。
 * 这里改为传统 View 直接更新 TextView/ImageView，保证刷新可靠性。
 */
class FloatingOverlayService : LifecycleService() {

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var textContainerView: View? = null
    private var iconView: ImageView? = null
    private var statusView: TextView? = null
    private var contentView: TextView? = null
    private var conflictActionsView: View? = null
    private var btnReplace: Button? = null
    private var btnRenameSave: Button? = null
    private var resultActionsView: View? = null
    private var btnOpen: Button? = null

    private var statusCompactText: String = ""
    private var statusExpandedText: String = ""
    private var contentRawText: String? = null
    private var isSuccess: Boolean = false
    private var isError: Boolean = false
    private var operation: String = ""
    private var isExpanded: Boolean = false

    private var currentJob: Job? = null
    private val pendingConflictDecision = AtomicReference<FileConflictDecision?>(null)
    private var lastDownloadedFileUri: Uri? = null
    private var lastDownloadedFileName: String? = null

    private var longPressRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPLOAD_TEXT -> startUploadText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            ACTION_UPLOAD_FILE -> {
                val uriString = intent.getStringExtra(EXTRA_FILE_URI).orEmpty()
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                startUploadFile(uriString = uriString, fileName = fileName)
            }
            ACTION_DOWNLOAD_CLIPBOARD -> startDownloadClipboard()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun previewForOverlay(raw: String, maxChars: Int = 80): String {
        val normalized = raw.trim().replace("\r", " ").replace("\n", " ")
        if (normalized.isEmpty()) return ""
        return if (normalized.length > maxChars) normalized.take(maxChars) + "…" else normalized
    }

    private fun setStatus(compact: String, expanded: String = compact) {
        statusCompactText = compact
        statusExpandedText = expanded
        updateOverlayViews()
    }

    private fun setContent(text: String?) {
        contentRawText = text
        updateOverlayViews()
    }

    private fun setState(success: Boolean, error: Boolean) {
        isSuccess = success
        isError = error
        updateOverlayViews()
    }

    private fun startUploadText(text: String) {
        if (text.isEmpty()) {
            stopSelf()
            return
        }

        currentJob?.cancel()
        operation = SyncOperation.UPLOAD_CLIPBOARD
        isSuccess = false
        isError = false
        isExpanded = false
        pendingConflictDecision.set(null)
        lastDownloadedFileUri = null
        lastDownloadedFileName = null
        statusCompactText = "正在准备上传…"
        statusExpandedText = statusCompactText
        contentRawText = text

        showOverlay()
        updateOverlayViews()

        currentJob = lifecycleScope.launch {
            setStatus("正在读取配置…")
            val config = ConfigStorage.loadConfig(this@FloatingOverlayService)
            if (config == null) {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_config_missing)))
                autoCloseIfNeeded()
                return@launch
            }

            setStatus("正在上传剪贴板…")
            val result = withContext(Dispatchers.IO) { SyncClipboardApi.uploadText(config, text) }

            if (result.success) {
                setState(success = true, error = false)
                setStatus(getString(R.string.toast_upload_success))
                autoCloseIfNeeded()
                return@launch
            }

            val errorMessage = result.errorMessage ?: "未知错误"
            if (errorMessage == "网络请求超时") {
                setStatus("正在确认服务器状态…")
                val confirmed = runCatching {
                    val profile = SyncClipboardApi.getClipboardProfile(config, timeoutMs = 8_000)
                    profile.success &&
                        profile.data?.type?.trim()?.lowercase(Locale.ROOT) == "text" &&
                        (profile.data?.clipboard ?: "") == text
                }.getOrNull() == true
                if (confirmed) {
                    setState(success = true, error = false)
                    setStatus(getString(R.string.toast_upload_success))
                } else {
                    setState(success = false, error = true)
                    setStatus(getString(R.string.toast_error_prefix, errorMessage))
                }
            } else {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, errorMessage))
            }
            autoCloseIfNeeded()
        }
    }

    private fun startUploadFile(uriString: String, fileName: String) {
        currentJob?.cancel()
        operation = SyncOperation.UPLOAD_FILE
        isSuccess = false
        isError = false
        isExpanded = false
        pendingConflictDecision.set(null)
        lastDownloadedFileUri = null
        lastDownloadedFileName = null
        statusCompactText = "正在准备上传文件…"
        statusExpandedText = statusCompactText
        contentRawText = fileName.ifBlank { null }

        showOverlay()
        updateOverlayViews()

        if (uriString.isBlank()) {
            setState(success = false, error = true)
            setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_file_missing)))
            autoCloseIfNeeded()
            return
        }

        currentJob = lifecycleScope.launch {
            setStatus("正在读取配置…")
            val config = withContext(Dispatchers.IO) { ConfigStorage.loadConfig(this@FloatingOverlayService) }
            if (config == null) {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_config_missing)))
                autoCloseIfNeeded()
                return@launch
            }

            val uri = Uri.parse(uriString)
            val resolvedName = fileName.ifBlank { resolveFileName(uri) ?: "shared_file" }
            setContent(resolvedName)

            var totalBytes: Long = -1L
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0) totalBytes = it.getLong(index)
                    }
                }
            }

            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_file_open_failed)))
                autoCloseIfNeeded()
                return@launch
            }

            val startTime = System.currentTimeMillis()
            input.use { stream ->
                val result = withContext(Dispatchers.IO) {
                    SyncClipboardApi.uploadFile(
                        config,
                        resolvedName,
                        stream,
                        totalBytes,
                        onStage = { stage ->
                            when (stage) {
                                SyncClipboardApi.UploadFileStage.UPLOADING_CONTENT -> {
                                    setStatus("正在上传文件…")
                                }
                                SyncClipboardApi.UploadFileStage.WAITING_UPLOAD_RESPONSE -> {
                                    setStatus(
                                        compact = "正在上传文件… 等待服务器响应…",
                                        expanded = "正在上传文件…\n状态：文件内容已发送，等待服务器响应…"
                                    )
                                }
                                SyncClipboardApi.UploadFileStage.UPDATING_PROFILE -> {
                                    setStatus(
                                        compact = "正在提交文件信息…",
                                        expanded = "正在提交文件信息…\n状态：正在更新服务器剪贴板类型为文件…"
                                    )
                                }
                                SyncClipboardApi.UploadFileStage.DONE -> Unit
                            }
                        }
                    ) { uploaded, total ->
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val elapsedSec = if (elapsedMs <= 0) 1 else elapsedMs / 1000
                        val speedBytesPerSec = if (elapsedSec <= 0) uploaded else uploaded / elapsedSec
                        val compact = FileTransferUtils.buildFileUploadProgressCollapsedText(
                            uploadedBytes = uploaded,
                            totalBytes = total,
                            speedBytesPerSec = speedBytesPerSec
                        )
                        val expanded = FileTransferUtils.buildFileUploadProgressExpandedText(
                            uploadedBytes = uploaded,
                            totalBytes = total,
                            speedBytesPerSec = speedBytesPerSec,
                            elapsedSec = elapsedSec
                        )
                        setStatus(compact = compact, expanded = expanded)
                    }
                }

                if (result.success) {
                    setState(success = true, error = false)
                    setStatus(getString(R.string.toast_upload_file_success))
                    autoCloseIfNeeded()
                } else {
                    setState(success = false, error = true)
                    setStatus(
                        getString(
                            R.string.toast_error_prefix,
                            result.errorMessage ?: getString(R.string.error_file_upload_failed)
                        )
                    )
                    autoCloseIfNeeded()
                }
            }
        }
    }

    private fun startDownloadClipboard() {
        currentJob?.cancel()
        operation = SyncOperation.DOWNLOAD_CLIPBOARD
        isSuccess = false
        isError = false
        isExpanded = false
        pendingConflictDecision.set(null)
        lastDownloadedFileUri = null
        lastDownloadedFileName = null
        statusCompactText = "正在准备下载…"
        statusExpandedText = statusCompactText
        contentRawText = null

        showOverlay()
        updateOverlayViews()

        currentJob = lifecycleScope.launch {
            setStatus("正在读取配置…")
            val config = ConfigStorage.loadConfig(this@FloatingOverlayService)
            if (config == null) {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_config_missing)))
                autoCloseIfNeeded()
                return@launch
            }

            setStatus("正在获取服务器剪贴板…")
            val profileResult = withContext(Dispatchers.IO) { SyncClipboardApi.getClipboardProfile(config) }
            if (!profileResult.success || profileResult.data == null) {
                setState(success = false, error = true)
                setStatus(
                    getString(
                        R.string.toast_error_prefix,
                        profileResult.errorMessage ?: getString(R.string.error_server_not_text)
                    )
                )
                autoCloseIfNeeded()
                return@launch
            }

            val profile = profileResult.data
            val normalizedType = profile.type.trim().lowercase(Locale.ROOT)
            val fileNameFromFileField = profile.file?.trim()?.takeIf { it.isNotEmpty() }
            val fileNameFromClipboardField =
                if (normalizedType == "file") profile.clipboard?.trim()?.takeIf { it.isNotEmpty() } else null

            if (fileNameFromFileField != null) {
                downloadFileFromServer(config, fileNameFromFileField)
                return@launch
            }
            if (fileNameFromClipboardField != null) {
                downloadFileFromServer(config, fileNameFromClipboardField)
                return@launch
            }
            if (normalizedType != "text") {
                val fallbackName = profile.clipboard?.trim()?.takeIf { it.isNotEmpty() }
                if (fallbackName != null) {
                    downloadFileFromServer(config, fallbackName)
                    return@launch
                }
            }

            if (normalizedType != "text") {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_server_not_text)))
                autoCloseIfNeeded()
                return@launch
            }

            val text = profile.clipboard ?: ""
            if (text.isEmpty()) {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_server_not_text)))
                autoCloseIfNeeded()
                return@launch
            }

            setStatus("正在写入本机剪贴板…")
            setContent(text)

            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(ClipData.newPlainText("SyncClipboard", text))

            setState(success = true, error = false)
            setStatus(getString(R.string.toast_download_success))
            autoCloseIfNeeded()
        }
    }

    private suspend fun downloadFileFromServer(config: ServerConfig, remoteFileName: String) {
        val startTime = System.currentTimeMillis()
        setState(success = false, error = false)
        setStatus("正在准备下载文件…")
        setContent(remoteFileName)
        showResultActions(show = false)
        lastDownloadedFileUri = null
        lastDownloadedFileName = null

        val downloadDirPath = FileTransferUtils.getPublicDownloadsPath()
        val resolver = contentResolver

        val existingUri = FileTransferUtils.findExistingDownloadEntry(resolver, remoteFileName)
        val (targetUri, finalFileName) = if (existingUri == null) {
            val uri = FileTransferUtils.createDownloadEntry(this, resolver, remoteFileName)
            if (uri == null) {
                setState(success = false, error = true)
                setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_file_create_failed)))
                autoCloseIfNeeded()
                return
            }
            uri to remoteFileName
        } else {
            val decision = waitUserDecisionForFileConflict(remoteFileName)
            if (decision == FileConflictDecision.REPLACE) {
                existingUri to remoteFileName
            } else {
                val newName = FileTransferUtils.generateNonConflictingDownloadName(resolver, remoteFileName)
                val uri = FileTransferUtils.createDownloadEntry(this, resolver, newName)
                if (uri == null) {
                    setState(success = false, error = true)
                    setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_file_create_failed)))
                    autoCloseIfNeeded()
                    return
                }
                uri to newName
            }
        }

        setContent(finalFileName)
        resolver.openOutputStream(targetUri, "w")?.use { out ->
            val result = withContext(Dispatchers.IO) {
                SyncClipboardApi.downloadFileToStream(config, remoteFileName, out) { downloaded, total ->
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val elapsedSec = if (elapsedMs <= 0) 1 else elapsedMs / 1000
                    val speedBytesPerSec = if (elapsedSec <= 0) downloaded else downloaded / elapsedSec
                    val compact = FileTransferUtils.buildFileDownloadProgressCollapsedText(
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speedBytesPerSec
                    )
                    val expanded = FileTransferUtils.buildFileDownloadProgressExpandedText(
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speedBytesPerSec,
                        elapsedSec = elapsedSec
                    )
                    setStatus(compact = compact, expanded = expanded)
                    setContent(finalFileName)
                }
            }
            if (!result.success) {
                setState(success = false, error = true)
                setStatus(
                    getString(
                        R.string.toast_error_prefix,
                        result.errorMessage ?: getString(R.string.error_file_download_failed)
                    )
                )
                autoCloseIfNeeded()
                return
            }
        } ?: run {
            setState(success = false, error = true)
            setStatus(getString(R.string.toast_error_prefix, getString(R.string.error_file_create_failed)))
            autoCloseIfNeeded()
            return
        }

        lastDownloadedFileUri = targetUri
        lastDownloadedFileName = finalFileName
        setState(success = true, error = false)
        setStatus("已保存至 \"$downloadDirPath\"")
        showResultActions(show = true)
        autoCloseIfNeeded(allowAutoClose = false)
    }

    private suspend fun waitUserDecisionForFileConflict(fileName: String): FileConflictDecision {
        pendingConflictDecision.set(null)
        showConflictActions(show = true)
        showResultActions(show = false)
        setStatus("文件已存在：$fileName")
        setContent("请选择：替换 / 重命名保存")

        while (true) {
            val decision = pendingConflictDecision.get()
            if (decision != null) {
                pendingConflictDecision.set(null)
                showConflictActions(show = false)
                return decision
            }
            delay(120)
        }
    }

    private fun showConflictActions(show: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showConflictActions(show) }
            return
        }
        conflictActionsView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showResultActions(show: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showResultActions(show) }
            return
        }
        resultActionsView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun openDownloadedFile() {
        val uri = lastDownloadedFileUri ?: return
        val name = lastDownloadedFileName
        val resolvedType = contentResolver.getType(uri)
        val guessedType = if (name != null) FileTransferUtils.guessMimeTypeFromName(name) else null
        val finalType = resolvedType ?: guessedType ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, finalType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用于打开该文件的应用", Toast.LENGTH_SHORT).show()
        }
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

    private fun autoCloseIfNeeded(allowAutoClose: Boolean = true) {
        if (!allowAutoClose) return
        val delaySeconds = UiStyleStorage.loadAutoCloseDelaySeconds(this)
        if (delaySeconds <= 0f) return
        lifecycleScope.launch {
            delay((delaySeconds * 1000).toLong())
            stopSelf()
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

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

        val root = LayoutInflater.from(this).inflate(R.layout.view_floating_overlay, null, false)
        val textContainer = root.findViewById<View>(R.id.floating_overlay_text_container)
        val icon = root.findViewById<ImageView>(R.id.floating_overlay_icon)
        val status = root.findViewById<TextView>(R.id.floating_overlay_status)
        val content = root.findViewById<TextView>(R.id.floating_overlay_content)
        val conflictActions = root.findViewById<View>(R.id.floating_overlay_conflict_actions)
        val replaceButton = root.findViewById<Button>(R.id.floating_overlay_btn_replace)
        val renameSaveButton = root.findViewById<Button>(R.id.floating_overlay_btn_rename_save)
        val resultActions = root.findViewById<View>(R.id.floating_overlay_result_actions)
        val openButton = root.findViewById<Button>(R.id.floating_overlay_btn_open)

        root.setOnTouchListener { _, event -> handleTouch(event) }

        try {
            windowManager.addView(root, overlayParams)
            overlayView = root
            params = overlayParams
            textContainerView = textContainer
            iconView = icon
            statusView = status
            contentView = content
            conflictActionsView = conflictActions
            btnReplace = replaceButton
            btnRenameSave = renameSaveButton
            conflictActions.visibility = View.GONE
            resultActionsView = resultActions
            btnOpen = openButton
            resultActions.visibility = View.GONE

            replaceButton.setOnClickListener { pendingConflictDecision.set(FileConflictDecision.REPLACE) }
            renameSaveButton.setOnClickListener { pendingConflictDecision.set(FileConflictDecision.RENAME_SAVE) }
            openButton.setOnClickListener { openDownloadedFile() }

            applyExpandedState()
        } catch (_: Exception) {
            stopSelf()
            return
        }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        applyExpandedState()
        updateOverlayViews()
    }

    private fun applyExpandedState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyExpandedState() }
            return
        }

        val status = statusView ?: return
        val content = contentView ?: return
        val container = textContainerView

        if (isExpanded) {
            applyTextBehaviorExpanded(status, maxLines = 6)
            applyTextBehaviorExpanded(content, maxLines = 10)
            container?.let { view ->
                view.layoutParams = view.layoutParams.apply { width = dpToPx(320) }
            }
        } else {
            applyTextBehaviorCollapsed(status)
            applyTextBehaviorCollapsed(content)
            container?.let { view ->
                view.layoutParams = view.layoutParams.apply { width = dpToPx(240) }
            }
        }

        container?.requestLayout()
        overlayView?.requestLayout()
    }

    private fun applyTextBehaviorCollapsed(view: TextView) {
        view.isSingleLine = true
        view.setHorizontallyScrolling(true)
        view.ellipsize = TextUtils.TruncateAt.MARQUEE
        view.marqueeRepeatLimit = -1
        view.isSelected = true
    }

    private fun applyTextBehaviorExpanded(view: TextView, maxLines: Int) {
        view.isSelected = false
        view.isSingleLine = false
        view.setHorizontallyScrolling(false)
        view.maxLines = maxLines
        view.ellipsize = TextUtils.TruncateAt.END
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val currentParams = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isTouchOnActionButton(event)) return false
                dragging = false
                downX = event.rawX
                downY = event.rawY
                startX = currentParams.x
                startY = currentParams.y

                val longPressSeconds = UiStyleStorage.loadLongPressCloseSeconds(this)
                if (longPressSeconds > 0f) {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = Runnable { stopSelf() }.also {
                        mainHandler.postDelayed(it, (longPressSeconds * 1000).toLong())
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                }
                if (dragging) {
                    currentParams.x = (startX + dx.toInt()).coerceAtLeast(0)
                    currentParams.y = (startY + dy.toInt()).coerceAtLeast(0)
                    overlayView?.let {
                        runCatching { windowManager.updateViewLayout(it, currentParams) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                longPressRunnable = null
                val wasDragging = dragging
                dragging = false
                if (event.actionMasked == MotionEvent.ACTION_UP && !wasDragging) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                        toggleExpanded()
                    }
                }
                return true
            }
            else -> return false
        }
    }

    private fun isTouchOnActionButton(event: MotionEvent): Boolean {
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        return isPointInsideView(x, y, btnReplace) ||
            isPointInsideView(x, y, btnRenameSave) ||
            isPointInsideView(x, y, btnOpen)
    }

    private fun isPointInsideView(x: Int, y: Int, view: View?): Boolean {
        val v = view ?: return false
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + v.width
        val bottom = top + v.height
        return x in left..right && y in top..bottom
    }

    private fun updateOverlayViews() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateOverlayViews() }
            return
        }

        val icon = iconView ?: return
        val status = statusView ?: return
        val content = contentView ?: return

        val iconRes = if (operation == SyncOperation.DOWNLOAD_CLIPBOARD) {
            R.drawable.ic_qs_download
        } else {
            R.drawable.ic_qs_upload
        }
        icon.setImageResource(iconRes)

        val tintColor = when {
            isSuccess -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
            isError -> ContextCompat.getColor(this, android.R.color.holo_red_dark)
            else -> ContextCompat.getColor(this, android.R.color.black)
        }
        icon.imageTintList = ColorStateList.valueOf(tintColor)

        status.text = (if (isExpanded) statusExpandedText else statusCompactText).ifBlank { "准备中…" }
        val raw = contentRawText?.takeIf { it.isNotBlank() }
        content.text = when {
            raw == null -> "—"
            isExpanded -> raw.trim()
            else -> previewForOverlay(raw)
        }

        val conflictVisible = conflictActionsView?.visibility == View.VISIBLE
        if (!conflictVisible) {
            val shouldShowOpen =
                isSuccess && operation == SyncOperation.DOWNLOAD_CLIPBOARD && lastDownloadedFileUri != null
            resultActionsView?.visibility = if (shouldShowOpen) View.VISIBLE else View.GONE
        }
    }

    private fun hideOverlay() {
        val current = overlayView ?: return
        overlayView = null
        params = null
        textContainerView = null
        iconView = null
        statusView = null
        contentView = null
        conflictActionsView = null
        btnReplace = null
        btnRenameSave = null
        resultActionsView = null
        btnOpen = null
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_UPLOAD_TEXT = "com.example.syncclipboard.action.UPLOAD_TEXT"
        const val ACTION_UPLOAD_FILE = "com.example.syncclipboard.action.UPLOAD_FILE"
        const val ACTION_DOWNLOAD_CLIPBOARD = "com.example.syncclipboard.action.DOWNLOAD_CLIPBOARD"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_FILE_URI = "file_uri"
        const val EXTRA_FILE_NAME = "file_name"
    }
}

private enum class FileConflictDecision {
    REPLACE,
    RENAME_SAVE
}
