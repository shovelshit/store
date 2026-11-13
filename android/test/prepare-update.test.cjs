const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const CERT = 'a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97';
const RELEASE_URL = 'https://example.test/releases/download/android-v1.4.0-dev/apk-store-1.4.0.apk';
const payload = Buffer.from('signed apk fixture');

async function fixture(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'prepare-update-'));
  const server = http.createServer((request, response) => {
    if (server.redirects?.[request.url]) {
      response.writeHead(302, { Location: server.redirects[request.url] });
      response.end();
      return;
    }
    response.writeHead(server.assetStatus || 200);
    response.end(server.assetBytes || payload);
  });
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const sdk = path.join(dir, 'sdk');
    const tools = path.join(sdk, 'build-tools', '33.0.1');
    fs.mkdirSync(tools, { recursive: true });
    fs.mkdirSync(path.join(sdk, 'build-tools', '37.0.0'), { recursive: true });
    const script = `#!/usr/bin/env node
const cert = process.env.TEST_CERT || '${CERT}';
if (process.argv[1].endsWith('aapt2') && process.argv[2] === 'dump') {
  process.stdout.write("package: name='io.github.shovelshit.apkstore' versionCode='6' versionName='1.4.0'\\nsdkVersion:'24'\\n");
} else if (process.argv[1].endsWith('apksigner') && process.argv[2] === 'verify') {
  process.stdout.write('Signer #1 certificate SHA-256 digest: ' + cert + '\\n');
} else process.exit(9);
`;
    for (const name of ['aapt2', 'apksigner']) fs.writeFileSync(path.join(tools, name), script, { mode: 0o755 });
    const apk = path.join(dir, 'apk-store-1.4.0.apk');
    const manifest = path.join(dir, 'AndroidManifest.xml');
    const notes = path.join(dir, 'notes.txt');
    const output = path.join(dir, 'update.json');
    fs.writeFileSync(apk, payload);
    fs.writeFileSync(manifest, '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.shovelshit.apkstore" android:versionCode="6" android:versionName="1.4.0"/>');
    fs.writeFileSync(notes, 'Release notes\n');
    fs.writeFileSync(output, 'previous index');
    const options = { apk, manifest, sdk, url: RELEASE_URL, channel: 'development', notes, output,
      fetchAsset: async (requestedUrl, init) => {
        assert.equal(init.redirect, 'manual');
        assert.equal(init.credentials, 'omit');
        return fetch(`http://127.0.0.1:${server.address().port}${new URL(requestedUrl).pathname}`, init);
      } };
    await fn({ options, output, apk, manifest, notes, sdk, server });
  } finally {
    if (server.listening) await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('creates a missing nested index parent only after verification', async () => fixture(async ({ options, output }) => {
  const nested = path.join(path.dirname(output), 'missing', 'android', 'update.json');
  options.output = nested;
  await (await import('../scripts/prepare-update.mjs')).prepareUpdate(options);
  assert.equal(JSON.parse(fs.readFileSync(nested, 'utf8')).versionCode, 6);
}));

test('failed verification leaves a missing index parent absent', async () => fixture(async ({ options, output, server }) => {
  const nested = path.join(path.dirname(output), 'missing', 'android', 'update.json');
  options.output = nested;
  server.assetBytes = Buffer.from('different');
  await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(options));
  assert.equal(fs.existsSync(path.dirname(nested)), false);
}));

test('rejects empty notes without replacing an index', async () => fixture(async ({ options, output, notes }) => {
  fs.writeFileSync(notes, '  \n\t');
  await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(options), /notes/i);
  assert.equal(fs.readFileSync(output, 'utf8'), 'previous index');
}));

test('rejects oversized UTF-8 index without replacing an index', async () => fixture(async ({ options, output, notes }) => {
  fs.writeFileSync(notes, '中'.repeat(22000));
  await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(options), /index|索引/i);
  assert.equal(fs.readFileSync(output, 'utf8'), 'previous index');
}));

test('accepts an index at the exact 65536-byte UTF-8 boundary', async () => fixture(async ({ options, output, notes }) => {
  const emptyNotes = { schema: 1, packageName: 'io.github.shovelshit.apkstore', versionCode: 6,
    versionName: '1.4.0', minSdk: 24, channel: 'development', url: RELEASE_URL,
    size: payload.length, sha256: createHash('sha256').update(payload).digest('hex'),
    certificateSha256: CERT, notes: '' };
  const overhead = Buffer.byteLength(JSON.stringify(emptyNotes, null, 2) + '\n');
  fs.writeFileSync(notes, 'A'.repeat(65536 - overhead));
  await (await import('../scripts/prepare-update.mjs')).prepareUpdate(options);
  assert.equal(fs.statSync(output).size, 65536);
  fs.writeFileSync(notes, 'A'.repeat(65537 - overhead));
  await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(options), /index|索引/i);
  assert.equal(fs.statSync(output).size, 65536);
}));

test('rejects empty APK size before publishing', async () => fixture(async ({ options, output, apk }) => {
  const prepare = (await import('../scripts/prepare-update.mjs')).prepareUpdate;
  fs.truncateSync(apk, 0);
  await assert.rejects(prepare(options), /size|大小/i);
  assert.equal(fs.readFileSync(output, 'utf8'), 'previous index');
}));

test('rejects an APK larger than the consumer limit before reading or publishing it', async () => fixture(async ({ options, output, apk }) => {
  fs.truncateSync(apk, 2 ** 30 + 1);
  await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(options), /size/i);
  assert.equal(fs.readFileSync(output, 'utf8'), 'previous index');
}));

