# Android 9 ADB Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Store APK 1.3.0 with native ADB connection, application permission management, and APK installation for devices with ADB already enabled.

**Architecture:** A Java-only ADB transport and operation layer talks directly to adbd. Native Android screens own connection lifecycle and user confirmations; the remote WebView never receives a privileged bridge. Existing download and normal installation flows remain usable without ADB.

**Tech Stack:** Java 11 language level, Android API 24+, Android 9/API 28 primary target, SDK aapt2/d8/apksigner, Node built-in test runner for launching Java behavioral tests.

## Global Constraints

- 用户确认设备已经开放 ADB，不需要开发电脑端开启 ADB 的流程。
- 默认连接本机 `127.0.0.1:5555`，允许修改主机地址和端口并记住设置。
- “ADB 提权”指通过已获授权的 ADB shell 执行操作；不将普通应用进程变为 root，也不承诺设备固件允许所有命令。
- 高权限入口仅在原生页面，不向远程 WebView 暴露执行 ADB 的 JavaScript 接口。
- 同一连接串行执行变更操作；切换目标或断开后，旧请求不得更新新目标的状态。
- 包名、权限名和命令参数严格校验，执行固定的功能命令，不提供任意 shell 终端。
- 本次不修改 Store 账号、GitHub Token、Worker、D1 或 Movie 功能。
- Use `apply_patch` for file changes. Preserve others' changes and private keys. No default uninstall, data clearing, forced downgrade, root escalation, or automatic grants.
- Package remains `io.github.shovelshit.apkstore`; release 1.3.0 / versionCode 5; existing signing identity must not be silently replaced.
- Do not commit APKs, keystores, private ADB identity files, or generated build directories. Runtime ADB keys persist only in the app-private directory.

## File structure and execution

Store root is `/Users/lijinfeng/private/tools/modules/store`, branch `codex/android9-adb-tools`; parent remains `/Users/lijinfeng/private/tools`. Existing baseline Store tests: 28 passing. No unrelated dirty files; the approved spec is the only initial untracked document. Work in this existing feature checkout, not master; do not create another checkout during these tasks.

ADB transport files live in `android/app/src/main/java/io/github/shovelshit/apkstore/adb/`, independent of Android APIs. UI and content-URI integration live alongside the existing Activity files. Each task owns its listed files and its behavioral tests. No parallel implementers. Parent handles SDK/emulator preparation, task reviews, independent testing, integration, and publication.

### Task 1: Java ADB transport and identity

**Files:**
- Create: `android/app/src/main/java/io/github/shovelshit/apkstore/adb/AdbClient.java`
- Create: `android/app/src/main/java/io/github/shovelshit/apkstore/adb/AdbIdentity.java`
- Create: `android/test/AdbClientTest.java`
- Create: `android/test/adb.test.cjs` (Node launcher that compiles/runs Java tests in an isolated temporary directory)

**Interfaces:**
- `AdbIdentity.loadOrCreate(File directory)` returns `KeyPair`; raw encoded key material must not be logged. `AdbIdentity` also produces protocol-compatible RSA signatures and adbd public-key payloads.
- `new AdbClient(KeyPair identity)` followed by `connect(String host, int port, AuthListener listener)`; listener reports waiting for device authorization. Construct before connect so another thread can call `close()` to cancel a blocked handshake.
- `AdbClient.shell(String command, int timeoutMillis)` returns `ShellResult` with public final `String output`, `int exitCode`; an unambiguous per-command random exit marker distinguishes failure from empty success. Do not trust a marker absent from output.
- `AdbClient.push(InputStream source, long length, String remotePath, ProgressListener listener)` implements sync SEND/DATA/DONE and verifies server OKAY/FAIL. Progress listener receives transferred and total bytes. Callers own the input stream.
- `AdbClient.close()` is idempotent and may run concurrently to cancel network I/O; other methods are serialized by the consumer.
- Define `AuthListener.onWaitingForAuthorization()` and `ProgressListener.onProgress(long transferred, long total)` within AdbClient. Operations throw descriptive `IOException` for disconnect/protocol/timeout failures.

- [ ] **Step 1: Write behavioral protocol tests first.** Use a TCP ServerSocket fake adbd and literal independent packet fixtures. Cover fragmented headers/payloads, CNXN, token signature/public key authentication, WRTE/OKAY flow, shell error exit status, sync transfer exact bytes and FAIL, malformed header/length/checksum, stream-ID mismatch, timeout, cancellation and truncation.

```java
// Real sockets at the dependency boundary; expected output is a literal.
AdbClient.ShellResult result = client.shell("id", 2000);
check(result.exitCode == 0 && result.output.equals("uid=2000(shell)\n"), "shell result");
```

