package com.familylocation.admin;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebResourceError;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class AdminActivity extends Activity {
    private static final class ChallengeCancelledException extends Exception {
    }

    private static final String PREFS = "family_location_admin";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String KEY_PENDING_UPDATE_INSTALL_ID = "pending_update_install_id";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String DEFAULT_SERVER_URL = "";
    private static final int APP_VERSION_CODE = 44;
    private static final String APP_VERSION_NAME = "2.0.11";
    private static final String ADMIN_APK_NAME = "location-admin-release.apk";
    private static final String ADMIN_UPDATE_PATH = "";
    private static final String USER_AGENT = "loc-admin-app/" + APP_VERSION_NAME + " loc-app/" + APP_VERSION_NAME;
    private static final String TAG = "FamilyLocationAdmin";
    private static final long MAX_CACHE_BYTES = 50L * 1024L * 1024L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView statusView;
    private long updateDownloadId = -1L;
    private long pendingInstallDownloadId = -1L;
    private long installingDownloadId = -1L;
    private BroadcastReceiver updateReceiver;
    private final List<WebView> managedWebViews = new ArrayList<>();
    private volatile boolean activityForeground;
    private volatile int challengeGeneration;
    private volatile boolean challengeCancelled;
    private String loginDraftUsername = "";
    private String loginDraftPassword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        runStartupMaintenance();
        String serverUrl = storedServerUrl();
        if (serverUrl.isEmpty()) {
            serverUrl = readAssetServerUrl();
        }
        if (serverUrl.isEmpty()) {
            serverUrl = DEFAULT_SERVER_URL;
        }
        prefs().edit().putString(KEY_SERVER_URL, normalizeUrl(serverUrl)).apply();
        if (!prefs().getString(KEY_SESSION_COOKIE, "").trim().isEmpty()) {
            showStartupLoading();
            checkStoredSessionThenUpdate();
            return;
        }
        showLogin("");
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(colorSurface());
        window.setNavigationBarColor(colorSurface());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !isDarkMode()) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }


    private void showStartupLoading() {
        LinearLayout card = screen("后台");
        card.addView(infoPanel("正在检查登录状态和后台更新。"), blockParams(12));
        setScreen(card, true);
        setStatus("");
    }

    private void checkStoredSessionThenUpdate() {
        runBackground(() -> {
            try {
                checkAdminUpdateThenShowHome(ADMIN_UPDATE_PATH);
            } catch (Exception exception) {
                prefs().edit().remove(KEY_SESSION_COOKIE).apply();
                runUi(() -> showLogin("登录已失效，请重新登录。"));
            }
        });
    }

    private void showLogin(String message) {
        LinearLayout card = screen("后台登录");
        EditText username = input("管理员账号");
        EditText password = input("管理员密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button login = primaryButton("登录后台");
        login.setOnClickListener(view -> login(username.getText().toString(), password.getText().toString()));
        username.setText(loginDraftUsername);
        password.setText(loginDraftPassword);
        card.addView(username, blockParams(12));
        card.addView(password, blockParams(12));
        card.addView(login, blockParams(0));
        setScreen(card, true);
        setStatus(message);
    }

    private void login(String username, String password) {
        if (username.trim().isEmpty() || password.isEmpty()) {
            setStatus("请输入后台账号和密码。");
            return;
        }

        loginDraftUsername = username.trim();
        loginDraftPassword = password;
        setStatus("正在登录后台");
        runBackground(() -> {
            try {
                String challengeToken = completeAppChallenge("login");
                JSONObject payload = new JSONObject()
                    .put("username", username.trim())
                    .put("password", password)
                    .put("terms_accepted", true)
                    .put("cross_border_transfer_accepted", true)
                    .put("browser_fingerprint", deviceCookieValue())
                    .put("turnstile_token", challengeToken);
                JSONObject response = postJson("api/login.php", payload);
                String redirect = response.optString("redirect", "");
                if (redirect.isEmpty()) {
                    throw new IllegalStateException("该账号不是后台管理员。");
                }
                checkAdminUpdateThenShowHome(redirect);
            } catch (ChallengeCancelledException exception) {
                runUi(() -> setStatus(""));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private String completeAppChallenge(String purpose) throws Exception {
        JSONObject start = postJson("api/app_challenge.php", new JSONObject().put("purpose", purpose));
        if (!start.optBoolean("challenge_required", true)) {
            return start.optString("app_challenge_token", "");
        }

        String challengeId = start.optString("challenge_id", "");
        String challengeSecret = start.optString("challenge_secret", "");
        String challengeUrl = start.optString("challenge_url", "");
        if (challengeId.isEmpty() || challengeSecret.isEmpty() || challengeUrl.isEmpty()) {
            throw new IllegalStateException("Cloudflare 质询初始化失败。");
        }

        int generation = challengeGeneration + 1;
        challengeGeneration = generation;
        challengeCancelled = false;
        runUi(() -> showAppChallengeWebView(challengeUrl, () -> cancelAppChallenge(generation)));

        long deadline = System.currentTimeMillis() + 300000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2500L);
            if (isChallengeCancelled(generation)) {
                throw new ChallengeCancelledException();
            }
            JSONObject poll = getJson("api/app_challenge.php?id=" + urlEncode(challengeId) + "&secret=" + urlEncode(challengeSecret));
            if (poll.optBoolean("verified", false)) {
                String token = poll.optString("app_challenge_token", "");
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }

        throw new IllegalStateException("Cloudflare 质询超时，请重新登录。");
    }

    private void cancelAppChallenge(int generation) {
        challengeCancelled = true;
        if (challengeGeneration == generation) {
            challengeGeneration += 1;
        }
        destroyManagedWebViews();
        showLogin("");
    }

    private boolean isChallengeCancelled(int generation) {
        return challengeCancelled || challengeGeneration != generation;
    }

    private void checkAdminUpdateThenShowHome(String redirectPath) throws Exception {
        JSONObject update = getJson("api/admin_app_update.php?version_code=" + APP_VERSION_CODE);
        if (update.optBoolean("update_required", false)) {
            String apkUrl = update.optString("apk_url", "").trim();
            if (apkUrl.isEmpty()) {
                throw new IllegalStateException("后台更新包地址为空。");
            }
            runUi(() -> showAdminUpdate(update, redirectPath));
            return;
        }
        runUi(() -> showAdminHome(redirectPath));
    }

    private void checkAdminUpdateManually(String redirectPath) {
        setStatus("正在检查后台更新。");
        runBackground(() -> {
            try {
                JSONObject update = getJson("api/admin_app_update.php?version_code=" + APP_VERSION_CODE);
                if (update.optBoolean("update_required", false)) {
                    String apkUrl = update.optString("apk_url", "").trim();
                    if (apkUrl.isEmpty()) {
                        throw new IllegalStateException("后台更新包地址为空。");
                    }
                    runUi(() -> showAdminUpdate(update, redirectPath));
                    return;
                }
                runUi(() -> setStatus("后台 App 已是最新版本：" + APP_VERSION_NAME + "。"));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showAdminUpdate(JSONObject update, String redirectPath) {
        String versionName = update.optString("latest_version_name", "");
        int versionCode = update.optInt("latest_version_code", 0);
        boolean force = update.optBoolean("force_update", true);
        String apkUrl = update.optString("apk_url", "");
        LinearLayout card = screen("后台更新");
        String message = "检测到后台 App 新版本：" + versionName + " (" + versionCode + ")" +
            "\n更新包位于 private 目录，登录后台后自动下载。";
        if (force) {
            message += "\n当前版本需要先更新后再进入后台。";
        }
        card.addView(infoPanel(message), blockParams(14));
        Button download = primaryButton("重新下载后台更新");
        download.setOnClickListener(view -> downloadAdminUpdate(apkUrl));
        card.addView(download, blockParams(10));
        if (!force) {
            Button later = secondaryButton("稍后进入后台");
            later.setOnClickListener(view -> showAdminHome(redirectPath));
            card.addView(later, blockParams(0));
        }
        setScreen(card, true);
        setStatus("已登录，正在自动下载后台更新。");
        downloadAdminUpdate(apkUrl);
    }

    private void downloadAdminUpdate(String apkUrl) {
        try {
            String target = apkUrl == null ? "" : apkUrl.trim();
            if (target.isEmpty()) {
                throw new IllegalStateException("后台更新包地址为空。");
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(target));
            request.setTitle("位置后台更新");
            request.setDescription("正在下载 " + ADMIN_APK_NAME);
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            prepareUpdateApkFile();
            request.setDestinationUri(Uri.fromFile(updateApkFile()));
            request.addRequestHeader("User-Agent", USER_AGENT);
            String cookies = cookieHeader();
            if (!cookies.trim().isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("系统下载服务不可用。");
            }
            registerUpdateReceiver();
            updateDownloadId = manager.enqueue(request);
            pendingInstallDownloadId = -1L;
            prefs().edit().remove(KEY_PENDING_UPDATE_INSTALL_ID).apply();
            installingDownloadId = -1L;
            startUpdateInstallPolling(updateDownloadId, 0);
            setStatus("后台 APK 已开始下载，完成后会自动打开安装确认。");
        } catch (Exception exception) {
            setStatus("下载后台更新失败：" + exception.getMessage());
        }
    }

    private void registerUpdateReceiver() {
        if (updateReceiver != null) {
            return;
        }
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (downloadId == updateDownloadId) {
                    tryInstallDownloadedUpdate(downloadId);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    private void startUpdateInstallPolling(long downloadId, int attempts) {
        mainHandler.postDelayed(() -> {
            if (downloadId != updateDownloadId && downloadId != pendingInstallDownloadId) {
                return;
            }
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                setStatus("系统下载服务不可用。");
                return;
            }
            String status = downloadStatus(manager, downloadId);
            if ("success".equals(status)) {
                tryInstallDownloadedUpdate(downloadId);
                return;
            }
            if (status.startsWith("后台 APK 下载失败")) {
                setStatus(status);
                return;
            }
            if (attempts < 90) {
                startUpdateInstallPolling(downloadId, attempts + 1);
            }
        }, attempts == 0 ? 1000L : 2000L);
    }

    private void tryInstallDownloadedUpdate(long downloadId) {
        if (installingDownloadId == downloadId) {
            return;
        }
        installingDownloadId = downloadId;
        installDownloadedUpdate(downloadId);
    }

    private void installDownloadedUpdate(long downloadId) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("系统下载服务不可用。");
            }
            String status = downloadStatus(manager, downloadId);
            if (!"success".equals(status)) {
                installingDownloadId = -1L;
                if (status.startsWith("failed:")) {
                    throw new IllegalStateException("后台 APK 下载失败，错误码：" + status.substring("failed:".length()));
                }
                throw new IllegalStateException("后台 APK 仍在下载中，请稍后再试。");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                pendingInstallDownloadId = downloadId;
                prefs().edit().putLong(KEY_PENDING_UPDATE_INSTALL_ID, downloadId).apply();
                installingDownloadId = -1L;
                setStatus("后台 APK 已下载，请允许本应用安装未知应用后返回安装。");
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                return;
            }
            File apkFile = updateApkFile();
            if (!apkFile.isFile()) {
                throw new IllegalStateException("无法读取已下载 APK。");
            }
            Uri apkUri = updateApkUri();
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri(ADMIN_APK_NAME, apkUri));
            try {
                startActivity(install);
            } catch (Exception firstException) {
                Intent fallback = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                fallback.setClipData(ClipData.newRawUri(ADMIN_APK_NAME, apkUri));
                startActivity(fallback);
            }
            pendingInstallDownloadId = -1L;
            prefs().edit().remove(KEY_PENDING_UPDATE_INSTALL_ID).apply();
            setStatus("下载完成，请确认安装新版本后台。");
        } catch (Exception exception) {
            installingDownloadId = -1L;
            setStatus("打开后台安装失败：" + exception.getMessage());
        }
    }

    private void prepareUpdateApkFile() throws Exception {
        File apkFile = updateApkFile();
        File parent = apkFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建更新缓存目录。");
        }
        if (apkFile.exists() && !apkFile.delete()) {
            throw new IllegalStateException("无法清理旧更新包，请手动删除后重试。");
        }
    }

    private File updateApkFile() {
        File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            directory = new File(getFilesDir(), "updates");
        }
        return new File(directory, ADMIN_APK_NAME);
    }

    private Uri updateApkUri() {
        return Uri.parse("content://" + getPackageName() + ".apkprovider/" + ADMIN_APK_NAME);
    }

    private String downloadStatus(DownloadManager manager, long downloadId) {
        android.database.Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor == null || !cursor.moveToFirst()) {
                return "找不到后台更新下载记录。";
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                return "success";
            }
            if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                return "后台 APK 下载失败，错误码：" + reason;
            }
            if (status == DownloadManager.STATUS_PAUSED) {
                return "后台 APK 下载已暂停，请检查网络后重试。";
            }
            return "后台 APK 仍在下载中，请稍后再试。";
        } catch (Exception exception) {
            return "无法读取后台 APK 下载状态：" + exception.getMessage();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityForeground = true;
        long savedPendingInstall = prefs().getLong(KEY_PENDING_UPDATE_INSTALL_ID, -1L);
        if (pendingInstallDownloadId <= 0 && savedPendingInstall > 0) {
            pendingInstallDownloadId = savedPendingInstall;
        }
        if (pendingInstallDownloadId > 0 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls())) {
            long downloadId = pendingInstallDownloadId;
            pendingInstallDownloadId = -1L;
            installingDownloadId = -1L;
            installDownloadedUpdate(downloadId);
        }
    }

    @Override
    protected void onStop() {
        activityForeground = false;
        destroyManagedWebViews();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyManagedWebViews();
        if (updateReceiver != null) {
            try {
                unregisterReceiver(updateReceiver);
            } catch (Exception ignored) {
                // Receiver may already be unregistered by the system.
            }
            updateReceiver = null;
        }
        super.onDestroy();
    }



    private void runStartupMaintenance() {
        Thread maintenanceThread = new Thread(this::trimAppCaches, "loc-cache-maintenance");
        maintenanceThread.setDaemon(true);
        maintenanceThread.start();
    }

    private void trimAppCaches() {
        try {
            trimDirectoryToLimit(getCacheDir(), MAX_CACHE_BYTES);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                trimDirectoryToLimit(getCodeCacheDir(), MAX_CACHE_BYTES / 4L);
            }
        } catch (Exception exception) {
            android.util.Log.w(TAG, "Cache trim failed: " + exception.getMessage());
        }
    }

    private void trimDirectoryToLimit(File directory, long maxBytes) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        List<File> files = new ArrayList<>();
        collectFiles(directory, files);
        long totalBytes = 0L;
        for (File file : files) {
            totalBytes += Math.max(0L, file.length());
        }
        if (totalBytes <= maxBytes) {
            return;
        }
        files.sort((left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        for (File file : files) {
            if (totalBytes <= maxBytes) {
                break;
            }
            long length = Math.max(0L, file.length());
            if (file.delete()) {
                totalBytes -= length;
            }
        }
    }

    private void collectFiles(File file, List<File> files) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            files.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFiles(child, files);
        }
    }


    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            destroyManagedWebViews();
            runStartupMaintenance();
        }
    }

    @Override
    public void onLowMemory() {
        destroyManagedWebViews();
        trimAppCaches();
        super.onLowMemory();
    }

    private boolean canLoadForegroundWebView() {
        return activityForeground && !isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed());
    }

    private WebView managedWebView() {
        WebView webView = new WebView(this);
        managedWebViews.add(webView);
        return webView;
    }

    private void destroyManagedWebView(WebView webView) {
        if (webView == null) {
            return;
        }
        managedWebViews.remove(webView);
        try {
            ViewGroup parent = webView.getParent() instanceof ViewGroup ? (ViewGroup) webView.getParent() : null;
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
        } catch (Exception ignored) {
            // WebView may already be torn down by lifecycle.
        }
    }

    private void destroyManagedWebViews() {
        List<WebView> snapshot = new ArrayList<>(managedWebViews);
        for (WebView webView : snapshot) {
            destroyManagedWebView(webView);
        }
        managedWebViews.clear();
    }

    private boolean handleWebViewRendererGone(WebView webView, String message) {
        destroyManagedWebView(webView);
        if (message != null && !message.trim().isEmpty()) {
            setStatus(message);
        }
        return true;
    }


    private LinearLayout challengeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(cardBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }
        statusView = null;
        return card;
    }

    private void showAppChallengeWebView(String challengeUrl, Runnable onBack) {
        LinearLayout card = challengeCard();
        card.addView(infoPanel("请在下方完成 Cloudflare 质询，完成后后台 App 会自动继续。"), blockParams(10));
        if (!canLoadForegroundWebView()) {
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        WebView challengeView = managedWebView();
        WebSettings settings = challengeView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString() + " " + USER_AGENT);
        challengeView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return handleWebViewRendererGone(view, "");
            }
        });
        challengeView.loadUrl(challengeUrl);
        LinearLayout.LayoutParams params = blockParams(10);
        params.height = dp(120);
        card.addView(challengeView, params);
        Button back = secondaryButton("返回修改密码");
        back.setOnClickListener(view -> {
            if (onBack != null) {
                onBack.run();
            }
        });
        card.addView(back, blockParams(0));
        setScreen(card, true);
    }

    private void showAdminHome(String redirectPath) {
        LinearLayout card = screen("后台");
        card.addView(infoPanel("后台登录已验证，正在加载原生管理概览。\n\n服务端返回入口：" + redirectPath), blockParams(14));
        setScreen(card, true);
        setStatus("正在加载后台数据。");
        loadAdminSummary(redirectPath);
    }

    private void loadAdminSummary(String redirectPath) {
        runBackground(() -> {
            try {
                JSONObject response = getJson("api/admin_summary.php");
                runUi(() -> showAdminDashboard(response, redirectPath));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showAdminDashboard(JSONObject response, String redirectPath) {
        LinearLayout card = screen("后台概览");
        JSONObject stats = response.optJSONObject("stats");
        if (stats != null) {
            card.addView(sectionTitle("统计"), blockParams(8));
            card.addView(infoPanel(
                "用户：" + stats.optInt("users") +
                    " / 活跃：" + stats.optInt("active_users") +
                    " / 在线：" + stats.optInt("online_users") +
                    "\n家庭组：" + stats.optInt("groups") +
                    " / 定位记录：" + stats.optInt("locations") +
                    " / 未关闭工单：" + stats.optInt("open_tickets")
            ), blockParams(16));
        }

        JSONArray locations = response.optJSONArray("locations");
        card.addView(sectionTitle("最新定位"), blockParams(8));
        if (locations == null || locations.length() == 0) {
            card.addView(infoPanel("暂无最新定位。"), blockParams(16));
        } else {
            int count = Math.min(locations.length(), 8);
            for (int index = 0; index < count; index += 1) {
                JSONObject location = locations.optJSONObject(index);
                if (location == null) {
                    continue;
                }
                String name = displayName(location);
                String address = locationAddress(location);
                String line = name +
                    "\n组：" + location.optString("group_name", "") +
                    " / " + location.optString("role_label", "") +
                    "\n坐标：" + formatCoordinate(location.optDouble("latitude")) + ", " + formatCoordinate(location.optDouble("longitude")) +
                    "\n地址：" + (address.isEmpty() ? "未解析" : address) +
                    "\n时间：" + location.optString("updated_at", "");
                card.addView(infoPanel(line), blockParams(12));
            }
        }

        JSONArray users = response.optJSONArray("users");
        card.addView(sectionTitle("最近用户"), blockParams(8));
        if (users == null || users.length() == 0) {
            card.addView(infoPanel("暂无用户。"), blockParams(16));
        } else {
            int count = Math.min(users.length(), 8);
            for (int index = 0; index < count; index += 1) {
                JSONObject user = users.optJSONObject(index);
                if (user == null) {
                    continue;
                }
                String line = displayName(user) +
                    "\n状态：" + (user.optBoolean("is_active", false) ? "启用" : "停用") +
                    " / " + (user.optBoolean("online", false) ? "在线" : "离线") +
                    "\n最后在线：" + user.optString("last_seen_at", "无");
                card.addView(infoPanel(line), blockParams(12));
            }
        }

        JSONArray groups = response.optJSONArray("groups");
        card.addView(sectionTitle("家庭组"), blockParams(8));
        if (groups == null || groups.length() == 0) {
            card.addView(infoPanel("暂无家庭组。"), blockParams(16));
        } else {
            int count = Math.min(groups.length(), 8);
            for (int index = 0; index < count; index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group == null) {
                    continue;
                }
                String line = group.optString("display_name", group.optString("group_name", "")) +
                    "\n成员：" + group.optInt("member_count") +
                    " / 邀请码：" + group.optString("group_code", "未生成");
                card.addView(infoPanel(line), blockParams(12));
            }
        }

        Button usersManage = primaryButton("账号管理");
        usersManage.setOnClickListener(view -> showUserManager(response, redirectPath));
        Button announcementManage = secondaryButton("公告管理");
        announcementManage.setOnClickListener(view -> showAnnouncementManager(response, redirectPath));
        Button inviteManage = secondaryButton("邀请码管理");
        inviteManage.setOnClickListener(view -> showInviteManager(response, redirectPath));
        Button ticketManage = secondaryButton("工单管理");
        ticketManage.setOnClickListener(view -> showTicketManager(response, redirectPath));
        Button checkUpdate = secondaryButton("检查后台更新");
        checkUpdate.setOnClickListener(view -> checkAdminUpdateManually(redirectPath));
        Button refresh = secondaryButton("刷新后台数据");
        refresh.setOnClickListener(view -> showAdminHome(redirectPath));
        Button relogin = secondaryButton("重新登录");
        relogin.setOnClickListener(view -> showLogin(""));
        card.addView(usersManage, blockParams(10));
        card.addView(buttonRow(announcementManage, inviteManage), blockParams(10));
        card.addView(buttonRow(ticketManage, checkUpdate), blockParams(10));
        card.addView(refresh, blockParams(10));
        card.addView(relogin, blockParams(0));
        setScreen(card, false);
        setStatus("后台数据已加载：" + response.optString("server_time", ""));
    }


    private void showAnnouncementManager(JSONObject response, String redirectPath) {
        LinearLayout card = screen("公告管理");
        JSONObject announcement = response.optJSONObject("announcement");
        EditText title = input("公告标题");
        EditText body = input("公告内容");
        body.setSingleLine(false);
        body.setMinLines(5);
        CheckBox active = new CheckBox(this);
        active.setText("启用公告");
        active.setTextColor(colorText());
        if (announcement != null) {
            title.setText(announcement.optString("title", ""));
            body.setText(announcement.optString("body", ""));
            active.setChecked(announcement.optBoolean("is_active", false));
            card.addView(infoPanel("当前版本：" + announcement.optInt("version", 1) + "\n更新时间：" + announcement.optString("updated_at", "")), blockParams(12));
        }
        Button save = primaryButton("保存公告");
        save.setOnClickListener(view -> {
            JSONObject payload = adminPayload("save_announcement");
            putJson(payload, "title", title.getText().toString());
            putJson(payload, "body", body.getText().toString());
            putJson(payload, "is_active", active.isChecked());
            postAdminAction(payload, redirectPath);
        });
        Button back = secondaryButton("返回概览");
        back.setOnClickListener(view -> showAdminDashboard(response, redirectPath));
        card.addView(title, blockParams(10));
        card.addView(body, blockParams(10));
        card.addView(active, blockParams(12));
        card.addView(save, blockParams(10));
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private void showInviteManager(JSONObject response, String redirectPath) {
        LinearLayout card = screen("邀请码管理");
        EditText code = input("邀请码：留空自动生成");
        EditText note = input("备注");
        EditText maxUses = input("最大使用次数");
        maxUses.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxUses.setText("1");
        CheckBox groupCreate = new CheckBox(this);
        groupCreate.setText("创建家庭组邀请码");
        groupCreate.setTextColor(colorText());
        CheckBox allowOwner = new CheckBox(this);
        allowOwner.setText("注册人成为家庭组管理员");
        allowOwner.setTextColor(colorText());
        Button add = primaryButton("添加邀请码");
        add.setOnClickListener(view -> {
            JSONObject payload = adminPayload("add_invite_code");
            putJson(payload, "code", code.getText().toString());
            putJson(payload, "note", note.getText().toString());
            putJson(payload, "invite_type", groupCreate.isChecked() ? "group_create" : "invite");
            putJson(payload, "allow_group_owner", allowOwner.isChecked());
            putJson(payload, "max_uses", parseInt(maxUses.getText().toString(), 1));
            postAdminAction(payload, redirectPath);
        });
        Button back = secondaryButton("返回概览");
        back.setOnClickListener(view -> showAdminDashboard(response, redirectPath));
        card.addView(code, blockParams(8));
        card.addView(note, blockParams(8));
        card.addView(maxUses, blockParams(8));
        card.addView(groupCreate, blockParams(6));
        card.addView(allowOwner, blockParams(10));
        card.addView(add, blockParams(16));

        JSONArray invites = response.optJSONArray("invites");
        card.addView(sectionTitle("最近邀请码"), blockParams(8));
        if (invites == null || invites.length() == 0) {
            card.addView(infoPanel("暂无邀请码。"), blockParams(12));
        } else {
            int count = Math.min(invites.length(), 20);
            for (int index = 0; index < count; index += 1) {
                JSONObject invite = invites.optJSONObject(index);
                if (invite == null) {
                    continue;
                }
                int inviteId = invite.optInt("id", 0);
                boolean enabled = invite.optBoolean("is_active", false);
                String line = invite.optString("code", "") + " / " + invite.optString("invite_type_label", "")
                    + "\n状态：" + (enabled ? "启用" : "停用")
                    + " / 使用：" + invite.optInt("used_count") + " / " + invite.optInt("max_uses")
                    + "\n备注：" + invite.optString("note", "")
                    + "\n绑定家庭组：" + (invite.optString("assigned_group_name", "").isEmpty() ? "无" : invite.optString("assigned_group_name", ""));
                card.addView(infoPanel(line), blockParams(6));
                Button toggle = secondaryButton(enabled ? "停用邀请码" : "启用邀请码");
                toggle.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("toggle_invite_code");
                    putJson(payload, "invite_id", inviteId);
                    putJson(payload, "next", !enabled);
                    postAdminAction(payload, redirectPath);
                });
                card.addView(toggle, blockParams(10));
            }
        }
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private void showUserManager(JSONObject response, String redirectPath) {
        LinearLayout card = screen("账号管理");
        JSONArray users = response.optJSONArray("users");
        if (users == null || users.length() == 0) {
            card.addView(infoPanel("暂无账号。"), blockParams(12));
        } else {
            int count = Math.min(users.length(), 30);
            for (int index = 0; index < count; index += 1) {
                JSONObject user = users.optJSONObject(index);
                if (user == null) {
                    continue;
                }
                int userId = user.optInt("id", 0);
                boolean active = user.optBoolean("is_active", false);
                EditText username = input("账号");
                username.setText(user.optString("username", ""));
                EditText displayNameInput = input("显示名");
                displayNameInput.setText(user.optString("display_name", ""));
                EditText interval = input("上报间隔秒");
                interval.setInputType(InputType.TYPE_CLASS_NUMBER);
                interval.setText(String.valueOf(Math.max(30, user.optInt("report_interval_seconds", 300))));
                CheckBox debug = new CheckBox(this);
                debug.setText("调试模式");
                debug.setTextColor(colorText());
                debug.setChecked(user.optBoolean("debug_mode", false));
                EditText newPassword = input("新密码：留空不重置");
                newPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                card.addView(sectionTitle(displayName(user)), blockParams(6));
                card.addView(infoPanel("状态：" + (active ? "启用" : "停用") + " / " + (user.optBoolean("online", false) ? "在线" : "离线")
                    + "\n家庭组：" + user.optString("group_name", "") + " / " + user.optString("role_label", "")
                    + "\n最后在线：" + user.optString("last_seen_at", "无")), blockParams(8));
                card.addView(username, blockParams(6));
                card.addView(displayNameInput, blockParams(6));
                card.addView(interval, blockParams(6));
                card.addView(debug, blockParams(6));
                Button save = secondaryButton("保存账号信息");
                save.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("update_user");
                    putJson(payload, "user_id", userId);
                    putJson(payload, "username", username.getText().toString());
                    putJson(payload, "display_name", displayNameInput.getText().toString());
                    putJson(payload, "report_interval_seconds", parseInt(interval.getText().toString(), 300));
                    putJson(payload, "debug_mode", debug.isChecked());
                    postAdminAction(payload, redirectPath);
                });
                Button toggle = secondaryButton(active ? "停用账号" : "启用账号");
                toggle.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("toggle_user");
                    putJson(payload, "user_id", userId);
                    putJson(payload, "next", !active);
                    postAdminAction(payload, redirectPath);
                });
                Button reset = secondaryButton("重置密码");
                reset.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("reset_password");
                    putJson(payload, "user_id", userId);
                    putJson(payload, "new_password", newPassword.getText().toString());
                    postAdminAction(payload, redirectPath);
                });
                card.addView(buttonRow(save, toggle), blockParams(8));
                card.addView(newPassword, blockParams(6));
                card.addView(reset, blockParams(16));
            }
        }
        Button back = secondaryButton("返回概览");
        back.setOnClickListener(view -> showAdminDashboard(response, redirectPath));
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private void showTicketManager(JSONObject response, String redirectPath) {
        LinearLayout card = screen("工单管理");
        JSONArray tickets = response.optJSONArray("tickets");
        if (tickets == null || tickets.length() == 0) {
            card.addView(infoPanel("暂无工单。"), blockParams(12));
        } else {
            int count = Math.min(tickets.length(), 30);
            for (int index = 0; index < count; index += 1) {
                JSONObject ticket = tickets.optJSONObject(index);
                if (ticket == null) {
                    continue;
                }
                int ticketId = ticket.optInt("id", 0);
                boolean closed = "closed".equals(ticket.optString("status", ""));
                card.addView(sectionTitle("#" + ticketId + " " + ticket.optString("subject", "工单")), blockParams(6));
                card.addView(infoPanel("用户：" + displayName(ticket) + " / " + ticket.optString("group_name", "")
                    + "\n状态：" + ticket.optString("status_label", "")
                    + "\n更新时间：" + ticket.optString("updated_at", "")
                    + "\n最后消息：" + ticket.optString("last_message", "")), blockParams(8));
                EditText reply = input("回复内容");
                reply.setSingleLine(false);
                reply.setMinLines(3);
                Button send = secondaryButton("回复工单");
                send.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("reply_ticket");
                    putJson(payload, "ticket_id", ticketId);
                    putJson(payload, "reply", reply.getText().toString());
                    postAdminAction(payload, redirectPath);
                });
                Button status = secondaryButton(closed ? "重新打开" : "关闭工单");
                status.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("update_ticket_status");
                    putJson(payload, "ticket_id", ticketId);
                    putJson(payload, "status", closed ? "open" : "closed");
                    postAdminAction(payload, redirectPath);
                });
                card.addView(reply, blockParams(6));
                card.addView(buttonRow(send, status), blockParams(16));
            }
        }
        Button back = secondaryButton("返回概览");
        back.setOnClickListener(view -> showAdminDashboard(response, redirectPath));
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private void postAdminAction(JSONObject payload, String redirectPath) {
        setStatus("正在提交后台操作。");
        runBackground(() -> {
            try {
                JSONObject response = postJson("api/admin_manage.php", payload);
                String message = response.optString("message", "后台操作已完成。");
                runUi(() -> {
                    setStatus(message);
                    loadAdminSummary(redirectPath);
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private JSONObject adminPayload(String action) {
        JSONObject payload = new JSONObject();
        putJson(payload, "action", action);
        return payload;
    }

    private void putJson(JSONObject payload, String key, Object value) {
        try {
            payload.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String displayName(JSONObject item) {
        String displayName = item.optString("display_name", "").trim();
        String username = item.optString("username", "").trim();
        if (!displayName.isEmpty() && !username.isEmpty()) {
            return displayName + "（" + username + "）";
        }
        if (!displayName.isEmpty()) {
            return displayName;
        }
        return username.isEmpty() ? "未命名" : username;
    }

    private String locationAddress(JSONObject location) {
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        if (diagnostics == null) {
            return "";
        }

        String preferred = diagnostics.optString("preferred_address", "").trim();
        if (!preferred.isEmpty()) {
            return preferred;
        }

        JSONArray sources = diagnostics.optJSONArray("sources");
        if (sources == null) {
            return "";
        }

        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source != null && "gps".equals(source.optString("type", ""))) {
                String address = source.optString("address", "").trim();
                if (!address.isEmpty()) {
                    return address;
                }
            }
        }

        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null) {
                continue;
            }
            String address = source.optString("address", "").trim();
            if (!address.isEmpty()) {
                return address;
            }
        }
        return "";
    }

    private JSONObject getJson(String endpoint) throws Exception {
        String target = serverUrl() + endpoint;
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cookie", cookieHeader());

        int status = connection.getResponseCode();
        captureCookies(connection);
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readResponse(stream);
        connection.disconnect();
        assertJsonResponse(text);
        JSONObject response = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (status < 200 || status >= 300 || !response.optBoolean("ok", false)) {
            throw new IllegalStateException(response.optString("message", "请求失败。"));
        }
        return response;
    }

    private JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        String target = serverUrl() + endpoint;
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cookie", cookieHeader());
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int status = connection.getResponseCode();
        captureCookies(connection);
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readResponse(stream);
        connection.disconnect();
        assertJsonResponse(text);
        JSONObject response = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (status < 200 || status >= 300 || !response.optBoolean("ok", false)) {
            throw new IllegalStateException(response.optString("message", "请求失败。"));
        }
        return response;
    }

    private void captureCookies(HttpURLConnection connection) {
        List<String> values = connection.getHeaderFields().get("Set-Cookie");
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> cookies = new ArrayList<>();
        String existing = prefs().getString(KEY_SESSION_COOKIE, "");
        if (existing != null && !existing.trim().isEmpty()) {
            for (String part : existing.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    cookies.add(trimmed);
                }
            }
        }
        for (String value : values) {
            String cookie = value.split(";", 2)[0].trim();
            if (cookie.isEmpty()) {
                continue;
            }
            String name = cookie.split("=", 2)[0];
            for (int index = cookies.size() - 1; index >= 0; index -= 1) {
                if (cookies.get(index).startsWith(name + "=")) {
                    cookies.remove(index);
                }
            }
            cookies.add(cookie);
        }
        prefs().edit().putString(KEY_SESSION_COOKIE, joinCookies(cookies)).apply();
    }

    private String cookieHeader() {
        String session = prefs().getString(KEY_SESSION_COOKIE, "");
        String device = DEVICE_COOKIE_NAME + "=" + deviceCookieValue();
        if (session == null || session.trim().isEmpty()) {
            return device;
        }
        return session + "; " + device;
    }

    private String joinCookies(List<String> cookies) {
        StringBuilder builder = new StringBuilder();
        for (String cookie : cookies) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie);
        }
        return builder.toString();
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

    private LinearLayout screen(String titleText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(cardBackground());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colorText());
        card.addView(title, blockParams(8));
        statusView = body("");
        statusView.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusView.setBackground(pillBackground());
        statusView.setVisibility(View.GONE);
        card.addView(statusView, blockParams(16));
        return card;
    }

    private void setScreen(LinearLayout card) {
        setScreen(card, false);
    }

    private void setScreen(LinearLayout card, boolean center) {
        content = card;
        if (center) {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(dp(16), dp(16), dp(16), dp(16));
            root.setBackgroundColor(colorSurface());
            root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(colorSurface());
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private LinearLayout buttonRow(Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int gap = dp(8);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        leftParams.setMargins(0, 0, gap, 0);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rightParams.setMargins(gap, 0, 0, 0);
        row.addView(left, leftParams);
        row.addView(right, rightParams);
        return row;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(15);
        view.setLineSpacing(0, 1.15f);
        view.setTextColor(colorMuted());
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(colorText());
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView infoPanel(String text) {
        TextView view = body(text);
        view.setTextColor(colorText());
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(panelBackground());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            view.setElevation(dp(1));
        }
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setTextSize(16);
        view.setTextColor(colorText());
        view.setHintTextColor(colorMuted());
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(buttonBackground(Color.rgb(110, 45, 45)));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorText());
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(buttonBackground(isDarkMode() ? Color.rgb(50, 37, 37) : Color.rgb(237, 228, 228)));
        return button;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(29, 17, 17) : Color.WHITE);
        drawable.setCornerRadius(dp(18));
        return drawable;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(43, 24, 24) : Color.rgb(252, 248, 248));
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), isDarkMode() ? Color.rgb(72, 45, 45) : Color.rgb(231, 217, 217));
        return drawable;
    }

    private GradientDrawable pillBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(63, 29, 29) : Color.rgb(246, 226, 226));
        drawable.setCornerRadius(dp(999));
        return drawable;
    }

    private RippleDrawable buttonBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(45, 255, 255, 255)), drawable, null);
    }

    private LinearLayout.LayoutParams blockParams(int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(bottomMarginDp));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int colorSurface() {
        return isDarkMode() ? Color.rgb(31, 3, 3) : Color.rgb(243, 238, 238);
    }

    private int colorText() {
        return isDarkMode() ? Color.rgb(241, 225, 225) : Color.rgb(34, 23, 23);
    }

    private int colorMuted() {
        return isDarkMode() ? Color.rgb(174, 152, 152) : Color.rgb(111, 92, 92);
    }

    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private String formatCoordinate(double value) {
        return String.format(java.util.Locale.US, "%.6f", value);
    }

    private void setStatus(String message) {
        if (statusView != null) {
            String value = message == null ? "" : message.trim();
            statusView.setText(value);
            statusView.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void runBackground(Runnable runnable) {
        new Thread(runnable, "loc-admin-native").start();
    }

    private void runUi(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String storedServerUrl() {
        return normalizeUrl(prefs().getString(KEY_SERVER_URL, ""));
    }

    private String readAssetServerUrl() {
        try (InputStream stream = getAssets().open("server-url.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return normalizeUrl(reader.readLine());
        } catch (Exception ignored) {
            return "";
        }
    }

    private String serverUrl() {
        return normalizeUrl(prefs().getString(KEY_SERVER_URL, ""));
    }

    private String normalizeUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url : url + "/";
    }

    private void assertJsonResponse(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || value.startsWith("{") || value.startsWith("[")) {
            return;
        }
        String lower = value.toLowerCase(java.util.Locale.US);
        if (lower.contains("cloudflare") || lower.contains("cf-chl") || lower.contains("turnstile")) {
            throw new IllegalStateException("Cloudflare 质询拦截了 App API。原生 App 不能执行网页质询，请在 Cloudflare WAF 对 /api/* 跳过 Managed Challenge，或允许 loc-app User-Agent 后再试。");
        }
        throw new IllegalStateException("服务端返回了非 JSON 响应，请检查服务器地址和反代规则。");
    }

    private String urlEncode(String value) throws Exception {
        return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private String deviceCookieValue() {
        String value = prefs().getString(KEY_DEVICE_COOKIE, "");
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
        prefs().edit().putString(KEY_DEVICE_COOKIE, value).apply();
        return value;
    }
}
