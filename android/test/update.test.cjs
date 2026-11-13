const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

test('update policy, coalescing, and bounded transport', () => {
  const root = path.resolve(__dirname, '../app/src/main/java/io/github/shovelshit/apkstore');
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'store-update-test-'));
  try {
    const sources = ['UpdatePolicy.java', 'UpdateTransport.java', 'UpdateGate.java']
      .map(name => path.join(root, name));
    const tests = ['UpdatePolicyTest.java', 'UpdateTransportTest.java', 'UpdateGateTest.java']
      .map(name => path.join(__dirname, name));
    const compile = spawnSync('javac', ['-d', temporary, ...sources, ...tests], { encoding: 'utf8' });
    if (compile.status !== 0) throw new Error(`javac failed:\n${compile.stdout}${compile.stderr}`);
    for (const name of ['UpdatePolicyTest', 'UpdateTransportTest', 'UpdateGateTest']) {
      const result = spawnSync('java', ['-cp', temporary, `io.github.shovelshit.apkstore.${name}`], { encoding: 'utf8' });
      if (result.status !== 0) throw new Error(`${name} failed:\n${result.stdout}${result.stderr}`);
    }
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