- [ ] **Step 2: Run `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home node --test android/test/adb.test.cjs` and record expected missing-feature failure.** Keep tests meaningful, not assertions about source strings or constants.
- [ ] **Step 3: Implement bounded protocol operations.** Encode little-endian 24-byte ADB headers, verify magic/length and checksums with negotiated protocol version awareness, negotiate maxdata, AUTH RSA token signing and little-endian Android public key format. Bound payloads and output (2 MiB), use deadlines as well as socket inactivity timeouts, and handle partial packets independently of TCP reads. Gracefully close streams and reject unusable connections after failure.

```java
try (AdbClient client = new AdbClient(identity)) {
    client.connect(host, port, () -> showWaitingState());
    AdbClient.ShellResult identityResult = client.shell("id", 10000);
}
```

- [ ] **Step 4: Re-run Java transport tests and `node --test pages/store/*.test.cjs`; report RED/GREEN evidence.** No Android APIs may enter the transport so it runs on host JVM and Android API 24.
- [ ] **Step 5: Commit task files only.** Report exact interfaces, known limits, tests and commit in the task report. Do not touch UI/build scripts or approved documents.

### Task 2: Native ADB application management and installation

**Files:**
- Create: `android/app/src/main/java/io/github/shovelshit/apkstore/adb/AdbOperations.java`
- Create: `android/app/src/main/java/io/github/shovelshit/apkstore/AdbController.java`
- Create: `android/app/src/main/java/io/github/shovelshit/apkstore/AdbActivity.java`
- Create: `android/app/src/main/java/io/github/shovelshit/apkstore/ApkInstallSource.java`
- Modify: `android/app/src/main/java/io/github/shovelshit/apkstore/MainActivity.java`
- Modify: `android/app/src/main/java/io/github/shovelshit/apkstore/DownloadsActivity.java`
- Modify: `android/app/src/main/AndroidManifest.xml` (activity registration, not version until Task 3)
- Create: `android/test/AdbOperationsTest.java`, extend `android/test/adb.test.cjs`

**Interfaces:**
- Consumes Task 1 AdbClient / AdbIdentity; use a narrow `AdbOperations.CommandRunner` functional interface returning AdbClient.ShellResult to test operation behavior without Android.
- `AdbOperations` owns fixed commands, application/permission dumps parsing, supported runtime/special permissions, post-change state refresh, and install upload/cleanup sequencing. UI consumes typed application/permission results; preserve unsupported/unknown states instead of treating empty output as granted.
- `AdbController` owns a single executor, current AdbClient, generation id and close/cancel. Android main-thread callbacks are guarded by generation and destruction state. Invalidating is synchronous; queued old mutations never execute after disconnect/target switch. No reconnection side effect without explicit connect.
- `AdbActivity` accepts an optional explicit intent download id or document URI initiated by native UI. `ApkInstallSource` copies readable content to a bounded app-cache file when necessary, returns known length/display name/archive metadata, and cleans local temporary data. Do not trust provider displayName for shell commands.

- [ ] **Step 1: Write operation behavioral tests first.** Literal dumpsys and pm fixtures must cover requested/granted/unsupported permissions, Android 9 appops output variants, shell failures, command injection rejection, remote package selection, permissions not declared, installation failure and cleanup, and target generation invalidation. Commands are asserted at the dependency boundary with hand-derived expected strings.

```java
// "a.b; id" must be rejected before any command executes.
expectIllegalArgument(() -> operations.readPermissions("a.b; id"));
// A permission change is successful only after refresh confirms the new state.
```

- [ ] **Step 2: Run focused Java tests and record missing-operation failures.** Extend the launcher to include the new pure-Java class/tests; do not compile Android Activities with host-only classpath.
- [ ] **Step 3: Implement native UI and operations.** Follow existing simple programmatic layouts, Chinese labels, visible target/identity/Android version, host+port fields, waiting-auth/help/error states, disconnect and retry. Default third-party application list, search and include-system toggle. Read the actual remote requested permissions. Support declared Android 9 runtime storage/location/camera/microphone permissions via pm grant/revoke, WRITE_SECURE_SETTINGS through pm, and SYSTEM_ALERT_WINDOW / WRITE_SETTINGS / GET_USAGE_STATS through appops, with proper state refresh and unsupported reporting. Granting is explicitly user initiated.
- [ ] **Step 4: Implement install entry points.** Download completed rows gain an `ADB 安装` button that opens native ADB screen with download id, without changing ordinary install. ADB screen also uses ACTION_OPEN_DOCUMENT for local APK selection. Display selected file, archive info when available and target before a separate install confirmation. Upload in bounded chunks to `/data/local/tmp/apk-store-<random>.apk`, then `pm install -r <validated path>`; always attempt cleanup and report cleanup failure. Keep progress responsive, prevent duplicate jobs, offer cancel, show actual errors, and preserve normal install fallback. No privileged WebView bridge or exported tool Activity.
- [ ] **Step 5: Run operation/transport tests and Store regressions, compile Java against SDK android-34 with Java 17 `--release 11`.** Check async callbacks/rotation/destruction, document picker cancellation, source stream closure, and native entrance layout on phone/tablet. Parent independent testing will exercise actual APK after Task 3.
- [ ] **Step 6: Commit only task implementation/tests, report RED/GREEN and actual feature behavior.** No backend edits or signing changes.

