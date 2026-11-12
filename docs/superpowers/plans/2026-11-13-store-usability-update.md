# Store usability, downloads and updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver APK 1.4.0 with usable native ADB navigation, truthful GitHub downloads and verified self-update discovery/download/install.

**Architecture:** Preserve the Java ADB transport/controller and move navigation into compact native panes. Fix existing DownloadManager boundaries separately from a new application-scoped update coordinator that downloads into private storage and verifies bytes/package/signing identity before exposing a read-only install URI. Publish a static update index through the existing Store asset deployment after release assets are confirmed downloadable.

**Tech Stack:** Android native Java (min24/target34), current SDK/JDK17 build script, Node behavioral/JVM tests, existing Worker static asset deployment. No AndroidX/Gradle requirement.

## Global Constraints

- 改版版本拟为 1.4.0 / versionCode 6；与本次发布的 1.3.0 开发版保持签名连续，可覆盖该开发版。更早未知签名版本的兼容性仍不能保证。
- ADB 命令、认证协议、用户 0 范围和原生高权限边界保持原有约束，不增加 WebView ADB 桥或任意 shell 入口。
- 返回栏和紧凑的目标状态固定在顶部，不随内容滚走。顶部返回与系统返回行为一致。
- 采用一个原生 Activity 内的三个页签，切换页签保留当前连接、搜索条件和已选文件。
- 索引地址为 `https://store.ltools.asia/android/update.json`，由 Store 静态资源部署提供；不扫描仓库，也不让每台设备调用 GitHub API。
- 冷启动后后台检查更新，自动检查一小时内不重复；另提供始终可用的“检查更新”入口和当前版本信息，不依赖网页登录。
- 本次“自动更新”指自动发现新版本并完成用户确认后的下载与校验；安装保留 Android 系统确认。无人值守静默安装不纳入本方案。
- 不自动卸载旧版、不清除数据。沿用忽略的 `android/.keystore/development.keystore`；证书 SHA256 `a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97`。不提交、展示或交付私钥。
- 不修改 Movie、D1、用户配置表、Store 登录、GitHub Token 或资源绑定；静态索引不要求登录。
- 用户实际下载样例：`/store/api/github/asset?account=shovelshit&repo=shovelshit%2Fmy-knowledge-base&id=690802170`。上游附件是 `release-manifest.json`，1573 字节，SHA256 `2910adeff09687cac1f0984e5bbdc5db446977e0374c61139bb764e46e6f5b82`，不能硬编码为 APK。
- 网页无法知道系统已建立任务时，只提示“已请求下载，请查看下载列表”，不能谎报下载成功。原生提示以实际任务状态为准，完成广播也核对成功状态。
- 产品变更由 developer 实施，依次进行独立测试、独立验收。页面/下载测试必须验证真实字节，不以本地伪造完成记录代替网络下载成功。
- 继续现有已隔离的 codex/store-usability 分支与 checkout；此前 worktree 创建曾阻塞，保留用户的已知工作区选择，不重复询问。不得操作用户 emulator-5554。

### Task 1: Compact ADB navigation and permission workflow

**Files:** Modify `android/app/src/main/java/io/github/shovelshit/apkstore/AdbActivity.java`; create a small navigation/presentation Java helper under the same package if needed; extend `android/test/adb.test.cjs` and add its JVM behavior tests. Do not change MainActivity, Manifest, downloads, transport or version in this task.

**Interfaces:** Consume existing AdbController generation guards and AdbOperations results unchanged. Preserve public `AdbActivity.EXTRA_DOWNLOAD_ID`; a positive extra opens the INSTALL tab and selects that DownloadManager file. Other tasks do not depend on new internal UI helpers.

- [ ] Step 1: Write failing navigation behavior tests before UI implementation. Test detail Back returns to the apps list preserving search/position, root Back exits, busy root Back requests confirmation, tab switches keep selected file and active connection. Example boundary:
```java
navigation.showApplications("fixture", 7);
navigation.openPermissions("io.github.fixture");
assertEquals(RETURN_TO_LIST, navigation.back(false));
assertEquals("fixture", navigation.query());
assertEquals(7, navigation.listPosition());
assertEquals(CONFIRM_EXIT, navigation.back(true));
```
  Use a small real production navigation state object consumed by Activity, not source-text assertions or a mock-only UI. Choose its names locally and document them in the report.
