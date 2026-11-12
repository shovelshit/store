const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const helpersStart = html.indexOf('  function githubPrivateHint(');
const start = html.indexOf('  async function loadGithub() {');
const end = html.indexOf('  function renderGithub() {', start);
assert.ok(helpersStart > 0 && start > helpersStart && end > start);
const flow = html.slice(helpersStart, end);
const selectStart = html.indexOf("  $('#ghAccounts').addEventListener('change',");
const selectEnd = html.indexOf("  $('#ghProxyMode').value", selectStart);
assert.ok(selectStart > 0 && selectEnd > selectStart);
const selectFlow = html.slice(selectStart, selectEnd);

function harness() {
  const calls = [];
  let resolveFirst;
  const first = new Promise((resolve) => { resolveFirst = resolve; });
  const elements = {
    '#ghUser': { value: 'bob' },
    '#ghAccounts': { addEventListener(_type, handler) { this.change = handler; } },
    '#ghStatus': { textContent: '' },
    '#ghTokenState': { textContent: '' },
    '#ghList': { innerHTML: '' },
    '#ghRefresh': { addEventListener() {} },
    '#ghMore': { hidden: false, disabled: false, addEventListener(_type, handler) { this.click = handler; } }
  };
  const state = {
    ghUser: 'bob', ghRequestId: 9, ghPage: 4, ghLoaded: true,
    ghAccounts: [], ghRepos: [{ fullName: 'alice/old' }], ghReleaseCache: { 'alice/old': [] }
  };
  let proxyRefreshes = 0;
  let renders = 0;
  const scope = {
    state, URLSearchParams, $: (selector) => elements[selector],
    localStorage: { setItem(key, value) { this[key] = value; } },
    refreshProxyState() { proxyRefreshes++; },
    renderGithub() { renders++; },
    closeGithubDialog() {},
    githubApi(url) {
      calls.push(url);
      if (url.includes('page=1')) return first;
      return Promise.resolve({ repos: [{ fullName: `bob/page-${calls.length}` }], hasMore: false });
    }
  };
  vm.runInNewContext(`${flow}\n${selectFlow}\nthis.loadGithub = loadGithub;`, scope);
  return { scope, state, elements, calls, resolveFirst,
    get proxyRefreshes() { return proxyRefreshes; }, get renders() { return renders; } };
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
  assert.match(fixture.elements['#ghStatus'].textContent, /bob · 1 个仓库/);
  assert.equal(fixture.renders, 1);
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
  assert.equal(fixture.elements['#ghStatus'].textContent, 'bob · 0 个仓库');
  assert.equal(fixture.renders, 1);
});

test('selecting a saved account immediately loads its repositories', async () => {
  const fixture = harness();
  const pending = fixture.elements['#ghAccounts'].change({ target: { value: 'alice' } });
  assert.equal(fixture.elements['#ghUser'].value, 'alice');
  assert.equal(fixture.state.ghUser, 'alice');
  assert.deepEqual(fixture.calls, ['repos?account=alice&page=1']);
  fixture.resolveFirst({ repos: [{ fullName: 'alice/private-app', private: true }], hasMore: false });
  await pending;
  assert.deepEqual(fixture.state.ghRepos.map((repo) => repo.fullName), ['alice/private-app']);
  assert.equal(fixture.elements['#ghStatus'].textContent, 'alice · 1 个仓库');
  assert.equal(fixture.renders, 1);
});

test('saved token with no private repos shows a permission diagnostic', async () => {
  const fixture = harness();
  fixture.state.ghAccounts = [{ login: 'bob', hasToken: true }];
  const pending = fixture.scope.loadGithub();
  fixture.resolveFirst({ repos: [{ fullName: 'bob/public', private: false }], hasMore: false,
    diagnostics: { upstreamPrivateCount: 0, accountPrivateCount: 0, classicRepoScopeMissing: true } });
  await pending;
  assert.match(fixture.elements['#ghStatus'].textContent, /Token.*repo.*私有仓库/);
});

test('last page reports private repositories filtered from earlier GitHub pages', async () => {
  const fixture = harness();
  fixture.state.ghAccounts = [{ login: 'bob', hasToken: true }];
  const pending = fixture.scope.loadGithub();
  fixture.resolveFirst({ repos: [{ fullName: 'bob/public', private: false }], hasMore: true,
    diagnostics: { upstreamPrivateCount: 1, accountPrivateCount: 0, classicRepoScopeMissing: null } });
  await pending;
  assert.doesNotMatch(fixture.elements['#ghStatus'].textContent, /私有仓库/);
  await fixture.elements['#ghMore'].click();
  assert.equal(fixture.state.ghPage, 2);
  assert.match(fixture.elements['#ghStatus'].textContent, /私有仓库不属于此账号或已归档/);
});

test('late response from previous selected account cannot replace current listing or hint', async () => {
  const fixture = harness();
  fixture.state.ghAccounts = [{ login: 'alice', hasToken: true }, { login: 'bob', hasToken: true }];
  let resolveAlice;
  let resolveBob;
  const alice = new Promise((resolve) => { resolveAlice = resolve; });
  const bob = new Promise((resolve) => { resolveBob = resolve; });
  fixture.scope.githubApi = (url) => url.includes('account=alice') ? alice : bob;
  const oldRequest = fixture.elements['#ghAccounts'].change({ target: { value: 'alice' } });
  const currentRequest = fixture.elements['#ghAccounts'].change({ target: { value: 'bob' } });
  resolveBob({ repos: [{ fullName: 'bob/private', private: true }], hasMore: false,
    diagnostics: { upstreamPrivateCount: 1, accountPrivateCount: 1, classicRepoScopeMissing: null } });
  await currentRequest;
  resolveAlice({ repos: [{ fullName: 'alice/public', private: false }], hasMore: false,
    diagnostics: { upstreamPrivateCount: 0, accountPrivateCount: 0, classicRepoScopeMissing: true } });
  await oldRequest;
  assert.deepEqual(fixture.state.ghRepos.map((repo) => repo.fullName), ['bob/private']);
  assert.equal(fixture.elements['#ghStatus'].textContent, 'bob · 1 个仓库');
  assert.equal(fixture.renders, 1);
});
