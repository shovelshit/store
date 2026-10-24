#!/usr/bin/env node
// 免 Gradle 的 Android APK 构建脚本：aapt2 + javac + d8 + zipalign + apksigner
//
// 注意：aapt2 对非 ASCII 路径（例如用户目录含中文）会报 failed to open directory，
// 因此先把工程复制到纯 ASCII 的暂存目录构建，再把 APK 拷回 android/dist/。
//
// 用法: node scripts/build-apk.mjs [--sdk <Android SDK 路径>]

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  copyFileSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import { deflateRawSync } from "node:zlib";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ANDROID_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const DIST = join(ANDROID_ROOT, "dist");
const KEYSTORE = join(ANDROID_ROOT, ".keystore", "debug.keystore");
const VERSION = "1.2.0";

const args = process.argv.slice(2);
const optionValue = (name) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
};

function fail(message) {
  console.error(`\n[build-apk] 失败: ${message}`);
  process.exit(1);
}

function run(command, commandArgs, label) {
  console.log(`\n[build-apk] ${label || command}`);
  // Windows 下 .bat 不能直接 spawn（EINVAL），需要经 cmd.exe 转发
  const isBatch = /\.bat$/i.test(command);
  const result = isBatch
    ? spawnSync("cmd.exe", ["/d", "/c", command, ...commandArgs], { stdio: "inherit" })
    : spawnSync(command, commandArgs, { stdio: "inherit" });
  if (result.error) fail(`${command}: ${result.error.message}`);
  if (result.status !== 0) fail(`${label || command} 退出码 ${result.status}`);
}

const isAscii = (value) => /^[\x20-\x7e]*$/.test(String(value));

function findSdk() {
  const candidates = [
    optionValue("--sdk"),
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    "D:\\Android-data",
    join(process.env.LOCALAPPDATA || "", "Android", "Sdk")
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (existsSync(join(candidate, "platform-tools")) || existsSync(join(candidate, "build-tools"))) return candidate;
  }
  fail("找不到 Android SDK，请用 --sdk 指定，或设置 ANDROID_HOME");
}

function findWorkRoot() {
  const candidates = [
    process.env.APK_BUILD_DIR,
    process.platform === "win32" ? "C:\\Users\\Public\\apkbuild" : join(tmpdir(), "apkbuild")
  ].filter(Boolean);
  for (const candidate of candidates) {
    if (!isAscii(candidate)) continue;
    try {
      mkdirSync(candidate, { recursive: true });
      writeFileSync(join(candidate, ".write-test"), "ok");
      return candidate;
    } catch {
      // 换下一个候选
    }
  }
  fail("找不到可写的 ASCII 构建目录，可用 APK_BUILD_DIR 指定");
}

const versionKey = (name) => name.split(".").map((part) => Number.parseInt(part, 10) || 0);
function compareVersions(a, b) {
  const left = versionKey(a);
  const right = versionKey(b);
  for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
    const diff = (left[i] || 0) - (right[i] || 0);
    if (diff !== 0) return diff;
  }
  return 0;
}

function pickBuildTools(sdk) {
  const dir = join(sdk, "build-tools");
  const versions = readdirSync(dir).filter((name) => existsSync(join(dir, name, "aapt2.exe")));
  if (!versions.length) fail(`build-tools 下没有可用的 aapt2: ${dir}`);
  return join(dir, versions.sort(compareVersions).pop());
}

function pickPlatform(sdk) {
  const dir = join(sdk, "platforms");
  const available = readdirSync(dir).filter((name) => existsSync(join(dir, name, "android.jar")));
  if (!available.length) fail(`platforms 下没有 android.jar: ${dir}`);
  const preferred = available.find((name) => name === "android-34") || available.sort(compareVersions).pop();
  return { name: preferred, jar: join(dir, preferred, "android.jar") };
}

function walk(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    return entry.isDirectory() ? walk(full) : [full];
  });
}

// ---- 最小 zip 追加实现：保留原有条目（resources.arsc 的存储方式不变） ----
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function findEocd(buffer) {
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 22 - 0xffff); i -= 1) {
    if (buffer.readUInt32LE(i) === 0x06054b50) return i;
  }
  throw new Error("zip 结构异常：找不到 EOCD");
}

function dosDateTime(date) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, day };
}

