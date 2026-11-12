# APK 下载与 ADB 工具 · Android App

把 `../pages/store/` 的 APK 下载页装进一个最小 WebView 容器，产出可侧载的安装包。
不用 Gradle / Android Studio，直接用 SDK build-tools 构建。

## 产物

```
android/dist/apk-store-1.3.0.apk
android/dist/apk-store-1.3.0.apk.sha256
```

- 包名 `io.github.shovelshit.apkstore`
- minSdk 24（Android 7.0），targetSdk 34
- 启动图标：`res/mipmap-xxxhdpi/ic_launcher.png`（蓝色渐变 + 下载箭头 + APK 字样）
- 当前版本 1.3.0（versionCode 5）；APK 文件名从 Manifest 读取
- 原有签名优先；没有原签名时仅可显式生成开发签名，**不能保证覆盖安装旧版**

## 构建

前置：JDK 17+、Android SDK（build-tools + platforms）。Windows/macOS 使用各平台原生 SDK 工具。
SDK 路径按以下顺序查找：`--sdk` 参数 → `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `D:\Android-data` → `%LOCALAPPDATA%\Android\Sdk`。

```sh
node android/scripts/build-apk.mjs --sdk /path/to/Android/sdk
# 只有确认原有签名密钥时，使用 --ks 指向该密钥
node android/scripts/build-apk.mjs --sdk /path/to/Android/sdk --ks /path/to/original.keystore
# 原签名不可用时，显式选择独立开发签名
node android/scripts/build-apk.mjs --sdk /path/to/Android/sdk --dev-signing
node --test android/test/*.test.cjs
node --test pages/store/*.test.cjs
```

脚本会依次执行 `aapt2 compile` → `aapt2 link` → `javac` → `d8` → 合并 `classes.dex` →
`zipalign` → `apksigner sign/verify`，最后把 APK 和 SHA-256 sidecar 放进 `android/dist/`。
从仓库根目录执行上述命令。签名默认使用仓库现有 `android/.keystore/debug.keystore`
或 `--ks` 指定的现有 keystore，别名 `androiddebugkey`、密码 `android`；若原有密钥不同，
设置 `APK_KS_ALIAS`、`APK_KS_PASS` 和 `APK_KEY_PASS` 环境变量（不要写入版本库或终端命令历史）。
缺少原密钥时构建会失败，除非使用 `--dev-signing`；该选项将独立密钥保存在忽略的
`android/.keystore/development.keystore`，后续需再次显式指定 `--dev-signing` 以复用。
妥善备份该密钥；不可将 APK 或私钥提交到 Git。

### 为什么要有临时目录

`aapt2` 在含非 ASCII 字符的路径（例如 `D:\Users\电脑\...`）下会报
`failed to open directory`。所以脚本在 ASCII 临时父目录中创建每次唯一的子目录，
复制源码构建，再拷回仓库。`APK_BUILD_DIR` 可指定可写的 ASCII 父目录，清理只移除本次子目录。

## 行为

- 启动加载 `https://store.ltools.asia/`（入口写在 `MainActivity.HOME_URL`，改这里即可换地址）
- 页面里的 `.apk` 链接不再交给 WebView，而是 `DownloadManager` 下载到系统「下载」目录，
  并记录到应用内的下载列表
- 右下角「↓ 下载」按钮打开**下载管理**（`DownloadsActivity`）：实时进度、已完成、失败，
  可**安装 / 取消 / 删除**
- 安装时若没有「安装未知应用」权限，会跳转系统授权页（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`），
  返回后自动继续安装
- Android 9 及以下首次下载会申请存储权限，拒绝则提示无法保存
- 返回键先回退网页历史；断网时显示内置「无法连接 / 重试」页
- 允许明文 HTTP（上游 AList 是 `http://appstore.cnmlynk.org`），否则 Android 9+ 会拦下载
- 启用 DOM Storage，页面里的视图偏好可保存
- Store 网页需要访问密钥登录；只有已授权会话才能浏览受保护目录，具体权限由服务端控制

## ADB 连接与管理

在目标设备的开发者选项中启用无线 ADB（Android 9 通常先通过可信电脑执行
`adb tcpip 5555`），确认设备和手机在可互通网络。手机 App 打开原生「ADB 设备管理」，
输入目标设备的 IP 和 ADB TCP 端口，点击「连接 / 重试」，首次连接需在**目标设备**确认 RSA 授权。
只连接自己管理或获授权的设备；App 的 ADB 身份保存在应用私有目录，卸载或清除应用数据后
可能需要在目标设备重新授权。更改目标地址、断开或取消后需手动重新连接；设备端安装可能在
本机取消后继续，请检查目标状态。Android 11+ 的无线调试配对端口/配对码流程不由本 App 支持，
仅连接已启用的传统 ADB TCP 服务端口。部分厂商 ROM 禁用无线 ADB 或限制 shell 权限。

连接后可搜索用户 0 的第三方或系统应用，查看实际权限并在确认弹窗后修改支持的权限。
支持可变更且由目标应用声明的运行时权限、`WRITE_SECURE_SETTINGS`，以及
`SYSTEM_ALERT_WINDOW`、`WRITE_SETTINGS`、`PACKAGE_USAGE_STATS` 对应的 AppOps；
不提供任意 shell 命令。修改后重新读取目标状态，失败会显示错误；系统版本、ROM、应用声明和
设备策略可能拒绝某项授权。所有应用查询、授权和安装操作均显式针对目标设备的用户 0。

普通安装仍从下载管理走 Android 系统安装器，可能需要「安装未知应用」授权；它安装到**本机**，
不需要 ADB。下载完成的「ADB 安装」或原生页面「选择本地 APK」可选择已下载/文档中的 APK，
检查目标与包信息后另行确认，再上传并用目标设备 `pm install -r --user 0` 安装。
目标设备可能拒绝版本降级、不同签名更新或安装策略；使用不同开发签名的本 App 自身 APK
也不能覆盖原签名版本，须先备份需要保留的数据并卸载旧版本后安装（卸载会清除 App 私有数据）。

## 已知限制

- 页面依赖父仓库 Worker 的 `/store/api/*`；网络、账号和会话状态由服务端校验。
- 当前开发签名只适合受控测试；正式发布应使用并妥善保管与既有版本一致的签名。