### Task 3: Cross-platform build, version and delivery documentation

**Files:**
- Modify: `android/scripts/build-apk.mjs`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/io/github/shovelshit/apkstore/MainActivity.java` (User-Agent version)
- Modify: `android/README.md`
- Modify: `.gitignore` (ignore Android outputs and keys if missing)
- Create: `android/test/build-apk.test.cjs`

**Interfaces:**
- Consumes completed Java sources and behavioral test launcher.
- `node android/scripts/build-apk.mjs --sdk <path>` builds on macOS and Windows; optional `--ks <path>` uses a provided existing keystore. Existing repository keystore is preferred, no silent replacement. If absent, fail clearly unless an explicit development-signing flag is supplied; name the resulting artifact and signing limitation clearly.
- Java tool resolution honors JAVA_HOME and native platform suffix; SDK selection supports native aapt2/d8/apksigner and retains Windows wrappers. Version is derived from Manifest instead of keeping duplicate literals.
- Use `mkdtemp` under a selected ASCII temporary parent. Never recursively remove the selected parent, SDK or source root. Cleanup removes only the generated per-build child.

- [ ] **Step 1: Write executable build-script tests with fake SDK tool executables.** Assert native tool selection, produced APK/version name, missing original signing key behavior, explicit development signing, and that unrelated marker files in an APK_BUILD_DIR parent survive build cleanup. Do not test source strings.
- [ ] **Step 2: Run build tests and record expected failures against Windows-only original script.** Keep fake tools representative and argument-aware; actual SDK build remains required in Step 4.
- [ ] **Step 3: Implement platform compatibility and version 1.3.0 / 5.** Preserve current signing when present and support an explicitly chosen development key without logging secrets. Produce APK plus SHA-256 sidecar. Update Android README with connection/authentication, supported grants, ordinary vs ADB install, device restrictions, test/build commands, and signing/upgrade caveat. Remove outdated README statement that Store auth is disabled.
- [ ] **Step 4: Run full Android tests and Store tests, then perform actual SDK build with Java 17 and SDK `/Users/lijinfeng/Library/Android/sdk`.** Original Store key is currently absent in this checkout. Parent is investigating recoverable existing artifacts/keys; if none found, use explicit development signing for the deliverable and report that it cannot be guaranteed to overwrite the old APK. Do not use absence of a key to skip producing an installable development APK.
- [ ] **Step 5: Verify signed artifact with apksigner and aapt dump badging.** Independently confirm package, minSdk, version, SHA-256 and signer identity. Do not commit private key or artifact.
- [ ] **Step 6: Commit task code/docs/tests and report exact artifact paths.** Parent will run independent Android device tests where available, final code review, acceptance, and publish the authorized release after verifying results.

## Parent verification and integration checklist

- [ ] Task 1 review: spec compliance and task quality.
- [ ] Task 2 review: spec compliance and task quality.
- [ ] Task 3 review: spec compliance and task quality.
- [ ] Independent tester runs protocol/operation/build/Store checks on final branch and real SDK artifact inspection; exercise an Android 9 emulator if available. Existing local emulators are API 25/33, no device currently attached; do not label those as Android 9 verification.
- [ ] Whole-branch code review using original Store base `ea829bed9b79aafb14688e14b5a1b99474040f5b` and full diff, resolving consequential findings.
- [ ] Acceptance agent checks requested connection/authorization/install behaviors against approved spec and evidence, with real-device limits explicit.
- [ ] Commit Store source, push Store before parent submodule pin; no D1 migration.
- [ ] Publish APK and SHA-256 with release notes that disclose signer limitation if applicable. A development signature must not be described as an update-compatible release without signer evidence.
- [ ] Retain artifact/report links, verify remote version/assets and clean checkout, report how to connect and install.
