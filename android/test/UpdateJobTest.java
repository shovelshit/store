package io.github.shovelshit.apkstore;

import java.io.File;
import java.nio.file.Files;

public final class UpdateJobTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("update-job-").toFile();
        File occupied = new File(root,"updates");
        if (!occupied.createNewFile()) throw new AssertionError();
        try {
            final boolean[] transferred = {false};
            Exception error = UpdateJob.run(() -> {
                if (!occupied.isDirectory() && !occupied.mkdirs()) throw new java.io.IOException("update directory unavailable");
                return occupied;
            },"a".repeat(64),part -> transferred[0] = true);
            if (error == null || transferred[0]) throw new AssertionError("directory failure did not return retryable error");
            if (new File(occupied,"a".repeat(64)+".part").exists()) throw new AssertionError("partial exposed");
        } finally { occupied.delete(); root.delete(); }
    }
}
