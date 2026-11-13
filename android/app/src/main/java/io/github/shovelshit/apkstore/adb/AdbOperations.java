package io.github.shovelshit.apkstore.adb;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed, validated Android package operations; no arbitrary shell input reaches the transport. */
public final class AdbOperations {
    public static final class CommandFailure extends IOException {
        CommandFailure(String message) { super(message); }
    }
    public static final class VerificationFailure extends IOException {
        VerificationFailure(String message) { super(message); }
    }
    public interface CommandRunner { AdbClient.ShellResult run(String command) throws IOException; }
    public interface Uploader {
        void upload(InputStream source, long length, String remotePath, AdbClient.ProgressListener progress) throws IOException;
    }
    public enum State { GRANTED, DENIED, UNKNOWN, NOT_DECLARED, UNSUPPORTED }
    public static final class Application {
        public final String packageName;
        public Application(String packageName) { this.packageName = packageName; }
    }
    public static final class Permission {
        public final String name;
        public final State state;
        public final boolean mutable;
        Permission(String name, State state, boolean mutable) {
            this.name = name; this.state = state; this.mutable = mutable;
        }
        public boolean canGrant() {
            return mutable && (state == State.DENIED || state == State.UNKNOWN
                    && Arrays.asList(SPECIAL).contains(name.substring("android.permission.".length())));
        }
        public boolean canRevoke() { return mutable && state == State.GRANTED; }
    }
    public static final class DeviceInfo {
        public final String shellIdentity, manufacturer, model, androidVersion;
        DeviceInfo(String shellIdentity, String manufacturer, String model, String androidVersion) {
            this.shellIdentity = shellIdentity; this.manufacturer = manufacturer;
            this.model = model; this.androidVersion = androidVersion;
        }
    }
    public static final class PermissionSnapshot {
        public final String packageName;
        public final List<Permission> permissions;
        PermissionSnapshot(String packageName, List<Permission> permissions) {
            this.packageName = packageName;
            this.permissions = Collections.unmodifiableList(permissions);
        }
        public Permission find(String name) {
            for (Permission permission : permissions) if (permission.name.equals(name)) return permission;
            return null;
        }
    }
    private static final Pattern PACKAGE = Pattern.compile("(?:android|[a-zA-Z_][a-zA-Z_0-9]*(?:\\.[a-zA-Z_][a-zA-Z_0-9]*)+)");
    private static final Pattern PERMISSION = Pattern.compile("android\\.permission\\.[A-Z_0-9]+");
    private static final Pattern GRANT = Pattern.compile("(android\\.permission\\.[A-Z_0-9]+):\\s+granted=(true|false)");
    private static final Pattern USER = Pattern.compile("(?m)^\\s*User (\\d+):");
    private static final String[] RUNTIME = {
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION",
        "CAMERA", "RECORD_AUDIO"
    };
    private static final String[] SPECIAL = {"SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "PACKAGE_USAGE_STATS"};
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CommandRunner commands;

    public AdbOperations(CommandRunner commands) { this.commands = commands; }

    public List<Application> listApplications(boolean system) throws IOException {
        String output = shell("pm list packages --user 0 " + (system ? "-s" : "-3"));
        List<Application> apps = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith("package:")) {
                String name = line.substring(8).trim();
                if (PACKAGE.matcher(name).matches()) apps.add(new Application(name));
            }
        }
        if (apps.isEmpty() && !output.trim().isEmpty()) throw new IOException("无法解析应用列表: " + output);
        return apps;
    }

    public String androidVersion() throws IOException { return shell("getprop ro.build.version.release").trim(); }

    public DeviceInfo deviceInfo() throws IOException {
        String identity = shell("id").trim();
        if (!identity.matches("(?s)^uid=[0-9]+\\b.*") || identity.length() > 512) {
            throw new IOException("无法确认目标设备的 ADB 执行身份");
        }
        String manufacturer = property("ro.product.manufacturer");
        String model = property("ro.product.model");
        String version = property("ro.build.version.release");
        return new DeviceInfo(identity, manufacturer, model, version);
    }

    private String property(String name) throws IOException {
        String value = shell("getprop " + name).trim();
        if (value.isEmpty() || value.length() > 128) throw new IOException("设备信息不可读: " + name);
        return value;
    }

    public PermissionSnapshot readPermissions(String packageName) throws IOException {
        validPackage(packageName);
        String dump = shell("dumpsys package " + packageName);
        if (!dump.contains("Package [" + packageName + "]")) throw new IOException("目标应用不存在或权限信息不可读");
        if (!dump.contains("requested permissions:")) throw new IOException("目标应用权限清单不可读");
        Matcher users = USER.matcher(dump);
        int userZeroStart = -1, userZeroEnd = dump.length(), globalEnd = dump.length();
        while (users.find()) {
            if (globalEnd == dump.length()) globalEnd = users.start();
            if (userZeroStart >= 0) { userZeroEnd = users.start(); break; }
            if (users.group(1).equals("0")) userZeroStart = users.end();
        }
        String userZero = userZeroStart < 0 ? "" : dump.substring(userZeroStart, userZeroEnd);
        String global = dump.substring(0, globalEnd);
        List<String> requested = new ArrayList<>();
        boolean inRequested = false;
        for (String line : dump.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.equals("requested permissions:")) { inRequested = true; continue; }
            if (inRequested && !line.startsWith("      ")) inRequested = false;
            if (inRequested && PERMISSION.matcher(trimmed).matches() && !requested.contains(trimmed)) requested.add(trimmed);
        }
        List<Permission> permissions = new ArrayList<>();
        for (String name : requested) {
            String suffix = name.substring("android.permission.".length());
            boolean runtime = Arrays.asList(RUNTIME).contains(suffix);
            boolean special = Arrays.asList(SPECIAL).contains(suffix);
            boolean secure = suffix.equals("WRITE_SECURE_SETTINGS");
            State state = State.UNSUPPORTED;
            if (special) state = appOp(packageName, operation(suffix));
            else if (runtime || secure) {
                Matcher matcher = GRANT.matcher(runtime ? userZero : global);
                state = runtime && (userZeroStart < 0 || !userZero.contains("runtime permissions:"))
                        ? State.UNKNOWN : State.DENIED;
                while (matcher.find()) if (matcher.group(1).equals(name)) {
                    state = Boolean.parseBoolean(matcher.group(2)) ? State.GRANTED : State.DENIED;
                }
            }
            permissions.add(new Permission(name, state, runtime || special || secure));
        }
        List<String> supported = new ArrayList<>();
        supported.addAll(Arrays.asList(RUNTIME));
        supported.add("WRITE_SECURE_SETTINGS");
        supported.addAll(Arrays.asList(SPECIAL));
        for (String suffix : supported) {
            String name = "android.permission." + suffix;
            if (!requested.contains(name)) permissions.add(new Permission(name, State.NOT_DECLARED, false));
        }
        return new PermissionSnapshot(packageName, permissions);
    }

    public PermissionSnapshot setPermission(String packageName, String permission, boolean grant) throws IOException {
        validPackage(packageName);
        if (!PERMISSION.matcher(permission).matches()) throw new IllegalArgumentException("无效权限名");
        String suffix = permission.substring("android.permission.".length());
        boolean special = Arrays.asList(SPECIAL).contains(suffix);
        if (!special && !Arrays.asList(RUNTIME).contains(suffix) && !suffix.equals("WRITE_SECURE_SETTINGS")) {
            throw new IllegalArgumentException("不支持修改此权限");
        }
        PermissionSnapshot before = readPermissions(packageName);
        Permission selected = before.find(permission);
        if (selected == null || !selected.mutable) throw new IllegalArgumentException("目标应用未声明此权限");
        if (special) shell("cmd appops set --user 0 " + packageName + " " + operation(suffix) + " " + (grant ? "allow" : "ignore"));
        else shell("pm " + (grant ? "grant" : "revoke") + " --user 0 " + packageName + " " + permission);
        PermissionSnapshot after = readPermissions(packageName);
        State actual = after.find(permission).state;
        if (actual != (grant ? State.GRANTED : State.DENIED)) throw new VerificationFailure("权限修改后未确认目标状态: " + actual);
        return after;
    }

    public String install(InputStream source, long length, Uploader uploader, AdbClient.ProgressListener progress) throws IOException {
        if (source == null || uploader == null || length <= 0) throw new IllegalArgumentException("无效安装包");
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        StringBuilder hex = new StringBuilder();
        for (byte b : nonce) hex.append(String.format(Locale.US, "%02x", b & 255));
        String path = "/data/local/tmp/apk-store-" + hex + ".apk";
        IOException failure = null;
        String result = null;
        try {
            uploader.upload(source, length, path, progress);
            result = shell("pm install -r --user 0 " + path).trim();
            if (!result.equals("Success")) throw new IOException("安装结果未确认: " + result);
        } catch (IOException error) { failure = error; }
        try { shell("rm -f " + path); }
        catch (IOException cleanup) {
            if (failure == null) failure = new IOException("安装结果: " + result + "；临时文件清理失败: " + cleanup.getMessage(), cleanup);
            else {
                IOException combined = new IOException(failure.getMessage() + "；临时文件清理失败: " + cleanup.getMessage(), failure);
                combined.addSuppressed(cleanup);
                failure = combined;
            }
        }
        if (failure != null) throw failure;
        return result;
    }

    private State appOp(String packageName, String operation) throws IOException {
        String output = shell("cmd appops get --user 0 " + packageName + " " + operation);
        Matcher matcher = Pattern.compile("(?m)^\\s*" + operation + ":\\s*(?:mode=)?(allow|ignore|deny|default|foreground)\\b").matcher(output);
        if (!matcher.find()) return State.UNKNOWN;
        return matcher.group(1).equals("allow") ? State.GRANTED : State.DENIED;
    }

    private static String operation(String suffix) {
        return suffix.equals("PACKAGE_USAGE_STATS") ? "GET_USAGE_STATS" : suffix;
    }

    private String shell(String command) throws IOException {
        AdbClient.ShellResult result = commands.run(command);
        if (result.exitCode != 0) throw new CommandFailure(command.split(" ")[0] + " 失败 (" + result.exitCode + "): " + result.output);
        return result.output;
    }

    public static void validPackage(String packageName) {
        if (packageName == null || !PACKAGE.matcher(packageName).matches()) throw new IllegalArgumentException("无效应用包名");
    }
}
