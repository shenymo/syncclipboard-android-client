package com.syncclipboard

/**
 * 服务器配置：
 * - baseUrl: SyncClipboard 服务地址，例如 http://192.168.5.194:5033
 * - username/password: 用于 basic 认证，按照 "basic base64(username:password)" 拼接。
 */
data class ServerConfig(
    val baseUrl: String,
    val username: String,
    val token: String
)
