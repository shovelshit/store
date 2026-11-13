package io.github.shovelshit.apkstore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public final class DownloadVerificationTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        final int[] parses = {0};
        DownloadVerification.ArchiveInspector inspector = stream -> { parses[0]++; return true; };
        check(!DownloadVerification.isApk(new ByteArrayInputStream("<html>error</html>".getBytes("UTF-8")), inspector),
                "HTML under APK name is not an APK");
        check(parses[0] == 0, "HTML must not reach archive parser");
        byte[] plausibleArchive = { 'P', 'K', 3, 4, 1, 2 };
        check(DownloadVerification.isApk(new ByteArrayInputStream(plausibleArchive), inspector),
                "valid archive parser outcome permits APK");
        check(parses[0] == 1, "normal APK is delegated to the actual archive parser");
        check(!DownloadVerification.isApk(new ByteArrayInputStream(plausibleArchive), stream -> false),
                "archive parser rejects corrupt ZIP despite magic header");
        check(DownloadVerification.isApk(new ByteArrayInputStream(plausibleArchive) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        }, inspector), "short reads must not reject valid archive headers");
    }
}
