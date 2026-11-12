const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const fakeTool = `#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const name = path.basename(process.argv[1]).replace(/\\.(exe|bat)$/, '');
const args = process.argv.slice(2);
const option = (key) => args[args.indexOf(key) + 1];
if (name === 'aapt2' && args[0] === 'compile') fs.writeFileSync(option('-o'), 'resources');
if (name === 'aapt2' && args[0] === 'link') {
  // Empty ZIP, with an EOCD, so the real script's dex insertion runs.
  fs.writeFileSync(option('-o'), Buffer.from('504b0506000000000000000000000000000000000000', 'hex'));
  fs.mkdirSync(option('--java'), { recursive: true });
}
if (name === 'javac') fs.writeFileSync(path.join(option('-d'), 'Fixture.class'), 'class');
if (name === 'd8') fs.writeFileSync(path.join(option('--output'), 'classes.dex'), 'dex');
if (name === 'zipalign') fs.copyFileSync(args[args.length - 2], args[args.length - 1]);
if (name === 'keytool') fs.writeFileSync(option('-keystore'), 'dev-key');
if (name === 'apksigner' && args[0] === 'sign') {
  if (!fs.existsSync(option('--ks'))) process.exit(8);
  fs.copyFileSync(args[args.length - 1], option('--out'));
}
if (!['aapt2', 'javac', 'd8', 'zipalign', 'keytool', 'apksigner'].includes(name)) process.exit(9);
`;

function fixture(fn) {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'apk-build-test-'));
  try {
    const android = path.join(temp, 'android');
    const sdk = path.join(temp, 'sdk');
    const buildTools = path.join(sdk, 'build-tools', '33.0.1');
    const javaBin = path.join(temp, 'jdk', 'bin');
    const workParent = path.join(temp, 'work');
    fs.mkdirSync(path.join(android, 'scripts'), { recursive: true });
    fs.mkdirSync(path.join(android, 'app', 'src', 'main', 'java'), { recursive: true });
    fs.mkdirSync(path.join(android, 'app', 'src', 'main', 'res'), { recursive: true });
    fs.copyFileSync(path.join(root, 'scripts', 'build-apk.mjs'), path.join(android, 'scripts', 'build-apk.mjs'));
    fs.writeFileSync(path.join(android, 'app', 'src', 'main', 'AndroidManifest.xml'),
      '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test" android:versionCode="5" android:versionName="1.3.0"/>');
    fs.writeFileSync(path.join(android, 'app', 'src', 'main', 'java', 'Fixture.java'), 'class Fixture {}');
    fs.mkdirSync(buildTools, { recursive: true });
    fs.mkdirSync(javaBin, { recursive: true });
    fs.mkdirSync(path.join(sdk, 'platforms', 'android-34'), { recursive: true });
    fs.writeFileSync(path.join(sdk, 'platforms', 'android-34', 'android.jar'), 'jar');
    for (const name of ['aapt2', 'd8', 'zipalign', 'apksigner']) executable(path.join(buildTools, name), fakeTool);
    for (const name of ['javac', 'keytool']) executable(path.join(javaBin, name), fakeTool);
    fs.mkdirSync(workParent);
    fs.writeFileSync(path.join(workParent, 'keep.txt'), 'unrelated');
    const run = (...args) => spawnSync(process.execPath,
      [path.join(android, 'scripts', 'build-apk.mjs'), '--sdk', sdk, ...args],
      { encoding: 'utf8', env: { ...process.env, JAVA_HOME: path.join(temp, 'jdk'), APK_BUILD_DIR: workParent } });
    return fn({ android, run, workParent });
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
}

function executable(file, source) {
  fs.writeFileSync(file, source, { mode: 0o755 });
}

test('requires an existing signer unless development signing is explicit', () => fixture(({ run, android }) => {
  const result = run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /keystore|签名/i);
  assert.equal(fs.existsSync(path.join(android, '.keystore', 'debug.keystore')), false);
}));

test('native SDK tools produce a manifest-versioned APK and hash without deleting the work parent', () => fixture(({ run, android, workParent }) => {
  const result = run('--dev-signing');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const apk = path.join(android, 'dist', 'apk-store-1.3.0.apk');
  assert.equal(fs.existsSync(apk), true);
  assert.match(fs.readFileSync(`${apk}.sha256`, 'utf8'), /^[a-f0-9]{64}  apk-store-1\.3\.0\.apk\n$/);
  assert.equal(fs.readFileSync(path.join(workParent, 'keep.txt'), 'utf8'), 'unrelated');
  assert.deepEqual(fs.readdirSync(workParent), ['keep.txt']);
  assert.equal(fs.existsSync(path.join(android, '.keystore', 'development.keystore')), true);
  if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(android, '.keystore', 'development.keystore')).mode & 0o077, 0);
  assert.equal(fs.existsSync(path.join(android, '.keystore', 'debug.keystore')), false);
}));

test('provided keystore is used without overwriting or generating a repository key', () => fixture(({ run, android, workParent }) => {
  const key = path.join(workParent, 'original.keystore');
  fs.writeFileSync(key, 'provided');
  const result = run('--ks', key);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(fs.readFileSync(key, 'utf8'), 'provided');
  assert.equal(fs.existsSync(path.join(android, '.keystore', 'debug.keystore')), false);
  assert.equal(fs.existsSync(path.join(android, 'dist', 'apk-store-1.3.0.apk')), true);
}));

test('existing repository signing key takes priority over development signing', () => fixture(({ run, android }) => {
  const key = path.join(android, '.keystore', 'debug.keystore');
  fs.mkdirSync(path.dirname(key));
  fs.writeFileSync(key, 'original');
  const result = run('--dev-signing');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(fs.readFileSync(key, 'utf8'), 'original');
  assert.equal(fs.existsSync(path.join(android, '.keystore', 'development.keystore')), false);
}));

test('development signing reuses its persisted key on the next explicit build', () => fixture(({ run, android }) => {
  const key = path.join(android, '.keystore', 'development.keystore');
  const first = run('--dev-signing');
  assert.equal(first.status, 0, first.stdout + first.stderr);
  const initial = fs.readFileSync(key);
  const second = run('--dev-signing');
  assert.equal(second.status, 0, second.stdout + second.stderr);
  assert.deepEqual(fs.readFileSync(key), initial);
  assert.doesNotMatch(second.stdout, /keytool \(生成开发签名\)/);
}));
