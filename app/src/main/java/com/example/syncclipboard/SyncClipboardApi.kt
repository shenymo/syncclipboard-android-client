package com.example.syncclipboard

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
}

