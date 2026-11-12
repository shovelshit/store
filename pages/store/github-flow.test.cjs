const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const start = html.indexOf('  async function loadGithub() {');
const end = html.indexOf('  function renderGithub() {', start);
assert.ok(start > 0 && end > start);
const flow = html.slice(start, end);

function harness() {
  const calls = [];
  let resolveFirst;
  const first = new Promise((resolve) => { resolveFirst = resolve; });
  const elements = {
    '#ghStatus': { textContent: '' },
    '#ghList': { innerHTML: '' },
    '#ghMore': { hidden: false, disabled: false, addEventListener(_type, handler) { this.click = handler; } }
  };
  const state = {
    ghUser: 'bob', ghRequestId: 9, ghPage: 4, ghLoaded: true,
    ghRepos: [{ fullName: 'alice/old' }], ghReleaseCache: { 'alice/old': [] }
  };
  let proxyRefreshes = 0;
  const scope = {
    state, URLSearchParams, $: (selector) => elements[selector],
    refreshProxyState() { proxyRefreshes++; },
    renderGithub() {},
    githubApi(url) {
      calls.push(url);
      if (url.includes('page=1')) return first;
      return Promise.resolve({ repos: [{ fullName: `bob/page-${calls.length}` }], hasMore: false });
    }
  };
  vm.runInNewContext(`${flow}\nthis.loadGithub = loadGithub;`, scope);
  return { scope, state, elements, calls, resolveFirst, get proxyRefreshes() { return proxyRefreshes; } };
}

test('新账号首屏等待期间旧加载更多按钮不可请求旧页码', async () => {
  const fixture = harness();
  const pending = fixture.scope.loadGithub();
  assert.equal(fixture.elements['#ghMore'].hidden, true);
  assert.equal(fixture.elements['#ghMore'].disabled, true);
  assert.equal(fixture.state.ghPage, 0);
  assert.equal(fixture.state.ghLoaded, false);
  await fixture.elements['#ghMore'].click();
  assert.deepEqual(fixture.calls, ['repos?account=bob&page=1']);
  fixture.resolveFirst({ repos: [{ fullName: 'bob/first' }], hasMore: true });
  await pending;
  assert.equal(fixture.elements['#ghMore'].disabled, false);
  await fixture.elements['#ghMore'].click();
  assert.equal(fixture.calls.at(-1), 'repos?account=bob&page=2');
});

test('fresh release listing initializes the saved download route', async () => {
  const fixture = harness();
  const pending = fixture.scope.loadGithub();
  assert.equal(fixture.proxyRefreshes, 1);
  fixture.resolveFirst({ repos: [], hasMore: false });
  await pending;
});
