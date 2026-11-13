const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const sdk = process.env.ANDROID_SDK_ROOT || '/Users/lijinfeng/Library/Android/sdk';
const serial = process.env.STORE_TEST_SERIAL;
const tools = path.join(sdk, 'build-tools', '33.0.1');
const android = path.join(sdk, 'platforms', 'android-34', 'android.jar');
const device = __dirname;

function run(program, args) {
  return execFileSync(program, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

test('batch summaries survive real AdbActivity recreation', { skip: !serial }, () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'store-batch-lifecycle-'));
  try {
    const classes = path.join(tmp, 'classes');
    const dex = path.join(tmp, 'dex');
    fs.mkdirSync(classes);
    fs.mkdirSync(dex);
    const unsigned = path.join(tmp, 'unsigned.apk');
    const aligned = path.join(tmp, 'aligned.apk');
    const signed = path.join(tmp, 'signed.apk');
    run(path.join(tools, 'aapt2'), ['link', '-I', android, '--manifest', path.join(device, 'AndroidManifest.xml'),
      '--min-sdk-version', '24', '--target-sdk-version', '28', '-o', unsigned]);
    run(path.join(process.env.JAVA_HOME, 'bin', 'javac'), ['--release', '11', '-classpath', android, '-d', classes,
      path.join(device, 'BatchLifecycleInstrumentation.java')]);
    const files = fs.readdirSync(path.join(classes, 'io/github/shovelshit/apkstore/device'))
      .filter(name => name.startsWith('BatchLifecycleInstrumentation') && name.endsWith('.class'))
      .map(name => path.join(classes, 'io/github/shovelshit/apkstore/device', name));
    run(path.join(tools, 'd8'), ['--lib', android, '--min-api', '24', '--output', dex, ...files]);
    run('zip', ['-j', unsigned, path.join(dex, 'classes.dex')]);
    run(path.join(tools, 'zipalign'), ['-p', '-f', '4', unsigned, aligned]);
    run(path.join(tools, 'apksigner'), ['sign', '--ks', path.join(device, '../../.keystore/development.keystore'),
      '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
      '--out', signed, aligned]);
    run(path.join(sdk, 'platform-tools', 'adb'), ['-s', serial, 'install', '-r', signed]);
    const output = run(path.join(sdk, 'platform-tools', 'adb'), ['-s', serial, 'shell', 'am', 'instrument', '-w',
      'io.github.shovelshit.apkstore.device/.BatchLifecycleInstrumentation']);
    assert.match(output, /INSTRUMENTATION_RESULT: PASS=real AdbActivity recreation preserves interrupted and visible batch summaries/,
      `instrumentation failed:\n${output}`);
    assert.match(output, /INSTRUMENTATION_CODE: -1/);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});
