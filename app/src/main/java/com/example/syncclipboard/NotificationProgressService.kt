package com.example.syncclipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class NotificationProgressService : LifecycleService() {

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var currentJob: Job? = null

    private var operation: String = ProgressActivity.OP_UPLOAD_CLIPBOARD
    private var statusText: String = ""
    private var contentText: String? = null

    private var lastDownloadedFileUri: Uri? = null
    private var lastDownloadedFileName: String? = null

    private val pendingConflictDecision = AtomicReference<FileConflictDecision?>(null)

    private enum class FileConflictDecision {
        REPLACE,
        KEEP_BOTH
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        when (intent?.action) {
            ACTION_PREPARE_UPLOAD_CLIPBOARD -> {
                operation = ProgressActivity.OP_UPLOAD_CLIPBOARD
                updateState(
                    status = "等待开始上传…",
                    content = "点击“${getString(R.string.notification_action_start_upload)}”读取剪贴板"
                )
                updateForegroundNotification(running = true, showStartUpload = true)
                return START_NOT_STICKY
            }
            ACTION_UPLOAD_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val op = intent.getStringExtra(EXTRA_OPERATION)
                startUploadText(text = text, op = op)
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD_CLIPBOARD -> {
                startDownloadClipboard()
                return START_NOT_STICKY
            }
            ACTION_UPLOAD_FILE -> {
                val uriString = intent.getStringExtra(EXTRA_FILE_URI).orEmpty()
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                startUploadFile(uriString = uriString, fileName = fileName)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                currentJob?.cancel()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLOSE -> {
                notificationManager.cancel(NOTIFICATION_ID)
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FILE_CONFLICT_DECISION -> {
                val decision = intent.getStringExtra(EXTRA_DECISION).orEmpty()
                pendingConflictDecision.set(
                    if (decision == DECISION_KEEP_BOTH) FileConflictDecision.KEEP_BOTH else FileConflictDecision.REPLACE
                )
                return START_NOT_STICKY
            }
        }

        stopSelf()
        return START_NOT_STICKY
    }

    private fun startUploadText(text: String, op: String?) {
        currentJob?.cancel()
        lastDownloadedFileUri = null
        lastDownloadedFileName = null

        operation = op ?: ProgressActivity.OP_UPLOAD_CLIPBOARD
        if (text.isBlank()) {
            finishWithResult(
                success = false,
                finalStatus = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_clipboard_empty)
                ),
                finalContent = null
            )
            return
        }

        updateState(
            status = "正在读取配置…",
            content = previewForOverlay(text).ifEmpty { null }
        )
        updateForegroundNotification(running = true)

        currentJob = lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { ConfigStorage.loadConfig(this@NotificationProgressService) }
            if (config == null) {
                finishWithResult(
                    success = false,
                    finalStatus = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_config_missing)
                    ),
                    finalContent = null
                )
                return@launch
            }

            updateState(
                status = if (operation == ProgressActivity.OP_UPLOAD_SHARED_TEXT) "正在上传分享文本…" else "正在上传剪贴板…",
                content = previewForOverlay(text).ifEmpty { null }
            )
            updateForegroundNotification(running = true)

            val result = withContext(Dispatchers.IO) { SyncClipboardApi.uploadText(config, text) }
            if (result.success) {
                finishWithResult(
                    success = true,
                    finalStatus = getString(R.string.toast_upload_success),
                    finalContent = previewForOverlay(text).ifEmpty { null }
                )
            } else {
                finishWithResult(
                    success = false,
                    finalStatus = getString(
                        R.string.toast_error_prefix,
                        result.errorMessage ?: "未知错误"
                    ),
                    finalContent = previewForOverlay(text).ifEmpty { null }
                )
            }
        }
    }

    private fun startUploadFile(uriString: String, fileName: String) {
        currentJob?.cancel()
        lastDownloadedFileUri = null
        lastDownloadedFileName = null

        operation = ProgressActivity.OP_UPLOAD_FILE
        if (uriString.isBlank()) {
            finishWithResult(
                success = false,
                finalStatus = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_file_missing)
                ),
                finalContent = null
            )
            return
        }

        updateState(status = "正在准备上传文件…", content = fileName.ifBlank { null })
        updateForegroundNotification(running = true)

        currentJob = lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { ConfigStorage.loadConfig(this@NotificationProgressService) }
            if (config == null) {
                finishWithResult(
                    success = false,
                    finalStatus = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_config_missing)
                    ),
                    finalContent = null
                )
                return@launch
            }

            val uri = Uri.parse(uriString)
            var totalBytes: Long = -1L
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (index >= 0) totalBytes = it.getLong(index)
                    }
                }
            }

            val startTime = System.currentTimeMillis()
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                finishWithResult(
                    success = false,
                    finalStatus = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_file_open_failed)
                    ),
                    finalContent = fileName.ifBlank { null }
                )
                return@launch
            }

            input.use { stream ->
                val result = withContext(Dispatchers.IO) {
                    SyncClipboardApi.uploadFile(
                        config,
                        fileName.ifBlank { "shared_file" },
                        stream,
                        totalBytes
                    ) { uploaded, total ->
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val elapsedSec = if (elapsedMs <= 0) 1 else elapsedMs / 1000
                        val speedBytesPerSec = if (elapsedSec <= 0) uploaded else uploaded / elapsedSec
                        updateState(
                            status = buildFileUploadProgressText(
                                uploadedBytes = uploaded,
                                totalBytes = total,
                                speedBytesPerSec = speedBytesPerSec
                            ),
                            content = fileName.ifBlank { null }
                        )
                        updateForegroundNotification(running = true)
                    }
                }

                if (result.success) {
                    finishWithResult(
                        success = true,
                        finalStatus = getString(R.string.toast_upload_file_success),
                        finalContent = fileName.ifBlank { null }
                    )
                } else {
                    finishWithResult(
                        success = false,
                        finalStatus = getString(
                            R.string.toast_error_prefix,
                            result.errorMessage ?: getString(R.string.error_file_upload_failed)
                        ),
                        finalContent = fileName.ifBlank { null }
                    )
                }
            }
        }
    }

    private fun startDownloadClipboard() {
        currentJob?.cancel()
        lastDownloadedFileUri = null
        lastDownloadedFileName = null

        operation = ProgressActivity.OP_DOWNLOAD_CLIPBOARD
        updateState(status = "正在读取配置…", content = null)
        updateForegroundNotification(running = true)

        currentJob = lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { ConfigStorage.loadConfig(this@NotificationProgressService) }
            if (config == null) {
                finishWithResult(
                    success = false,
                    finalStatus = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_config_missing)
                    ),
                    finalContent = null
                )
                return@launch
            }

            updateState(status = "正在获取服务器剪贴板…", content = null)
            updateForegroundNotification(running = true)

            val profileResult = withContext(Dispatchers.IO) { SyncClipboardApi.getClipboardProfile(config) }
            if (!profileResult.success || profileResult.data == null) {
                finishWithResult(
                    success = false,
                    finalStatus = getString(
                        R.string.toast_error_prefix,
                        profileResult.errorMessage ?: getString(R.string.error_server_not_text)
                    ),
                    finalContent = null
                )
                return@launch
            }

            val profile = profileResult.data
            val normalizedType = profile.type.trim().lowercase(Locale.ROOT)

            val fileNameFromFileField = profile.file?.trim()?.takeIf { it.isNotEmpty() }
            if (fileNameFromFileField != null) {
                val result = withContext(Dispatchers.IO) {
                    downloadFileToDownloadDir(config, fileNameFromFileField)
                }
                finishWithResult(
                    success = result.success,
                    finalStatus = result.message,
                    finalContent = result.content
                )
                return@launch
            }

            val fileNameFromClipboardField =
                if (normalizedType == "file") profile.clipboard?.trim()?.takeIf { it.isNotEmpty() } else null
            if (fileNameFromClipboardField != null) {
                val result = withContext(Dispatchers.IO) {
                    downloadFileToDownloadDir(config, fileNameFromClipboardField)
                }
                finishWithResult(
                    success = result.success,
                    finalStatus = result.message,
                    finalContent = result.content
                )
                return@launch
            }

            if (normalizedType == "text") {
                val text = profile.clipboard ?: ""
                if (text.isEmpty()) {
                    finishWithResult(
                        success = false,
                        finalStatus = getString(
                            R.string.toast_error_prefix,
                            getString(R.string.error_server_not_text)
                        ),
                        finalContent = null
                    )
                    return@launch
                }

                updateState(status = "正在写入本机剪贴板…", content = previewForOverlay(text).ifEmpty { null })
                updateForegroundNotification(running = true)

                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SyncClipboard", text)
                clipboardManager.setPrimaryClip(clip)

                finishWithResult(
                    success = true,
                    finalStatus = getString(R.string.toast_download_success),
                    finalContent = previewForOverlay(text).ifEmpty { null }
                )
                return@launch
            }

            finishWithResult(
                success = false,
                finalStatus = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_server_not_text)
                ),
                finalContent = null
            )
        }
    }

    private data class OperationResult(
        val success: Boolean,
        val message: String,
        val content: String? = null
    )

    private suspend fun downloadFileToDownloadDir(config: ServerConfig, fileName: String): OperationResult {
        return try {
            val startTime = System.currentTimeMillis()
            updateState(status = "正在准备下载文件…", content = fileName)
            updateForegroundNotification(running = true)

            val downloadDirPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

            val existingUri = findExistingDownloadEntry(fileName)
            val (targetUri, finalFileName) = if (existingUri == null) {
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
                val decision = waitUserDecisionForFileConflict(fileName)
                if (decision == FileConflictDecision.REPLACE) {
                    existingUri to fileName
                } else {
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
                    fileName,
                    out
                ) { downloaded, total ->
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val elapsedSec = if (elapsedMs <= 0) 1 else elapsedMs / 1000
                    val speedBytesPerSec = if (elapsedSec <= 0) downloaded else downloaded / elapsedSec
                    updateState(
                        status = buildFileDownloadProgressText(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            speedBytesPerSec = speedBytesPerSec
                        ),
                        content = finalFileName
                    )
                    updateForegroundNotification(running = true)
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

            lastDownloadedFileUri = targetUri
            lastDownloadedFileName = finalFileName

            OperationResult(
                success = true,
                message = "已保存至 \"$downloadDirPath\"",
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

    private fun guessMimeTypeFromName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return null
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private suspend fun waitUserDecisionForFileConflict(fileName: String): FileConflictDecision {
        pendingConflictDecision.set(null)
        updateState(status = "文件已存在：$fileName", content = "请选择：替换 / 保留")
        updateForegroundNotification(running = true, showConflictActions = true)

        while (true) {
            val decision = pendingConflictDecision.get()
            if (decision != null) {
                pendingConflictDecision.set(null)
                updateForegroundNotification(running = true, showConflictActions = false)
                return decision
            }
            delay(120)
        }
    }

    private fun previewForOverlay(raw: String, maxChars: Int = 80): String {
        val normalized = raw.trim().replace("\r", " ").replace("\n", " ")
        if (normalized.isEmpty()) return ""
        return if (normalized.length > maxChars) normalized.take(maxChars) + "…" else normalized
    }

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

    private fun updateState(status: String, content: String?) {
        statusText = status
        contentText = content
    }

    private fun ensureForeground() {
        createNotificationChannelIfNeeded()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                running = true,
                showStartUpload = false,
                showConflictActions = false
            )
        )
    }

    private fun updateForegroundNotification(
        running: Boolean,
        showStartUpload: Boolean = false,
        showConflictActions: Boolean = false
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                running = running,
                showStartUpload = showStartUpload,
                showConflictActions = showConflictActions
            )
        )
    }

    private fun finishWithResult(success: Boolean, finalStatus: String, finalContent: String?) {
        updateState(status = finalStatus, content = finalContent)
        val finalNotification = buildNotification(
            running = false,
            showStartUpload = false,
            showConflictActions = false,
            success = success
        )
        stopForeground(false)
        notificationManager.notify(NOTIFICATION_ID, finalNotification)

        val delaySeconds = UiStyleStorage.loadAutoCloseDelaySeconds(this)
        if (delaySeconds > 0f) {
            lifecycleScope.launch {
                delay((delaySeconds * 1000).toLong())
                notificationManager.cancel(NOTIFICATION_ID)
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private fun buildNotification(
        running: Boolean,
        showStartUpload: Boolean,
        showConflictActions: Boolean,
        success: Boolean? = null
    ): Notification {
        val displayStatus = statusText.ifBlank { if (running) "准备中…" else "完成" }
        val displayContent = contentText?.takeIf { it.isNotBlank() } ?: "—"

        val smallIcon = when (operation) {
            ProgressActivity.OP_DOWNLOAD_CLIPBOARD -> R.drawable.ic_qs_download
            else -> R.drawable.ic_qs_upload
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(displayStatus)
            .setContentText(displayContent)
            .setColor(Color.parseColor(if (success == true) "#4CAF50" else if (success == false) "#F44336" else "#2196F3"))
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val contentIntent = Intent(this, SettingsActivity::class.java)
        val contentPending = PendingIntent.getActivity(
            this,
            10,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(contentPending)

        if (running) {
            val cancelIntent = Intent(this, NotificationProgressService::class.java).apply { action = ACTION_CANCEL }
            val cancelPending = PendingIntent.getService(
                this,
                11,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_action_cancel), cancelPending)
        } else {
            val closeIntent = Intent(this, NotificationProgressService::class.java).apply { action = ACTION_CLOSE }
            val closePending = PendingIntent.getService(
                this,
                12,
                closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_action_close), closePending)
        }

        if (showStartUpload) {
            val startUploadIntent = Intent(this, ClipboardBridgeActivity::class.java).apply {
                putExtra(ClipboardBridgeActivity.EXTRA_MODE, ClipboardBridgeActivity.MODE_NOTIFICATION_UPLOAD_EXECUTE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val startUploadPending = PendingIntent.getActivity(
                this,
                13,
                startUploadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_action_start_upload), startUploadPending)
        }

        if (showConflictActions) {
            val replaceIntent = Intent(this, NotificationProgressService::class.java).apply {
                action = ACTION_FILE_CONFLICT_DECISION
                putExtra(EXTRA_DECISION, DECISION_REPLACE)
            }
            val keepIntent = Intent(this, NotificationProgressService::class.java).apply {
                action = ACTION_FILE_CONFLICT_DECISION
                putExtra(EXTRA_DECISION, DECISION_KEEP_BOTH)
            }
            val replacePending = PendingIntent.getService(
                this,
                14,
                replaceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val keepPending = PendingIntent.getService(
                this,
                15,
                keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_action_replace), replacePending)
            builder.addAction(0, getString(R.string.notification_action_keep_both), keepPending)
        }

        val fileUri = lastDownloadedFileUri
        if (!running && operation == ProgressActivity.OP_DOWNLOAD_CLIPBOARD && fileUri != null) {
            val name = lastDownloadedFileName
            val resolvedType = contentResolver.getType(fileUri)
            val guessedType = if (name != null) guessMimeTypeFromName(name) else null
            val finalType = resolvedType ?: guessedType ?: "*/*"
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, finalType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openPending = PendingIntent.getActivity(
                this,
                16,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_action_open_file), openPending)
        }

        return builder.build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "SyncClipboard",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PREPARE_UPLOAD_CLIPBOARD = "com.example.syncclipboard.action.NOTIFY_PREPARE_UPLOAD_CLIPBOARD"
        const val ACTION_UPLOAD_TEXT = "com.example.syncclipboard.action.NOTIFY_UPLOAD_TEXT"
        const val ACTION_DOWNLOAD_CLIPBOARD = "com.example.syncclipboard.action.NOTIFY_DOWNLOAD_CLIPBOARD"
        const val ACTION_UPLOAD_FILE = "com.example.syncclipboard.action.NOTIFY_UPLOAD_FILE"
        const val ACTION_CANCEL = "com.example.syncclipboard.action.NOTIFY_CANCEL"
        const val ACTION_CLOSE = "com.example.syncclipboard.action.NOTIFY_CLOSE"
        const val ACTION_FILE_CONFLICT_DECISION = "com.example.syncclipboard.action.NOTIFY_FILE_CONFLICT_DECISION"

        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_OPERATION = "extra_operation"
        const val EXTRA_FILE_URI = "file_uri"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DECISION = "extra_decision"

        private const val DECISION_REPLACE = "replace"
        private const val DECISION_KEEP_BOTH = "keep_both"

        private const val NOTIFICATION_CHANNEL_ID = "syncclipboard_notification_progress"
        private const val NOTIFICATION_ID = 2001
    }
}
