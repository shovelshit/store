package io.github.shovelshit.apkstore;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies provider content into private cache so the install confirmation has stable bytes and length. */
public final class ApkInstallSource implements AutoCloseable {
    private static final long MAX_BYTES = 1024L * 1024L * 1024L;
    public final String name;
    public final String packageName;
    public final String versionName;
    public final long length;
    private final File file;

    private ApkInstallSource(File file, String name, String packageName, String versionName) {
        this.file = file; this.name = name; this.packageName = packageName; this.versionName = versionName;
        this.length = file.length();
    }
    public static ApkInstallSource fromUri(Context context, Uri uri) throws IOException {
        String label = "已选择 APK";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.trim().isEmpty()) label = value;
            }
        } catch (RuntimeException ignored) { /* Provider metadata is optional. */ }
        File file = File.createTempFile("adb-install-", ".apk", context.getCacheDir());
        boolean complete = false;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new IOException("无法打开安装包");
            byte[] buffer = new byte[65536];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_BYTES) throw new IOException("安装包超过 1 GB 上限");
                output.write(buffer, 0, count);
            }
            if (size == 0) throw new IOException("安装包为空");
            complete = true;
        } finally { if (!complete) file.delete(); }
        PackageInfo info;
        try { info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0); }
        catch (RuntimeException ignored) { info = null; }
        return new ApkInstallSource(file, label, info == null ? null : info.packageName,
                info == null ? null : info.versionName);
    }
    public InputStream open() throws IOException { return new FileInputStream(file); }
    @Override public void close() { file.delete(); }
}
