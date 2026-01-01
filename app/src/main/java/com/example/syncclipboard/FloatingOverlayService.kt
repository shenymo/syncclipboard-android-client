package com.example.syncclipboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.syncclipboard.ui.theme.SyncClipboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

/**
 * 悬浮窗进度展示 + 后台执行上传/下载任务。
 *
 * 设计目标：
 * - 不启动任何 Activity（避免长时间打断前台应用）
 * - 通过 TYPE_APPLICATION_OVERLAY 展示小窗，默认不抢焦点，允许点击穿透到后方应用
 * - 作为前台服务运行，提升稳定性（Android 12+）
 */
class FloatingOverlayService : LifecycleService() {

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    private val statusTextState = mutableStateOf("")
    private val isSuccessState = mutableStateOf(false)
    private val isErrorState = mutableStateOf(false)
    private val operationState = mutableStateOf("")

    private val overlayViewModelStoreOwner = OverlayViewModelStoreOwner()
    private val overlaySavedStateOwner = OverlaySavedStateOwner(this)

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        when (intent?.action) {
            ACTION_UPLOAD_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                startUploadText(text)
            }
            ACTION_DOWNLOAD_CLIPBOARD -> {
                startDownloadClipboard()
            }
            else -> {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        overlayViewModelStoreOwner.clear()
        super.onDestroy()
    }

    private fun startUploadText(text: String) {
        if (text.isEmpty()) {
            stopSelf()
            return
        }

        operationState.value = ProgressActivity.OP_UPLOAD_CLIPBOARD
        isSuccessState.value = false
        isErrorState.value = false
        statusTextState.value = getString(R.string.progress_upload_clipboard)

        showOverlay()
        updateForegroundNotification()

        lifecycleScope.launch {
            val config = ConfigStorage.loadConfig(this@FloatingOverlayService)
            if (config == null) {
                isErrorState.value = true
                statusTextState.value = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_config_missing)
                )
                updateForegroundNotification()
                autoCloseIfNeeded()
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                SyncClipboardApi.uploadText(config, text)
            }

