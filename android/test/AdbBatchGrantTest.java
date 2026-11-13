import io.github.shovelshit.apkstore.adb.AdbBatchGrant;
import io.github.shovelshit.apkstore.adb.AdbClient;
import io.github.shovelshit.apkstore.adb.AdbOperations;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AdbBatchGrantTest {
    static final String PKG = "com.example.one";
    static final String CAMERA = "android.permission.CAMERA";
    static final String FINE = "android.permission.ACCESS_FINE_LOCATION";
    static final String OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW";
    static final String INTERNET = "android.permission.INTERNET";
    static final class Device implements AdbOperations.CommandRunner {
        final List<String> commands = new ArrayList<>();
        boolean camera, fine = true, overlay;
        String refusal, unchanged;
        int transportAfter = Integer.MAX_VALUE;
        @Override public AdbClient.ShellResult run(String command) throws IOException {
            commands.add(command);
            if (commands.size() > transportAfter) throw new IOException("connection lost");
            if (command.equals("dumpsys package " + PKG)) return new AdbClient.ShellResult(
                    "Package [" + PKG + "] (123):\n    requested permissions:\n"
                    + "      " + CAMERA + "\n      " + FINE + "\n      " + OVERLAY + "\n      " + INTERNET + "\n"
                    + "    User 0: installed=true\n      runtime permissions:\n"
                    + "        " + CAMERA + ": granted=" + camera + "\n"
                    + "        " + FINE + ": granted=" + fine + "\n", 0);
            if (command.equals("cmd appops get --user 0 " + PKG + " SYSTEM_ALERT_WINDOW"))
                return new AdbClient.ShellResult("SYSTEM_ALERT_WINDOW: " + (overlay ? "allow" : "ignore"), 0);
            if (command.equals(refusal)) return new AdbClient.ShellResult("Permission denied", 1);
            if (command.equals("pm grant --user 0 " + PKG + " " + CAMERA)) {
                if (!command.equals(unchanged)) camera = true;
                return new AdbClient.ShellResult("", 0);
            }
            if (command.equals("cmd appops set --user 0 " + PKG + " SYSTEM_ALERT_WINDOW allow")) {
                if (!command.equals(unchanged)) overlay = true;
                return new AdbClient.ShellResult("", 0);
            }
            throw new AssertionError("unsafe command: " + command);
        }
        AdbBatchGrant.Result run(List<String> selected, AtomicBoolean cancelled, List<AdbBatchGrant.Progress> events) {
            return AdbBatchGrant.run(new AdbOperations(this), PKG, selected, cancelled::get, events::add);
        }
    }
    static void eq(Object actual, Object expected) { if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected); }
    static void yes(boolean value) { if (!value) throw new AssertionError("false"); }
    static int mutations(Device device) {
        int count = 0;
        for (String command : device.commands) if (command.startsWith("pm grant") || command.startsWith("cmd appops set")) count++;
        return count;
    }
    public static void main(String[] args) {
        Device first = new Device();
        AdbBatchGrant.Result success = first.run(Arrays.asList(CAMERA, FINE, OVERLAY, INTERNET), new AtomicBoolean(), new ArrayList<>());
        eq(success.grantedCount(), 2); eq(success.skippedCount(), 2); eq(success.failedCount(), 0);
        eq(success.snapshot.find(CAMERA).state, AdbOperations.State.GRANTED);
        eq(success.snapshot.find(OVERLAY).state, AdbOperations.State.GRANTED);
        eq(mutations(first), 2);
        Device stale = new Device(); stale.camera = true;
        eq(stale.run(Arrays.asList(CAMERA, OVERLAY), new AtomicBoolean(), new ArrayList<>()).grantedCount(), 1);
        eq(mutations(stale), 1);
        Device invalid = new Device();
        AdbBatchGrant.Result rejected = invalid.run(Arrays.asList(CAMERA, CAMERA, INTERNET, "android.permission.RECORD_AUDIO", "bad; id"), new AtomicBoolean(), new ArrayList<>());
        eq(rejected.grantedCount(), 1); eq(rejected.skippedCount(), 4); eq(mutations(invalid), 1);
        Device denied = new Device(); denied.refusal = "pm grant --user 0 " + PKG + " " + CAMERA;
        AdbBatchGrant.Result partial = denied.run(Arrays.asList(CAMERA, OVERLAY), new AtomicBoolean(), new ArrayList<>());
        eq(partial.failedCount(), 1); eq(partial.grantedCount(), 1); eq(mutations(denied), 2);
        Device appopsDenied = new Device();
        appopsDenied.refusal = "cmd appops set --user 0 " + PKG + " SYSTEM_ALERT_WINDOW allow";
        AdbBatchGrant.Result appopsPartial = appopsDenied.run(Arrays.asList(OVERLAY, CAMERA), new AtomicBoolean(), new ArrayList<>());
        eq(appopsPartial.failedCount(), 1); eq(appopsPartial.grantedCount(), 1);
        eq(appopsPartial.snapshot.find(CAMERA).state, AdbOperations.State.GRANTED);
        Device unchanged = new Device(); unchanged.unchanged = "pm grant --user 0 " + PKG + " " + CAMERA;
        eq(unchanged.run(Arrays.asList(CAMERA, OVERLAY), new AtomicBoolean(), new ArrayList<>()).failedCount(), 1);
        eq(mutations(unchanged), 2);
        Device lost = new Device(); lost.transportAfter = 7;
        AdbBatchGrant.Result interrupted = lost.run(Arrays.asList(CAMERA, OVERLAY), new AtomicBoolean(), new ArrayList<>());
        yes(interrupted.interrupted); eq(interrupted.grantedCount(), 1); eq(mutations(lost), 1);
        yes(interrupted.reason.contains("connection lost"));
        yes(interrupted.snapshot == null);
        Device unreadable = new Device(); unreadable.transportAfter = 5;
        AdbBatchGrant.Result uncertain = unreadable.run(Arrays.asList(CAMERA, OVERLAY), new AtomicBoolean(), new ArrayList<>());
        yes(uncertain.interrupted); eq(uncertain.grantedCount(), 0); eq(uncertain.pendingCount(), 2);
        yes(uncertain.snapshot == null);
        eq(mutations(unreadable), 1);
        Device between = new Device(); AtomicBoolean stop = new AtomicBoolean();
        AdbBatchGrant.Result cancellation = AdbBatchGrant.run(new AdbOperations(between), PKG,
                Arrays.asList(CAMERA, OVERLAY), stop::get, progress -> {
                    if (progress.grantedCount() == 1) stop.set(true);
                });
        eq(cancellation.grantedCount(), 1); yes(cancellation.cancelled); eq(mutations(between), 1);
        Device retry = new Device(); retry.camera = true;
        eq(retry.run(Arrays.asList(CAMERA, OVERLAY), new AtomicBoolean(), new ArrayList<>()).grantedCount(), 1);
        eq(mutations(retry), 1);
        System.out.println("AdbBatchGrantTest PASS");
    }
}
