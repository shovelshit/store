package io.github.shovelshit.apkstore;

import java.util.HashMap;
import java.util.Map;

public final class UpdatePolicyTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static Map<String, Object> release() {
        Map<String, Object> m = new HashMap<>();
        m.put("schema", 1); m.put("packageName", "io.github.shovelshit.apkstore");
        m.put("versionCode", 6); m.put("versionName", "1.4.0"); m.put("minSdk", 28);
        m.put("channel", "development"); m.put("url", "https://example.org/update.apk");
        m.put("size", 123L);
        m.put("sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        m.put("certificateSha256", "a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97");
        m.put("notes", "Changes"); return m;
    }
    static void fails(Map<String,Object> map) {
        try { UpdatePolicy.parse(map); throw new AssertionError("accepted invalid manifest"); }
        catch (IllegalArgumentException expected) { /* expected */ }
    }
    public static void main(String[] args) {
        Map<String,Object> m = release();
        UpdatePolicy.Release r = UpdatePolicy.parse(m);
        UpdatePolicy.Installed current = new UpdatePolicy.Installed("io.github.shovelshit.apkstore", 5, 28,
                "a724a2980a46a41e296353e2553256f4dceeb645810f25a41e2ecb3873558a97");
        check(UpdatePolicy.decide(r,current) == UpdatePolicy.Decision.AVAILABLE);
        check(UpdatePolicy.decide(r,new UpdatePolicy.Installed(current.packageName,6,28,current.certificateSha256)) == UpdatePolicy.Decision.CURRENT);
        check(UpdatePolicy.decide(r,new UpdatePolicy.Installed(current.packageName,7,28,current.certificateSha256)) == UpdatePolicy.Decision.CURRENT);
        m = release(); m.put("schema", 2); fails(m);
        m = release(); m.remove("size"); fails(m);
        m = release(); m.put("size", 0); fails(m);
        m = release(); m.put("url", "http://example.org/update.apk"); fails(m);
        m = release(); m.put("versionCode", 6L); fails(m);
        m = release(); m.put("sha256", "not-a-hash"); fails(m);
        m = release(); m.put("channel", "production"); fails(m);
        m = release(); m.put("certificateSha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        try { UpdatePolicy.decide(UpdatePolicy.parse(m), current); throw new AssertionError("certificate accepted"); }
        catch (IllegalArgumentException expected) { }
        m = release(); m.put("minSdk", 29);
        check(UpdatePolicy.decide(UpdatePolicy.parse(m), current) == UpdatePolicy.Decision.INCOMPATIBLE);
        check(UpdatePolicy.shouldCheck(3_600_000L, 1, true));
        check(!UpdatePolicy.shouldCheck(3_599_999L, 1, false));
        check(UpdatePolicy.shouldCheck(3_600_001L, 1, false));
    }
}
