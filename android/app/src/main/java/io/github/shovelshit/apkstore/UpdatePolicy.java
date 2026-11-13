package io.github.shovelshit.apkstore;

import java.util.Map;

final class UpdatePolicy {
    static final long MAX_APK = 1L << 30;
    static final String CHANNEL = "development";
    enum Decision { AVAILABLE, CURRENT, INCOMPATIBLE }

    static final class Release {
        final String packageName, versionName, channel, url, sha256, certificateSha256, notes;
        final int versionCode, minSdk;
        final long size;
        Release(String packageName, int versionCode, String versionName, int minSdk, String channel,
                String url, long size, String sha256, String certificateSha256, String notes) {
            this.packageName = packageName; this.versionCode = versionCode; this.versionName = versionName;
            this.minSdk = minSdk; this.channel = channel; this.url = url; this.size = size;
            this.sha256 = sha256; this.certificateSha256 = certificateSha256; this.notes = notes;
        }
    }
    static final class Installed {
        final String packageName, certificateSha256;
        final int versionCode, sdk;
        Installed(String packageName, int versionCode, int sdk, String certificateSha256) {
            this.packageName = packageName; this.versionCode = versionCode; this.sdk = sdk;
            this.certificateSha256 = certificateSha256;
        }
    }
    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String) || ((String)value).trim().isEmpty()) throw new IllegalArgumentException("无效的更新字段: " + key);
        return (String)value;
    }
    private static int number(Map<String,Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Integer) || (int)value <= 0) throw new IllegalArgumentException("无效的更新字段: " + key);
        return (int)value;
    }
    static Release parse(Map<String,Object> map) {
        if (map == null || !Integer.valueOf(1).equals(map.get("schema"))) throw new IllegalArgumentException("不支持的更新索引版本");
        String pkg = str(map,"packageName"), version = str(map,"versionName"), channel = str(map,"channel");
        String url = str(map,"url"), hash = str(map,"sha256"), cert = str(map,"certificateSha256");
        Object bytes = map.get("size");
        if (!(bytes instanceof Integer || bytes instanceof Long) || ((Number)bytes).longValue() <= 0
                || ((Number)bytes).longValue() > MAX_APK) throw new IllegalArgumentException("无效的安装包大小");
        try {
            java.net.URI parsed = new java.net.URI(url);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null || parsed.getFragment() != null || parsed.getPort() > 65535)
                throw new IllegalArgumentException("安装包必须使用 HTTPS");
        } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("安装包地址无效",e); }
        if (!hash.matches("[a-fA-F0-9]{64}") || !cert.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("无效的校验值");
        if (!CHANNEL.equals(channel)) throw new IllegalArgumentException("发布渠道不匹配");
        return new Release(pkg, number(map,"versionCode"), version, number(map,"minSdk"), channel,
                url, ((Number)bytes).longValue(), hash.toLowerCase(java.util.Locale.ROOT),
                cert.toLowerCase(java.util.Locale.ROOT), str(map,"notes"));
    }
    static Decision decide(Release release, Installed installed) {
        if (!release.packageName.equals(installed.packageName)) throw new IllegalArgumentException("应用包名不匹配");
        if (!release.certificateSha256.equalsIgnoreCase(installed.certificateSha256)) throw new IllegalArgumentException("签名证书不匹配");
        if (release.versionCode <= installed.versionCode) return Decision.CURRENT;
        return release.minSdk <= installed.sdk ? Decision.AVAILABLE : Decision.INCOMPATIBLE;
    }
    static boolean shouldCheck(long now, long last, boolean force) {
        return force || last <= 0 || now < last || now - last >= 3_600_000L;
    }
}
