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
      path.join(root, 'app/src/main/java/io/github/shovelshit/apkstore/adb/AdbOperations.java'),
      path.join(root, 'app/src/main/java/io/github/shovelshit/apkstore/AdbController.java'),
      path.join(root, 'app/src/main/java/io/github/shovelshit/apkstore/AdbNavigation.java'),
      path.join(root, 'app/src/main/java/io/github/shovelshit/apkstore/AdbPresentation.java'),
      path.join(__dirname, 'AdbClientTest.java'),
      path.join(__dirname, 'AdbOperationsTest.java'),
      path.join(__dirname, 'AdbControllerTest.java'),
      path.join(__dirname, 'AdbNavigationTest.java'),
      path.join(__dirname, 'AdbPresentationTest.java'),
    ];
    const compile = spawnSync('javac', ['-d', temporary, ...sources], { encoding: 'utf8' });
    if (compile.status !== 0) throw new Error(`javac failed:\n${compile.stdout}${compile.stderr}`);
    const run = spawnSync('java', ['-cp', temporary, 'AdbClientTest'], { encoding: 'utf8', timeout: 30000 });
    if (run.status !== 0) throw new Error(`JVM tests failed:\n${run.stdout}${run.stderr}`);
    const operations = spawnSync('java', ['-cp', temporary, 'AdbOperationsTest'], { encoding: 'utf8', timeout: 30000 });
    if (operations.status !== 0) throw new Error(`Operations JVM tests failed:\n${operations.stdout}${operations.stderr}`);
    const controller = spawnSync('java', ['-cp', temporary, 'AdbControllerTest'], { encoding: 'utf8', timeout: 30000 });
    if (controller.status !== 0) throw new Error(`Controller JVM tests failed:\n${controller.stdout}${controller.stderr}`);
    const navigation = spawnSync('java', ['-cp', temporary, 'AdbNavigationTest'], { encoding: 'utf8', timeout: 30000 });
    if (navigation.status !== 0) throw new Error(`Navigation JVM tests failed:\n${navigation.stdout}${navigation.stderr}`);
    const presentation = spawnSync('java', ['-cp', temporary, 'AdbPresentationTest'], { encoding: 'utf8', timeout: 30000 });
    if (presentation.status !== 0) throw new Error(`Presentation JVM tests failed:\n${presentation.stdout}${presentation.stderr}`);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
});
