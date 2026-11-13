# Store Batch Grant and Unified UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为指定应用提供可核验的一键授权，统一原生UI，将三个底部悬浮入口移到顶部，交付同签名1.4.1。

**Architecture:** 沿用原生Java、后台串行ADB和现有发布链路。批量授权执行与页面分离，复用权限声明检查和状态回读；共享小型原生样式组件，不引入UI框架。主页面改为垂直正常布局，顶部工具栏/任务摘要占据自身空间，WebView填充剩余区域。

**Tech Stack:** Java8-compatible Android API24+、Android9运行验证、JDK17、现有无Gradle SDK构建、Node test/JVM行为测试、现有静态更新索引。

## Global Constraints

- 已批准spec：`docs/superpowers/specs/2026-11-13-store-batch-grant-unified-ui-design.md`。
- 包名`io.github.shovelshit.apkstore`，minSdk24/targetSdk34，Task3前保留1.4.0/code6，Task3升级1.4.1/code7。
- 只处理指定设备user0和选定应用已声明、现有支持、实际可授予的权限；不新增任意shell/WebView高权限桥/root获取/批量卸载。
- 已授权跳过；一次确认可取消勾选；逐项实际回读；单项拒绝继续，断连/取消停止后续命令；不虚报成功或回滚。
- 不修改后端、账号、Token、D1、Movie、调度、签名身份和既有下载/更新校验边界。
- 共享主色#2563eb、背景#f4f6fb、白色面板、正文#0f172a、次要文字#64748b，尺寸使用dp/sp，保持足够触控面积。
- 顶部工具栏与下载摘要采用正常布局，不覆盖WebView，不保留必要的底部悬浮入口；处理报告的WindowInsets，不宣称能探测所有厂商Dock。
- 源码编辑用apply_patch；忽略的签名密钥/依赖/产物/审查记录不得提交；保留其他人的改动。
- SDK`/Users/lijinfeng/Library/Android/sdk`，JDK17`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`；不使用默认JDK21构建。
- 实现任务串行并经过任务审查；完成后全分支审查（最多一次最终修复波+一次范围复审）、独立tester、actual acceptance、发布和部署验证。
- 用户设备emulator5554/5556不在测试范围。自有AVD可测试，但不得wipe/delete其他AVD；测试设置修改后恢复。

## File Structure

- `adb/AdbBatchGrant.java`：平台无关的批量执行、进度和结果，包名/权限校验、串行处理、取消和失败分类。
- `adb/AdbOperations.java`：保留现有权限行为，必要时增加结构化命令失败类型，以区分设备策略拒绝和连接中断。
- `AdbBatchGrantUi.java`：权限选择确认、后台任务UI状态与结果展示；避免将AdbActivity显著扩成单体。
- `AdbActivity.java`：入口、目标/操作生命周期、复用现有串行控制器、后续统一样式。
- `NativeUi.java`：四个Activity共享的视觉/标题栏/控件与安全区域处理，避免巨型基础Activity。
- `res/values/styles.xml`及Manifest主题引用：若原生对话框/勾选框需要共享accent，使用兼容API24的轻量应用主题；Task2只改主题，不改版本。
- `MainActivity.java`：顶部工具栏、下载摘要、WebView布局；`DownloadsActivity.java`/`UpdateActivity.java`接入共享UI。
- `android/test/AdbBatchGrantTest.java`、现有JVM入口：批量语义回归；测试辅助只放test目录。
- Manifest/Main UserAgent/README：Task3版本及说明；索引仅在真实远端APK验证后生成。

### Task 1: 可核验的一键授权与应用权限页入口

**Files:** Create `android/app/src/main/java/io/github/shovelshit/apkstore/adb/AdbBatchGrant.java`, `android/app/src/main/java/io/github/shovelshit/apkstore/AdbBatchGrantUi.java`, `android/test/AdbBatchGrantTest.java`; Modify `AdbActivity.java`, `adb/AdbOperations.java`, `android/test/adb.test.cjs`, corresponding existing JVM tests only when contracts require it.

**Interfaces:** Consumes current `AdbOperations.readPermissions(String)` / `setPermission(String,String,boolean)`, `AdbController.submitWithProgress`, existing generation isolation. Produces `AdbBatchGrant.run(AdbOperations operations, String packageName, List<String> selected, BooleanSupplier cancelled, Consumer<Progress> progress): Result`; immutable Progress/Result/entry outcomes, with selected permission identity, actual confirmed outcomes, reasons and final snapshot when readable. UI helper owns only batch dialogs/rendering and cancellation; activity supplies current connection/selection/submit gates. Narrow equivalent signatures may be chosen if recorded explicitly in the report for subsequent tasks.

