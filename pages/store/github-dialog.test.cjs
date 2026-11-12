const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
function section(start, end) {
  const from = html.indexOf(start);
  const to = html.indexOf(end, from);
  assert.ok(from > 0 && to > from, `${start} section exists`);
  return html.slice(from, to);
}
const rendering = section('  function renderGithub() {', '  function renderCrumb() {');
const clickHandler = section("  document.addEventListener('click',", "  document.addEventListener('keydown',");
const selection = section("  $('#ghAccounts').addEventListener('change',", "  $('#ghProxyMode').value");

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fixture() {
  const pending = [];
  const calls = [];
  const downloads = [];
  const dialog = {
    open: false,
    showModal() { this.open = true; },
    close() { this.open = false; this.events.close?.(); },
    events: {},
    addEventListener(type, handler) { this.events[type] = handler; }
  };
  const elements = {
    '#ghDialog': dialog,
    '#ghDialogTitle': { textContent: '' },
    '#ghDialogContent': { innerHTML: '' },
    '#ghList': { innerHTML: '' },
    '#ghStatus': { textContent: '' },
    '#ghTokenState': { textContent: '' },
    '#ghRefresh': { addEventListener() {} },
    '#ghUser': { value: 'alice' },
    '#ghAccounts': { addEventListener(_type, handler) { this.change = handler; } }
  };
  const state = {
    ghUser: 'alice', ghRequestId: 1, ghDialogRequestId: 0,
    ghRepos: [
      { fullName: 'alice/app', private: true, description: 'Application', updatedAt: '2026-11-12' },
      { fullName: 'alice/other', private: false, description: 'Other', updatedAt: '2026-11-11' }
    ], ghReleaseCache: {}, ghAccounts: [], ghLoaded: true, ghProxy: 'https://proxy.example/', ghProxyOk: true
  };
  const document = { addEventListener(type, handler) { this[type] = handler; } };
  const scope = {
    state, URLSearchParams, document, $: (selector) => elements[selector],
    escapeHtml: (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]),
    formatDate: (value) => value || '', formatSize: (value) => `${value} B`,
    localStorage: { setItem() {} }, refreshProxyState() {},
    githubApi(url) {
      calls.push(url);
      if (url.startsWith('releases?')) { const item = deferred(); pending.push(item); return item.promise; }
      return Promise.resolve({ repos: [], hasMore: false });
    },
    loadGithub() { state.ghRequestId++; state.ghReleaseCache = {}; return Promise.resolve(); },
    triggerDownload(url) { downloads.push(url); }, toast() {},
    deliveryUrl(url) { return `https://proxy.example/${url}`; }
  };
  vm.runInNewContext(`${rendering}\n${clickHandler}\n${selection}`, scope);
  function click(attribute, value) {
    const button = { dataset: { [attribute]: value }, focus() { this.focused = true; } };
    const event = { target: { closest(selector) { return selector === `[data-${attribute.replace(/[A-Z]/g, (ch) => `-${ch.toLowerCase()}`)}]` ? button : null; } }, stopPropagation() {} };
    return { button, result: document.click(event) };
  }
  return { scope, state, elements, dialog, calls, pending, downloads, click };
}

test('repository cards open a named dialog and fetch only the chosen releases', async () => {
  const f = fixture();
  f.scope.renderGithub();
  assert.match(f.elements['#ghList'].innerHTML, /data-gh-repo="alice\/app"/);
  assert.doesNotMatch(f.elements['#ghList'].innerHTML, /gh-release/);
  const opened = f.click('ghRepo', 'alice/app');
  assert.equal(f.dialog.open, true);
  assert.equal(f.elements['#ghDialogTitle'].textContent, 'alice/app');
  assert.match(f.elements['#ghDialogContent'].innerHTML, /加载/);
  assert.deepEqual(f.calls, ['releases?account=alice&repo=alice%2Fapp']);
  f.pending[0].resolve({ releases: [{ tag: 'v1', publishedAt: '2026-11-12', assets: [] }] });
  await opened.result;
  assert.match(f.elements['#ghDialogContent'].innerHTML, /v1/);
  assert.doesNotMatch(f.elements['#ghList'].innerHTML, /v1/);
});

