# SyncClipboard Android Client

一个简单的 Android 客户端，用来把手机上的文本 / 文件剪贴板与已有的 SyncClipboard 服务器进行同步。

> 本仓库只包含 Android 客户端，需要自行部署 SyncClipboard 服务端（接口示例见根目录 `Syncclipboardapi_files/swagger.json`）。

## 功能特点一览

- 通过 HTTP 与已有 SyncClipboard 服务交互，使用 **Basic 认证（`username:password`）**。
- 支持 **上传 / 下载文本剪贴板**。
- 支持通过系统 **分享** 上传任意文件 / 图片，在服务器侧以文件形式保存。
- 提供 **快捷设置磁贴（Quick Settings Tile）**，一键上传 / 下载剪贴板。
- 所有操作统一在一个前台 **进度界面** 中显示，支持对话框和 BottomSheet 两种样式。
- 下载文件时，支持 **同名文件“替换 / 保留”选择**，并提供“打开”按钮调用系统应用查看文件。

## 服务器与鉴权

- 客户端通过 `SyncClipboardApi.kt` 与服务端交互：
  - 上传文本：`PUT /SyncClipboard.json`
  - 下载文本 / 文件状态：`GET /SyncClipboard.json`
  - 上传文件内容：`PUT /file/{fileName}`
- 认证使用 HTTP Basic：
  - 请求头：`authorization = "basic " + base64(username:password)`。
  - 你需要在服务端按同样方式校验用户名和密码。

## 界面与设置

### 服务器配置

在主界面 `SettingsActivity` 中可以设置：

- 服务器地址（Base URL），例如：`http://192.168.x.x:5033`
- 用户名
- 密码

配置会存储在 `SharedPreferences` 中（`ConfigStorage`），应用重启后仍然有效。  
同时提供「测试连接」按钮，会调用 `/SyncClipboard.json` 验证地址和用户名/密码是否正确。

### 进度界面样式

所有一次性操作（上传、下载、文件传输、测试连接）都由 `ProgressActivity` 承担。  
在设置页可以选择两种样式（`UiStyleStorage` 持久化）：

- **对话框样式**：小对话框悬浮在当前应用之上，下方界面略微变暗。
- **BottomSheet 样式**：底部弹出的半屏卡片，带轻微圆角和阴影。

另外还有一个行为开关：

- **BottomSheet 行为**
  - 选项：“点击空白处关闭底部弹窗”
  - 关闭：只能下拉或按返回键关闭，避免误触；
  - 开启：点击 BottomSheet 以外区域即可关闭。

对话框样式下仍保留简短 Toast 提示；BottomSheet 模式下只用卡片里的文字提示，不再弹 Toast，避免遮挡。

## 使用流程

### 1. 配置服务器

1. 打开应用，进入设置界面；
2. 填写：
   - 服务器地址（如 `http://192.168.x.x:5033`）
   - 用户名
   - 密码
3. 点击「保存配置」保存到本地；
4. 可点击「测试连接」验证与服务器之间的连通性和 Basic 认证是否通过。

### 2. 上传 / 下载文本剪贴板

- 上传文本：
  - 在系统任意位置复制文本；
  - 从系统快捷设置中添加“上传剪贴板”磁贴；
  - 点击磁贴后，会弹出 `ProgressActivity`，显示“正在上传剪贴板…”以及结果。

- 下载文本：
  - 确保服务器端当前剪贴板 `Type = "Text"`；
  - 点击“下载剪贴板”磁贴；
  - 成功后，文本会写入本机系统剪贴板，并在界面上显示一小段内容。

### 3. 通过“分享”上传文本或文件

`ShareReceiveActivity` 作为系统分享入口：

- 分享文本：
  - 在任意 App 中选择文本 → 系统“分享” → 选择 “SyncClipboard”；
  - 会以文本形式上传到服务器剪贴板。

- 分享文件 / 图片：
  - 在文件管理器 / 相册等 App 中分享文件或图片到 “SyncClipboard”；
  - `ProgressActivity` 会显示上传进度（包括大小和速度）。