- [ ] Step1: Add JVM behavior tests before implementation; use real AdbOperations and fake external CommandRunner with stateful realistic dumpsys/appops responses. Example observable expectations:

```java
// fixture: CAMERA denied, FINE_LOCATION granted, OVERLAY denied, INTERNET unsupported
// select CAMERA+FINE_LOCATION+OVERLAY+INTERNET; real engine runs against fixture shell
eq(result.grantedCount(), 2);
eq(result.skippedCount(), 2);
// assert emitted commands target user0, no INTERNET grant, no FINE_LOCATION mutation;
// mutate fixture only for commands actually accepted, then verify final readback states.
```

Also cover nonzero pm/appops denial followed by a succeeding item, zero-exit with unchanged state reported failure, transportIOException stops pending items, cancellation between items stops subsequent commands, stale preview re-read, duplicates/invalid/undeclared permissions rejected or explained without unsafe commands, and retry skips actual granted items. Do not assert constants or source text.

- [ ] Step2: Run `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH node --test android/test/adb.test.cjs`; record expected RED caused by missing batch behavior, not syntax/setup errors.
- [ ] Step3: Implement engine with fresh permission read before execution, whitelist/declaration checks, deduplicated stable ordering, cancellation checks before new commands, final actual states, progress copied across threads. Distinguish a command-local permission refusal from transport failure; do not continue through dead connection. Preserve already-confirmed results if later reads fail. Do not fake transactional rollback.
- [ ] Step4: Add “一键授权” to permission details, count/target/package, one scrollable checkbox confirmation with Chinese names and explicit special permissions; support zero candidates, user unchecking all, progress/current item, stop action, outcome summary and retry. Lock application/target and mutations during batch; callbacks from old generation/destroyed Activity must not repaint another target. On cancel/exit/disconnect, show last confirmed progress and unconfirmed/remaining work honestly. Preserve existing per-item grant/revoke and app-list return/search behavior.
- [ ] Step5: Focused GREEN then full Store test suite once; compile/sign SDK APK to catch native wiring. Run `node android/scripts/build-apk.mjs --sdk /Users/lijinfeng/Library/Android/sdk --dev-signing` with JDK17; retain existing compiler diagnostics, never print key data. Self-review and commit only owned source/tests. Report commands/output/RED-GREEN and exact engine/UI interfaces.

### Task 2: 统一原生UI与顶部工具栏、安全区域

**Files:** Create `android/app/src/main/java/io/github/shovelshit/apkstore/NativeUi.java`; Modify `MainActivity.java`, `AdbActivity.java`, `AdbBatchGrantUi.java`, `DownloadsActivity.java`, `UpdateActivity.java`; optionally create `android/app/src/main/res/values/styles.xml` and change Manifest theme reference only to unify native dialog/checkbox accent (version remains1.4.0/code6). Focused test files only for new behavioral logic, or test-only native instrumentation under `android/test/device/`.

**Interfaces:** Consumes existing Activities, batch helper from Task1. Produces shared dp/card/text/button/header/insets helpers used by all four Activity screens. `NativeUi` is styling/layout only, not a navigation/auth/controller abstraction. Preserve each Activity's lifecycle/task state contracts.

- [ ] Step1: Read Task1 interface report and current code; reproduce/document the old three-bottom-entry and bottom-overlay layout. For new layout/inset decision logic write an observable failing regression first, preferably native instrumentation measuring real view bounds, or a JVM test only if a genuine platform-independent policy is needed. Pure color/spacing changes need visual checks, not source-grep/mirror tests or fake framework recreation.
- [ ] Step2: Implement coherent blue/white shared header, 48dp target where practical, typography, density-scaled spacing, card/input/buttons and selected-tab states. Preserve destructive action distinction. Update batch dialogs/result view to match. ADB no longer indicates selected tabs merely as disabled gray controls.

```java
// Main structure: every child owns layout space, no BOTTOM|END entry overlays.
LinearLayout root = new LinearLayout(this);
root.setOrientation(LinearLayout.VERTICAL);
root.addView(toolbar);       // brand/title + 下载 / ADB / 更新
root.addView(downloadBar);   // GONE unless task summary exists
root.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));
// webContainer contains WebView and only its page-load progress at TOP.
```

