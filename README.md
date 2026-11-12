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

页面第三个标签「GitHub 发布」先加载账号下的仓库列表，点击仓库后才加载 releases：

- 账号与下载线路存在浏览器 `localStorage`；可选 Token 只提交到 Worker，明文不进入本地存储
- **下载线路是下拉框**：`自动探测（推荐）` / `gh-proxy.com` / `ghfast.top` / `ghproxy.net` /
  `直连 GitHub` / `自定义…`
  - 自动探测会按顺序用 `no-cors` 请求探测（6 秒超时），选第一个可用的；都不可用就直连
  - 选具体站点或自定义前缀时也会探测一次，不可用则自动改为直连，并在配置行下方显示状态
- 数据由父仓库 Worker 的 `GET /store/api/github/repos?account=<账号>` 和
  `GET /store/api/github/releases?account=<账号>&repo=<owner/repo>` 提供。
  公开数据按账号和仓库分别在 KV 缓存 30 分钟；带 Token 的私有数据不进入公共 KV
- 下载地址会拼上选中的加速前缀，例如
  `https://gh-proxy.com/https://github.com/<owner>/<repo>/releases/download/<tag>/<file>`
- Token 按 Store 用户和 GitHub 账号加密保存于 D1 `user_config`，不会回显；
  私有附件经登录后的同源 Worker 接口下载，不交给外部加速站
- Fine-grained PAT 至少需要目标仓库的 **Metadata: read**（仓库列表）和
  **Contents: read**（Release 与私有附件下载）；授权范围须包含要展示的私有仓库
- 不配置 Token 时受 GitHub 匿名 API 的来源 IP 限额约束；仓库列表按页加载，每次最多一次 GitHub API 请求

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
