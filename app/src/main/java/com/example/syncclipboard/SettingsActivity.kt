package com.example.syncclipboard

import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.syncclipboard.ui.theme.SyncClipboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 简单设置界面：
 * - 填写服务器地址、用户名、密码
 * - 保存到 SharedPreferences
 * - “测试连接”按钮调用服务器 /SyncClipboard.json
 */
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

    var progressUiStyle by rememberSaveable { mutableStateOf(UiStyleStorage.STYLE_DIALOG) }
    var bottomSheetCancelOnTouchOutside by rememberSaveable { mutableStateOf(false) }

    var isTesting by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val currentContext by rememberUpdatedState(context)
    LaunchedEffect(Unit) {
        ConfigStorage.loadConfig(context)?.let { config ->
            baseUrl = config.baseUrl
            username = config.username
            token = config.token
        }
        progressUiStyle = UiStyleStorage.loadProgressStyle(context)
        bottomSheetCancelOnTouchOutside = UiStyleStorage.loadBottomSheetCancelOnTouchOutside(context)
    }

    fun openOverlayPermissionSettings() {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${currentContext.packageName}")
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        currentContext.startActivity(intent)
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingRadioRow(
                    selected = progressUiStyle == UiStyleStorage.STYLE_DIALOG,
                    text = stringResource(id = R.string.settings_ui_style_dialog),
                    onClick = {
                        progressUiStyle = UiStyleStorage.STYLE_DIALOG
                        UiStyleStorage.saveProgressStyle(context, UiStyleStorage.STYLE_DIALOG)
                    }
                )
                SettingRadioRow(
                    selected = progressUiStyle == UiStyleStorage.STYLE_BOTTOM_SHEET,
                    text = stringResource(id = R.string.settings_ui_style_bottom_sheet),
                    onClick = {
                        progressUiStyle = UiStyleStorage.STYLE_BOTTOM_SHEET
                        UiStyleStorage.saveProgressStyle(context, UiStyleStorage.STYLE_BOTTOM_SHEET)
                    }
                )
                SettingRadioRow(
                    selected = progressUiStyle == UiStyleStorage.STYLE_FLOATING_WINDOW,
                    text = stringResource(id = R.string.settings_ui_style_floating_window),
                    onClick = {
                        progressUiStyle = UiStyleStorage.STYLE_FLOATING_WINDOW
                        UiStyleStorage.saveProgressStyle(context, UiStyleStorage.STYLE_FLOATING_WINDOW)
                        if (!Settings.canDrawOverlays(context)) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.settings_overlay_permission_required)
                                )
                            }
                            openOverlayPermissionSettings()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()

            Text(
                text = stringResource(id = R.string.settings_bottom_sheet_behavior_title),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        bottomSheetCancelOnTouchOutside = !bottomSheetCancelOnTouchOutside
                        UiStyleStorage.saveBottomSheetCancelOnTouchOutside(
                            context,
                            bottomSheetCancelOnTouchOutside
                        )
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = bottomSheetCancelOnTouchOutside,
                    onCheckedChange = { checked ->
                        bottomSheetCancelOnTouchOutside = checked
                        UiStyleStorage.saveBottomSheetCancelOnTouchOutside(context, checked)
                    }
                )
                Text(text = stringResource(id = R.string.settings_bottom_sheet_cancel_on_touch_outside))
            }

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

@Composable
private fun SettingRadioRow(
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = text)
    }
}
