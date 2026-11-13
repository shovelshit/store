package io.github.shovelshit.apkstore;

import java.io.File;
import java.nio.file.Files;

public final class UpdatePromotionTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("update-promotion-").toFile();
        File other = new File(root,"other");
        if (!other.mkdir()) throw new AssertionError();
        File part = new File(root,"hash.part"), completed = new File(root,"hash.apk");
        File foreign = new File(other,"foreign.part");
        try {
            Files.write(part.toPath(),new byte[]{1,2,3});
            Files.write(completed.toPath(),new byte[]{9});
            UpdatePromotion.promote(part,completed);
            if (part.exists() || !java.util.Arrays.equals(Files.readAllBytes(completed.toPath()),new byte[]{1,2,3}))
                throw new AssertionError("verified bytes not atomically promoted over existing APK");
            Files.write(foreign.toPath(),new byte[]{4});
            try { UpdatePromotion.promote(foreign,completed); throw new AssertionError("cross-directory promotion accepted"); }
            catch (java.io.IOException expected) { /* same-directory contract */ }
            if (!foreign.exists() || !java.util.Arrays.equals(Files.readAllBytes(completed.toPath()),new byte[]{1,2,3}))
                throw new AssertionError("failed promotion damaged prior APK");
            try { UpdatePromotion.promote(part,completed); throw new AssertionError("missing partial accepted"); }
            catch (java.io.IOException expected) { /* checked failure */ }
        } finally {
            part.delete(); completed.delete(); foreign.delete(); other.delete(); root.delete();
        }
    }
}
