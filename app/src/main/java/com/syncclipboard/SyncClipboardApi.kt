package com.syncclipboard

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 与 SyncClipboard 服务交互的最简 API：
 * - PUT /SyncClipboard.json 上传文本剪贴板
 * - GET /SyncClipboard.json 下载文本剪贴板
 *
 * 认证方式：
 *   header: authorization = "basic " + base64(username:password)
 */
object SyncClipboardApi {

    data class ApiResult<T>(val success: Boolean, val data: T? = null, val errorMessage: String? = null)

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val CALL_TIMEOUT_MS = 25_000L
    private const val FILE_CALL_TIMEOUT_MS = 5 * 60_000L

    private val executor = Executors.newCachedThreadPool()

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

    private fun HttpURLConnection.applyCommonConfig(config: ServerConfig) {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("authorization", buildAuthHeader(config))
        setRequestProperty("Accept", "application/json")
        // 明确声明 keep-alive，尽量复用 TCP 连接，减少重复握手成本（服务端需支持）。
        setRequestProperty("Connection", "Keep-Alive")
    }

    private fun HttpURLConnection.closeQuietly() {
        runCatching { inputStream }.getOrNull()?.runCatching { close() }
        runCatching { errorStream }.getOrNull()?.runCatching { close() }
        runCatching { disconnect() }
    }

    private fun <T> callWithTimeout(
        timeoutMs: Long,
        createConnection: () -> HttpURLConnection,
        block: (HttpURLConnection) -> ApiResult<T>
    ): ApiResult<T> {
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val future = executor.submit<ApiResult<T>> {
            val conn = createConnection()
            connRef.set(conn)
            try {
                block(conn)
            } finally {
                conn.closeQuietly()
            }
        }

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            connRef.get()?.closeQuietly()
            future.cancel(true)
            ApiResult(success = false, errorMessage = "网络请求超时")
        } catch (e: InterruptedException) {
            connRef.get()?.closeQuietly()
            future.cancel(true)
            throw e
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    fun uploadText(config: ServerConfig, text: String): ApiResult<Unit> {
        return try {
            callWithTimeout(
                timeoutMs = CALL_TIMEOUT_MS,
                createConnection = {
                    val url = URL(buildApiUrl(config))
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        applyCommonConfig(config)
                    }
                }
            ) { conn ->
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
                    // 显式消耗并关闭输入流，防止 StrictMode 警告 "reachable from finalizer"
                    runCatching { conn.inputStream.close() }
                    ApiResult(success = true)
                } else {
                    val message = readStreamAsString(conn.errorStream)
                        ?: "${conn.responseCode} ${conn.responseMessage}"
                    ApiResult(success = false, errorMessage = message)
                }
            }
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    /**
     * 获取当前服务器端剪贴板完整 Profile（Type / Clipboard / File）。
     */
    fun getClipboardProfile(config: ServerConfig, timeoutMs: Long = CALL_TIMEOUT_MS): ApiResult<ClipboardProfile> {
        return try {
            callWithTimeout(
                timeoutMs = timeoutMs,
                createConnection = {
                    val url = URL(buildApiUrl(config))
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        applyCommonConfig(config)
                    }
                }
            ) { conn ->
                val code = conn.responseCode
                if (code !in 200..299) {
                    val message = readStreamAsString(conn.errorStream)
                        ?: "${conn.responseCode} ${conn.responseMessage}"
                    return@callWithTimeout ApiResult(success = false, errorMessage = message)
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
            }
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    fun downloadText(config: ServerConfig): ApiResult<String> {
        return try {
            callWithTimeout(
                timeoutMs = CALL_TIMEOUT_MS,
                createConnection = {
                    val url = URL(buildApiUrl(config))
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        applyCommonConfig(config)
                    }
                }
            ) { conn ->
                val code = conn.responseCode
                if (code !in 200..299) {
                    val message = readStreamAsString(conn.errorStream)
                        ?: "${conn.responseCode} ${conn.responseMessage}"
                    return@callWithTimeout ApiResult(success = false, errorMessage = message)
                }

                val text = readStreamAsString(conn.inputStream) ?: ""
                val json = JSONObject(text)
                val type = json.optString("Type", "")
                if (type != "Text") {
                    return@callWithTimeout ApiResult(success = false, errorMessage = "服务器当前剪贴板不是 Text 类型")
                }
                val clipboard = json.optString("Clipboard", "")
                if (clipboard.isEmpty()) {
                    return@callWithTimeout ApiResult(success = false, errorMessage = "服务器返回的剪贴板内容为空")
                }
                ApiResult(success = true, data = clipboard)
            }
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    fun testConnection(config: ServerConfig): ApiResult<Unit> {
        // 只要能正常返回 JSON（无论当前是 Text 还是 File）就认为连接成功
        val result = getClipboardProfile(config)
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
    fun uploadFile(
        config: ServerConfig,
        fileName: String,
        input: InputStream,
        totalBytes: Long = -1L,
        onStage: ((UploadFileStage) -> Unit)? = null,
        onProgress: ((uploadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): ApiResult<Unit> {
        return try {
            // 文件上传可能耗时较长：单独设置更大的总超时，避免“卡死无反馈”
            callWithTimeout(
                timeoutMs = FILE_CALL_TIMEOUT_MS,
                createConnection = {
                    val baseUrl = config.baseUrl.trimEnd('/')
                    val fileUrl = URL("$baseUrl/file/$fileName")
                    (fileUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        doOutput = true
                        applyCommonConfig(config)
                    }
                }
            ) { uploadConn ->
                onStage?.invoke(UploadFileStage.UPLOADING_CONTENT)
                uploadConn.outputStream.use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var uploaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        out.write(buffer, 0, count)
                        uploaded += count
                        onProgress?.invoke(uploaded, totalBytes)
                    }
                }

                onStage?.invoke(UploadFileStage.WAITING_UPLOAD_RESPONSE)
                val uploadCode = uploadConn.responseCode
                if (uploadCode !in 200..299) {
                    val message = readStreamAsString(uploadConn.errorStream)
                        ?: "${uploadConn.responseCode} ${uploadConn.responseMessage}"
                    return@callWithTimeout ApiResult(success = false, errorMessage = message)
                }

                // 第二步：更新 SyncClipboard.json 为 File 类型
                onStage?.invoke(UploadFileStage.UPDATING_PROFILE)
                val syncUrl = URL(buildApiUrl(config))
                val syncConn = (syncUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    applyCommonConfig(config)
                }

                return@callWithTimeout try {
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
                        onStage?.invoke(UploadFileStage.DONE)
                        // 显式关闭输入流
                        runCatching { syncConn.inputStream.close() }
                        ApiResult(success = true)
                    } else {
                        val message = readStreamAsString(syncConn.errorStream)
                            ?: "${syncConn.responseCode} ${syncConn.responseMessage}"
                        ApiResult(success = false, errorMessage = message)
                    }
                } finally {
                    syncConn.closeQuietly()
                }
            }
        } catch (e: Exception) {
            ApiResult(success = false, errorMessage = e.message ?: e.toString())
        }
    }

    enum class UploadFileStage {
        UPLOADING_CONTENT,
        WAITING_UPLOAD_RESPONSE,
        UPDATING_PROFILE,
        DONE
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
        var conn: HttpURLConnection? = null
        return try {
            val baseUrl = config.baseUrl.trimEnd('/')
            val fileUrl = URL("$baseUrl/file/$fileName")

            conn = (fileUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                applyCommonConfig(config)
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
        } finally {
            conn?.closeQuietly()
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
