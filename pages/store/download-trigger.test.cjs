const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const start = html.indexOf('  function triggerDownload(url) {');
const end = html.indexOf('  async function openViewer(', start);
assert.ok(start > 0 && end > start);

test('private attachment click requests exact same-origin URL without claiming transfer success', async () => {
  const links = [];
  const messages = [];
  const url = '/store/api/github/asset?account=shovelshit&repo=shovelshit%2Fmy-knowledge-base&id=690802170';
  const document = { createElement() { const link = { click() { links.push({ href: this.href, download: this.download }); }, remove() {} }; return link; },
    body: { appendChild() {} } };
  const scope = { document, toast: (value) => messages.push(value), resolveUrl: async () => url };
  vm.runInNewContext(`${html.slice(start, end)}\nthis.downloadFile = downloadFile;`, scope);
  await scope.downloadFile('release-manifest.json', null);
  assert.deepEqual(links, [{ href: url, download: '' }]);
  assert.deepEqual(messages, ['已提交下载请求，请在下载管理中查看状态']);
});

test('GitHub asset action dispatches the real anchor and identifies direct delivery as a request', async () => {
  const links = [];
  const messages = [];
  const url = '/store/api/github/asset?account=shovelshit&repo=shovelshit%2Fmy-knowledge-base&id=690802170';
  const button = { dataset: { ghUrl: url } };
  const document = {
    createElement() { return { click() { links.push(this.href); }, remove() {} }; },
    body: { appendChild() {} },
    addEventListener(type, handler) { if (type === 'click') this.clickHandler = handler; }
  };
  const clickStart = html.indexOf("  document.addEventListener('click',");
  const clickEnd = html.indexOf("  document.addEventListener('keydown',", clickStart);
  const scope = { document, toast: (value) => messages.push(value), deliveryUrl() { throw new Error('private route must stay same-origin'); } };
  vm.runInNewContext(`${html.slice(start, end)}\n${html.slice(clickStart, clickEnd)}`, scope);
  await document.clickHandler({ target: { closest(selector) { return selector === '[data-gh-url]' ? button : null; } }, stopPropagation() {} });
  assert.deepEqual(links, [url]);
  assert.deepEqual(messages, ['已提交下载请求（直连），请查看下载状态']);
});
