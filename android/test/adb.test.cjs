const { test } = require('node:test');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

test('ADB transport JVM protocol tests', () => {
  const root = path.resolve(__dirname, '..');
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'store-adb-test-'));
  try {
    const sources = [
      path.join(root, 'app/src/main/java/io/github/shovelshit/apkstore/adb/AdbClient.java'),
      path.join(root, 'app/src/main/java/io/github/shovelshit/apkstore/adb/AdbIdentity.java'),
      path.join(__dirname, 'AdbClientTest.java'),
    ];
    const compile = spawnSync('javac', ['-d', temporary, ...sources], { encoding: 'utf8' });
    if (compile.status !== 0) throw new Error(`javac failed:\n${compile.stdout}${compile.stderr}`);
    const run = spawnSync('java', ['-cp', temporary, 'AdbClientTest'], { encoding: 'utf8', timeout: 30000 });
    if (run.status !== 0) throw new Error(`JVM tests failed:\n${run.stdout}${run.stderr}`);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
