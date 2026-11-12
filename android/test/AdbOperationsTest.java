import io.github.shovelshit.apkstore.adb.AdbClient;
import io.github.shovelshit.apkstore.adb.AdbOperations;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class AdbOperationsTest {
    static final class Runner implements AdbOperations.CommandRunner {
        final List<String> commands = new ArrayList<>();
        final List<AdbClient.ShellResult> results = new ArrayList<>();
        @Override public AdbClient.ShellResult run(String command) {
            commands.add(command);
            return results.remove(0);
        }
        Runner reply(String output, int exit) { results.add(new AdbClient.ShellResult(output, exit)); return this; }
    }
    static void eq(Object actual, Object expected) { if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected); }
    static void yes(boolean value) { if (!value) throw new AssertionError("false"); }
    interface CheckedAction { void run() throws Exception; }
    static void rejected(CheckedAction action) throws Exception { try { action.run(); throw new AssertionError("accepted"); } catch (IllegalArgumentException expected) {} }

    public static void main(String[] args) throws Exception {
        Runner r = new Runner();
        AdbOperations ops = new AdbOperations(r);
        rejected(() -> ops.readPermissions("a.b; id"));
        eq(r.commands.size(), 0);
        r.reply("package:com.example.one\npackage:org.other.app\n", 0);
        eq(ops.listApplications(false).size(), 2);
        eq(r.commands.get(0), "pm list packages -3");
        r.reply("package:android\n", 0);
        eq(ops.listApplications(true).size(), 1);
        eq(r.commands.get(1), "pm list packages -s");

        String dump = "Package [com.example.one] (123):\n    requested permissions:\n"
                + "      android.permission.CAMERA\n      android.permission.ACCESS_FINE_LOCATION\n"
                + "      android.permission.WRITE_SECURE_SETTINGS\n      android.permission.INTERNET\n"
                + "    install permissions:\n      android.permission.WRITE_SECURE_SETTINGS: granted=true\n"
                + "    runtime permissions:\n      android.permission.CAMERA: granted=false, flags=[ USER_SET ]\n"
                + "      android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ USER_SET ]\n";
        r.reply(dump, 0);
        AdbOperations.PermissionSnapshot permissions = ops.readPermissions("com.example.one");
        eq(permissions.find("android.permission.CAMERA").state, AdbOperations.State.DENIED);
        eq(permissions.find("android.permission.ACCESS_FINE_LOCATION").state, AdbOperations.State.GRANTED);
        eq(permissions.find("android.permission.WRITE_SECURE_SETTINGS").state, AdbOperations.State.GRANTED);
        eq(permissions.find("android.permission.INTERNET").state, AdbOperations.State.UNSUPPORTED);
        eq(permissions.find("android.permission.SYSTEM_ALERT_WINDOW").state, AdbOperations.State.NOT_DECLARED);
        eq(r.commands.get(2), "dumpsys package com.example.one");
        eq(r.commands.size(), 3);
        eq(permissions.find("android.permission.RECORD_AUDIO").state, AdbOperations.State.NOT_DECLARED);
        String revoked = "Package [com.example.one] (123):\n    requested permissions:\n"
                + "      android.permission.CAMERA\n      android.permission.WRITE_SECURE_SETTINGS\n"
                + "      android.permission.PACKAGE_USAGE_STATS\n    User 0: installed=true\n      runtime permissions:\n";
        Runner revokedRunner = new Runner();
        revokedRunner.reply(revoked, 0).reply("GET_USAGE_STATS: ignore\n", 0);
        AdbOperations.PermissionSnapshot revokedSnapshot = new AdbOperations(revokedRunner).readPermissions("com.example.one");
        eq(revokedSnapshot.find("android.permission.CAMERA").state, AdbOperations.State.DENIED);
        eq(revokedSnapshot.find("android.permission.WRITE_SECURE_SETTINGS").state, AdbOperations.State.DENIED);
        eq(revokedSnapshot.find("android.permission.PACKAGE_USAGE_STATS").state, AdbOperations.State.DENIED);
        eq(revokedRunner.commands.get(1), "cmd appops get com.example.one GET_USAGE_STATS");

        Runner denied = new Runner();
        AdbOperations broken = new AdbOperations(denied);
        denied.reply("Permission Denial", 1);
        try { broken.readPermissions("com.example.one"); throw new AssertionError("accepted failed shell"); }
        catch (IOException expected) { yes(expected.getMessage().contains("Permission Denial")); }

        Runner change = new Runner();
        AdbOperations changing = new AdbOperations(change);
        change.reply(dump, 0).reply("", 0).reply(dump, 0);
        try { changing.setPermission("com.example.one", "android.permission.CAMERA", true); throw new AssertionError("unconfirmed grant"); }
        catch (IOException expected) { yes(expected.getMessage().contains("未确认")); }
        eq(change.commands.get(1), "pm grant com.example.one android.permission.CAMERA");

        Runner undeclared = new Runner();
        undeclared.reply(dump, 0);
        rejected(() -> new AdbOperations(undeclared).setPermission("com.example.one", "android.permission.SYSTEM_ALERT_WINDOW", true));
        eq(undeclared.commands.size(), 1);
        Runner special = new Runner();
        String overlay = "Package [com.example.one] (123):\n    requested permissions:\n      android.permission.SYSTEM_ALERT_WINDOW\n";
        special.reply(overlay, 0).reply("SYSTEM_ALERT_WINDOW: mode=ignore\n", 0)
                .reply("", 0).reply(overlay, 0).reply("SYSTEM_ALERT_WINDOW: allow; time=+1m ago\n", 0);
        eq(new AdbOperations(special).setPermission("com.example.one", "android.permission.SYSTEM_ALERT_WINDOW", true)
                .find("android.permission.SYSTEM_ALERT_WINDOW").state, AdbOperations.State.GRANTED);
        eq(special.commands.get(2), "cmd appops set com.example.one SYSTEM_ALERT_WINDOW allow");

        Runner upload = new Runner();
        AdbOperations installing = new AdbOperations(upload);
        final List<String> events = new ArrayList<>();
        AdbOperations.Uploader uploader = (source, length, path, progress) -> {
            events.add("upload:" + path + ":" + length);
            eq(source.read(), 1);
        };
        upload.reply("Failure [INSTALL_FAILED_INVALID_APK]", 1).reply("", 0);
        try { installing.install(new ByteArrayInputStream(new byte[]{1, 2}), 2, uploader, null); throw new AssertionError("installed"); }
        catch (IOException expected) { yes(expected.getMessage().contains("INSTALL_FAILED_INVALID_APK")); }
        eq(upload.commands.size(), 2);
        yes(upload.commands.get(0).matches("pm install -r /data/local/tmp/apk-store-[a-f0-9]+\\.apk"));
        eq(upload.commands.get(1), "rm -f " + upload.commands.get(0).substring("pm install -r ".length()));
        eq(events.size(), 1);
        Runner cleanup = new Runner();
        cleanup.reply("Success\n", 0).reply("rm: Permission denied", 1);
        try { new AdbOperations(cleanup).install(new ByteArrayInputStream(new byte[]{1}), 1, uploader, null); throw new AssertionError("cleanup error hidden"); }
        catch (IOException expected) { yes(expected.getMessage().contains("清理失败")); }
        System.out.println("AdbOperationsTest PASS");
    }
}
