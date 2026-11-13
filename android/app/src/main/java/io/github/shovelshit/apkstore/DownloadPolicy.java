package io.github.shovelshit.apkstore;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

final class DownloadPolicy {
    private static final Pattern EXTENDED = Pattern.compile("(?:^|;)\\s*filename\\*\\s*=\\s*(?:\"?UTF-8''([^;\"]+)\"?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN = Pattern.compile("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", Pattern.CASE_INSENSITIVE);
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private DownloadPolicy() {}

    static String fileName(String url, String disposition, String mime) {
        String candidate = null;
        if (disposition != null) {
            Matcher encoded = EXTENDED.matcher(disposition);
            if (encoded.find()) {
                try { candidate = URLDecoder.decode(encoded.group(1).replace("+", "%2B"), StandardCharsets.UTF_8.name()); }
                catch (Exception ignored) { /* fall back to plain filename */ }
            }
            if (candidate == null) {
                Matcher plain = PLAIN.matcher(disposition);
                if (plain.find()) candidate = plain.group(1) != null ? plain.group(1) : plain.group(2);
            }
        }
        if (candidate == null || candidate.isEmpty()) {
            try {
                String path = new URI(url).getRawPath();
                if (path != null && !path.endsWith("/")) {
                    candidate = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"), StandardCharsets.UTF_8.name());
                }
            } catch (Exception ignored) { /* use MIME fallback */ }
        }
        candidate = sanitize(candidate);
        if (candidate.isEmpty()) candidate = "download";
        if (!candidate.contains(".")) {
            String type = normalizedMime(mime);
            if (type.equals("application/json")) candidate += ".json";
            else if (type.equals(APK_MIME)) candidate += ".apk";
        }
        return candidate;
    }

    private static String sanitize(String input) {
        if (input == null) return "";
        String name = input.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
        name = name.replaceAll("^[. ]+|[. ]+$", "");
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }

    static String normalizedMime(String mime) {
        return mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static boolean isInstallable(String name, String mime) {
        String type = normalizedMime(mime);
        if (!type.isEmpty() && !type.equals(APK_MIME) && !type.equals("application/octet-stream")) return false;
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    static boolean completedSuccessfully(int status) { return status == 8; }
    static boolean active(int status) { return status == 1 || status == 2 || status == 4; }

    static String destination(String name) { return "ApkStore/" + UUID.randomUUID() + "/" + name; }

    static String statusText(int status, int reason) {
        if (status == 1) return "等待中";
        if (status == 2) return "下载中";
        if (status == 4) {
            if (reason == 1) return "等待网络可用";
            if (reason == 2) return "等待网络连接";
            if (reason == 3) return "等待 Wi-Fi";
            if (reason == 4) return "等待重试";
            if (reason == 5) return "等待设备恢复";
            return "已暂停 (" + reason + ")";
        }
        if (status == 16) {
            if (reason >= 400 && reason <= 599) return "HTTP " + reason;
            switch (reason) {
                case 1001: return "文件写入失败";
                case 1002: return "HTTP 响应异常";
                case 1004: return "HTTP 数据错误";
                case 1005: return "重定向过多";
                case 1006: return "空间不足";
                case 1007: return "设备不可用";
                case 1008: return "无法恢复下载";
                case 1009: return "文件已存在";
                default: return "错误代码 " + reason;
            }
        }
        return "";
    }
}
