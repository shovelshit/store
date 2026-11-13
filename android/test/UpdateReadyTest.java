package io.github.shovelshit.apkstore;

import java.lang.ref.WeakReference;

public final class UpdateReadyTest {
    public static void main(String[] args) {
        UpdatePolicy.Release release = UpdatePolicy.parse(UpdatePolicyTest.release());
        UpdatePolicy.Installed old = new UpdatePolicy.Installed(release.packageName,5,28,release.certificateSha256);
        UpdatePolicy.Installed upgraded = new UpdatePolicy.Installed(release.packageName,6,28,release.certificateSha256);
        UpdateReady ready = new UpdateReady();
        ready.restore(release,old);
        if (ready.release() != release) throw new AssertionError("ready cache missing");
        // A failed or identical check cannot erase independently retained, installable bytes.
        ready.checked(null);
        if (ready.latest() != null) throw new AssertionError("offline check acquired fake release");
        ready.checked(release);
        if (ready.latest() != release) throw new AssertionError("new check not recorded");
        if (ready.release() != release) throw new AssertionError("check erased ready cache");
        ready.restore(release,upgraded);
        if (ready.release() != null) throw new AssertionError("obsolete APK remains installable");
        final int[] called = {0};
        UpdateCompletion completion = new UpdateCompletion(verified -> called[0]++);
        WeakReference<UpdateCompletion> reference = new WeakReference<>(completion);
        completion.clear();
        completion.deliver(release);
        if (called[0] != 0 || reference.get() == null) throw new AssertionError("destroyed callback invoked");
    }
}
