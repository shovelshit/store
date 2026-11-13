const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const CERT = 'a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97';
const URL = 'https://example.test/releases/download/android-v1.4.0-dev/apk-store-1.4.0.apk';
const payload = Buffer.from('signed apk fixture');

async function fixture(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'prepare-update-'));
  const server = http.createServer((request, response) => {
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
    const options = { apk, manifest, sdk, url: URL, channel: 'development', notes, output,
      fetchAsset: async (_url, init) => fetch(`http://127.0.0.1:${server.address().port}/release.apk`, init) };
    await fn({ options, output, apk, manifest, server });
  } finally {
    if (server.listening) await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('publishes actual APK fields after independently fetching matching release bytes', async () => fixture(async ({ options, output }) => {
  const result = await options.fetchAsset(options.url);
  assert.equal(result.status, 200);
  await (await import('../scripts/prepare-update.mjs')).prepareUpdate(options);
  assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), {
    schema: 1, packageName: 'io.github.shovelshit.apkstore', versionCode: 6,
    versionName: '1.4.0', minSdk: 24, channel: 'development', url: URL,
    size: payload.length, sha256: createHash('sha256').update(payload).digest('hex'),
    certificateSha256: CERT, notes: 'Release notes'
  });
}));

for (const [name, mutate] of [
  ['remote digest differs', ({ server }) => { server.assetBytes = Buffer.from('different'); }],
  ['release asset is missing', ({ server }) => { server.assetStatus = 404; }],
  ['production URL is not HTTPS', ({ options }) => { options.url = 'http://127.0.0.1/apk-store-1.4.0.apk'; }],
  ['manifest version differs', ({ manifest }) => { fs.writeFileSync(manifest, fs.readFileSync(manifest, 'utf8').replace('versionCode="6"', 'versionCode="7"')); }],
  ['APK signer differs', ({ options }) => { options.env = { ...process.env, TEST_CERT: '0'.repeat(64) }; }]
]) {
  test(`refuses ${name} and preserves the prior index`, async () => fixture(async (state) => {
    mutate(state);
    await assert.rejects((await import('../scripts/prepare-update.mjs')).prepareUpdate(state.options));
    assert.equal(fs.readFileSync(state.output, 'utf8'), 'previous index');
  }));
}
