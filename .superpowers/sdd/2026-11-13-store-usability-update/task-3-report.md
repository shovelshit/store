# Task 3: Native update implementation

## Scope

- Added strict schema-1 policy (`development` channel), one-hour startup check with manual bypass, immutable observable coordinator state, shared background jobs, HTTPS-only bounded index transport and bounded APK transfer.
- Added private `.part` transfer, size/SHA-256 and Android PackageManager archive verification (package, code/name, minSdk, exact installed/index signing certificate), atomic completed hash-named APK, persisted ready metadata and interrupted-job recovery.
- Added an unexported read-only completed-APK provider, unknown-source permission request, system install confirmation, and a MainActivity update entry. Manifest stays versionCode 5 / 1.3.0. Existing DownloadManager and ADB paths unchanged.

## Red / green

- RED: `javac -d /tmp/store-update-red android/test/UpdatePolicyTest.java android/test/UpdateTransportTest.java` failed with 27 missing `UpdatePolicy`/`UpdateTransport` references. Later `javac -d /tmp/store-update-red android/app/src/main/java/io/github/shovelshit/apkstore/UpdatePolicy.java android/test/UpdatePolicyTest.java` failed at missing `Decision.INCOMPATIBLE`; both were observed before implementation.
- GREEN: `node --test android/test/*.test.cjs` passed 8/8; `node --test pages/store/*.test.cjs` passed 30/30. Host tests cover version/SDK/channel/certificate/schema/type/URL policy, throttling, duplicate gate, actual loopback HTTP response/redirect/size/hash/status boundaries.
- SDK: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH node scripts/build-apk.mjs --sdk /Users/lijinfeng/Library/Android/sdk --dev-signing` passed with build-tools 33.0.1, D8, zipalign and apksigner; signer SHA-256 `a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97`. Earlier default JDK 21 build reached javac but old D8 threw an internal NPE in pre-existing `AdbActivity$1`; JDK 17 resolves it.
- `git diff --check` passed. A fresh final run follows this report edit.

## Controlled Android test seam / limitations

`UpdateCoordinator(Context, IndexSource)` is package-private and lets same-package instrumentation supply controlled JSON bytes; production `get(context)` always uses fixed `https://store.ltools.asia/android/update.json`. `UpdateTransport`'s package-private `localTest=true` permits loopback HTTP in host JVM tests only; production paths call `false`. There is no exported configuration or transport bridge.

Android 9 UI, persisted-ready revalidation, PackageManager archive checks, unknown-source permission, provider read grants/path rejection, and system installer confirmation require independent device verification. No production update index exists yet; version 5 cannot discover a real version 6 until the separate publication task. The supplied same-signer version-6 test APK is a controlled fixture, not a production release. Do not claim the JVM suite demonstrates Android UI behavior.