function appendZipEntry(zipBuffer, entryName, data) {
  const eocd = findEocd(zipBuffer);
  const count = zipBuffer.readUInt16LE(eocd + 10);
  const centralSize = zipBuffer.readUInt32LE(eocd + 12);
  const centralOffset = zipBuffer.readUInt32LE(eocd + 16);

  const name = Buffer.from(entryName, "utf8");
  const compressed = deflateRawSync(data, { level: 9 });
  const checksum = crc32(data);
  const { time, day } = dosDateTime(new Date());

  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt16LE(0x0800, 6);
  local.writeUInt16LE(8, 8);
  local.writeUInt16LE(time, 10);
  local.writeUInt16LE(day, 12);
  local.writeUInt32LE(checksum, 14);
  local.writeUInt32LE(compressed.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(name.length, 26);
  local.writeUInt16LE(0, 28);
  const localRecord = Buffer.concat([local, name, compressed]);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt16LE(0x0800, 8);
  central.writeUInt16LE(8, 10);
  central.writeUInt16LE(time, 12);
  central.writeUInt16LE(day, 14);
  central.writeUInt32LE(checksum, 16);
  central.writeUInt32LE(compressed.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(name.length, 28);
  central.writeUInt16LE(0, 30);
  central.writeUInt16LE(0, 32);
  central.writeUInt16LE(0, 34);
  central.writeUInt16LE(0, 36);
  central.writeUInt32LE(0, 38);
  central.writeUInt32LE(centralOffset, 42);
  const centralRecord = Buffer.concat([central, name]);

  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(count + 1, 8);
  end.writeUInt16LE(count + 1, 10);
  end.writeUInt32LE(centralSize + centralRecord.length, 12);
  end.writeUInt32LE(centralOffset + localRecord.length, 16);
  end.writeUInt16LE(0, 20);

  return Buffer.concat([
    zipBuffer.subarray(0, centralOffset),
    localRecord,
    zipBuffer.subarray(centralOffset, centralOffset + centralSize),
    centralRecord,
    end
  ]);
}

// ---- 构建 ----
const sdk = findSdk();
const buildTools = pickBuildTools(sdk);
const platform = pickPlatform(sdk);
const work = findWorkRoot();

console.log(`[build-apk] SDK: ${sdk}`);
console.log(`[build-apk] build-tools: ${buildTools.split(/[\\/]/).pop()}`);
console.log(`[build-apk] platform: ${platform.name}`);
console.log(`[build-apk] 暂存目录: ${work}`);

rmSync(work, { recursive: true, force: true });
const SRC = join(work, "src");
const BUILD = join(work, "build");
const OUT = join(work, "dist");
cpSync(join(ANDROID_ROOT, "app", "src", "main"), SRC, { recursive: true });
for (const directory of [BUILD, OUT, join(BUILD, "gen"), join(BUILD, "classes"), join(BUILD, "dex")]) {
  mkdirSync(directory, { recursive: true });
}

const tool = (name) => join(buildTools, `${name}.exe`);
const batch = (name) => join(buildTools, `${name}.bat`);
const java = (name) => (process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", `${name}.exe`) : name);

// 1. 编译资源
run(tool("aapt2"), ["compile", "--dir", join(SRC, "res"), "-o", join(BUILD, "res.zip")], "aapt2 compile");

// 2. 链接资源 + 生成 R.java
run(tool("aapt2"), [
  "link",
  "-o", join(BUILD, "base.apk"),
  "-I", platform.jar,
  "--manifest", join(SRC, "AndroidManifest.xml"),
  "-R", join(BUILD, "res.zip"),
  "--java", join(BUILD, "gen"),
  "--auto-add-overlay",
  "--min-sdk-version", "24",
  "--target-sdk-version", "34"
], "aapt2 link");

// 3. 编译 Java
const sources = [
  ...walk(join(BUILD, "gen")).filter((file) => file.endsWith(".java")),
  ...walk(join(SRC, "java")).filter((file) => file.endsWith(".java"))
];
run(java("javac"), [
  "--release", "11",
  "-encoding", "UTF-8",
  "-classpath", platform.jar,
  "-d", join(BUILD, "classes"),
  ...sources
], `javac (${sources.length} 个源文件)`);

// 4. 转 dex
const classes = walk(join(BUILD, "classes")).filter((file) => file.endsWith(".class"));
run(batch("d8"), [
  "--lib", platform.jar,
  "--min-api", "24",
  "--output", join(BUILD, "dex"),
  ...classes
], `d8 (${classes.length} 个 class)`);

// 5. 合并 classes.dex
const dexFile = join(BUILD, "dex", "classes.dex");
if (!existsSync(dexFile)) fail("d8 未生成 classes.dex");
writeFileSync(
  join(BUILD, "app-unsigned.apk"),
  appendZipEntry(readFileSync(join(BUILD, "base.apk")), "classes.dex", readFileSync(dexFile))
);
console.log(`[build-apk] classes.dex 已并入 APK (${statSync(dexFile).size} bytes)`);

// 6. 对齐
run(tool("zipalign"), [
  "-p", "-f", "4",
  join(BUILD, "app-unsigned.apk"),
  join(BUILD, "app-aligned.apk")
], "zipalign");

// 7. 签名（缺失时生成 debug keystore，构建后回写仓库）
const signingKey = join(work, "debug.keystore");
const hasRepoKey = existsSync(KEYSTORE);
if (hasRepoKey) {
  copyFileSync(KEYSTORE, signingKey);
} else {
  run(java("keytool"), [
    "-genkeypair", "-v",
    "-keystore", signingKey,
    "-alias", "androiddebugkey",
    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
    "-storepass", "android", "-keypass", "android",
    "-dname", "CN=Android Debug,O=Android,C=US"
  ], "keytool (生成 debug 签名)");
}

const signed = join(OUT, "apk-store.apk");
run(batch("apksigner"), [
  "sign",
  "--ks", signingKey,
  "--ks-key-alias", "androiddebugkey",
  "--ks-pass", "pass:android",
  "--key-pass", "pass:android",
  "--out", signed,
  join(BUILD, "app-aligned.apk")
], "apksigner sign");

run(batch("apksigner"), ["verify", "--print-certs", signed], "apksigner verify");

// 8. 拷回仓库
mkdirSync(DIST, { recursive: true });
const output = join(DIST, `apk-store-${VERSION}.apk`);
copyFileSync(signed, output);
if (!hasRepoKey) {
  mkdirSync(dirname(KEYSTORE), { recursive: true });
  copyFileSync(signingKey, KEYSTORE);
  console.log(`[build-apk] 已保存调试签名: ${KEYSTORE}`);
}

const sha256 = createHash("sha256").update(readFileSync(output)).digest("hex");
const size = statSync(output).size;
console.log(`\n[build-apk] 完成: ${output}`);
console.log(`[build-apk] 大小: ${(size / 1024 / 1024).toFixed(2)} MB`);
console.log(`[build-apk] SHA-256: ${sha256}`);
