package io.github.shovelshit.apkstore;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;


public class MainActivity extends Activity {
    private static final String HOME_URL = "https://store.ltools.asia/";
    private static final int REQUEST_STORAGE = 1001;

    private WebView webView;
    private ProgressBar progress;
    private LinearLayout downloadBar;
    private ProgressBar downloadProgress;
    private TextView downloadLabel;
    private String pendingUrl;
    private String pendingDisposition;
    private String pendingMime;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private BroadcastReceiver downloadReceiver;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateDownloadBar();
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NativeUi.BACKGROUND);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), 0, dp(10), 0);
        toolbar.setBackgroundColor(Color.WHITE);
        TextView brand = NativeUi.text(this, "应用商店", 18, NativeUi.INK);
        brand.setSingleLine(true);
        brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, dp(56), 1));
        toolbar.addView(NativeUi.headerAction(this, "↓ 下载", "打开下载管理",
                () -> startActivity(new Intent(this, DownloadsActivity.class))),
                new LinearLayout.LayoutParams(dp(76), dp(48)));
        toolbar.addView(NativeUi.headerAction(this, "ADB", "打开 ADB 设备管理",
                () -> startActivity(new Intent(this, AdbActivity.class))),
                new LinearLayout.LayoutParams(dp(56), dp(48)));
        toolbar.addView(NativeUi.headerAction(this, "更新", "检查应用更新",
                () -> startActivity(new Intent(this, UpdateActivity.class))),
                new LinearLayout.LayoutParams(dp(56), dp(48)));
        root.addView(toolbar);

        // A transient download summary occupies its own row above the page.
        downloadBar = new LinearLayout(this);
        downloadBar.setOrientation(LinearLayout.VERTICAL);
        downloadBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        downloadBar.setVisibility(View.GONE);
        downloadBar.setBackground(NativeUi.shape(this, Color.WHITE, 6, true));
        downloadBar.setOnClickListener(view -> startActivity(new Intent(this, DownloadsActivity.class)));
        downloadLabel = NativeUi.text(this, "", 12, NativeUi.INK);
        downloadLabel.setSingleLine(true);
        downloadLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        downloadBar.addView(downloadLabel);
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
        progressParams.topMargin = dp(6);
        downloadBar.addView(downloadProgress, progressParams);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.setMargins(dp(10), dp(6), dp(10), dp(6));
        root.addView(downloadBar, summaryParams);

        FrameLayout webContainer = new FrameLayout(this);
        webView = new WebView(this);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);

        webContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        barParams.gravity = Gravity.TOP;
        webContainer.addView(progress, barParams);
        root.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        NativeUi.applyInsets(this, root);
        UpdateCoordinator.get(getApplicationContext()).check(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " ApkStore/1.4.1");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.startsWith("http")) {
                    showOffline(description);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, contentDisposition, mimeType);
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();

        // 安装包直链交给 DownloadManager，避免 WebView 展示二进制
        if (path.endsWith(".apk")) {
            startDownload(url, null, "application/vnd.android.package-archive");
            return true;
        }
        if (scheme.equals("http") || scheme.equals("https")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // 没有可处理该 scheme 的应用
        }
        return true;
    }

    private void startDownload(String url, String contentDisposition, String mimeType) {
        // Android 9 及以下写公共下载目录需要存储权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingUrl = url;
            pendingDisposition = contentDisposition;
            pendingMime = mimeType;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        enqueueDownload(url, contentDisposition, mimeType);
    }

    private void enqueueDownload(String url, String contentDisposition, String mimeType) {
        try {
            String fileName = DownloadPolicy.fileName(url, contentDisposition, mimeType);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            JSONArray items = DownloadStore.read(this);
            for (int i = 0; i < items.length(); i++) {
                if (items.optJSONObject(i) == null) continue;
                long previous = items.optJSONObject(i).optLong("id");
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(previous))) {
                    if (cursor != null && cursor.moveToFirst() && DownloadPolicy.active(cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))) {
                        if (url.equals(items.optJSONObject(i).optString("source"))) {
                            Toast.makeText(this, "已有相同下载任务，请在「下载」查看", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                }
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            Uri target = Uri.parse(url);
            Uri home = Uri.parse(HOME_URL);
            if (home.getHost().equalsIgnoreCase(target.getHost())
                    && home.getScheme().equalsIgnoreCase(target.getScheme())
                    && home.getPort() == target.getPort()) {
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            }
            String type = DownloadPolicy.normalizedMime(mimeType);
            if (!type.isEmpty()) request.setMimeType(type);
            request.setTitle(fileName);
            request.setDescription("正在下载文件");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    DownloadPolicy.destination(fileName));

            long id = manager.enqueue(request);
            DownloadStore.add(this, id, fileName, type, url);
            Toast.makeText(this, "已加入下载队列，请在「下载」查看状态", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "下载失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        String url = pendingUrl;
        String disposition = pendingDisposition;
        String mime = pendingMime;
        pendingUrl = null;
        pendingDisposition = null;
        pendingMime = null;
        if (granted && url != null) {
            enqueueDownload(url, disposition, mime);
        } else {
            Toast.makeText(this, "没有存储权限，无法保存文件", Toast.LENGTH_LONG).show();
        }
    }

    private void showOffline(String detail) {
        String html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{font-family:sans-serif;display:grid;place-items:center;height:100vh;margin:0;"
                + "background:#f4f6fb;color:#0f172a}div{text-align:center;padding:24px}a{display:inline-block;"
                + "margin-top:16px;padding:12px 22px;background:#2563eb;color:#fff;border-radius:10px;"
                + "text-decoration:none}</style>"
                + "<div><h2>无法连接</h2><p>" + detail + "</p><a href=\"" + HOME_URL + "\">重试</a></div>";
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    /** 扫描下载记录，更新顶部摘要（下载由系统 DownloadManager 在后台继续）。 */
    private void updateDownloadBar() {
        if (downloadBar == null) return;
        JSONArray items = DownloadStore.read(this);
        DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        int active = 0;
        int percent = -1;
        String runningName = null;

        for (int i = 0; i < items.length(); i += 1) {
            long id = items.optJSONObject(i) == null ? 0 : items.optJSONObject(i).optLong("id");
            if (id <= 0) continue;
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) continue;
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) continue;
                active += 1;
                if (status == DownloadManager.STATUS_RUNNING && runningName == null) {
                    long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    runningName = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                    percent = total > 0 ? (int) (done * 100 / total) : -1;
                }
            } catch (Exception ignored) {
                // 单条查询失败不影响其它任务
            }
        }

        if (active == 0) {
            downloadBar.setVisibility(View.GONE);
            return;
        }
        downloadBar.setVisibility(View.VISIBLE);
        if (active > 1) {
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgress(0);
            downloadLabel.setText(String.format("后台下载中 · %d 个任务", active));
        } else {
            downloadProgress.setIndeterminate(percent < 0);
            if (percent >= 0) downloadProgress.setProgress(percent);
            downloadLabel.setText(percent >= 0
                    ? String.format("后台下载中 %d%% · %s", percent, runningName == null ? "" : runningName)
                    : "后台下载中 · " + (runningName == null ? "" : runningName));
        }
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateDownloadBar();
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id <= 0) return;
                JSONArray records = DownloadStore.read(MainActivity.this);
                boolean owned = false;
                String name = "";
                String mime = "";
                for (int i = 0; i < records.length(); i++) {
                    if (records.optJSONObject(i) != null && records.optJSONObject(i).optLong("id") == id) {
                        owned = true;
                        name = records.optJSONObject(i).optString("name");
                        mime = records.optJSONObject(i).optString("mime");
                        break;
                    }
                }
                if (!owned) return;
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) return;
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    String receivedType = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                    if (receivedType != null && !receivedType.isEmpty()) mime = receivedType;
                    String message = DownloadPolicy.completedSuccessfully(status)
                            ? "文件下载完成，可在「下载」查看"
                            : "下载未完成：" + DownloadPolicy.statusText(status, reason);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (downloadReceiver == null) registerDownloadReceiver();
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        ticker.removeCallbacks(tick);
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
            downloadReceiver = null;
        }
        super.onPause();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.setDownloadListener(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
