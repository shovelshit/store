const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

const adb = process.env.ADB || '/Users/lijinfeng/Library/Android/sdk/platform-tools/adb';
const serial = process.env.STORE_TEST_SERIAL;

function shell(...args) {
  return execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
}

function bounds(node) {
  const match = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  assert.ok(match, `missing bounds: ${node}`);
  return match.slice(1).map(Number);
}

test('main navigation occupies layout above the WebView', { skip: !serial }, async () => {
  shell('shell', 'am', 'start', '-n', 'io.github.shovelshit.apkstore/.MainActivity');
  let dumped = false;
  for (let attempt = 0; attempt < 4 && !dumped; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 500));
    try { shell('shell', 'uiautomator', 'dump', '/sdcard/store-ui.xml'); dumped = true; }
    catch (error) { if (attempt === 3) throw error; }
  }
  const xml = shell('exec-out', 'cat', '/sdcard/store-ui.xml');
  const web = xml.match(/<node\b[^>]*class="android.webkit.WebView"[^>]*>/);
  assert.ok(web, 'WebView must be visible');
  const [,, webRight, webBottom] = bounds(web[0]);
  const [, webTop] = bounds(web[0]);
  for (const label of ['下载', 'ADB', '更新']) {
    const node = [...xml.matchAll(/<node\b[^>]*>/g)].map(m => m[0])
      .find(value => new RegExp(`text="[^\"]*${label}[^\"]*"`).test(value));
    assert.ok(node, `${label} entry must be visible`);
    const [left, top, right, bottom] = bounds(node);
    assert.ok(right - left >= 40 && right <= webRight,
      `${label} must remain a visible tap target: ${left}-${right} vs width ${webRight}`);
    assert.ok(bottom <= webTop, `${label} must end above WebView: ${top}-${bottom} vs ${webTop}-${webBottom}`);
  }
});