文件上传流程：

1. `PUT /file/{fileName}` 上传文件内容；
2. `PUT /SyncClipboard.json` 设置 `Type = "File"`，`File = fileName`。

### 4. 下载服务器上的文件（含同名冲突处理）

当服务器端 `ClipboardProfile` 中 `File` 字段非空时，“下载剪贴板”会进入 **文件下载** 流程：

1. 应用在系统公共 Download 目录创建 / 选择目标文件名：
   - 先检查 Download 目录中是否已存在同名文件；
   - 如不存在，直接使用服务器给出的文件名；
   - 如存在，则弹出冲突提示。
2. 同名文件冲突提示界面：
   - 标题：显示原文件名，例如 `report.pdf`；
   - 内容：`目标目录中已存在同名文件，是否保留已存在的同名文件？`
   - 两个按钮：
     - **替换**：覆盖已有文件内容，文件名和后缀不变；
     - **保留**：保留原文件，同时下载为新的文件名：
       - 例如：`report.pdf` → `report (2).pdf`、`report (3).pdf`…，只在名字后追加序号，不改扩展名。
3. 确定最终文件名后，从服务器下载文件内容并写入该文件。
4. 下载完成后的界面：
   - 标题：`已保存至 "下载目录路径"`（如 `/storage/emulated/0/Download`）；
   - 内容：显示实际保存的文件名（例如 `report (2).pdf`）；
   - 按钮：**打开**
     - 点击后使用 `ACTION_VIEW` + 合理的 MIME type（根据后缀猜测）；
     - 交由系统选择可以打开该类型文件的应用（如 Word、WPS、文件查看器等）；
     - 若系统无可用应用，则提示“没有可用于打开该文件的应用”。

## 构建与运行

### 环境要求

- Android Studio（建议使用与 Gradle 8.6 / Kotlin 2.0.21 兼容的版本）
- Android SDK：
  - `compileSdkVersion = 35`
  - `targetSdkVersion = 35`
  - `minSdkVersion = 31`

当前 `app/build.gradle` 中的应用版本：

- `versionCode = 2`
- `versionName = "1.1"`

### 构建调试版本

在项目根目录执行：

```bash
./gradlew assembleDebug
```

生成的调试 APK 位于：

- `app/build/outputs/apk/debug/app-debug.apk`

可直接通过 Android Studio 运行到真机或模拟器。

## 代码结构概览

- `app/src/main/AndroidManifest.xml`  
  应用入口、Activity 声明、TileService 声明、网络权限等。

- `app/src/main/java/com/example/syncclipboard/SyncClipboardApi.kt`  
  与服务器交互的最小 HTTP API：上传 / 下载文本、上传 / 下载文件、测试连接等。

- `SettingsActivity.kt`  
  服务器地址、用户名、密码配置界面，包含“测试连接”。

- `ProgressActivity.kt`  
  所有前台操作（上传 / 下载 / 文件传输 / 测试连接）的统一进度界面，包含：
  - 文本上传 / 下载逻辑；
  - 文件上传 / 下载、速度和进度文本；
  - 文件同名冲突处理（替换 / 保留）；
  - 下载完成后的“打开”按钮。

- `ShareReceiveActivity.kt`  
  系统“分享”入口，将文本或文件转发给 `ProgressActivity` 做上传。

- `UploadClipboardTileService.kt` / `DownloadClipboardTileService.kt`  
  快捷设置磁贴入口，各自绑定上传/下载操作，并使用对应图标。

- `ConfigStorage.kt` / `UiStyleStorage.kt`  
  使用 `SharedPreferences` 保存服务器配置和进度界面样式/行为。

- `SyncClipboardApp.kt`  
  `Application` 实现，用于启用 Material Dynamic Color（根据系统配色动态调整主题）。

## 许可证

当前仓库未在代码中显式声明许可证。如需开源或分发，请根据你的实际需求补充许可证信息。
