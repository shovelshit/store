package io.github.shovelshit.apkstore;

import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Completed APKs are checked once off the UI thread; DownloadManager URIs are not filesystem paths. */
final class ApkArchiveVerifier {
    enum Result { VERIFYING, VALID, INVALID }

    private static ApkArchiveVerifier instance;

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Long, Result> results = new HashMap<>();

    private ApkArchiveVerifier(Context context) { this.context = context.getApplicationContext(); }

    static synchronized ApkArchiveVerifier get(Context context) {
        if (instance == null) instance = new ApkArchiveVerifier(context);
        return instance;
    }

    Result result(long id, Uri uri, Runnable onVerified) {
        Result previous = results.get(id);
        if (previous != null) return previous;
        results.put(id, Result.VERIFYING);
        worker.execute(() -> {
            boolean valid = false;
            if (uri != null) {
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input != null) valid = DownloadVerification.isApk(input, archive -> inspect(archive));
                } catch (Exception ignored) { /* unreadable or invalid archive */ }
            }
            final Result finalResult = valid ? Result.VALID : Result.INVALID;
            main.post(() -> { results.put(id, finalResult); onVerified.run(); });
        });
        return Result.VERIFYING;
    }

    private boolean inspect(InputStream archive) throws IOException {
        File temporary = File.createTempFile("apk-check-", ".apk", context.getCacheDir());
        try {
            try (OutputStream output = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = archive.read(bytes)) != -1) output.write(bytes, 0, count);
            }
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(temporary.getAbsolutePath(), 0);
            return info != null && info.packageName != null && !info.packageName.isEmpty();
        } finally {
            temporary.delete();
        }
    }

    void forget(long id) { results.remove(id); }
}
