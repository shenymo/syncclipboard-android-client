package com.example.syncclipboard

import android.content.Context

/**
 * 使用 SharedPreferences 存储和读取服务器配置。
 */
object ConfigStorage {

    private const val PREF_NAME = "syncclipboard_config"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_TOKEN = "token"

    fun saveConfig(context: Context, config: ServerConfig) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_TOKEN, config.token)
            .apply()
    }

    fun loadConfig(context: Context): ServerConfig? {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val baseUrl = sp.getString(KEY_BASE_URL, null)
        val username = sp.getString(KEY_USERNAME, null)
        val token = sp.getString(KEY_TOKEN, null)
        return if (!baseUrl.isNullOrBlank() && !username.isNullOrBlank() && !token.isNullOrBlank()) {
            ServerConfig(baseUrl, username, token)
        } else {
            null
        }
    }
}

