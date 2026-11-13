const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

test('native download policy matches attachment and DownloadManager states', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'store-download-test-'));
  try {
    const root = path.resolve(__dirname, '../app/src/main/java/io/github/shovelshit/apkstore');
    const compile = spawnSync('javac', ['-d', temp, path.join(root, 'DownloadPolicy.java'),
      path.join(root, 'DownloadVerification.java'), path.join(__dirname, 'DownloadPolicyTest.java'),
      path.join(__dirname, 'DownloadVerificationTest.java')], { encoding: 'utf8' });
    assert.equal(compile.status, 0, `${compile.stdout}${compile.stderr}`);
    const run = spawnSync('java', ['-cp', temp, 'io.github.shovelshit.apkstore.DownloadPolicyTest'], { encoding: 'utf8' });
    assert.equal(run.status, 0, `${run.stdout}${run.stderr}`);
    const verification = spawnSync('java', ['-cp', temp, 'io.github.shovelshit.apkstore.DownloadVerificationTest'], { encoding: 'utf8' });
    assert.equal(verification.status, 0, `${verification.stdout}${verification.stderr}`);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
