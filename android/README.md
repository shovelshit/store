# APK 下载 · Android 壳

把 `../pages/store/` 的 APK 下载页装进一个最小 WebView 容器，产出可侧载的安装包。
不用 Gradle / Android Studio，直接用 SDK build-tools 构建。

## 产物

```
android/dist/apk-store-1.0.0.apk
```

- 包名 `io.github.shovelshit.apkstore`
- minSdk 24（Android 7.0），targetSdk 34
- 启动图标：`res/mipmap-xxxhdpi/ic_launcher.png`（蓝色渐变 + 下载箭头 + APK 字样）
- 自签名（debug keystore），适合侧载/内测，**不能直接上架应用商店**

## 构建

前置：JDK 17+（本机 21）、Android SDK（platform-tools + build-tools + platforms）。
SDK 路径按以下顺序查找：`--sdk` 参数 → `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `D:\Android-data` → `%LOCALAPPDATA%\Android\Sdk`。

```sh
node scripts/build-apk.mjs
# 或指定 SDK
node scripts/build-apk.mjs --sdk "D:\Android-data"
```

脚本会依次执行 `aapt2 compile` → `aapt2 link` → `javac` → `d8` → 合并 `classes.dex` →
`zipalign` → `apksigner sign/verify`，最后把 APK 拷回 `dist/`。

### 为什么要有临时目录

`aapt2` 在含非 ASCII 字符的路径（例如 `D:\Users\电脑\...`）下会报
`failed to open directory`。所以脚本先把源码复制到 `C:\Users\Public\apkbuild`
（可用 `APK_BUILD_DIR` 覆盖）构建，再拷回仓库。

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

## 已知限制

- 页面依赖父仓库 Worker 的 `/store/api/*`。父仓库已放开该接口的账号校验，
  若以后重新加回 `requireActiveStoreAccount`，App 打开后会显示「加载失败（HTTP 401）」。
- 只保留调试签名；正式分发请换自己的 keystore，并把 `--ks`、`--ks-pass` 改成正式参数。