test('card shows the short repository name while its action retains the full identity', () => {
  const f = fixture();
  f.state.ghRepos = [
    { name: 'app', fullName: 'alice/app', pushedAt: '2026-11-12' },
    { fullName: 'alice/other' },
    { name: '<safe>', fullName: 'alice/<safe>' }
  ];
  f.scope.renderGithub();
  const cards = f.elements['#ghList'].innerHTML;
  assert.match(cards, /class="gh-repo-title" title="alice\/app">app<\/span>/);
  assert.match(cards, /class="gh-repo-title" title="alice\/other">other<\/span>/);
  assert.match(cards, /class="gh-repo-title" title="alice\/&lt;safe&gt;">&lt;safe&gt;<\/span>/);
  assert.match(cards, /data-gh-repo="alice\/app" aria-label="查看 alice\/app 的 Release"/);
});

test('cached releases reopen without a second request and closing restores focus', async () => {
  const f = fixture();
  const first = f.click('ghRepo', 'alice/app');
  f.pending[0].resolve({ releases: [] });
  await first.result;
  f.dialog.close();
  assert.equal(first.button.focused, true);
  const second = f.click('ghRepo', 'alice/app');
  await second.result;
  assert.equal(f.dialog.open, true);
  assert.match(f.elements['#ghDialogContent'].innerHTML, /暂无 Release/);
  assert.equal(f.calls.length, 1);
});

test('closing while loading prevents a late response from changing the dialog', async () => {
  const f = fixture();
  const first = f.click('ghRepo', 'alice/app');
  f.dialog.close();
  f.pending[0].resolve({ releases: [{ tag: 'late', assets: [] }] });
  await first.result;
  assert.equal(f.dialog.open, false);
  assert.doesNotMatch(f.elements['#ghDialogContent'].innerHTML, /late/);
});

test('release errors remain in the dialog without replacing the repository status', async () => {
  const f = fixture();
  const opened = f.click('ghRepo', 'alice/app');
  f.pending[0].reject(new Error('unavailable'));
  await opened.result;
  assert.match(f.elements['#ghDialogContent'].textContent, /加载 Release 失败：unavailable/);
  assert.equal(f.elements['#ghStatus'].textContent, '');
  assert.equal(f.dialog.open, true);
});

test('close control invalidates a pending release request', async () => {
  const f = fixture();
  const opened = f.click('ghRepo', 'alice/app');
  await f.click('ghClose', '').result;
  f.pending[0].resolve({ releases: [{ tag: 'late', assets: [] }] });
  await opened.result;
  assert.equal(f.dialog.open, false);
  assert.doesNotMatch(f.elements['#ghDialogContent'].innerHTML, /late/);
});

test('selecting another repository while loading ignores the older result', async () => {
  const f = fixture();
  const first = f.click('ghRepo', 'alice/app');
  const second = f.click('ghRepo', 'alice/other');
  f.pending[1].resolve({ releases: [{ tag: 'current', assets: [] }] });
  await second.result;
  f.pending[0].resolve({ releases: [{ tag: 'late', assets: [] }] });
  await first.result;
  assert.equal(f.elements['#ghDialogTitle'].textContent, 'alice/other');
  assert.match(f.elements['#ghDialogContent'].innerHTML, /current/);
  assert.doesNotMatch(f.elements['#ghDialogContent'].innerHTML, /late/);
});

test('account changes close the dialog and invalidate pending releases', async () => {
  const f = fixture();
  const first = f.click('ghRepo', 'alice/app');
  await f.elements['#ghAccounts'].change({ target: { value: 'bob' } });
  f.pending[0].resolve({ releases: [{ tag: 'late', assets: [] }] });
  await first.result;
  assert.equal(f.dialog.open, false);
  assert.equal(f.state.ghUser, 'bob');
  assert.doesNotMatch(f.elements['#ghDialogContent'].innerHTML, /late/);
});

test('private asset keeps its same-origin download URL and escapes its name', async () => {
  const f = fixture();
  const first = f.click('ghRepo', 'alice/app');
  f.pending[0].resolve({ releases: [{ tag: 'v1', assets: [{ id: 17, name: '<app>.apk', size: 42 }] }] });
  await first.result;
  const markup = f.elements['#ghDialogContent'].innerHTML;
  assert.match(markup, /&lt;app&gt;\.apk/);
  assert.match(markup, /data-gh-url="\/store\/api\/github\/asset\?account=alice&amp;repo=alice%2Fapp&amp;id=17"/);
  await f.click('ghUrl', '/store/api/github/asset?account=alice&repo=alice%2Fapp&id=17').result;
  assert.deepEqual(f.downloads, ['/store/api/github/asset?account=alice&repo=alice%2Fapp&id=17']);
});
