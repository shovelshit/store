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
import android.graphics.drawable.GradientDrawable;
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
import android.webkit.URLUtil;
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

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3));
        barParams.gravity = Gravity.TOP;
        root.addView(progress, barParams);

        // 右下角「下载」入口：打开应用内下载管理
        TextView downloads = new TextView(this);
        downloads.setText("↓ 下载");
        downloads.setTextSize(13);
        downloads.setTextColor(Color.WHITE);
        downloads.setGravity(Gravity.CENTER);
        downloads.setElevation(dp(6));
        GradientDrawable fab = new GradientDrawable();
        fab.setColor(Color.parseColor("#2563EB"));
        fab.setCornerRadius(dp(22));
        downloads.setBackground(fab);
        downloads.setOnClickListener(view -> startActivity(new Intent(this, DownloadsActivity.class)));
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(92), dp(44));
        fabParams.gravity = Gravity.BOTTOM | Gravity.END;
        fabParams.setMargins(0, 0, dp(18), dp(18));
        root.addView(downloads, fabParams);

        // 后台下载进度条（有任务时显示，点击进入下载管理）
        downloadBar = new LinearLayout(this);
        downloadBar.setOrientation(LinearLayout.VERTICAL);
        downloadBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        downloadBar.setElevation(dp(4));
        downloadBar.setVisibility(View.GONE);
        GradientDrawable barBackground = new GradientDrawable();
        barBackground.setColor(Color.WHITE);
        barBackground.setCornerRadius(dp(12));
        barBackground.setStroke(dp(1), Color.parseColor("#E5E9F2"));
        downloadBar.setBackground(barBackground);
        downloadBar.setOnClickListener(view -> startActivity(new Intent(this, DownloadsActivity.class)));

        downloadLabel = new TextView(this);
        downloadLabel.setTextSize(12);
        downloadLabel.setTextColor(Color.parseColor("#0F172A"));
        downloadLabel.setSingleLine(true);
        downloadBar.addView(downloadLabel);

        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5));
        progressParams.topMargin = dp(6);
        downloadBar.addView(downloadProgress, progressParams);

        FrameLayout.LayoutParams downloadBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        downloadBarParams.gravity = Gravity.BOTTOM;
        downloadBarParams.setMargins(dp(14), 0, dp(14), dp(74));
        root.addView(downloadBar, downloadBarParams);

        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " ApkStore/1.1");

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
                startDownload(url, contentDisposition);
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
            startDownload(url, null);
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

    private void startDownload(String url, String contentDisposition) {
        // Android 9 及以下写公共下载目录需要存储权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingUrl = url;
            pendingDisposition = contentDisposition;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        enqueueDownload(url, contentDisposition);
    }

    private void enqueueDownload(String url, String contentDisposition) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition,
                    "application/vnd.android.package-archive");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType("application/vnd.android.package-archive");
            request.setTitle(fileName);
            request.setDescription("正在下载安装包");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = manager.enqueue(request);
            DownloadStore.add(this, id, fileName);
            Toast.makeText(this, "已开始下载，可在「下载」里查看进度", Toast.LENGTH_SHORT).show();
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
        pendingUrl = null;
        pendingDisposition = null;
        if (granted && url != null) {
            enqueueDownload(url, disposition);
        } else {
            Toast.makeText(this, "没有存储权限，无法保存安装包", Toast.LENGTH_LONG).show();
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

    /** 扫描下载记录，更新底部常驻进度条（下载由系统 DownloadManager 在后台继续） */
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
                if (id > 0) Toast.makeText(MainActivity.this, "下载完成，可在「下载」里安装", Toast.LENGTH_SHORT).show();
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