            if (result.success) {
                isSuccessState.value = true
                statusTextState.value = getString(R.string.toast_upload_success)
            } else {
                isErrorState.value = true
                statusTextState.value = getString(
                    R.string.toast_error_prefix,
                    result.errorMessage ?: "未知错误"
                )
            }
            updateForegroundNotification()
            autoCloseIfNeeded()
        }
    }

    private fun startDownloadClipboard() {
        operationState.value = ProgressActivity.OP_DOWNLOAD_CLIPBOARD
        isSuccessState.value = false
        isErrorState.value = false
        statusTextState.value = getString(R.string.progress_download_clipboard)

        showOverlay()
        updateForegroundNotification()

        lifecycleScope.launch {
            val config = ConfigStorage.loadConfig(this@FloatingOverlayService)
            if (config == null) {
                isErrorState.value = true
                statusTextState.value = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_config_missing)
                )
                updateForegroundNotification()
                autoCloseIfNeeded()
                return@launch
            }

            val profileResult = withContext(Dispatchers.IO) {
                SyncClipboardApi.getClipboardProfile(config)
            }
            if (!profileResult.success || profileResult.data == null) {
                isErrorState.value = true
                statusTextState.value = getString(
                    R.string.toast_error_prefix,
                    profileResult.errorMessage ?: getString(R.string.error_server_not_text)
                )
                updateForegroundNotification()
                autoCloseIfNeeded()
                return@launch
            }

            val profile = profileResult.data
            val normalizedType = profile.type.trim().lowercase(Locale.ROOT)

            val fileNameFromFileField = profile.file?.trim()?.takeIf { it.isNotEmpty() }
            if (fileNameFromFileField != null) {
                isErrorState.value = true
                statusTextState.value = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_server_not_text)
                )
                updateForegroundNotification()
                autoCloseIfNeeded()
                return@launch
            }

            val fileNameFromClipboardField =
                if (normalizedType == "file") profile.clipboard?.trim()?.takeIf { it.isNotEmpty() } else null
            if (fileNameFromClipboardField != null) {
                isErrorState.value = true
                statusTextState.value = getString(
                    R.string.toast_error_prefix,
                    getString(R.string.error_server_not_text)
                )
                updateForegroundNotification()
                autoCloseIfNeeded()
                return@launch
            }

            if (normalizedType == "text") {
                val text = profile.clipboard ?: ""
                if (text.isEmpty()) {
                    isErrorState.value = true
                    statusTextState.value = getString(
                        R.string.toast_error_prefix,
                        getString(R.string.error_server_not_text)
                    )
                    updateForegroundNotification()
                    autoCloseIfNeeded()
                    return@launch
                }

                val clipboardManager =
                    getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SyncClipboard", text)
                clipboardManager.setPrimaryClip(clip)

                isSuccessState.value = true
                statusTextState.value = getString(R.string.toast_download_success)
                updateForegroundNotification()
                autoCloseIfNeeded()
                return@launch
            }

            isErrorState.value = true
            statusTextState.value = getString(
                R.string.toast_error_prefix,
                getString(R.string.error_server_not_text)
            )
            updateForegroundNotification()
            autoCloseIfNeeded()
        }
    }

    private fun autoCloseIfNeeded() {
        val delaySeconds = UiStyleStorage.loadAutoCloseDelaySeconds(this)
        if (delaySeconds <= 0f) return
        lifecycleScope.launch {
            delay((delaySeconds * 1000).toLong())
            stopSelf()
        }
    }

    private fun showOverlay() {
        if (view != null) return

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

        val composeView = ComposeView(this).apply {
            // ComposeView 在 Activity 外使用时，需要手动提供 ViewTree owners，否则会因缺失 LifecycleOwner 崩溃。
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeViewModelStoreOwner(overlayViewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(overlaySavedStateOwner)
            setContent {
                val statusText by rememberUpdatedState(statusTextState.value)
                val isSuccess by rememberUpdatedState(isSuccessState.value)
                val isError by rememberUpdatedState(isErrorState.value)
                val operation by rememberUpdatedState(operationState.value)
                val moveHandler = remember {
                    { dx: Float, dy: Float -> moveBy(dx, dy) }
                }
                SyncClipboardTheme {
                    FloatingServiceCard(
                        operation = operation,
                        statusText = statusText,
                        isSuccess = isSuccess,
                        isError = isError,
                        onMoveBy = moveHandler,
                        onClose = { stopSelf() }
                    )
                }
            }
        }

        try {
            windowManager.addView(composeView, overlayParams)
            view = composeView
            params = overlayParams
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun hideOverlay() {
        val current = view ?: return
        view = null
        params = null
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

    private fun ensureForeground() {
        val notification = buildForegroundNotification(
            title = getString(R.string.app_name),
            content = statusTextState.value.ifEmpty { getString(R.string.settings_ui_style_title) }
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID,
            buildForegroundNotification(
                title = getString(R.string.app_name),
                content = statusTextState.value.ifEmpty { getString(R.string.settings_ui_style_title) }
            )
        )
    }

    private fun buildForegroundNotification(title: String, content: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SyncClipboard",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    @Composable
    private fun FloatingServiceCard(
        operation: String,
        statusText: String,
        isSuccess: Boolean,
        isError: Boolean,
        onMoveBy: (dx: Float, dy: Float) -> Unit,
        onClose: () -> Unit
    ) {
        val iconVector = when {
            isSuccess -> Icons.Default.Check
            isError -> Icons.Default.Close
            operation == ProgressActivity.OP_DOWNLOAD_CLIPBOARD -> Icons.Default.CloudDownload
            else -> Icons.Default.CloudUpload
        }

        val iconColor = when {
            isSuccess -> MaterialTheme.colorScheme.primary
            isError -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }

        val longPressSeconds = remember { UiStyleStorage.loadLongPressCloseSeconds(this) }

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
                                } catch (_: TimeoutCancellationException) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_UPLOAD_TEXT = "com.example.syncclipboard.action.UPLOAD_TEXT"
        const val ACTION_DOWNLOAD_CLIPBOARD = "com.example.syncclipboard.action.DOWNLOAD_CLIPBOARD"
        const val EXTRA_TEXT = "extra_text"

        private const val NOTIFICATION_CHANNEL_ID = "syncclipboard_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private class OverlayViewModelStoreOwner : ViewModelStoreOwner {
        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore
            get() = store

        fun clear() {
            store.clear()
        }
    }

    private class OverlaySavedStateOwner(
        private val lifecycleOwner: androidx.lifecycle.LifecycleOwner
    ) : SavedStateRegistryOwner {
        private val controller: SavedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle
            get() = lifecycleOwner.lifecycle

        override val savedStateRegistry
            get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
        }
    }
}
