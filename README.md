# APK 下载页

应用商店页面的源码（`pages/store/`），现在是免登录的 **APK 下载页**：
以列表形式展示上游 AList（`appstore.cnmlynk.org`）里的文件夹、APK 与图片，
APK 统一使用内置图标，图片用 AList 的 `thumb` 显示缩略图并可点开预览大图。
页面按平板/触屏设备优化（`pointer: coarse` 加大点击目标）。

- 入口域名仍为 `https://store.ltools.asia/`（沿用 `/store/` 路径，不改 Worker 路由）
- 不再有登录门禁：没有 `auth.js` / `auth.css`，页面加载即拉取目录
- 目录走父仓库已有接口 `/store/api/*`（AList 反代）
- **下载与预览都直连上游**：直接用 AList 返回的 `raw_url` / `thumb`，不再经 `/store/file`
- Worker 的 Store CSP 需要放开外域 `img-src`，否则缩略图会被 CSP 拦截
- Worker 侧的 `/store/api/*` 与 `/store/file` 已放开账号校验（仅保留 POST 同源检查），
  匿名即可浏览与下载；`/store/auth/*` 接口仍存在，但页面不再使用

## GitHub 发布

页面第三个标签「GitHub 发布」会扫描指定账号下**所有公开仓库的 releases**，不需要登录：

- 账号与下载线路都可以在页面上改，存在浏览器 `localStorage`
- **下载线路是下拉框**：`自动探测（推荐）` / `gh-proxy.com` / `ghfast.top` / `ghproxy.net` /
  `直连 GitHub` / `自定义…`
  - 自动探测会按顺序用 `no-cors` 请求探测（6 秒超时），选第一个可用的；都不可用就直连
  - 选具体站点或自定义前缀时也会探测一次，不可用则自动改为直连，并在配置行下方显示状态
- 数据由父仓库 Worker 的 `GET /store/api/github/releases?user=<账号>` 提供，
  结果在 KV 里缓存 30 分钟（`refresh=1` 可强制刷新）
- 下载地址会拼上选中的加速前缀，例如
  `https://gh-proxy.com/https://github.com/<owner>/<repo>/releases/download/<tag>/<file>`
- 未配置 GitHub token，受 60 次/小时匿名 API 限制；仓库数量多的账号可能触发限流，
  返回的错误里会带上恢复时间

父仓库 `tools` 负责 Cloudflare Worker 与部署，并通过 `worker/scripts/build-assets.mjs`
把本页复制到 `worker/public/store/index.html`。本仓库不能独立部署。

Android 安装包（WebView 壳）在 [`android/`](android/README.md)：

```sh
cd android && node scripts/build-apk.mjs   # 产物: android/dist/apk-store-<版本>.apk
```

本地检查（需要 Node.js）：

```sh
node --test pages/store/*.test.cjs
```

集成后请再跑父仓库的 Worker 与部署测试。
