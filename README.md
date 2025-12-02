# SyncClipboard Android Client

一个简单的 Android 端 SyncClipboard 客户端，用来把手机上的文本 / 文件剪贴板与已有的 SyncClipboard 服务器进行同步。

主要特点：
- 通过 HTTP 与已有的 SyncClipboard 服务交互，使用 Basic 认证（`username:token`）。
- 支持上传 / 下载文本剪贴板。
- 支持通过系统「分享」上传文件或图片，并在服务器侧以文件形式保存。
- 提供快捷设置磁贴（Quick Settings Tile），一键上传或下载剪贴板。
- 进度界面支持对话框样式和 BottomSheet 底部弹出两种展示方式。

> 注意：本仓库只包含 Android 客户端，需要自行部署 SyncClipboard 服务端（见根目录 `Syncclipboardapi_files/swagger.json` 中的接口定义）。

## 功能概览

- **服务器配置**
  - 在主界面 `SettingsActivity` 中填写服务端地址、用户名、Token。
  - 配置通过 `SharedPreferences` 持久化存储，应用重启后仍然有效。
  - 提供「测试连接」按钮，会调用 `/SyncClipboard.json` 检查连通性和认证信息。

- **文本剪贴板同步**
  - 上传当前手机系统剪贴板到服务器（操作码 `upload_clipboard`）。
  - 从服务器下载当前剪贴板内容并写入手机系统剪贴板（操作码 `download_clipboard`）。
  - 服务器端剪贴板为 `Type = Text` 时才会写入本地剪贴板。

- **文件上传 / 下载**
  - 通过系统分享入口 `ShareReceiveActivity`，接收 `ACTION_SEND`：
    - `text/plain` 作为文本上传；
    - 图片 / 其他类型文件作为文件上传。
  - 文件上传流程：
    1. `PUT /file/{fileName}` 上传文件内容；
    2. `PUT /SyncClipboard.json` 写入 `Type = File` 和 `File = fileName`。
  - 下载文件时：
    - 若服务器返回的 `ClipboardProfile` 中 `File` 字段非空，则视为文件模式；
    - 文件保存到系统公共 Download 目录，并在界面显示保存路径。

- **快捷设置磁贴**
  - `UploadClipboardTileService`：一键上传当前系统剪贴板；
  - `DownloadClipboardTileService`：一键从服务器下载剪贴板并写入本地。
  - 点击磁贴会启动前台 `ProgressActivity` 显示执行进度和结果。

- **进度显示与 UI 风格**
  - 所有一次性操作（上传 / 下载 / 测试连接）均在 `ProgressActivity` 中执行。
  - 支持两种样式：
    - 对话框样式：半透明对话框悬浮在当前界面之上；
    - BottomSheet 样式：底部弹出的半屏对话框。
  - UI 风格可以在设置页中切换，选择结果通过 `UiStyleStorage` 保存。

## 构建与运行

### 环境要求

- Android Studio（建议使用与 Gradle 8.6 / Kotlin 2.0.21 兼容的版本）。
- Android SDK：
  - `compileSdkVersion = 35`
  - `targetSdkVersion = 35`
  - `minSdkVersion = 31`

### 使用 Gradle 构建

在项目根目录执行：

```bash
./gradlew assembleDebug
```

生成的调试 APK 会位于：

- `app/build/outputs/apk/debug/app-debug.apk`

你可以通过 Android Studio 直接导入工程并运行到真机或模拟器。

## 使用说明

1. **配置服务器**
   - 打开应用进入设置页；
   - 填写：
     - 服务器地址（例如 `http://192.168.x.x:5033`）；
     - 用户名；
     - Token；
   - 点击「保存配置」；
   - 可点击「测试连接」验证与服务器的连通性。

2. **上传 / 下载文本剪贴板**
   - 在系统中复制任意文本；
   - 从状态栏快捷设置中添加并点击「上传剪贴板」磁贴；
   - `ProgressActivity` 会提示上传进度和结果。
   - 要从服务器下载剪贴板：
     - 点击「下载剪贴板」磁贴；
     - 成功后文本会写入系统剪贴板，并在界面上显示。

3. **通过分享上传文本或文件**
   - 在任意应用中使用系统分享：
     - 选择文本 → 分享到「SyncClipboard」→ 作为文本上传；
     - 选择图片 / 文件 → 分享到「SyncClipboard」→ 作为文件上传。
   - 上传进度和结果同样在 `ProgressActivity` 中显示。

4. **下载服务器上的文件**
   - 若服务器剪贴板当前为文件模式（`File` 字段非空）：
     - 执行「下载剪贴板」操作时会触发文件下载；
     - 文件保存到公共 Download 目录，并在界面下方显示完整路径字符串。

## 代码结构概览

- `app/src/main/AndroidManifest.xml`：应用入口、Activity、TileService、权限声明。
- `app/src/main/java/com/example/syncclipboard/SyncClipboardApi.kt`：
  - 封装与 SyncClipboard 服务交互的 HTTP API；
  - 支持上传 / 下载文本、上传 / 下载文件、获取剪贴板 Profile、测试连接。
- `SettingsActivity.kt`：服务器配置界面。
- `ProgressActivity.kt`：上传 / 下载 / 测试连接时的前台进度界面。
- `ShareReceiveActivity.kt`：系统分享入口，将文本 / 文件转发给 `ProgressActivity`。
- `UploadClipboardTileService.kt`、`DownloadClipboardTileService.kt`：快捷设置磁贴入口。
- `ConfigStorage.kt`：使用 `SharedPreferences` 读写服务器配置信息。
- `UiStyleStorage.kt`：保存进度对话框的样式选择。
- `SyncClipboardApp.kt`：`Application` 实现，用于启用 Material Dynamic Color。

## 许可证

当前仓库未在代码中显式声明许可证。如需开源或分发，请根据你的实际需求补充许可证信息。