test('rejects a versionCode outside Android integer range', async () => fixture(async ({ options, output, manifest }) => {
  fs.writeFileSync(manifest, fs.readFileSync(manifest, 'utf8').replace('versionCode="6"', 'versionCode="2147483648"'));
  await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(options), /Manifest package or version/i);
  assert.equal(fs.readFileSync(output, 'utf8'), 'previous index');
}));

test('CLI rejects missing and malformed options before touching tools or files', () => {
  const script = path.resolve(__dirname, '../scripts/prepare-update.mjs');
  for (const args of [[], ['--apk', '--manifest', 'file'], ['--unknown', 'x'], ['--apk', 'file', '--apk', 'other']]) {
    const result = require('node:child_process').spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
    assert.equal(result.status, 1, result.stdout);
    assert.match(result.stderr, /option|required|unknown|duplicate|参数|缺少|未知|重复/i);
  }
});

test('CLI accepts SDK environment fallback when --sdk is absent', () => fixture(async ({ options, sdk }) => {
  const script = path.resolve(__dirname, '../scripts/prepare-update.mjs');
  const args = ['--apk', options.apk, '--manifest', options.manifest, '--url', options.url,
    '--channel', options.channel, '--notes', options.notes, '--output', options.output];
  const env = { ...process.env, ANDROID_HOME: sdk };
  delete env.ANDROID_SDK_ROOT;
  const result = require('node:child_process').spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', env });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /fetch failed|Release asset unavailable|network/i);
  assert.doesNotMatch(result.stderr, /SDK option|build-tools unavailable/i);
}));

test('publishes actual APK fields after independently fetching matching release bytes', async () => fixture(async ({ options, output }) => {
  await (await import('../scripts/prepare-update.mjs')).prepareUpdate(options);
  assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), {
    schema: 1, packageName: 'io.github.shovelshit.apkstore', versionCode: 6,
    versionName: '1.4.0', minSdk: 24, channel: 'development', url: RELEASE_URL,
    size: payload.length, sha256: createHash('sha256').update(payload).digest('hex'),
    certificateSha256: CERT, notes: 'Release notes'
  });
}));

test('follows an HTTPS release redirect and publishes the verified final bytes', async () => fixture(async ({ options, output, server }) => {
  server.redirects = { [new URL(RELEASE_URL).pathname]: 'https://objects.example.test/assets/apk-store-1.4.0.apk' };
  await (await import('../scripts/prepare-update.mjs')).prepareUpdate(options);
  assert.equal(JSON.parse(fs.readFileSync(output, 'utf8')).sha256, createHash('sha256').update(payload).digest('hex'));
}));

test('follows a relative HTTPS redirect against its current release URL', async () => fixture(async ({ options, output, server }) => {
  server.redirects = { [new URL(RELEASE_URL).pathname]: '../../assets/apk-store-1.4.0.apk' };
  await (await import('../scripts/prepare-update.mjs')).prepareUpdate(options);
  assert.equal(JSON.parse(fs.readFileSync(output, 'utf8')).size, payload.length);
}));

for (const [name, mutate] of [
  ['remote digest differs', ({ server }) => { server.assetBytes = Buffer.from('different'); }],
  ['release asset is missing', ({ server }) => { server.assetStatus = 404; }],
  ['production URL is not HTTPS', ({ options }) => { options.url = 'http://127.0.0.1/apk-store-1.4.0.apk'; }],
  ['redirect downgrade to HTTP', ({ server }) => { server.redirects = { [new URL(RELEASE_URL).pathname]: 'http://objects.example.test/assets/apk-store-1.4.0.apk' }; }],
  ['redirect loop', ({ server }) => { server.redirects = { [new URL(RELEASE_URL).pathname]: RELEASE_URL }; }],
  ['redirect chain beyond five hops', ({ server }) => {
    server.redirects = { [new URL(RELEASE_URL).pathname]: 'https://example.test/step-1' };
    for (let i = 1; i <= 6; i += 1) server.redirects[`/step-${i}`] = `https://example.test/step-${i + 1}`;
  }],
  ['redirect target with credentials', ({ server }) => { server.redirects = { [new URL(RELEASE_URL).pathname]: 'https://user:secret@objects.example.test/assets/apk-store-1.4.0.apk' }; }],
  ['manifest version differs', ({ manifest }) => { fs.writeFileSync(manifest, fs.readFileSync(manifest, 'utf8').replace('versionCode="6"', 'versionCode="7"')); }],
  ['manifest root version differs despite preceding comment', ({ manifest }) => {
    fs.writeFileSync(manifest, '<!-- android:versionCode="6" --><manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.shovelshit.apkstore" android:versionCode="7" android:versionName="1.4.0"/>');
  }],
  ['attribute is outside the manifest root', ({ manifest }) => {
    fs.writeFileSync(manifest, '<other xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.shovelshit.apkstore" android:versionCode="6" android:versionName="1.4.0"/>');
  }],
  ['APK signer differs', ({ options }) => { options.env = { ...process.env, TEST_CERT: '0'.repeat(64) }; }]
]) {
  test(`refuses ${name} and preserves the prior index`, async () => fixture(async (state) => {
    mutate(state);
    const errors = {
      'redirect downgrade to HTTP': /HTTPS/,
      'redirect loop': /redirect/i,
      'redirect chain beyond five hops': /redirect limit/,
      'redirect target with credentials': /credentials/,
      'manifest root version differs despite preceding comment': /APK package\/version/,
      'attribute is outside the manifest root': /Manifest package or version/
    };
    await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(state.options), errors[name]);
    assert.equal(fs.readFileSync(state.output, 'utf8'), 'previous index');
  }));
}
