package com.example.syncclipboard

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与 SyncClipboard 服务交互的最简 API：
 * - PUT /SyncClipboard.json 上传文本剪贴板
 * - GET /SyncClipboard.json 下载文本剪贴板
 *
 * 认证方式：
 *   header: authorization = "basic " + base64(username:token)
 */
object SyncClipboardApi {

    data class ApiResult<T>(val success: Boolean, val data: T? = null, val errorMessage: String? = null)

    /**
     * /SyncClipboard.json 返回的完整信息，用于区分 Text / File 等类型。
     */
    data class ClipboardProfile(
        val type: String,
        val clipboard: String?,
        val file: String?
    )

    private fun buildApiUrl(config: ServerConfig): String {
        val trimmed = config.baseUrl.trimEnd('/')
        return "$trimmed/SyncClipboard.json"
    }

    private fun buildAuthHeader(config: ServerConfig): String {
        val raw = "${config.username}:${config.token}"
        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "basic $encoded"
    }

    fun uploadText(config: ServerConfig, text: String): ApiResult<Unit> {
        return try {
            val url = URL(buildApiUrl(config))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("authorization", buildAuthHeader(config))
            }

            val body = JSONObject().apply {
                put("File", "")
                put("Clipboard", text)
                put("Type", "Text")
            }.toString()

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val code = conn.responseCode
            if (code in 200..299) {
                ApiResult(success = true)
            } else {
                val message = readStreamAsString(conn.errorStream)
                    ?: "${conn.responseCode} ${conn.responseMessage}"
                ApiResult(success = false, errorMessage = message)
            }
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    /**
     * 获取当前服务器端剪贴板完整 Profile（Type / Clipboard / File）。
     */
    fun getClipboardProfile(config: ServerConfig): ApiResult<ClipboardProfile> {
        return try {
            val url = URL(buildApiUrl(config))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("authorization", buildAuthHeader(config))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val message = readStreamAsString(conn.errorStream)
                    ?: "${conn.responseCode} ${conn.responseMessage}"
                return ApiResult(success = false, errorMessage = message)
            }

            val text = readStreamAsString(conn.inputStream) ?: ""
            val json = JSONObject(text)
            val type = json.optString("Type", "")
            val clipboard = json.optString("Clipboard", null)
            val file = json.optString("File", null)
            ApiResult(
                success = true,
                data = ClipboardProfile(
                    type = type,
                    clipboard = clipboard,
                    file = file
                )
            )
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    fun downloadText(config: ServerConfig): ApiResult<String> {
        return try {
            val url = URL(buildApiUrl(config))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("authorization", buildAuthHeader(config))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val message = readStreamAsString(conn.errorStream)
                    ?: "${conn.responseCode} ${conn.responseMessage}"
                return ApiResult(success = false, errorMessage = message)
            }

            val text = readStreamAsString(conn.inputStream) ?: ""
            val json = JSONObject(text)
            val type = json.optString("Type", "")
            if (type != "Text") {
                return ApiResult(success = false, errorMessage = "服务器当前剪贴板不是 Text 类型")
            }
            val clipboard = json.optString("Clipboard", "")
            if (clipboard.isEmpty()) {
                return ApiResult(success = false, errorMessage = "服务器返回的剪贴板内容为空")
            }
            ApiResult(success = true, data = clipboard)
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    fun testConnection(config: ServerConfig): ApiResult<Unit> {
        // 直接调用 downloadText，只要能正常返回 JSON 就认为连接成功
        val result = downloadText(config)
        return if (result.success) {
            ApiResult(success = true)
        } else {
            ApiResult(success = false, errorMessage = result.errorMessage)
        }
    }

    /**
     * 上传文件到服务器：
     * 1) PUT /file/{fileName} 上传文件内容
     * 2) PUT /SyncClipboard.json 设置 Type = File, File = fileName
     */
    fun uploadFile(config: ServerConfig, fileName: String, input: InputStream): ApiResult<Unit> {
        return try {
            val baseUrl = config.baseUrl.trimEnd('/')
            val fileUrl = URL("$baseUrl/file/$fileName")

            // 第一步：上传文件内容
            val uploadConn = (fileUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("authorization", buildAuthHeader(config))
            }

            uploadConn.outputStream.use { out ->
                copyStream(input, out)
            }

            val uploadCode = uploadConn.responseCode
            if (uploadCode !in 200..299) {
                val message = readStreamAsString(uploadConn.errorStream)
                    ?: "${uploadConn.responseCode} ${uploadConn.responseMessage}"
                return ApiResult(success = false, errorMessage = message)
            }

            // 第二步：更新 SyncClipboard.json 为 File 类型
            val syncUrl = URL(buildApiUrl(config))
            val syncConn = (syncUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("authorization", buildAuthHeader(config))
            }

            val body = JSONObject().apply {
                put("File", fileName)
                put("Clipboard", "")
                put("Type", "File")
            }.toString()

            OutputStreamWriter(syncConn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val syncCode = syncConn.responseCode
            if (syncCode in 200..299) {
                ApiResult(success = true)
            } else {
                val message = readStreamAsString(syncConn.errorStream)
                    ?: "${syncConn.responseCode} ${syncConn.responseMessage}"
                ApiResult(success = false, errorMessage = message)
            }
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    /**
     * 下载服务器上指定文件名的内容到给定输出流。
     * onProgress 回调用于报告下载进度（已下载字节数 / 总字节数），totalBytes 可能为 -1 表示未知。
     * 具体保存位置由调用方决定。
     */
    fun downloadFileToStream(
        config: ServerConfig,
        fileName: String,
        out: OutputStream,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): ApiResult<Unit> {
        return try {
            val baseUrl = config.baseUrl.trimEnd('/')
            val fileUrl = URL("$baseUrl/file/$fileName")

            val conn = (fileUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("authorization", buildAuthHeader(config))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val message = readStreamAsString(conn.errorStream)
                    ?: "${conn.responseCode} ${conn.responseMessage}"
                return ApiResult(success = false, errorMessage = message)
            }

            val total = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                    downloaded += count
                    onProgress?.invoke(downloaded, total)
                }
            }

            ApiResult(success = true)
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    private fun readStreamAsString(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line == null) break
                    sb.append(line)
                }
                sb.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    // 复制流的通用工具目前只用于无进度场景，保留以备后续使用。
    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
    }
}
