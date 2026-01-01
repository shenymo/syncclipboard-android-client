# SyncClipboard Android Client

这是一个 **SyncClipboard** 系统的 Android 客户端，用于在你的 Android 设备和 SyncClipboard 服务器之间无缝同步剪贴板（文本和文件）。

> **注意**：本仓库仅包含 **Android 客户端**。你需要自行部署运行 [SyncClipboard 服务端](https://github.com/DGP-Studio/SyncClipboard)（或兼容的实现）才能使用此应用。

## ✨ 功能特点

*   **剪贴板同步**：一键上传和下载文本剪贴板。
*   **文件传输**：
    *   **上传**：从任意应用（相册、文件管理器等）分享文件或图片到 "SyncClipboard" 即可上传至服务器。
    *   **下载**：直接从服务器下载文件到设备的 `Download` 目录。
    *   **冲突处理**：智能处理重名文件（支持“替换”或“保留两者”）。
    *   **即时打开**：下载完成后可直接调用系统兼容的应用打开文件。
*   **快捷设置磁贴 (Quick Settings Tiles)**：在通知栏提供便捷的“上传剪贴板”和“下载剪贴板”磁贴。
*   **多种界面样式**：
    *   **对话框 (Dialog)**：经典的居中弹窗。
    *   **底部弹窗 (Bottom Sheet)**：现代化的底部滑出面板。
    *   **悬浮窗 (Floating Window)**：不打扰操作的悬浮小窗（需要悬浮窗权限），适合多任务场景。
*   **安全**：支持 HTTP Basic 认证。
*   **Material Design**：遵循现代 Android 设计规范，支持动态取色。

## 📱 环境要求

*   **Android**：Android 12 (API level 31) 或更高版本。
*   **服务端**：可通过 HTTP/HTTPS 访问的兼容 SyncClipboard 服务端。

## 🚀 以此开始

### 1. 安装

目前你需要从源码构建 APK（请参考下方的 [源码构建](#-源码构建) 章节）。

### 2. 配置

1.  打开 **SyncClipboard** 应用。
2.  进入 **设置 (Settings)** 界面，配置以下信息：
    *   **服务器地址 (Server Address)**：服务器的完整 URL（例如 `http://192.168.1.100:5033`）。
    *   **用户名 (Username)**：你设置的用户名。
    *   **密码 / Token**：你设置的密码。
3.  点击 **保存配置 (Save Configuration)**。
4.  点击 **测试连接 (Test Connection)** 验证配置是否正确。

### 3. 界面自定义

你可以在 **进度界面样式 (Progress Window Style)** 中选择进度条的展示方式：
*   **对话框**：默认的居中弹窗。
*   **底部弹窗**：从底部滑出的面板。
    *   *选项*：可开关“点击空白处关闭”功能。
*   **悬浮窗**：始终显示在最上层的小窗口。

## 📖 使用指南

### 同步文本剪贴板
1.  **添加磁贴**：下拉通知栏，点击编辑（铅笔）图标，将 **上传剪贴板** 和 **下载剪贴板** 磁贴拖入活动区域。
2.  **上传**：在手机上复制好文本，然后点击通知栏的 **上传剪贴板** 磁贴。
3.  **下载**：点击 **下载剪贴板** 磁贴。服务器上的文本将自动复制到你的手机剪贴板。

### 传输文件

**上传（手机到服务器）：**
1.  打开任意应用（如相册、文件管理器）。
2.  选择文件或图片，点击 **分享**。
3.  在分享菜单中选择 **SyncClipboard**。
4.  文件将自动上传，同时服务器的剪贴板类型会被设置为 `File`。

**下载（服务器到手机）：**
1.  确保服务器当前的剪贴板类型是 `File`。
2.  点击手机通知栏的 **下载剪贴板** 磁贴。
3.  应用会自动检测到文件并开始下载到 **Downloads** 目录。
4.  **冲突处理**：如果本地已存在同名文件，会弹出提示：
    *   **替换**：覆盖现有文件。
    *   **保留**：保留原文件，新文件自动重命名（例如 `image (2).png`）。
5.  下载完成后，点击 **打开** 即可查看文件。

## 🛠 技术细节

### API 接口
客户端使用以下接口与服务端通信：

*   **鉴权**：HTTP Basic Auth (`Authorization: Basic <base64_credentials>`)
*   **GET /SyncClipboard.json**：获取当前剪贴板状态（类型、内容、文件 URL）。
*   **PUT /SyncClipboard.json**：更新剪贴板状态（文本内容或文件元数据）。
*   **PUT /file/{filename}**：上传文件原始内容。
*   **GET /file/{filename}**：下载文件原始内容。

### 权限说明
*   `INTERNET`：用于连接服务器。
*   `SYSTEM_ALERT_WINDOW`：仅在使用 **悬浮窗** 样式时需要。

## 🔨 源码构建

### 前置条件
*   Android Studio Koala 或更新版本。
*   JDK 17+。

### 构建命令
在项目根目录打开终端：

```bash
# 构建调试版 APK
./gradlew assembleDebug

# 构建正式版 APK
./gradlew assembleRelease
```

输出的 APK 文件位于：
`app/build/outputs/apk/debug/app-debug.apk`

## 📄 许可证

本项目开源。请参考仓库中的许可证文件（如有）。

---
*Created for personal use to bridge the gap between Android and PC clipboards.*
