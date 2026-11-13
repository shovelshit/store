# Bundled Android update transport dependencies

The APK includes OkHttp 4.12.0 (Square), Okio JVM 3.6.0 (Square), and Kotlin Standard Library/JDK7/JDK8 1.9.10 (JetBrains). Each is licensed under Apache License 2.0. The license text is in `third_party/Apache-2.0.txt`. Pinned Maven Central artifact URLs and SHA-256 values are recorded in `dependencies.lock.json`; the build verifies every downloaded or cached JAR before compiling or packaging it. No AndroidX runtime is introduced.
