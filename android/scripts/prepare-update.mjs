#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { existsSync, readdirSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const PACKAGE = "io.github.shovelshit.apkstore";
const CERT = "a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97";

function sdkTool(sdk, name) {
  const suffix = tool => process.platform === "win32" ? (tool === "apksigner" ? ".bat" : ".exe") : "";
  const versions = readdirSync(join(sdk, "build-tools"))
    .filter(version => /^\d+(?:\.\d+)+$/.test(version))
    .filter(version => ["aapt2", "apksigner"].every(tool => existsSync(join(sdk, "build-tools", version, tool + suffix(tool)))))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  if (!versions.length) throw new Error("Android SDK build-tools unavailable");
  return join(sdk, "build-tools", versions.at(-1), name + suffix(name));
}

function toolOutput(sdk, name, args, env) {
  const command = sdkTool(sdk, name);
  const batch = /\.bat$/i.test(command);
  const result = spawnSync(batch ? "cmd.exe" : command,
    batch ? ["/d", "/c", command, ...args] : args,
    { encoding: "utf8", env, maxBuffer: 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${name} metadata verification failed: ${result.error?.message || result.stderr || result.status}`);
  return result.stdout;
}

function manifestMetadata(xml) {
  const source = xml.replace(/<!--[\s\S]*?-->/g, "").replace(/^\s*<\?xml\b[^?]*\?>/, "").trimStart();
  if (!/^<manifest(?=[\s/>])/.test(source)) throw new Error("Manifest package or version invalid");
  let quote = "", end = -1;
  for (let i = "<manifest".length; i < source.length; i += 1) {
    const character = source[i];
    if (quote) {
      if (character === quote) quote = "";
    } else if (character === "'" || character === '"') {
      quote = character;
    } else if (character === ">") {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("Manifest package or version invalid");
  const attributes = new Map();
  const content = source.slice("<manifest".length, end).replace(/\/\s*$/, "");
  const token = /\s+([A-Za-z_:][\w.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/y;
  let offset = 0;
  while (offset < content.trimEnd().length) {
    token.lastIndex = offset;
    const match = token.exec(content);
    if (!match || attributes.has(match[1])) throw new Error("Manifest package or version invalid");
    attributes.set(match[1], match[2] ?? match[3]);
    offset = token.lastIndex;
  }
  const attr = name => attributes.get(name);
  const packageName = attr("package"), versionName = attr("android:versionName");
  const versionCode = Number(attr("android:versionCode"));
  if (attr("xmlns:android") !== "http://schemas.android.com/apk/res/android" || packageName !== PACKAGE
      || !/^\d+(?:\.\d+)+$/.test(versionName || "") || !Number.isSafeInteger(versionCode) || versionCode <= 0)
    throw new Error("Manifest package or version invalid");
  return { packageName, versionCode, versionName };
}

function validReleaseUrl(value, base) {
  const parsed = new URL(value, base);
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.hash)
    throw new Error("Release URL and redirects must be HTTPS without credentials or fragment");
  return parsed;
}

async function fetchVerifiedAsset(url, fetchAsset) {
  let current = validReleaseUrl(url);
  const visited = new Set();
  for (let hops = 0; hops <= 5; hops += 1) {
    if (visited.has(current.href)) throw new Error("Release redirect loop");
    visited.add(current.href);
    const response = await fetchAsset(current.href, { redirect: "manual", credentials: "omit" });
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      if (hops === 5) throw new Error("Release redirect limit exceeded");
      const location = response.headers.get("location");
      if (!location) throw new Error("Release redirect missing location");
      current = validReleaseUrl(location, current);
      await response.body?.cancel();
      continue;
    }
    if (!response.ok) throw new Error(`Release asset unavailable: HTTP ${response.status}`);
    return Buffer.from(await response.arrayBuffer());
  }
  throw new Error("Release redirect limit exceeded");
}

export async function prepareUpdate({ apk, manifest, sdk, url, channel, notes, output, fetchAsset = fetch, env = process.env }) {
  if (!apk || !manifest || !sdk || !url || !notes || !output) throw new Error("APK, Manifest, SDK, URL, notes and output are required");
  const releaseUrl = validReleaseUrl(url);
  if (channel !== "development") throw new Error("Unsupported release channel");
  const expected = manifestMetadata(await readFile(manifest, "utf8"));
  if (basename(releaseUrl.pathname) !== `apk-store-${expected.versionName}.apk`) throw new Error("Release asset name/version mismatch");
  const badging = toolOutput(sdk, "aapt2", ["dump", "badging", apk], env);
  const pkg = badging.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m);
  const minimum = badging.match(/^sdkVersion:'(\d+)'/m);
  if (!pkg || !minimum || pkg[1] !== expected.packageName || Number(pkg[2]) !== expected.versionCode || pkg[3] !== expected.versionName)
    throw new Error("APK package/version does not match Manifest");
  const minSdk = Number(minimum[1]);
  if (!Number.isSafeInteger(minSdk) || minSdk < 1) throw new Error("Invalid APK minSdk");
  const certificates = toolOutput(sdk, "apksigner", ["verify", "--print-certs", apk], env)
    .match(/Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})/g) || [];
  if (certificates.length !== 1 || certificates[0].split(": ")[1].toLowerCase() !== CERT)
    throw new Error("APK signer differs from installed certificate");

  const local = await readFile(apk);
  const size = (await stat(apk)).size;
  const sha256 = createHash("sha256").update(local).digest("hex");
  const remote = await fetchVerifiedAsset(url, fetchAsset);
  if (remote.length !== size || createHash("sha256").update(remote).digest("hex") !== sha256)
    throw new Error("Downloaded release asset differs from local APK");
  const release = {
    schema: 1, ...expected, minSdk, channel, url, size, sha256,
    certificateSha256: CERT, notes: (await readFile(notes, "utf8")).trim()
  };
  const temporary = join(dirname(output), `.${basename(output)}.${randomUUID()}.tmp`);
  try {
    await writeFile(temporary, JSON.stringify(release, null, 2) + "\n", { flag: "wx" });
    await rename(temporary, output);
  } finally {
    await rm(temporary, { force: true });
  }
  return release;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2);
  const value = name => args[args.indexOf(name) + 1];
  if (args.includes("--help")) {
    console.log("Usage: node android/scripts/prepare-update.mjs --apk FILE --manifest FILE --sdk DIR --url HTTPS_URL --channel development --notes FILE --output FILE");
  } else {
    try {
      const release = await prepareUpdate({
        apk: value("--apk"), manifest: value("--manifest"), sdk: value("--sdk") || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT,
        url: value("--url"), channel: value("--channel"), notes: value("--notes"), output: value("--output")
      });
      console.log(`Verified ${release.versionName}, ${release.size} bytes, SHA-256 ${release.sha256}`);
    } catch (error) {
      console.error(`Update index not published: ${error.message}`);
      process.exitCode = 1;
    }
  }
}
