package io.github.shovelshit.apkstore;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

final class UpdateArchive {
    static String certificate(Signature signature) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
        StringBuilder result = new StringBuilder();
        for (byte b : hash) result.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return result.toString();
    }
    static UpdatePolicy.Installed installed(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNATURES);
        if (info.signatures == null || info.signatures.length != 1) throw new IOException("无法读取当前应用签名");
        return new UpdatePolicy.Installed(info.packageName, info.versionCode, Build.VERSION.SDK_INT, certificate(info.signatures[0]));
    }
    static void verify(Context context, File file, UpdatePolicy.Release release) throws Exception {
        if (!file.isFile() || file.length() != release.size) throw new IOException("安装包大小不匹配");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer,0,n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        if (!hash.toString().equals(release.sha256)) throw new IOException("安装包 SHA-256 校验失败");
        UpdatePolicy.Installed current = installed(context);
        if (UpdatePolicy.decide(release,current) != UpdatePolicy.Decision.AVAILABLE) throw new IOException("更新版本或签名不匹配");
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);
        if (archive == null || archive.signatures == null || archive.signatures.length != 1
                || archive.applicationInfo == null) throw new IOException("无法读取安装包信息");
        if (!release.packageName.equals(archive.packageName) || archive.versionCode != release.versionCode
                || !release.versionName.equals(archive.versionName) || archive.applicationInfo.minSdkVersion != release.minSdk)
            throw new IOException("安装包版本、包名或系统要求不匹配");
        String cert = certificate(archive.signatures[0]);
        if (!cert.equals(release.certificateSha256) || !cert.equals(current.certificateSha256))
            throw new IOException("安装包签名不匹配");
    }
}