- [ ] Step3: Make entry labels readable/reachable at narrow phone width and short car landscape; title can shrink/hide but all three actions remain visible. Insets handling must be idempotent relative to initial padding, compatible API24, respond to changed insets/window size, and avoid double-applying decor-consumed system space. Scrollable screens retain bottom content breathing room; do not fabricate unreported Dock heights. Preserve web navigation, cookie/download bridge, task summary updates and cold-start update behavior.
- [ ] Step4: Use native UI geometry/screenshots on own Android9 AVD to verify main top actions do not overlap WebView/summary, secondary headers consistent, ADB selected tabs/actions/confirmation usable in landscape and narrow dimensions. Observe reported inset change twice to catch accumulating padding. Simulate bottom Dock occlusion in evidence explicitly labeled as simulation, not user-car proof. Tests belong in test helpers, not shipped production probe methods. Only own AVD if needed, e.g. `store_ui141_dev` on serial5586; do not use5554/5556 or another tester's device.
- [ ] Step5: Run full Store tests once, build/sign JDK17 APK, self-review, commit only scoped changes, report interfaces/visual evidence and limits. Do not bump version or publish here.

### Task 3: 1.4.1构建与发布准备

**Files:** Modify `android/app/src/main/AndroidManifest.xml`, `MainActivity.java` user-agent, `android/README.md`; update any literal version-specific test fixture only if it describes current manifest. Generated APK/SHA remain ignored and copied to external artifact folder; do not change index yet.

**Interfaces:** Consumes Task1+2 reviewed source and existing `build-apk.mjs`/`prepare-update.mjs`; produces same-signer1.4.1/code7 APK+SHA and documented source SHA, metadata/certificate/size/digest. Keeps live1.4 index until publication verification.

- [ ] Step1: Change Manifest to1.4.1/code7 and User-Agent to1.4.1. README describes top toolbar, per-app batch grant scope/partial failures/cancellation, system install confirmation, fixed index and current release commands. No further functional changes.
- [ ] Step2: Run full Store tests once, build with JDK17+existing `--dev-signing`, inspect actual output using SDK aapt2/apksigner and shasum. Independently compare certificate to public1.4.0 cert `a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97`. If mismatched, stop; never silently switch keys or uninstall old app.
- [ ] Step3: Commit source/docs only, report exact final binary hash/size/cert/package/code/min/target/sourceSHA. Source/tests ready is not release acceptance. Parent handles copy to external artifact directory, whole-branch review and independent test/acceptance gates.

## Controller Delivery Sequence

- [ ] After tasks and per-task reviews, full-branch review of Store base`b708ed4871ed12321c98b9fad472206afce3adf2`..final source. At most one final fix wave and scoped rereview; rule on residuals explicitly.
- [ ] Personal tester independently exercises final APK on own Android9 AVD: real batch grant/readback, partial refusal, UI retention and toolbar/Dock simulation, actual covering install/data preservation, download/ADB install/updater regression as appropriate. Parent inspects evidence, not only agent success summary.
- [ ] Personal acceptance evaluates requirement/evidence and actual final binary, scoped limits; must pass before publication.
- [ ] Publish exact reviewed Store source through existing authorized non-force master flow; upload1.4.1 APK+SHA prerelease only after checks. Prefer normal git, bounded check then exact-SHA GitData CAS fallback if existing TCP issue recurs. Keep release tag/source identity accurate.
- [ ] Independently fetch remote APK+SHA and verify bytes/signature. Generate index using production prepare-update CLI from verified HTTPS APK; commit generated index, update parent gitlink, deploy through existing automatic pipeline. No DB/config/cron changes.
- [ ] Verify public index200/field/body equality, remote APK metadata, CF deployment and existing Store/Movie/auth gates. Independent Android9 actual old1.4 native updater→public1.4.1 download/system install/CURRENT, retain data. Final actual acceptance of publication/runtime evidence.
- [ ] Archive this plan's ignored review reports externally, recoverably remove only its scratch workspace, preserve signing key/user AVDs/source branches, then concise Chinese completion with APK link and explicit testing scope.

## Self-review

All approved requirements map to Task1(batch), Task2(UI/toolbar/insets), Task3(version/build), or controller delivery gates. No new server/database work or UI framework. Implementation runs in existing `codex/store-usability` branch with clean baseline except this plan/spec; this is a continuation of the authorized development workflow, not implementation on master. No new architecture-agent gate is introduced.
