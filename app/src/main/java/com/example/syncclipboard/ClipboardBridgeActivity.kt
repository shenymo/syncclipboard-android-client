package com.example.syncclipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 仅用于从快捷设置磁贴触发时短暂获取前台焦点，读取系统剪贴板内容后立刻退出。
 *
 * 目的：
 * - 避免在后台/无焦点状态读取剪贴板被系统限制
 * - 读取到内容后交给 FloatingOverlayService 执行上传并展示悬浮窗进度
 */
class ClipboardBridgeActivity : AppCompatActivity() {

    private var handled = false
    private var mode: String = MODE_UPLOAD

    override fun onCreate(savedInstanceState: Bundle?) {
        // 透明/无界面 Activity：尽量减少对用户的打断
        super.onCreate(savedInstanceState)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UPLOAD

        if (mode == MODE_NOTIFICATION_PREPARE_UPLOAD) {
            handled = true
            val serviceIntent = Intent(this, NotificationProgressService::class.java).apply {
                action = NotificationProgressService.ACTION_PREPARE_UPLOAD_CLIPBOARD
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            finish()
            overridePendingTransition(0, 0)
            return
        }

        if (mode == MODE_NOTIFICATION_DOWNLOAD) {
            handled = true
            val serviceIntent = Intent(this, NotificationProgressService::class.java).apply {
                action = NotificationProgressService.ACTION_DOWNLOAD_CLIPBOARD
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            finish()
            overridePendingTransition(0, 0)
            return
        }

        if (mode == MODE_DOWNLOAD || mode == MODE_UPLOAD) {
            if (!Settings.canDrawOverlays(this)) {
            // 回退到应用内界面，引导用户授予悬浮窗权限
            val operation =
                if (mode == MODE_DOWNLOAD) ProgressActivity.OP_DOWNLOAD_CLIPBOARD
                else ProgressActivity.OP_UPLOAD_CLIPBOARD
            startActivity(
                Intent(this, ProgressActivity::class.java).apply {
                    putExtra(ProgressActivity.EXTRA_OPERATION, operation)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            finish()
            overridePendingTransition(0, 0)
            return
        }
        }

        if (mode == MODE_DOWNLOAD) {
            handled = true
            val intent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_DOWNLOAD_CLIPBOARD
            }
            ContextCompat.startForegroundService(this, intent)
            finish()
            overridePendingTransition(0, 0)
            return
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        if (mode != MODE_UPLOAD && mode != MODE_NOTIFICATION_UPLOAD_EXECUTE) return
        handled = true

        // 等到窗口真正获得焦点后再读剪贴板，避免系统判定“应用不在前台/无焦点”而拒绝。
        window.decorView.post {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboardManager.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this).toString()
            } else {
                ""
            }

            if (text.isEmpty()) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_error_prefix, getString(R.string.error_clipboard_empty)),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                overridePendingTransition(0, 0)
                return@post
            }

            if (mode == MODE_NOTIFICATION_UPLOAD_EXECUTE) {
                val serviceIntent = Intent(this, NotificationProgressService::class.java).apply {
                    action = NotificationProgressService.ACTION_UPLOAD_TEXT
                    putExtra(NotificationProgressService.EXTRA_OPERATION, ProgressActivity.OP_UPLOAD_CLIPBOARD)
                    putExtra(NotificationProgressService.EXTRA_TEXT, text)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                val intent = Intent(this, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_UPLOAD_TEXT
                    putExtra(FloatingOverlayService.EXTRA_TEXT, text)
                }
                ContextCompat.startForegroundService(this, intent)
            }

            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_MODE = "bridge_mode"
        const val MODE_UPLOAD = "upload"
        const val MODE_DOWNLOAD = "download"
        const val MODE_NOTIFICATION_PREPARE_UPLOAD = "notification_prepare_upload"
        const val MODE_NOTIFICATION_UPLOAD_EXECUTE = "notification_upload_execute"
        const val MODE_NOTIFICATION_DOWNLOAD = "notification_download"
    }
}
