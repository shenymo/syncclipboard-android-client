package com.syncclipboard

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.util.Locale

object FileTransferUtils {

    fun getPublicDownloadsPath(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    fun findExistingDownloadEntry(resolver: ContentResolver, fileName: String): Uri? {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        resolver.query(
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

    fun generateNonConflictingDownloadName(resolver: ContentResolver, originalName: String): String {
        val dotIndex = originalName.lastIndexOf('.')
        val base = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val ext = if (dotIndex > 0) originalName.substring(dotIndex) else ""

        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (findExistingDownloadEntry(resolver, candidate) == null) {
                return candidate
            }
            index++
        }
    }

    fun guessMimeTypeFromName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return null
        val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    fun createDownloadEntry(
        context: Context,
        resolver: ContentResolver,
        fileName: String
    ): Uri? {
        val mimeType = guessMimeTypeFromName(fileName) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    fun buildFileDownloadProgressCollapsedText(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long): String {
        val percent =
            if (totalBytes > 0) String.format(Locale.ROOT, "%.0f%%", downloadedBytes * 100.0 / totalBytes) else "—"
        val speed = formatSize(speedBytesPerSec) + "/s"
        return "正在下载文件… $percent ($speed)"
    }

    fun buildFileDownloadProgressExpandedText(
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        elapsedSec: Long
    ): String {
        val downloaded = formatSize(downloadedBytes)
        val total = if (totalBytes > 0) formatSize(totalBytes) else "未知大小"
        val speed = formatSize(speedBytesPerSec) + "/s"
        val percent =
            if (totalBytes > 0) String.format(Locale.ROOT, "%.1f%%", downloadedBytes * 100.0 / totalBytes) else "—"
        val elapsed = if (elapsedSec <= 0) "1s" else "${elapsedSec}s"
        return "正在下载文件…\n进度：$downloaded / $total ($percent)\n速度：$speed\n用时：$elapsed"
    }

    fun buildFileUploadProgressCollapsedText(uploadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long): String {
        val percent = if (totalBytes > 0 && uploadedBytes <= totalBytes) {
            val raw = uploadedBytes * 100.0 / totalBytes
            val capped = if (raw >= 100.0) 99.0 else raw
            String.format(Locale.ROOT, "%.0f%%", capped)
        } else {
            "—"
        }
        val speed = formatSize(speedBytesPerSec) + "/s"
        return if (totalBytes > 0 && uploadedBytes >= totalBytes) {
            "正在上传文件… $percent ($speed) 等待服务器响应…"
        } else {
            "正在上传文件… $percent ($speed)"
        }
    }

    fun buildFileUploadProgressExpandedText(
        uploadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        elapsedSec: Long
    ): String {
        val uploaded = formatSize(uploadedBytes)
        val total = if (totalBytes > 0) formatSize(totalBytes) else "未知大小"
        val speed = formatSize(speedBytesPerSec) + "/s"
        val percent = if (totalBytes > 0 && uploadedBytes <= totalBytes) {
            val raw = uploadedBytes * 100.0 / totalBytes
            val capped = if (raw >= 100.0) 99.9 else raw
            String.format(Locale.ROOT, "%.1f%%", capped)
        } else {
            "—"
        }
        val elapsed = if (elapsedSec <= 0) "1s" else "${elapsedSec}s"
        val sizeNote =
            if (totalBytes > 0 && uploadedBytes > totalBytes) "\n提示：文件大小信息可能不准确（已上传超过总大小）" else ""
        return if (totalBytes > 0 && uploadedBytes >= totalBytes) {
            "正在上传文件…\n进度：$uploaded / $total ($percent)\n速度：$speed\n用时：$elapsed\n状态：已发送，等待服务器响应…$sizeNote"
        } else {
            "正在上传文件…\n进度：$uploaded / $total ($percent)\n速度：$speed\n用时：$elapsed$sizeNote"
        }
    }
}
