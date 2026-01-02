# SyncClipboard Android Client

一个用于 **SyncClipboard** 系统的 Android 客户端：在 Android 设备与 SyncClipboard 服务端之间同步剪贴板内容，支持文本与文件（通过分享/下载）。

本仓库仅包含 Android 客户端。服务端请参考（或部署兼容实现）：https://github.com/Jeric-X/SyncClipboard

## 目录

- [功能概览](#功能概览)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [使用说明](#使用说明)
- [服务端兼容与接口](#服务端兼容与接口)
- [权限与安全说明](#权限与安全说明)
- [从源码构建](#从源码构建)
- [可选：Release 签名](#可选release-签名)
- [CI（GitHub Actions）](#cigithub-actions)
- [项目结构](#项目结构)
- [常见问题](#常见问题)
- [许可证](#许可证)

## 功能概览

- 文本剪贴板：上传/下载。
- 文件上传：从任意 App 的分享菜单将文件/图片“分享至 SyncClipboard”上传到服务端，同时将服务端剪贴板标记为 `File`。
- 文件下载：当服务端剪贴板为文件时，下载到系统下载目录下的 `Download/SyncClipboard/` 子目录；下载后可一键调用系统应用打开。
- 同名冲突处理：目标目录已存在同名文件时，可选择“替换”或“重命名保存（保留两者）”，重命名规则形如 `name (2).ext`。
- 快捷设置磁贴（Quick Settings Tiles）：提供“上传剪贴板”“下载剪贴板”两个磁贴，便于快速触发同步。
- 悬浮窗进度展示：上传/下载过程由前台 Service 执行并以悬浮窗展示进度（需要系统悬浮窗权限）；支持自定义长按关闭时长与自动关闭延迟。
- 认证：HTTP Basic Auth（请求头 `authorization: basic <base64(username:password)>`）。
- UI：Material 3 + Jetpack Compose（设置页为 Compose），悬浮窗使用传统 View 以提升刷新稳定性。

## 环境要求

- Android 12+（minSdk 31）
- compileSdk/targetSdk：35
- JDK：17+
- Android Studio：建议 Koala 或更新版本

## 快速开始

1) 构建并安装（调试版）：

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

2) 首次配置：

- 打开应用（启动页为设置页）。
- 填写：
  - 服务器地址：例如 `http://192.168.1.100:5033`
  - 用户名
  - 密码（或 Token）
- 点击“保存”，再点击“测试连接”。

3) 添加快捷磁贴：

- 下拉系统快捷设置面板 → 编辑 → 将“上传剪贴板”“下载剪贴板”拖入活动区域。

## 使用说明

### 上传文本剪贴板（手机 → 服务端）

- 复制一段文本到系统剪贴板。
- 点击快捷设置中的“上传剪贴板”磁贴。

实现要点：磁贴会启动一个透明的 `ClipboardBridgeActivity` 来获取前台焦点后读取系统剪贴板，再交给 `FloatingOverlayService` 执行上传并展示进度（避免后台无焦点读取剪贴板被系统限制）。

### 下载文本剪贴板（服务端 → 手机）

- 点击快捷设置中的“下载剪贴板”磁贴。
- 若服务端当前剪贴板类型为 `Text`，客户端会把内容写入手机系统剪贴板。

### 上传文件/图片（手机 → 服务端）

- 在相册/文件管理器等任意 App 选择文件/图片 → 分享 → 选择 “SyncClipboard”。
- 客户端会通过悬浮窗展示上传进度。

### 下载文件（服务端 → 手机）

- 点击“下载剪贴板”磁贴。
- 若服务端剪贴板类型为 `File`（或返回内容可解析为文件名），客户端会下载文件到：
  - 系统下载目录：`Download/SyncClipboard/`
- 如遇同名文件冲突，会弹出操作按钮：
  - 替换：覆盖（复用同一条下载记录）
  - 重命名保存：新建文件名并保存（例如 `image (2).png`）
- 下载完成后可点击“打开”调用系统应用打开该文件（通过 `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`）。

## 服务端兼容与接口

客户端按如下方式与服务端交互（基于 `HttpURLConnection`）：

- `GET /SyncClipboard.json`：获取当前剪贴板 Profile（`Type`/`Clipboard`/`File`）
- `PUT /SyncClipboard.json`：
  - 上传文本：`Type=Text, Clipboard=<text>, File=""`
  - 标记文件：`Type=File, File=<filename>, Clipboard=""`
- `PUT /file/{filename}`：上传文件原始内容（stream）
- `GET /file/{filename}`：下载文件原始内容（stream）

文件下载时，客户端优先使用 `File` 字段作为文件名；若 `Type=file` 且 `File` 为空，则尝试从 `Clipboard` 字段推断文件名（详见 `app/src/main/java/com/syncclipboard/FloatingOverlayService.kt` 的下载逻辑）。

## 权限与安全说明

### 权限

- `android.permission.INTERNET`：访问服务端。
- `android.permission.SYSTEM_ALERT_WINDOW`：悬浮窗进度展示所需（需要引导用户在系统设置中手动开启）。
- `android.permission.VIBRATE`：悬浮窗交互时的震动反馈。

### 网络与安全

- 认证为 HTTP Basic Auth；建议在公网或不可信网络环境中使用 **HTTPS**，避免凭据与内容明文传输。
- `AndroidManifest.xml` 中 `android:usesCleartextTraffic="true"` 已开启明文 HTTP 访问，便于局域网直连；如需强制 HTTPS 可自行修改并配合服务端部署。

## 从源码构建

### 构建 Debug / Release APK

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

产物目录：

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`（若未配置签名，则为 unsigned APK）

### 在 Android Studio 运行

- 用 Android Studio 打开工程根目录。
- 确保本机已配置 Android SDK（`local.properties` 中为 `sdk.dir=...`）。
- 选择 `app` 运行配置，连接真机或启动模拟器运行。

## 可选：Release 签名

`app/build.gradle` 支持“可选 release 签名”：仅当你提供以下 Gradle 属性时才会对 `release` 包进行签名，否则 `assembleRelease` 仍会生成未签名 APK。

需要的属性键：

- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

配置方式（二选一）：

- 在本机 `~/.gradle/gradle.properties` 或项目 `gradle.properties`/`local.properties` 中配置上述属性
- 在 CI 中通过环境变量传入：`ORG_GRADLE_PROJECT_RELEASE_STORE_FILE` 等（Gradle 会自动映射为 project properties）

## CI（GitHub Actions）

仓库自带手动触发的工作流（不在 push 时自动构建）：

- `.github/workflows/android-build.yml`：可选构建 debug/release，并可选创建 GitHub Release
- `.github/workflows/android-debug.yml`：仅构建 debug APK 并上传 artifact

## 项目结构

- `app/src/main/AndroidManifest.xml`：权限声明、入口 Activity、分享入口与磁贴/Service 注册
- `app/src/main/java/com/syncclipboard/SettingsActivity.kt`：设置页（服务器地址/用户名/密码、悬浮窗偏好、测试连接）
- `app/src/main/java/com/syncclipboard/SyncClipboardApi.kt`：与服务端通信的最小 API（文本/文件上传下载）
- `app/src/main/java/com/syncclipboard/FloatingOverlayService.kt`：悬浮窗 UI + 后台执行上传/下载任务
- `app/src/main/java/com/syncclipboard/ShareReceiveActivity.kt`：系统分享入口（接收文本或文件并触发上传）
- `app/src/main/java/com/syncclipboard/UploadClipboardTileService.kt`：上传剪贴板磁贴
- `app/src/main/java/com/syncclipboard/DownloadClipboardTileService.kt`：下载剪贴板磁贴
- `app/src/main/java/com/syncclipboard/FileTransferUtils.kt`：下载目录/重名策略/进度文案/类型推断等工具
- `app/src/main/java/com/syncclipboard/ConfigStorage.kt`、`UiStyleStorage.kt`：SharedPreferences 持久化配置

## 常见问题

### 1) 点击磁贴没反应 / 读取不到剪贴板？

- Android 对后台读取剪贴板有限制。本项目通过 `ClipboardBridgeActivity` 获取前台焦点后再读取剪贴板；若仍不生效，优先检查系统是否限制了该应用的后台启动/悬浮窗权限。

### 2) 为什么需要悬浮窗权限？

- 上传/下载任务由 `FloatingOverlayService` 执行并展示进度。若未授予“在其他应用上层显示”权限，磁贴与分享入口会引导你打开系统设置授权。

### 3) 文件下载保存到哪里？

- 保存到系统下载目录下的 `Download/SyncClipboard/` 子目录（使用 MediaStore Downloads 表，避免直接文件路径读写带来的权限问题）。

### 4) 使用 HTTPS 但连接失败？

- 如果你使用自签名证书，系统可能会因证书不受信任而拒绝连接。建议使用受信任证书或在局域网内使用 HTTP（已允许 cleartext）。

## 许可证

MIT License，见 `LICENSE`。
