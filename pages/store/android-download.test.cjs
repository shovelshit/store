const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../android/app/src/main/java/io/github/shovelshit/apkstore/MainActivity.java'), 'utf8');

test('同源私有 APK 下载携带 Store 会话 Cookie', () => {
  assert.match(source, /CookieManager\.getInstance\(\)\.getCookie\(url\)/);
  assert.match(source, /request\.addRequestHeader\("Cookie", cookie\)/);
});
