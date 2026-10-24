const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");

test("页面不包含账号登录门禁", () => {
  assert.doesNotMatch(html, /auth\.js/);
  assert.doesNotMatch(html, /auth\.css/);
  assert.doesNotMatch(html, /store-login-form/);
  assert.doesNotMatch(html, /storeSession/);
  assert.doesNotMatch(html, /store:authenticated/);
});

test("目录页使用列表布局而不是卡片网格", () => {
  assert.match(html, /<section class="list" id="list"/);
  assert.match(html, /class="row"/);
  assert.doesNotMatch(html, /class="grid"/);
});

test("APK、图片与其它文件都进入列表，并区分动作", () => {
  assert.match(html, /function isApkFile\(name\)/);
  assert.match(html, /function isImageFile\(name\)/);
  assert.match(html, /data-download=/);
  assert.match(html, /data-preview=/);
  assert.match(html, /IMG_EXT|IMAGE_EXT/);
});

test("图片使用 AList thumb 缩略图并可打开预览", () => {
  assert.match(html, /item\.thumb/);
  assert.match(html, /id="viewer"/);
  assert.match(html, /function openViewer\(path/);
});

test("所有 APK 共用一个内置图标", () => {
  assert.match(html, /const APK_ICON = 'data:image\/svg\+xml/);
  assert.match(html, /<img src="\$\{APK_ICON\}"/);
  assert.doesNotMatch(html, /icons\/manifest\.json/);
  assert.doesNotMatch(html, /state\.icons/);
});

test("下载与预览直连上游，不再走 Worker 文件代理", () => {
  assert.match(html, /function directUrl\(url\)/);
  assert.doesNotMatch(html, /\/store\/file/);
  assert.doesNotMatch(html, /safeUrl/);
  assert.match(html, /directUrl\(detail\.raw_url\)/);
});

test("GitHub 发布页支持配置账号与下载线路", () => {
  assert.match(html, /id="ghUser"/);
  assert.match(html, /id="ghProxyMode"/);
  assert.match(html, /GH_CANDIDATES/);
  assert.match(html, /function proxyUrl\(url\)/);
  assert.match(html, /\/store\/api\/github\/releases/);
  assert.match(html, /data-mode="github"/);
});

test("下载线路下拉框支持自动探测与自定义前缀", () => {
  assert.match(html, /<option value="auto">/);
  assert.match(html, /<option value="direct">/);
  assert.match(html, /<option value="custom">/);
  assert.match(html, /id="ghProxyCustom"/);
  assert.match(html, /function probeProxy\(prefix\)/);
  assert.match(html, /function deliveryUrl\(url\)/);
  assert.match(html, /mode: 'no-cors'/);
  assert.match(html, /id="ghProxyHint"/);
});

test("打开即加载首个分类，不等待登录", () => {
  assert.match(html, /loadPath\(CATEGORIES\[0\], CATEGORIES\[0\]\.path\)/);
  assert.match(html, /\/store\/api/);
});

test("保留平板与触屏尺寸适配", () => {
  assert.match(html, /@media \(min-width: 769px\) and \(max-width: 1180px\)/);
  assert.match(html, /@media \(pointer: coarse\)/);
});
