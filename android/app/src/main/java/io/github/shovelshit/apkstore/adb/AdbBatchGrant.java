package io.github.shovelshit.apkstore.adb;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serial, readback-confirmed grants for a single package on the current user-0 session. */
public final class AdbBatchGrant {
    public enum Outcome { GRANTED, SKIPPED, FAILED, PENDING }
    public static final class Entry {
        public final String permission;
        public final Outcome outcome;
        public final String reason;
        Entry(String permission, Outcome outcome, String reason) {
            this.permission = permission; this.outcome = outcome; this.reason = reason;
        }
    }
    public static class Progress {
        public final String packageName;
        public final List<Entry> entries;
        public final String current;
        Progress(String packageName, List<Entry> entries, String current) {
            this.packageName = packageName;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.current = current;
        }
        public int grantedCount() { return count(Outcome.GRANTED); }
        public int skippedCount() { return count(Outcome.SKIPPED); }
        public int failedCount() { return count(Outcome.FAILED); }
        public int pendingCount() { return count(Outcome.PENDING); }
        private int count(Outcome outcome) {
            int count = 0;
            for (Entry entry : entries) if (entry.outcome == outcome) count++;
            return count;
        }
    }
    public static final class Result extends Progress {
        public final AdbOperations.PermissionSnapshot snapshot;
        public final boolean interrupted, cancelled;
        public final String reason;
        Result(String pkg, List<Entry> entries, AdbOperations.PermissionSnapshot snapshot,
                boolean interrupted, boolean cancelled, String reason) {
            super(pkg, entries, null);
            this.snapshot = snapshot; this.interrupted = interrupted;
            this.cancelled = cancelled; this.reason = reason;
        }
    }
    private AdbBatchGrant() { }

    public static Result run(AdbOperations operations, String packageName, List<String> selected,
            BooleanSupplier cancelled, Consumer<Progress> progress) {
        AdbOperations.validPackage(packageName);
        if (operations == null || selected == null || cancelled == null || progress == null)
            throw new IllegalArgumentException("缺少授权参数");
        List<Entry> entries = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (String name : selected) {
            if (name == null || !unique.add(name)) entries.add(new Entry(name, Outcome.SKIPPED, "重复或无效权限"));
            else entries.add(new Entry(name, Outcome.PENDING, "尚未处理"));
        }
        AdbOperations.PermissionSnapshot snapshot = null;
        String reason = "";
        boolean interrupted = false, stopped = false;
        try {
            snapshot = operations.readPermissions(packageName);
            progress.accept(new Progress(packageName, entries, null));
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry.outcome != Outcome.PENDING) continue;
                if (cancelled.getAsBoolean()) { stopped = true; reason = "用户已停止"; break; }
                AdbOperations.Permission permission = snapshot.find(entry.permission);
                if (permission == null || !permission.mutable || permission.state == AdbOperations.State.UNSUPPORTED
                        || permission.state == AdbOperations.State.NOT_DECLARED) {
                    entries.set(i, new Entry(entry.permission, Outcome.SKIPPED, "未声明或不支持修改"));
                } else if (permission.state == AdbOperations.State.GRANTED) {
                    entries.set(i, new Entry(entry.permission, Outcome.SKIPPED, "已授权"));
                } else if (!permission.canGrant()) {
                    entries.set(i, new Entry(entry.permission, Outcome.SKIPPED, "状态不可确认，未执行"));
                } else {
                    progress.accept(new Progress(packageName, entries, entry.permission));
                    try {
                        snapshot = operations.setPermission(packageName, entry.permission, true);
                        entries.set(i, new Entry(entry.permission, Outcome.GRANTED, "回读确认已授权"));
                    } catch (AdbOperations.MutationRefusal refusal) {
                        entries.set(i, new Entry(entry.permission, Outcome.FAILED, refusal.getMessage()));
                        snapshot = operations.readPermissions(packageName);
                    } catch (IOException uncertain) {
                        snapshot = operations.readPermissions(packageName);
                        AdbOperations.Permission actual = snapshot.find(entry.permission);
                        if (actual != null && actual.state == AdbOperations.State.GRANTED)
                            entries.set(i, new Entry(entry.permission, Outcome.GRANTED, "回读确认已授权"));
                        else if (actual != null && actual.state == AdbOperations.State.DENIED)
                            entries.set(i, new Entry(entry.permission, Outcome.FAILED, uncertain.getMessage()));
                        else {
                            interrupted = true;
                            reason = "目标权限状态仍不可确认：" + uncertain.getMessage();
                            break;
                        }
                    }
                }
                progress.accept(new Progress(packageName, entries, null));
            }
            // A final snapshot is best-effort. Already confirmed entries remain confirmed.
            if (!stopped && !interrupted) snapshot = operations.readPermissions(packageName);
        } catch (IOException transport) {
            interrupted = true; reason = transport.getMessage(); snapshot = null;
        }
        return new Result(packageName, entries, snapshot, interrupted, stopped, reason);
    }
}