- [ ] Step 2: Run `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home node --test android/test/adb.test.cjs`; observe the missing navigation behavior fail.
- [ ] Step 3: Implement fixed Back/title/target chrome with a weighted content frame. Three native tabs: device connection controls; apps search/filter/recycled list with a separate detail pane; install file card/actions/progress. Render short device identity and put full id/groups/error in Details. Chinese permission names and states, declared relevant permissions first, explicit disabled reasons. Confirm mutations and busy exit; keep original controller closure/cancellation rules. Hide missing versionName rather than rendering null. Example Activity integration:
```java
headerBack.setOnClickListener(v -> handleBack());
@Override public void onBackPressed() { handleBack(); }
// tab changes only select pane visibility; they never reconnect or clear source.
```
- [ ] Step 4: Run Android JVM and Store page suites; SDK compile with current build script/development key for UI readiness, preserving 1.3.0 until Task4. Check actual fixed header/tab structure and no giant raw shell text in the default pane. Report compile evidence and navigation test behavior.
- [ ] Step 5: Commit only owned native/UI/test files, then task review before continuing.

### Task 2: Download fidelity, handoff and real status

**Files:** Modify `pages/store/index.html`, `android/app/src/main/java/io/github/shovelshit/apkstore/MainActivity.java`, `DownloadsActivity.java`, `DownloadStore.java`; add focused download policy/behavior helper/tests under `android/test/` and `pages/store/` as needed. No updater, version or ADB edits.

**Interfaces:** Preserve `DownloadStore.add(Context,long,String)` for existing callers (overload if storing MIME/source). DownloadManager URI remains the handoff to ordinary install/ADB install, but only actual APK downloads offer installation. All legacy records remain readable.

- [ ] Step 1: Record authenticated actual-asset status, Content-Type, Content-Disposition, filename, size and hash without printing credentials or private contents. Reproduce a same-origin no-extension URL serving UTF-8 filename* JSON and a real APK serving APK MIME. Baseline URLUtil currently receives hardcoded APK MIME; write failing tests for correct JSON filename/type, APK actions only for APK, pending vs failure vs success, and success broadcast only after successful status.
```java
assertEquals("release-manifest.json", policy.fileName(assetUrl,
    "attachment; filename*=UTF-8''release-manifest.json", "application/json"));
assertFalse(policy.isInstallable("release-manifest.json", "application/json"));
assertFalse(policy.completedSuccessfully(DownloadManager.STATUS_FAILED));
```
  Test the actual pure policy boundary consumed by UI; add behavioral JS tests for the actual download trigger/request feedback without replacing that function with a success mock.
- [ ] Step 2: Run focused tests and observe current misclassification/unconditional completion fail. Keep exact user sample as a regression fixture.
- [ ] Step 3: Carry WebView MIME and disposition through permission requests into enqueue, decode safe filename* with fallback, sanitize path/control characters, avoid duplicate active tasks and existing-file clobber. Preserve real non-APK extensions, title/type and same-origin-only Store Cookie behavior. Request acknowledgment is queue acknowledgment, not transfer success. Show DownloadManager paused/failed reason and actual completion state; never force HTML/JSON errors into an APK install action. For GitHub links, provide source/delivery metadata and honest request text, preserving standard-browser and private same-origin navigation. If a particular network/auth/redirect failure is proven, fix that boundary and add its regression; do not change unrelated auth or add an arbitrary native bridge.
- [ ] Step 4: Run page/JVM tests and actual modern WebView+DownloadManager with a validated isolated network: JSON private-style attachment and APK bytes/hash. Recheck authenticated user endpoint when available. No claims based solely on Toast, enqueue ID or seeded completed rows.
- [ ] Step 5: Commit owned files and review. Document remaining user-device limitations explicitly if the supplied endpoint succeeds under the verified authenticated route.

### Task 3: Native update discovery, verified download and installer

**Files:** Create focused update classes in `android/app/src/main/java/io/github/shovelshit/apkstore/` (manifest/policy, application-scoped coordinator, Activity, read-only APK provider); modify `MainActivity.java` for entry and startup check and `AndroidManifest.xml` for non-exported Activity/provider. Add JVM policy/network tests under `android/test/`. No version bump or static index yet.

**Interfaces:** Public update entry `UpdateActivity` with `new Intent(context, UpdateActivity.class)`; coordinator obtained with application context via `UpdateCoordinator.get(context)`, `check(boolean force)`, `download()`, immutable observable state and listener subscription/removal. Internal names may vary, but preserve a single shared operation across Activities. UpdateManifest JSON schema1 fields: packageName, versionCode, versionName, minSdk, channel, url, size, sha256, certificateSha256, notes. Current channel `development`.

