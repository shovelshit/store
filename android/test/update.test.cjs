const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { readFileSync } = require('node:fs');

test('update policy, coalescing, and bounded transport', () => {
  const root = path.resolve(__dirname, '../app/src/main/java/io/github/shovelshit/apkstore');
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'store-update-test-'));
  try {
    const lock = JSON.parse(readFileSync(path.resolve(__dirname, '../dependencies.lock.json'), 'utf8'));
    const deps = lock.map(item => path.resolve(__dirname, '../.deps', item.name));
    const setup = spawnSync('node', [path.resolve(__dirname, '../scripts/dependencies.mjs')], { encoding: 'utf8' });
    if (setup.status !== 0) throw new Error(`dependency resolution failed: ${setup.stderr}`);
    const cp = deps.join(path.delimiter);
    const sources = ['UpdatePolicy.java', 'UpdateTransport.java', 'UpdateGate.java', 'UpdateJob.java', 'UpdateReady.java', 'UpdateCompletion.java', 'UpdatePromotion.java']
      .map(name => path.join(root, name));
    const tests = ['UpdatePolicyTest.java', 'UpdateTransportTest.java', 'UpdateGateTest.java', 'UpdateJobTest.java', 'UpdateReadyTest.java', 'UpdatePromotionTest.java']
      .map(name => path.join(__dirname, name));
    const compile = spawnSync('javac', ['-cp', cp, '-d', temporary, ...sources, ...tests], { encoding: 'utf8' });
    if (compile.status !== 0) throw new Error(`javac failed:\n${compile.stdout}${compile.stderr}`);
    for (const name of ['UpdatePolicyTest', 'UpdateTransportTest', 'UpdateGateTest', 'UpdateJobTest', 'UpdateReadyTest', 'UpdatePromotionTest']) {
      const result = spawnSync('java', ['-cp', [temporary, ...deps].join(path.delimiter), `io.github.shovelshit.apkstore.${name}`], { encoding: 'utf8' });
      if (result.status !== 0) throw new Error(`${name} failed:\n${result.stdout}${result.stderr}`);
    }
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
