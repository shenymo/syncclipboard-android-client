package com.example.syncclipboard

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 简单设置界面：
 * - 填写服务器地址、用户名、Token
 * - 保存到 SharedPreferences
 * - “测试连接”按钮调用服务器 /SyncClipboard.json
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editBaseUrl = findViewById<EditText>(R.id.editBaseUrl)
        val editUsername = findViewById<EditText>(R.id.editUsername)
        val editToken = findViewById<EditText>(R.id.editToken)
        val buttonSave = findViewById<Button>(R.id.buttonSave)
        val buttonTest = findViewById<Button>(R.id.buttonTest)

        // 读取已有配置
        ConfigStorage.loadConfig(this)?.let { config ->
            editBaseUrl.setText(config.baseUrl)
            editUsername.setText(config.username)
            editToken.setText(config.token)
        }

        buttonSave.setOnClickListener {
            val baseUrl = editBaseUrl.text.toString().trim()
            val username = editUsername.text.toString().trim()
            val token = editToken.text.toString().trim()

            if (baseUrl.isEmpty() || username.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_config_missing), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val config = ServerConfig(baseUrl, username, token)
            ConfigStorage.saveConfig(this, config)
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        buttonTest.setOnClickListener {
            val baseUrl = editBaseUrl.text.toString().trim()
            val username = editUsername.text.toString().trim()
            val token = editToken.text.toString().trim()

            if (baseUrl.isEmpty() || username.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_config_missing), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val config = ServerConfig(baseUrl, username, token)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    SyncClipboardApi.testConnection(config)
                }
                if (result.success) {
                    Toast.makeText(this@SettingsActivity, "连接成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        "连接失败: ${result.errorMessage ?: "未知错误"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

