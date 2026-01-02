package com.syncclipboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object PermissionUtils {
    fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun requestOverlayPermission(context: Context, showToast: Boolean = true) {
        if (showToast) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_overlay_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
