package io.github.shovelshit.apkstore;

import io.github.shovelshit.apkstore.adb.AdbOperations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Presentation decisions independent of Android views and the ADB transport. */
public final class AdbPresentation {
    private AdbPresentation() { }

    public static List<AdbOperations.Application> filterApps(List<AdbOperations.Application> all, String search) {
        String query = search.trim().toLowerCase(Locale.US);
        List<AdbOperations.Application> filtered = new ArrayList<>();
        for (AdbOperations.Application app : all) {
            if (app.packageName.toLowerCase(Locale.US).contains(query)) filtered.add(app);
        }
        return filtered;
    }

    public static List<AdbOperations.Permission> orderedDeclared(List<AdbOperations.Permission> all) {
        List<AdbOperations.Permission> ordered = new ArrayList<>();
        for (AdbOperations.Permission item : all) if (item.mutable && item.state != AdbOperations.State.NOT_DECLARED) ordered.add(item);
        for (AdbOperations.Permission item : all) if (!item.mutable && item.state != AdbOperations.State.NOT_DECLARED) ordered.add(item);
        return ordered;
    }

    public static String permissionTitle(AdbOperations.Permission permission) {
        String suffix = permission.name.substring("android.permission.".length());
        switch (suffix) {
            case "CAMERA": return "相机";
            case "READ_EXTERNAL_STORAGE": return "读取存储";
            case "WRITE_EXTERNAL_STORAGE": return "写入存储";
            case "RECORD_AUDIO": return "麦克风";
            case "ACCESS_COARSE_LOCATION": return "大致位置";
            case "ACCESS_FINE_LOCATION": return "精确位置";
            case "WRITE_SECURE_SETTINGS": return "修改安全设置";
            case "SYSTEM_ALERT_WINDOW": return "悬浮窗";
            case "WRITE_SETTINGS": return "修改系统设置";
            case "PACKAGE_USAGE_STATS": return "应用使用情况";
            default:
                if (suffix.contains("INTERNET") || suffix.contains("NETWORK")) return "网络访问权限";
                if (suffix.contains("LOCATION")) return "位置权限";
                if (suffix.contains("STORAGE") || suffix.contains("MEDIA")) return "存储与媒体权限";
                if (suffix.contains("BLUETOOTH") || suffix.contains("WIFI")) return "设备连接权限";
                return "其他权限";
        }
    }

    public static String permissionDetail(AdbOperations.Permission permission) {
        String state;
        switch (permission.state) {
            case GRANTED: state = "已授权"; break;
            case DENIED: state = "未授权"; break;
            default: state = "未知";
        }
        String reason = "";
        if (!permission.canGrant() && !permission.canRevoke()) {
            if (permission.state == AdbOperations.State.UNSUPPORTED) reason = "\n此权限不支持通过 ADB 修改";
            else if (permission.state == AdbOperations.State.UNKNOWN) reason = "\n设备未返回可确认的权限状态";
            else reason = "\n此权限当前不可修改";
        }
        return state + " · " + permission.name + reason;
    }

    public static final class InstallProgress {
        private String summary = "", detail = "尚未开始安装";
        private int percent;
        public String summary() { return summary; }
        public String detail() { return detail; }
        public int percent() { return percent; }
        public void start() { percent = 0; summary = "正在上传安装包"; detail = "正在上传安装包…"; }
        public void uploaded(long done, long total) {
            percent = total <= 0 ? 0 : (int) Math.min(100, Math.max(0, done * 100.0 / total));
            summary = percent >= 100 ? "正在设备安装" : "上传 " + percent + "%";
            detail = percent >= 100 ? "上传完成，正在设备安装…" : "正在上传安装包：" + percent + "%";
        }
        public void succeeded(String result) { percent = 100; summary = "安装成功"; detail = "目标设备安装成功：" + result; }
        public void failed(String error) { summary = "安装失败"; detail = "安装失败或结果不确定：" + error + "；请检查目标设备状态"; }
    }
}
