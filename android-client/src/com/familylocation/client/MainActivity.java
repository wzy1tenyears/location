package com.familylocation.client;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_NOTIFICATION = 1002;
    private static final int REQUEST_BACKGROUND_LOCATION = 1003;
    private static final int APP_VERSION_CODE = 32;
    private static final String APP_VERSION_NAME = "1.2.6";
    private static final String PREFS = "family_location";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_GROUP_NAME = "group_name";
    private static final String KEY_GUARDIAN_CONTINUOUS_REPORTING = "guardian_continuous_reporting";
    private static final String KEY_GROUP_SESSIONS = "group_sessions_json";
    private static final String KEY_REPORT_INTERVAL_SECONDS = "report_interval_seconds";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String KEY_LOCATION_PERMISSION_REQUESTED = "location_permission_requested";
    private static final String KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested";
    private static final String KEY_BACKGROUND_LOCATION_PROMPT_SHOWN = "background_location_prompt_shown";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String TAG = "FamilyLocation";

    private WebView webView;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private long updateDownloadId = -1;
    private BroadcastReceiver updateReceiver;
    private TextView updateMessageView;
    private Button updateActionButton;
    private String pendingUpdateApkUrl = "";
    private int pendingUpdateVersionCode = 0;
    private boolean updateInstallPromptShown;
    private boolean updateDownloadComplete;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateDownloadPoller = new Runnable() {
        @Override
        public void run() {
            pollUpdateDownload();
        }
    };
    private boolean backgroundLocationPromptShown;
    private boolean batteryOptimizationPromptShown;
    private boolean locationPermissionRequestInFlight;
    private volatile boolean activityDestroyed;
    private int updateCheckGeneration;
    private final Runnable keepAliveSyncRunnable = () -> {
        checkPermissionIntegrity();
        syncKeepAliveService();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityDestroyed = false;
        configureWindow();
        scheduleKeepAliveServiceSync(1200);

        String serverUrl = getStoredServerUrl();
        if (serverUrl.isEmpty()) {
            serverUrl = readAssetServerUrl();
        }

        if (serverUrl.isEmpty()) {
            showServerSetup();
            return;
        }

        checkUpdateThenOpen(serverUrl);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(colorSurface());
        window.setNavigationBarColor(colorSurface());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = window.getDecorView().getSystemUiVisibility();
            if (!isDarkMode()) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private String getStoredServerUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return normalizeUrl(prefs.getString(KEY_SERVER_URL, ""));
    }

    private String readAssetServerUrl() {
        try (InputStream stream = getAssets().open("server-url.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return normalizeUrl(reader.readLine());
        } catch (Exception ignored) {
            return "";
        }
    }

    private void showServerSetup() {
        LinearLayout root = createScreenRoot();
        LinearLayout card = createScreenCard();

        TextView title = createTitle("位置");
        TextView description = createBodyText("填写 HTTPS 服务器地址后继续使用。");

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://example.com/");
        input.setTextSize(16);
        styleTextInput(input);

        Button button = new Button(this);
        button.setText("保存并打开");
        stylePrimaryButton(button);

        TextView message = new TextView(this);
        message.setTextColor(colorError());
        message.setTextSize(14);

        card.addView(title, blockParams(10));
        card.addView(description, blockParams(18));
        card.addView(input, blockParams(14));
        card.addView(button, blockParams(12));
        card.addView(message, blockParams(0));
        root.addView(card, cardParams());
        setContentView(root);

        button.setOnClickListener(view -> {
            String url = normalizeUrl(input.getText().toString());
            if (url.isEmpty() || !url.startsWith("https://")) {
                message.setText("请输入 HTTPS 服务器地址。");
                return;
            }

            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_URL, url)
                .apply();

            checkUpdateThenOpen(url);
        });
    }

    private void checkUpdateThenOpen(String url) {
        if (!isActivityUsable()) {
            return;
        }

        final int generation = ++updateCheckGeneration;
        showLoading("正在检查更新");

        new Thread(() -> {
            try {
                JSONObject update = fetchUpdateInfo(url);
                boolean updateRequired = update.optBoolean("update_required", false);
                boolean forceUpdate = update.optBoolean("force_update", true);
                String apkUrl = update.optString("apk_url", "");
                String versionName = update.optString("latest_version_name", "");
                int versionCode = update.optInt("latest_version_code", 0);

                if (updateRequired && forceUpdate && !apkUrl.isEmpty()) {
                    runOnUiThread(() -> {
                        if (isCurrentUpdateCheck(generation)) {
                            showUpdateRequired(versionName, versionCode, apkUrl);
                        }
                    });
                    return;
                }
            } catch (Exception exception) {
                Log.w(TAG, "Update check failed: " + exception.getMessage());
            }

            runOnUiThread(() -> {
                if (isCurrentUpdateCheck(generation)) {
                    openWebApp(url);
                }
            });
        }).start();
    }

    private boolean isCurrentUpdateCheck(int generation) {
        return isActivityUsable() && generation == updateCheckGeneration;
    }

    private boolean isActivityUsable() {
        return !activityDestroyed && !isFinishing() && !isDestroyed();
    }

    private JSONObject fetchUpdateInfo(String serverUrl) throws Exception {
        String url = normalizeUrl(serverUrl) + "api/app_update.php?version_code=" + APP_VERSION_CODE;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "loc-app/" + APP_VERSION_NAME);
        connection.setRequestProperty("Accept", "application/json");

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = readResponse(stream);
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status);
        }

        return new JSONObject(response);
    }

    private String readResponse(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private void showLoading(String text) {
        if (!isActivityUsable()) {
            return;
        }

        LinearLayout root = createScreenRoot();
        LinearLayout card = createScreenCard();
        TextView title = createTitle("位置");
        TextView message = createBodyText(text);
        message.setGravity(Gravity.CENTER_HORIZONTAL);

        card.addView(title, blockParams(10));
        card.addView(message, blockParams(0));
        root.addView(card, cardParams());
        setContentView(root);
    }

    private void showUpdateRequired(String versionName, int versionCode, String apkUrl) {
        if (!isActivityUsable()) {
            return;
        }

        pendingUpdateApkUrl = apkUrl == null ? "" : apkUrl;
        pendingUpdateVersionCode = versionCode;
        updateDownloadId = -1;
        updateInstallPromptShown = false;
        updateDownloadComplete = false;

        LinearLayout root = createScreenRoot();
        LinearLayout card = createScreenCard();

        TextView title = createTitle("需要更新");
        TextView message = createBodyText("请安装新版位置 " + versionName + " 后继续使用。下载完成后会自动打开安装界面。");
        updateMessageView = message;

        Button button = new Button(this);
        button.setText("下载更新");
        stylePrimaryButton(button);
        button.setOnClickListener(view -> requestUpdateDownload());
        updateActionButton = button;

        Button browserButton = new Button(this);
        browserButton.setText("浏览器下载");
        styleSecondaryButton(browserButton);
        browserButton.setOnClickListener(view -> openUpdateDownloadInBrowser());

        card.addView(title, blockParams(10));
        card.addView(message, blockParams(18));
        card.addView(button, blockParams(10));
        card.addView(browserButton, blockParams(0));
        root.addView(card, cardParams());
        setContentView(root);
    }

    private void requestUpdateDownload() {
        if (updateDownloadComplete && updateDownloadId > 0) {
            installDownloadedApk(updateDownloadId, false);
            return;
        }

        startUpdateDownload(true);
    }

    private void openUpdateDownloadInBrowser() {
        if (pendingUpdateApkUrl.isEmpty()) {
            updateUpdateUi("下载地址为空，请稍后重试。", "重新下载", true);
            return;
        }

        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(cacheBustedUrl(pendingUpdateApkUrl, pendingUpdateVersionCode)));
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browser);
        } catch (Exception exception) {
            Log.w(TAG, "Open update url failed: " + exception.getMessage());
            updateUpdateUi("无法打开浏览器：" + exception.getMessage(), "重新下载", true);
        }
    }

    private void startUpdateDownload(boolean userRequested) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                return;
            }

            if (updateDownloadId > 0) {
                int status = queryDownloadStatus(manager, updateDownloadId);
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    updateDownloadComplete = true;
                    updateUpdateUi("下载完成。可以重新打开安装界面。", "重新安装", true);
                    installDownloadedApk(updateDownloadId, false);
                    return;
                }

                if (isActiveDownloadStatus(status)) {
                    if (userRequested) {
                        confirmRestartUpdateDownload(manager);
                    }
                    return;
                }

                manager.remove(updateDownloadId);
                updateDownloadId = -1;
            }

            File targetFile = getUpdateApkFile(pendingUpdateVersionCode);
            if (targetFile.exists() && !targetFile.delete()) {
                Log.w(TAG, "Could not delete old update apk: " + targetFile.getAbsolutePath());
            }

            registerUpdateReceiver();
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(cacheBustedUrl(pendingUpdateApkUrl, pendingUpdateVersionCode)));
            request.setMimeType("application/vnd.android.package-archive");
            request.addRequestHeader("User-Agent", "loc-app/" + APP_VERSION_NAME);
            request.addRequestHeader("Cache-Control", "no-cache");
            request.addRequestHeader("Pragma", "no-cache");
            request.setTitle("位置更新");
            request.setDescription("正在下载新版位置");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, targetFile.getName());
            updateDownloadId = manager.enqueue(request);
            updateDownloadComplete = false;
            updateInstallPromptShown = false;
            updateUpdateUi("正在下载新版位置。下载完成后会自动打开一次安装界面。", "正在下载", true);
            scheduleUpdateDownloadPoll();
        } catch (Exception exception) {
            Log.w(TAG, "Update download failed: " + exception.getMessage());
            updateUpdateUi("下载启动失败：" + exception.getMessage(), "重新下载", true);
        }
    }

    private int queryDownloadStatus(DownloadManager manager, long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return -1;
            }

            int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return statusColumn >= 0 ? cursor.getInt(statusColumn) : -1;
        } catch (Exception exception) {
            Log.w(TAG, "Query update download failed: " + exception.getMessage());
            return -1;
        }
    }

    private boolean isActiveDownloadStatus(int status) {
        return status == DownloadManager.STATUS_PENDING
            || status == DownloadManager.STATUS_RUNNING
            || status == DownloadManager.STATUS_PAUSED;
    }

    private void confirmRestartUpdateDownload(DownloadManager manager) {
        showAppPrompt(
            "重新下载更新？",
            "更新包正在下载。确认后会取消当前下载，并重新下载新版安装包。",
            "重新下载",
            "取消",
            () -> {
                try {
                    manager.remove(updateDownloadId);
                } catch (Exception exception) {
                    Log.w(TAG, "Remove update download failed: " + exception.getMessage());
                }
                updateDownloadId = -1;
                updateDownloadComplete = false;
                updateInstallPromptShown = false;
                startUpdateDownload(false);
            }
        );
    }

    private void registerUpdateReceiver() {
        if (updateReceiver != null) {
            return;
        }

        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (completedId == updateDownloadId) {
                    updateDownloadComplete = true;
                    updateHandler.removeCallbacks(updateDownloadPoller);
                    updateUpdateUi("下载完成，正在打开安装界面。", "重新安装", true);
                    installDownloadedApk(completedId, true);
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    private void scheduleUpdateDownloadPoll() {
        updateHandler.removeCallbacks(updateDownloadPoller);
        updateHandler.postDelayed(updateDownloadPoller, 1200);
    }

    private void pollUpdateDownload() {
        if (updateDownloadId <= 0 || updateDownloadComplete) {
            return;
        }

        try {
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                return;
            }

            int status = queryDownloadStatus(manager, updateDownloadId);
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                updateDownloadComplete = true;
                updateUpdateUi("下载完成，正在打开安装界面。", "重新安装", true);
                installDownloadedApk(updateDownloadId, true);
                return;
            }

            if (status == DownloadManager.STATUS_FAILED) {
                updateDownloadId = -1;
                updateDownloadComplete = false;
                updateInstallPromptShown = false;
                updateUpdateUi("下载失败，请重新下载。", "重新下载", true);
                return;
            }

            if (isActiveDownloadStatus(status)) {
                scheduleUpdateDownloadPoll();
            }
        } catch (Exception exception) {
            Log.w(TAG, "Poll update download failed: " + exception.getMessage());
        }
    }

    private void installDownloadedApk(long downloadId, boolean automatic) {
        if (!isActivityUsable()) {
            return;
        }

        try {
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                return;
            }

            String localPath = "";
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
            try (Cursor cursor = manager.query(query)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    return;
                }

                int statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (statusColumn >= 0 && cursor.getInt(statusColumn) != DownloadManager.STATUS_SUCCESSFUL) {
                    return;
                }

                int localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                if (localUriColumn >= 0) {
                    String localUri = cursor.getString(localUriColumn);
                    if (localUri != null && !localUri.isEmpty()) {
                        localPath = Uri.parse(localUri).getPath();
                    }
                }
            }

            Uri uri = manager.getUriForDownloadedFile(downloadId);
            if (uri == null) {
                updateUpdateUi("下载完成，但无法读取安装包。请点击“重新安装”再试。", "重新安装", true);
                return;
            }

            String validationError = validateDownloadedApk(localPath);
            if (!validationError.isEmpty()) {
                updateDownloadComplete = false;
                updateInstallPromptShown = false;
                try {
                    manager.remove(downloadId);
                } catch (Exception ignored) {
                    // The invalid download may already be gone.
                }
                updateDownloadId = -1;
                updateUpdateUi(validationError, "重新下载", true);
                return;
            }

            if (automatic && updateInstallPromptShown) {
                updateUpdateUi("下载完成。可以重新打开安装界面。", "重新安装", true);
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                updateInstallPromptShown = true;
                updateUpdateUi("请允许安装未知来源应用，返回后点击“重新安装”。", "重新安装", true);
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
                return;
            }

            updateInstallPromptShown = true;
            updateDownloadComplete = true;
            updateUpdateUi("下载完成。可以重新打开安装界面。", "重新安装", true);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception exception) {
            Log.w(TAG, "Install update failed: " + exception.getMessage());
            updateUpdateUi("安装界面打开失败：" + exception.getMessage(), "重新安装", true);
        }
    }

    private void updateUpdateUi(String message, String buttonText, boolean enabled) {
        if (!isActivityUsable()) {
            return;
        }

        if (updateMessageView != null) {
            updateMessageView.setText(message);
        }

        if (updateActionButton != null) {
            updateActionButton.setText(buttonText);
            updateActionButton.setEnabled(enabled);
        }
    }

    private File getUpdateApkFile(int versionCode) {
        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            downloadsDir = getFilesDir();
        }

        String suffix = versionCode > 0 ? String.valueOf(versionCode) : "latest";
        return new File(downloadsDir, "location-update-v" + suffix + ".apk");
    }

    private String cacheBustedUrl(String url, int versionCode) {
        String separator = url != null && url.contains("?") ? "&" : "?";
        return (url == null ? "" : url) + separator
            + "v=" + Math.max(0, versionCode)
            + "&t=" + System.currentTimeMillis();
    }

    @SuppressWarnings("deprecation")
    private String validateDownloadedApk(String localPath) {
        File apkFile = null;
        if (localPath != null && !localPath.isEmpty()) {
            apkFile = new File(localPath);
        }

        if (apkFile == null || !apkFile.isFile()) {
            File expectedFile = getUpdateApkFile(pendingUpdateVersionCode);
            if (expectedFile.isFile()) {
                apkFile = expectedFile;
            }
        }

        if (apkFile == null || !apkFile.isFile()) {
            return "下载包不存在，请重新下载。";
        }

        PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
        if (packageInfo == null) {
            return "下载包校验失败，请重新下载。";
        }

        if (!getPackageName().equals(packageInfo.packageName)) {
            return "下载包不是位置 App，请重新下载。";
        }

        long archiveVersionCode = archiveVersionCode(packageInfo);
        if (archiveVersionCode <= APP_VERSION_CODE) {
            return "下载到的是旧版本，请重新下载。";
        }

        if (pendingUpdateVersionCode > 0 && archiveVersionCode != pendingUpdateVersionCode) {
            return "下载包版本不正确，请重新下载。";
        }

        return "";
    }

    private long archiveVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }

        return packageInfo.versionCode;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openWebApp(String url) {
        if (!isActivityUsable()) {
            return;
        }

        String normalizedUrl = normalizeUrl(url);
        Uri allowedOrigin = Uri.parse(normalizedUrl);
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(false);
        }

        webView = new WebView(this);
        webView.setBackgroundColor(colorSurface());
        webView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(webView);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, false);
        }
        ensureDeviceCookie(normalizedUrl);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        configureWebViewTheme(settings);
        String userAgent = settings.getUserAgentString();
        if (userAgent == null || !userAgent.contains("loc-app")) {
            settings.setUserAgentString((userAgent == null ? "" : userAgent) + " loc-app/" + APP_VERSION_NAME);
        }
        webView.addJavascriptInterface(new LocationBridge(), "LocationBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String targetUrl) {
                return shouldBlockNavigation(Uri.parse(targetUrl), allowedOrigin);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldBlockNavigation(request == null ? null : request.getUrl(), allowedOrigin);
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                flushCookiesAsync();
                if (loadedUrl != null && loadedUrl.contains("admin_logout=1")) {
                    view.clearHistory();
                }
                Log.i(TAG, "Loaded: " + loadedUrl);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    String description = error == null ? "Unknown error" : String.valueOf(error.getDescription());
                    Log.e(TAG, "Main frame load error: " + description);
                    showWebError(description);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                boolean didCrash = detail != null && detail.didCrash();
                Log.e(TAG, "WebView renderer gone, didCrash=" + didCrash);
                recoverWebViewAfterRendererGone(view);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }

                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                if (locationPermissionRequestInFlight) {
                    return;
                }

                if (!requestForegroundLocationPermissionIfNeeded()) {
                    pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
                    pendingGeoCallback = null;
                    pendingGeoOrigin = null;
                }
            }
        });

        webView.loadUrl(normalizedUrl);
    }

    private boolean shouldBlockNavigation(Uri uri, Uri allowedOrigin) {
        if (!isHttpUrl(uri)) {
            return true;
        }

        if (!isSameOrigin(uri, allowedOrigin)) {
            Log.w(TAG, "Blocked external navigation: " + uri);
            return true;
        }

        return false;
    }

    private boolean isHttpUrl(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }

        String scheme = uri.getScheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    private boolean isSameOrigin(Uri target, Uri allowedOrigin) {
        if (target == null || allowedOrigin == null) {
            return false;
        }

        String targetScheme = target.getScheme();
        String allowedScheme = allowedOrigin.getScheme();
        String targetHost = target.getHost();
        String allowedHost = allowedOrigin.getHost();
        if (targetScheme == null || allowedScheme == null || targetHost == null || allowedHost == null) {
            return false;
        }

        int targetPort = target.getPort();
        int allowedPort = allowedOrigin.getPort();
        return targetScheme.equalsIgnoreCase(allowedScheme)
            && targetHost.equalsIgnoreCase(allowedHost)
            && targetPort == allowedPort;
    }

    private void ensureDeviceCookie(String serverUrl) {
        String value = deviceCookieValue();
        StringBuilder cookie = new StringBuilder()
            .append(DEVICE_COOKIE_NAME)
            .append("=")
            .append(value)
            .append("; Path=/; Max-Age=315360000; SameSite=Lax; HttpOnly");

        if (serverUrl != null && serverUrl.toLowerCase().startsWith("https://")) {
            cookie.append("; Secure");
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setCookie(serverUrl, cookie.toString());
        flushCookiesAsync();
    }

    private void flushCookiesAsync() {
        new Thread(() -> {
            try {
                CookieManager.getInstance().flush();
            } catch (Exception exception) {
                Log.w(TAG, "Cookie flush failed: " + exception.getMessage());
            }
        }, "位置Cookie保存").start();
    }

    private String deviceCookieValue() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_DEVICE_COOKIE, "");
        if (value != null && value.matches("^[a-f0-9]{64}$")) {
            return value;
        }

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            builder.append(String.format("%02x", item & 0xff));
        }

        value = builder.toString();
        prefs.edit().putString(KEY_DEVICE_COOKIE, value).apply();
        return value;
    }

    @SuppressWarnings("deprecation")
    private void configureWebViewTheme(WebSettings settings) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_AUTO);
        }
    }

    public class LocationBridge {
        @JavascriptInterface
        public void setSessionState(String role, boolean guardianContinuousReporting, int reportIntervalSeconds, String groupName, String groupsJson) {
            int normalizedInterval = Math.max(60, reportIntervalSeconds);
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String normalizedGroupName = groupName == null ? "" : groupName.trim();
            String normalizedRole = normalizeRole(role);
            String normalizedGroupsJson = normalizeGroupSessionsJson(
                prefs,
                groupsJson,
                normalizedGroupName,
                normalizedRole,
                guardianContinuousReporting
            );

            prefs.edit()
                .putString(KEY_USER_ROLE, normalizedRole)
                .putString(KEY_GROUP_NAME, normalizedGroupName)
                .putBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, guardianContinuousReporting)
                .putString(KEY_GROUP_SESSIONS, normalizedGroupsJson)
                .putInt(KEY_REPORT_INTERVAL_SECONDS, normalizedInterval)
                .apply();
            runOnUiThread(() -> {
                checkPermissionIntegrity(true);
                scheduleKeepAliveServiceSync(300);
            });
        }

        @JavascriptInterface
        public void setSession(String role, boolean guardianContinuousReporting, int reportIntervalSeconds, String groupName) {
            setSessionState(role, guardianContinuousReporting, reportIntervalSeconds, groupName, "");
        }

        @JavascriptInterface
        public void clearSession() {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_USER_ROLE)
                .remove(KEY_GROUP_NAME)
                .remove(KEY_GROUP_SESSIONS)
                .putBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false)
                .apply();
            runOnUiThread(() -> syncKeepAliveService());
        }

        @JavascriptInterface
        public boolean getGuardianContinuousReporting() {
            return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false);
        }

        @JavascriptInterface
        public boolean getGuardianContinuousReportingForGroup(String groupName) {
            return guardianContinuousReportingForGroup(getSharedPreferences(PREFS, Context.MODE_PRIVATE), groupName);
        }
    }

    private String normalizeGroupSessionsJson(SharedPreferences prefs, String groupsJson, String currentGroupName, String currentRole, boolean currentContinuous) {
        JSONArray sessions = parseGroupSessions(groupsJson);
        if (sessions.length() == 0) {
            sessions = parseGroupSessions(prefs.getString(KEY_GROUP_SESSIONS, ""));
        }

        if (!currentGroupName.isEmpty()) {
            boolean found = false;
            for (int index = 0; index < sessions.length(); index += 1) {
                JSONObject session = sessions.optJSONObject(index);
                if (session == null || !currentGroupName.equals(session.optString("group_name", ""))) {
                    continue;
                }

                found = true;
                try {
                    session.put("role", normalizeRole(currentRole));
                    session.put("continuous", currentContinuous);
                } catch (Exception ignored) {
                    // Keep the best-effort session list.
                }
                break;
            }

            if (!found) {
                JSONObject session = new JSONObject();
                try {
                    session.put("group_name", currentGroupName);
                    session.put("role", normalizeRole(currentRole));
                    session.put("continuous", currentContinuous);
                    sessions.put(session);
                } catch (Exception ignored) {
                    // Keep the best-effort session list.
                }
            }
        }

        return sessions.toString();
    }

    private JSONArray parseGroupSessions(String groupsJson) {
        try {
            return new JSONArray(groupsJson == null ? "" : groupsJson);
        } catch (Exception exception) {
            return new JSONArray();
        }
    }

    private String normalizeRole(String role) {
        String value = role == null ? "" : role.trim();
        return "parent".equals(value) ? "monitor" : value;
    }

    private boolean guardianContinuousReportingForGroup(SharedPreferences prefs, String groupName) {
        String normalizedGroupName = groupName == null ? "" : groupName.trim();
        if (normalizedGroupName.isEmpty()) {
            return false;
        }

        JSONArray sessions = parseGroupSessions(prefs.getString(KEY_GROUP_SESSIONS, ""));
        for (int index = 0; index < sessions.length(); index += 1) {
            JSONObject session = sessions.optJSONObject(index);
            if (session == null || !normalizedGroupName.equals(session.optString("group_name", ""))) {
                continue;
            }

            return "guardian".equals(session.optString("role", "")) && session.optBoolean("continuous", false);
        }

        String currentGroupName = prefs.getString(KEY_GROUP_NAME, "");
        return normalizedGroupName.equals(currentGroupName)
            && "guardian".equals(prefs.getString(KEY_USER_ROLE, ""))
            && prefs.getBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false);
    }

    private boolean hasActiveReportGroup(SharedPreferences prefs) {
        JSONArray sessions = parseGroupSessions(prefs.getString(KEY_GROUP_SESSIONS, ""));
        for (int index = 0; index < sessions.length(); index += 1) {
            JSONObject session = sessions.optJSONObject(index);
            if (sessionShouldReport(session)) {
                return true;
            }
        }

        String groupName = prefs.getString(KEY_GROUP_NAME, "");
        if (groupName == null || groupName.trim().isEmpty()) {
            return false;
        }

        String role = normalizeRole(prefs.getString(KEY_USER_ROLE, ""));
        return "monitor".equals(role)
            || ("guardian".equals(role) && prefs.getBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false));
    }

    private boolean sessionShouldReport(JSONObject session) {
        if (session == null || session.optString("group_name", "").trim().isEmpty()) {
            return false;
        }

        String role = normalizeRole(session.optString("role", ""));
        return "monitor".equals(role)
            || ("guardian".equals(role) && session.optBoolean("continuous", false));
    }

    private void syncKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        if (!canRunNativeReportService()) {
            try {
                stopService(intent);
            } catch (Exception exception) {
                Log.w(TAG, "Keep-alive service stop failed: " + exception.getMessage());
            }
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Keep-alive service start failed: " + exception.getMessage());
        }
    }

    private void scheduleKeepAliveServiceSync(long delayMs) {
        updateHandler.removeCallbacks(keepAliveSyncRunnable);
        updateHandler.postDelayed(keepAliveSyncRunnable, Math.max(0, delayMs));
    }

    private boolean shouldNativeReport() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return hasActiveReportGroup(prefs);
    }

    private boolean canRunNativeReportService() {
        return shouldNativeReport()
            && hasForegroundLocationPermission()
            && hasBackgroundLocationPermission()
            && hasNotificationPermission();
    }

    private boolean hasForegroundLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionIntegrity() {
        checkPermissionIntegrity(false);
    }

    private void checkPermissionIntegrity(boolean loginCheck) {
        boolean nativeReport = shouldNativeReport();
        if (!nativeReport && !loginCheck) {
            return;
        }

        if (!hasForegroundLocationPermission()) {
            requestForegroundLocationPermissionIfNeeded();
            return;
        }

        if (!nativeReport) {
            return;
        }

        if (!hasNotificationPermission()) {
            requestNotificationPermission();
            return;
        }

        if (!hasBackgroundLocationPermission()) {
            requestBackgroundLocationPermissionIfNeeded();
            return;
        }

        requestBatteryOptimizationPermission();
    }

    private boolean requestForegroundLocationPermissionIfNeeded() {
        if (hasForegroundLocationPermission()) {
            return false;
        }

        if (locationPermissionRequestInFlight) {
            return true;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_LOCATION_PERMISSION_REQUESTED, false)) {
            return false;
        }

        prefs.edit().putBoolean(KEY_LOCATION_PERMISSION_REQUESTED, true).apply();
        locationPermissionRequestInFlight = true;
        requestPermissions(new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQUEST_LOCATION);
        return true;
    }

    private boolean requestBackgroundLocationPermissionIfNeeded() {
        if (hasBackgroundLocationPermission()) {
            return false;
        }

        if (!hasForegroundLocationPermission()) {
            return false;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_BACKGROUND_LOCATION_PROMPT_SHOWN, false)) {
            return false;
        }
        prefs.edit().putBoolean(KEY_BACKGROUND_LOCATION_PROMPT_SHOWN, true).apply();

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(new String[] {
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }, REQUEST_BACKGROUND_LOCATION);
            return true;
        }

        backgroundLocationPromptShown = true;

        showAppPrompt(
            "允许后台定位",
            "持续上报需要允许“始终允许”定位。打开设置后，请在权限里把定位改为始终允许。",
            "去设置",
            "稍后",
            () -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        );
        return true;
    }

    private boolean requestNotificationPermission() {
        if (hasNotificationPermission()) {
            return false;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) {
            return false;
        }

        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply();
        requestPermissions(new String[] {
            Manifest.permission.POST_NOTIFICATIONS
        }, REQUEST_NOTIFICATION);
        return true;
    }

    private void requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }

        if (batteryOptimizationPromptShown) {
            return;
        }
        batteryOptimizationPromptShown = true;

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception exception) {
            Log.w(TAG, "Battery optimization request failed: " + exception.getMessage());
        }
    }

    private void showWebError(String detail) {
        if (!isActivityUsable() || webView == null) {
            return;
        }

        String html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>body{margin:0;font-family:sans-serif;background:#eef3f1;color:#172220;display:grid;min-height:100vh;place-items:center}"
            + "main{padding:24px;max-width:420px}h1{font-size:22px;margin:0 0 12px}p{line-height:1.5;color:#64736f}"
            + "button{border:0;border-radius:8px;background:#0d5f54;color:white;font:inherit;font-weight:700;padding:12px 16px}</style></head>"
            + "<body><main><h1>页面加载失败</h1><p>" + escapeHtml(detail) + "</p>"
            + "<button onclick=\"location.reload()\">重试</button></main></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void showAppPrompt(String titleText, String messageText, String positiveText, String negativeText, Runnable onPositive) {
        if (!isActivityUsable()) {
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        GradientDrawable overlay = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            isDarkMode()
                ? new int[] { Color.argb(232, 9, 11, 15), Color.argb(218, 32, 35, 42) }
                : new int[] { Color.argb(226, 243, 245, 248), Color.argb(218, 210, 215, 222) }
        );
        root.setBackground(overlay);
        root.setAlpha(0f);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        LinearLayout card = createScreenCard();
        TextView title = createTitle(titleText);
        title.setGravity(Gravity.LEFT);
        TextView message = createBodyText(messageText);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);

        Button positive = new Button(this);
        positive.setText(positiveText);
        stylePrimaryButton(positive);
        positive.setOnClickListener(view -> {
            dialog.dismiss();
            if (onPositive != null) {
                onPositive.run();
            }
        });

        Button negative = new Button(this);
        negative.setText(negativeText);
        styleSecondaryButton(negative);
        negative.setOnClickListener(view -> dialog.dismiss());

        actions.addView(positive, blockParams(10));
        actions.addView(negative, blockParams(0));

        card.addView(title, blockParams(12));
        card.addView(message, blockParams(18));
        card.addView(actions, blockParams(0));

        FrameLayout.LayoutParams cardLayoutParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        );
        cardLayoutParams.setMargins(0, 0, 0, 0);
        root.addView(card, cardLayoutParams);

        dialog.setContentView(root);
        dialog.setCancelable(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        root.animate().alpha(1f).setDuration(200).start();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION) {
            locationPermissionRequestInFlight = false;
            if (pendingGeoCallback != null && pendingGeoOrigin != null) {
                boolean granted = false;
                for (int result : grantResults) {
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        granted = true;
                        break;
                    }
                }
                pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
        }

        if (requestCode == REQUEST_LOCATION
            || requestCode == REQUEST_BACKGROUND_LOCATION
            || requestCode == REQUEST_NOTIFICATION) {
            scheduleKeepAliveServiceSync(300);
            return;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryInstallCompletedUpdate();
        scheduleKeepAliveServiceSync(800);
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        updateCheckGeneration += 1;
        updateHandler.removeCallbacksAndMessages(null);
        if (updateReceiver != null) {
            try {
                unregisterReceiver(updateReceiver);
            } catch (Exception ignored) {
                // Receiver may already be unregistered by the system.
            }
            updateReceiver = null;
        }
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {
                // WebView may already be torn down by the renderer process.
            }
            webView = null;
        }

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        super.onBackPressed();
    }

    private void tryInstallCompletedUpdate() {
        if (!isActivityUsable()) {
            return;
        }

        if (updateDownloadId <= 0 || !updateDownloadComplete || updateInstallPromptShown) {
            return;
        }

        try {
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null && queryDownloadStatus(manager, updateDownloadId) == DownloadManager.STATUS_SUCCESSFUL) {
                installDownloadedApk(updateDownloadId, true);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Resume update install failed: " + exception.getMessage());
        }
    }

    private void recoverWebViewAfterRendererGone(WebView goneWebView) {
        if (goneWebView != null) {
            try {
                ViewParent parent = goneWebView.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(goneWebView);
                }
                goneWebView.destroy();
            } catch (Exception ignored) {
                // Renderer crash may already have invalidated the view.
            }
        }

        if (goneWebView == webView) {
            webView = null;
        }

        if (!isActivityUsable()) {
            return;
        }

        showLoading("正在恢复页面");
        String serverUrl = getStoredServerUrl();
        if (serverUrl.isEmpty()) {
            serverUrl = readAssetServerUrl();
        }
        final String recoveredUrl = serverUrl;
        updateHandler.postDelayed(() -> {
            if (isActivityUsable() && !recoveredUrl.isEmpty()) {
                openWebApp(recoveredUrl);
            }
        }, 300L);
    }

    private LinearLayout createScreenRoot() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(colorSurface());
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return root;
    }

    private LinearLayout createScreenCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(24));
        card.setBackground(roundedDrawable(colorSurfaceContainer(), dp(28)));
        card.setElevation(dp(2));
        return card;
    }

    private TextView createTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(colorOnSurface());
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        return title;
    }

    private TextView createBodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(colorOnSurfaceVariant());
        view.setTextSize(15);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private LinearLayout.LayoutParams blockParams(int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(bottomMarginDp));
        return params;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 0);
        return params;
    }

    private void stylePrimaryButton(Button button) {
        button.setAllCaps(false);
        button.setTextColor(colorOnPrimary());
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(52));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(rippleDrawable(colorPrimary(), dp(26)));
    }

    private void styleSecondaryButton(Button button) {
        button.setAllCaps(false);
        button.setTextColor(colorPrimary());
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(52));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(strokedDrawable(colorSurfaceContainer(), dp(26), colorOutline(), dp(1)));
    }

    private void styleTextInput(EditText input) {
        input.setTextColor(colorOnSurface());
        input.setHintTextColor(colorOnSurfaceVariant());
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setMinHeight(dp(56));
        input.setBackground(strokedDrawable(colorSurfaceContainer(), dp(16), colorOutline(), dp(1)));
    }

    private GradientDrawable roundedDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable strokedDrawable(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = roundedDrawable(color, radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private RippleDrawable rippleDrawable(int color, int radius) {
        return new RippleDrawable(
            ColorStateList.valueOf(colorRipple()),
            roundedDrawable(color, radius),
            null
        );
    }

    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int colorSurface() {
        return isDarkMode() ? Color.rgb(17, 24, 22) : Color.rgb(238, 243, 241);
    }

    private int colorSurfaceContainer() {
        return isDarkMode() ? Color.rgb(25, 34, 32) : Color.rgb(255, 255, 255);
    }

    private int colorOnSurface() {
        return isDarkMode() ? Color.rgb(238, 246, 243) : Color.rgb(23, 34, 32);
    }

    private int colorOnSurfaceVariant() {
        return isDarkMode() ? Color.rgb(167, 184, 179) : Color.rgb(100, 115, 111);
    }

    private int colorPrimary() {
        return isDarkMode() ? Color.rgb(54, 182, 156) : Color.rgb(13, 95, 84);
    }

    private int colorOnPrimary() {
        return isDarkMode() ? Color.rgb(3, 31, 26) : Color.WHITE;
    }

    private int colorOutline() {
        return isDarkMode() ? Color.rgb(46, 61, 57) : Color.rgb(217, 226, 223);
    }

    private int colorRipple() {
        return isDarkMode() ? Color.argb(48, 139, 230, 211) : Color.argb(38, 13, 95, 84);
    }

    private int colorError() {
        return isDarkMode() ? Color.rgb(255, 143, 147) : Color.rgb(166, 61, 64);
    }

    private String normalizeUrl(String value) {
        if (value == null) {
            return "";
        }

        String url = value.trim();
        if (url.isEmpty()) {
            return "";
        }

        if (!url.endsWith("/")) {
            url += "/";
        }

        return url;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
