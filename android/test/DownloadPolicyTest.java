package io.github.shovelshit.apkstore;

public final class DownloadPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(String expected, String actual) {
        check(expected.equals(actual), "expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        String asset = "https://store.ltools.asia/store/api/github/asset?account=shovelshit&repo=shovelshit%2Fmy-knowledge-base&id=690802170";
        equal("release-manifest.json", DownloadPolicy.fileName(asset,
                "attachment; filename*=UTF-8''release-manifest.json", "application/json"));
        equal("发布清单.json", DownloadPolicy.fileName(asset,
                "attachment; filename*=UTF-8''%E5%8F%91%E5%B8%83%E6%B8%85%E5%8D%95.json", "application/json"));
        equal("safe.json", DownloadPolicy.fileName(asset,
                "attachment; filename*=UTF-8''..%2F..%2Fsafe.json", "application/json"));
        equal("app.apk", DownloadPolicy.fileName("https://example.com/app.apk", null,
                "application/vnd.android.package-archive"));
        check(!DownloadPolicy.isInstallable("release-manifest.json", "application/json"), "JSON cannot install");
        check(!DownloadPolicy.isInstallable("app.apk", "application/json"), "JSON body cannot install");
        check(DownloadPolicy.isInstallable("app.apk", "application/octet-stream"), "APK filename can install");
        check(!DownloadPolicy.completedSuccessfully(16), "failed broadcast cannot signal success");
        check(DownloadPolicy.completedSuccessfully(8), "successful broadcast can signal success");
        equal("等待中", DownloadPolicy.statusText(1, 0));
        equal("等待网络连接", DownloadPolicy.statusText(4, 2));
        equal("HTTP 403", DownloadPolicy.statusText(16, 403));
        equal("文件已存在", DownloadPolicy.statusText(16, 1009));
        equal("无法恢复下载", DownloadPolicy.statusText(16, 1008));
        String first = DownloadPolicy.destination("release-manifest.json");
        String second = DownloadPolicy.destination("release-manifest.json");
        check(first.matches("ApkStore/[0-9a-f-]{36}/release-manifest\\.json"), "destination preserves name in private task directory");
        check(!first.equals(second), "each task gets a new destination");
        equal("下载中", DownloadPolicy.statusText(2, 0));
    }
}
