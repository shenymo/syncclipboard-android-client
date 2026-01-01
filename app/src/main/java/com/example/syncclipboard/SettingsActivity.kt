package com.example.syncclipboard

import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.syncclipboard.ui.theme.SyncClipboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyncClipboardTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var baseUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }

    var longPressCloseSeconds by rememberSaveable { mutableFloatStateOf(2.0f) }
    var autoCloseDelaySeconds by rememberSaveable { mutableFloatStateOf(3.0f) }

    var isTesting by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ConfigStorage.loadConfig(context)?.let { config ->
            baseUrl = config.baseUrl
            username = config.username
            token = config.token
        }
        longPressCloseSeconds = UiStyleStorage.loadLongPressCloseSeconds(context)
        autoCloseDelaySeconds = UiStyleStorage.loadAutoCloseDelaySeconds(context)
        UiStyleStorage.saveProgressStyle(context, UiStyleStorage.STYLE_FLOATING_WINDOW)
    }

    fun openOverlayPermissionSettings() {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_title)) }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.label_base_url),
                style = MaterialTheme.typography.labelLarge
            )
            TextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = "例如：http://192.168.5.194:5033") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true
            )

            Text(
                text = stringResource(id = R.string.label_username),
                style = MaterialTheme.typography.labelLarge
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                singleLine = true
            )

            Text(
                text = stringResource(id = R.string.label_token),
                style = MaterialTheme.typography.labelLarge
            )
            TextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            Text(
                text = stringResource(id = R.string.settings_ui_style_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(id = R.string.settings_ui_style_floating_window),
                style = MaterialTheme.typography.bodyMedium
            )
            if (!Settings.canDrawOverlays(context)) {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.settings_overlay_permission_required)
                            )
                        }
                        openOverlayPermissionSettings()
                    }
                ) {
                    Text(text = "授予悬浮窗权限")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            Text(
                text = "悬浮窗设置",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "长按关闭时间: ${String.format(\"%.1f\", longPressCloseSeconds)} 秒",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = longPressCloseSeconds,
                onValueChange = {
                    longPressCloseSeconds = it
                    UiStyleStorage.saveLongPressCloseSeconds(context, it)
                },
                valueRange = 0.5f..5.0f,
                steps = 9
            )

            Text(
                text = "自动关闭延迟: ${String.format(\"%.1f\", autoCloseDelaySeconds)} 秒",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = autoCloseDelaySeconds,
                onValueChange = {
                    autoCloseDelaySeconds = it
                    UiStyleStorage.saveAutoCloseDelaySeconds(context, it)
                },
                valueRange = 0.0f..10.0f,
                steps = 19
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val trimmedBaseUrl = baseUrl.trim()
                        val trimmedUsername = username.trim()
                        val trimmedToken = token.trim()
                        if (trimmedBaseUrl.isEmpty() || trimmedUsername.isEmpty() || trimmedToken.isEmpty()) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.error_config_missing)
                                )
                            }
                            return@Button
                        }
                        ConfigStorage.saveConfig(
                            context,
                            ServerConfig(trimmedBaseUrl, trimmedUsername, trimmedToken)
                        )
                        scope.launch { snackbarHostState.showSnackbar(message = "配置已保存") }
                    }
                ) {
                    Text(text = stringResource(id = R.string.button_save))
                }

                OutlinedButton(
                    onClick = {
                        if (isTesting) return@OutlinedButton
                        val trimmedBaseUrl = baseUrl.trim()
                        val trimmedUsername = username.trim()
                        val trimmedToken = token.trim()
                        if (trimmedBaseUrl.isEmpty() || trimmedUsername.isEmpty() || trimmedToken.isEmpty()) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.error_config_missing)
                                )
                            }
                            return@OutlinedButton
                        }

                        val config = ServerConfig(trimmedBaseUrl, trimmedUsername, trimmedToken)
                        scope.launch {
                            isTesting = true
                            val result = withContext(Dispatchers.IO) {
                                SyncClipboardApi.testConnection(config)
                            }
                            isTesting = false
                            if (result.success) {
                                snackbarHostState.showSnackbar(message = "连接成功")
                            } else {
                                snackbarHostState.showSnackbar(
                                    message = "连接失败: ${result.errorMessage ?: "未知错误"}"
                                )
                            }
                        }
                    },
                    enabled = !isTesting
                ) {
                    Text(text = stringResource(id = R.string.button_test_connection))
                }
            }
        }
    }
}
