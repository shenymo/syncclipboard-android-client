package com.example.syncclipboard

/**
 * 悬浮窗进度展示所需的操作类型标识。
 *
 * 只保留悬浮窗样式，因此不再承载任何 UI 样式选择逻辑。
 */
object SyncOperation {
    const val UPLOAD_CLIPBOARD = "upload_clipboard"
    const val DOWNLOAD_CLIPBOARD = "download_clipboard"
    const val UPLOAD_FILE = "upload_file"
    const val UPLOAD_SHARED_TEXT = "upload_shared_text"
}