- [ ] Step 1: Test pure manifest/policy from controlled map values and local HTTP transport, independent of Android JSON stubs. Assert version6 newer than5 AVAILABLE, equal/older no install, invalid/missing fields error, SDK floor, channel/certificate mismatch rejection, one-hour automatic throttle/manual bypass, duplicate operations coalesce, hash/size mismatch rejection. Example:
```java
assertEquals(AVAILABLE, policy.decide(release6, installed5));
assertEquals(CURRENT, policy.decide(release6, installed6));
assertThrows(() -> policy.decide(otherCertificateRelease, installed5));
```
- [ ] Step 2: Run focused tests and observe absent/incorrect behavior fail. Keep JSON library conversion at the Android adapter boundary; policy remains executable in host JVM tests.
- [ ] Step 3: Implement bounded HTTPS index fetch off main thread (15s, <=64KiB), explicit schema/type/URL validation, HTTPS-only redirects with bounded hops and no cookies/Authorization. Startup automatically checks with one-hour throttle; manual bypass; errors never become CURRENT. Visible update entry includes installed version and status. Show version/notes before user-initiated download.
- [ ] Step 4: Implement application-scoped background download with progress, duplicate-job prevention, bounded size (<=1GiB) and private `.part` output. Verify expected size/SHA256, Android archive packageName/versionCode/versionName/minSdk and exact installed signing identity before atomic rename to a completed immutable hash-named APK. Persist completed metadata for later recheck; recover interrupted process state as retryable failure, not eternal downloading. Do not rely on DownloadManager to bypass its validated-network constraint for self-update.
```java
// PackageManager verification occurs after complete transfer, before READY/install.
if (!expected.packageName.equals(archive.packageName)) throw new IOException("安装包不属于本应用");
if (archiveVersion != expected.versionCode) throw new IOException("安装包版本不匹配");
// compare certificate SHA-256 to both index and currently installed certificate.
```
- [ ] Step 5: Add a non-exported, URI-grant-enabled provider exposing only completed hash-named APKs under the update directory, read-only with query name/size/MIME. No arbitrary path traversal or partial-file exposure. UpdateActivity asks system unknown-source permission as needed, revalidates ready bytes and opens ACTION_VIEW with APK MIME and read URI grant. Going back removes listeners but preserves app-scoped job/result; process restart can display saved ready file and reverify before install. Main startup/update UI must not retain destroyed Activity references.
- [ ] Step 6: Run JVM/page suites and real SDK compilation; exercise controlled update metadata/error/corrupt file cases and installer URI permissions on Android9. Old1.3 lacks updater so first rollout is a manual APK install; use a test build/version fixture to exercise upgrade availability, never spoof a production update result.
- [ ] Step 7: Commit owned files and review policy, lifecycle and provider boundary before Task4.

### Task 4: Versioned build and safe release-index publication

**Files:** Modify `android/app/src/main/AndroidManifest.xml`, `MainActivity.java` UA, `android/scripts/build-apk.mjs`, `android/README.md`; create `android/scripts/prepare-update.mjs` plus tests, and eventually `pages/store/android/update.json` after release assets are verified. Final static file is publication data, not a fixture with nonexistent URLs.

**Interfaces:** Build output `android/dist/apk-store-1.4.0.apk` and `.apk.sha256`; public index schema matches Task3. Release tag `android-v1.4.0-dev`. Installed cert must remain `a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97`.

- [ ] Step 1: Test generator against temporary SDK/manifest/metadata inputs and a controlled local release server. A mismatched downloaded digest, missing asset, non-HTTPS production URL, version mismatch or wrong signer must refuse index publication and leave existing index untouched. A verified file yields literal schema fields matching the actual APK.
- [ ] Step 2: Implement generator reading actual APK metadata via SDK aapt2/apksigner and actual digest/size, then fetching selected release asset to independently compare bytes before atomic index output. CLI input includes APK path, version source Manifest, HTTPS release URL, channel and notes file. Tests may inject a controlled transport/fixture adapter; production must reject HTTP. Avoid embedding signing passwords/tokens in files/logs.
- [ ] Step 3: Set manifest1.4.0/code6 and UA1.4.0, build using existing explicit development key, verify signature/package/version/hash. Update docs with automatic check semantics, initial manual install, updater/network errors and release-index ordering. Run relevant generator/build/JVM/page suites.
- [ ] Step 4: After per-task and whole-branch review, independent Android tests and actual acceptance, push Store source non-force and create prerelease containing APK/SHA. Independently download and verify assets; generate public index only now, commit index in Store, update parent gitlink and push existing master publication path. No D1 migration or schedule pause.
- [ ] Step 5: Verify public index HTTP200 JSON without login, metadata equals remotely downloaded APK, checksum/signature valid, authenticated supplied private attachment still downloads, Cloudflare checks and live pages normal. Save APK/SHA/reports/screenshots outside repo; keep private key ignored. Clean only this plan's scratch safely after completion.
