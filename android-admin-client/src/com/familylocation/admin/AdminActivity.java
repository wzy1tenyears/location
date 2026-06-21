package com.familylocation.admin;

import android.app.Activity;
import android.app.Dialog;
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
import android.graphics.drawable.ColorDrawable;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.DisplayCutout;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.webkit.CookieManager;
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
    private static final String KEY_ACTIVE_UPDATE_DOWNLOAD_ID = "active_update_download_id";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String DEFAULT_SERVER_URL = "";
    private static final int APP_VERSION_CODE = 58;
    private static final String APP_VERSION_NAME = "2.1.0";
    private static final String ADMIN_APK_NAME = "location-admin-release.apk";
    private static final String ADMIN_UPDATE_PATH = "";
    private static final String USER_AGENT = "loc-admin-app/" + APP_VERSION_NAME + " loc-app/" + APP_VERSION_NAME;
    private static final String TAG = "FamilyLocationAdmin";
    private static final long MAX_CACHE_BYTES = 50L * 1024L * 1024L;
    private static final int ADMIN_TAB_OVERVIEW = 0;
    private static final int ADMIN_TAB_USERS = 1;
    private static final int ADMIN_TAB_GROUPS = 2;
    private static final int ADMIN_TAB_TICKETS = 3;
    private static final int ADMIN_TAB_LOGS = 4;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView statusView;
    private JSONObject lastAdminSummary;
    private String currentRedirectPath = ADMIN_UPDATE_PATH;
    private int currentAdminTab = ADMIN_TAB_OVERVIEW;
    private boolean adminLoggedIn;
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
        try {
            startApp();
        } catch (Throwable throwable) {
            showStartupCrash(throwable);
        }
    }

    private void startApp() {
        configureWindow();
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

    private void showStartupCrash(Throwable throwable) {
        Log.e(TAG, "Startup failed", throwable);
        String message = exceptionMessage(throwable);
        try {
            LinearLayout card = screen("启动失败");
            card.addView(body("后台 App 启动时遇到异常，请截图发给开发者。"), blockParams(8));
            TextView detail = body(message);
            detail.setTextColor(colorText());
            detail.setPadding(dp(12), dp(10), dp(12), dp(10));
            detail.setBackground(panelBackground());
            card.addView(detail, blockParams(0));
            setScreen(card, true);
        } catch (Throwable fallback) {
            TextView fallbackView = new TextView(this);
            fallbackView.setText("启动失败\n" + message);
            fallbackView.setTextColor(Color.BLACK);
            fallbackView.setPadding(24, 24, 24, 24);
            setContentView(fallbackView);
        }
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
        adminLoggedIn = false;
        lastAdminSummary = null;
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
            prefs().edit()
                .putLong(KEY_ACTIVE_UPDATE_DOWNLOAD_ID, updateDownloadId)
                .remove(KEY_PENDING_UPDATE_INSTALL_ID)
                .apply();
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
                prefs().edit().remove(KEY_ACTIVE_UPDATE_DOWNLOAD_ID).apply();
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

    private void safelyInstallPendingUpdate(long downloadId) {
        try {
            installDownloadedUpdate(downloadId);
        } catch (Exception exception) {
            pendingInstallDownloadId = -1L;
            installingDownloadId = -1L;
            prefs().edit()
                .remove(KEY_PENDING_UPDATE_INSTALL_ID)
                .remove(KEY_ACTIVE_UPDATE_DOWNLOAD_ID)
                .apply();
            setStatus("自动拉起后台安装失败：" + exception.getMessage());
        }
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
                prefs().edit()
                    .putLong(KEY_PENDING_UPDATE_INSTALL_ID, downloadId)
                    .remove(KEY_ACTIVE_UPDATE_DOWNLOAD_ID)
                    .apply();
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
            prefs().edit()
                .remove(KEY_PENDING_UPDATE_INSTALL_ID)
                .remove(KEY_ACTIVE_UPDATE_DOWNLOAD_ID)
                .apply();
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
        long savedActiveDownload = prefs().getLong(KEY_ACTIVE_UPDATE_DOWNLOAD_ID, -1L);
        if (updateDownloadId <= 0 && savedActiveDownload > 0) {
            updateDownloadId = savedActiveDownload;
            registerUpdateReceiver();
            startUpdateInstallPolling(savedActiveDownload, 0);
        }
        long savedPendingInstall = prefs().getLong(KEY_PENDING_UPDATE_INSTALL_ID, -1L);
        if (pendingInstallDownloadId <= 0 && savedPendingInstall > 0) {
            pendingInstallDownloadId = savedPendingInstall;
        }
        if (pendingInstallDownloadId > 0 && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls())) {
            long downloadId = pendingInstallDownloadId;
            pendingInstallDownloadId = -1L;
            installingDownloadId = -1L;
            safelyInstallPendingUpdate(downloadId);
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
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(cardBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }
        statusView = null;
        return card;
    }

    private TextView challengePrompt(String text) {
        TextView prompt = new TextView(this);
        prompt.setText(text);
        prompt.setTextColor(colorText());
        prompt.setTextSize(14);
        prompt.setGravity(Gravity.CENTER);
        prompt.setLineSpacing(dp(2), 1.0f);
        return prompt;
    }

    private void showAppChallengeWebView(String challengeUrl, Runnable onBack) {
        LinearLayout card = challengeCard();
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
        challengeView.setBackgroundColor(Color.TRANSPARENT);
        challengeView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        syncCookiesToWebView(challengeUrl);
        challengeView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                captureWebViewCookies(url);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return handleWebViewRendererGone(view, "");
            }
        });
        card.addView(challengePrompt("请完成 Cloudflare 验证，完成后会自动继续后台登录。"), blockParams(8));
        challengeView.loadUrl(challengeUrl);
        LinearLayout.LayoutParams params = blockParams(8);
        params.height = dp(96);
        card.addView(challengeView, params);
        Button back = secondaryButton("返回后台登录 / 修改密码");
        back.setOnClickListener(view -> {
            if (onBack != null) {
                onBack.run();
            }
        });
        card.addView(back, blockParams(0));
        setScreen(card, true);
    }

    private void showAdminHome(String redirectPath) {
        adminLoggedIn = true;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
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
                runUi(() -> {
                    lastAdminSummary = response;
                    currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
                    showCurrentAdminTab(response, currentRedirectPath);
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showCurrentAdminTab(JSONObject response, String redirectPath) {
        if (response == null) {
            loadAdminSummary(redirectPath);
            return;
        }
        switch (currentAdminTab) {
            case ADMIN_TAB_USERS:
                showUserManager(response, redirectPath);
                return;
            case ADMIN_TAB_GROUPS:
                showGroupManager(response, redirectPath);
                return;
            case ADMIN_TAB_TICKETS:
                showTicketManager(response, redirectPath);
                return;
            case ADMIN_TAB_LOGS:
                showLogManager(response, redirectPath);
                return;
            case ADMIN_TAB_OVERVIEW:
            default:
                showAdminDashboard(response, redirectPath);
        }
    }

    private void switchAdminTab(int tab) {
        currentAdminTab = tab;
        if (lastAdminSummary == null) {
            showAdminHome(currentRedirectPath);
            return;
        }
        showCurrentAdminTab(lastAdminSummary, currentRedirectPath);
    }

    private void showAdminDashboard(JSONObject response, String redirectPath) {
        currentAdminTab = ADMIN_TAB_OVERVIEW;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
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

        card.addView(sectionTitle("在线用户"), blockParams(8));
        card.addView(compactInfoPanel(onlineUserSummary(response.optJSONArray("users"))), blockParams(16));

        card.addView(sectionTitle("系统工具"), blockParams(8));
        Button securityManage = secondaryButton("安全策略");
        securityManage.setOnClickListener(view -> showSecurityManager(response, redirectPath));
        Button announcementManage = secondaryButton("公告管理");
        announcementManage.setOnClickListener(view -> showAnnouncementManager(response, redirectPath));
        Button inviteManage = secondaryButton("邀请码管理");
        inviteManage.setOnClickListener(view -> showInviteManager(response, redirectPath));
        Button checkUpdate = secondaryButton("检查后台更新");
        checkUpdate.setOnClickListener(view -> checkAdminUpdateManually(redirectPath));
        Button refresh = secondaryButton("刷新后台数据");
        refresh.setOnClickListener(view -> showAdminHome(redirectPath));
        Button relogin = secondaryButton("重新登录");
        relogin.setOnClickListener(view -> showLogin(""));
        card.addView(securityManage, blockParams(10));
        card.addView(buttonRow(announcementManage, inviteManage), blockParams(10));
        card.addView(checkUpdate, blockParams(10));
        card.addView(refresh, blockParams(10));
        card.addView(relogin, blockParams(0));
        setScreen(card, false);
        setStatus("后台数据已加载：" + response.optString("server_time", ""));
    }



    private void showSecurityManager(JSONObject response, String redirectPath) {
        LinearLayout card = screen("安全策略");
        JSONObject settings = response.optJSONObject("security_settings");
        if (settings == null) {
            settings = new JSONObject();
        }
        CheckBox banRoot = policyCheckBox("拦截 Root 环境", settings.optBoolean("ban_root_users", false));
        CheckBox banAdb = policyCheckBox("拦截 ADB 调试", settings.optBoolean("ban_adb_users", false));
        CheckBox banMock = policyCheckBox("拦截模拟定位", settings.optBoolean("ban_fake_location_users", false));
        CheckBox banAccessibility = policyCheckBox("拦截无障碍风险", settings.optBoolean("ban_accessibility_users", false));
        CheckBox banCapture = policyCheckBox("拦截抓包环境", settings.optBoolean("ban_packet_capture_users", false));
        CheckBox banSuspiciousPackages = policyCheckBox("拦截可疑包名", settings.optBoolean("ban_suspicious_packages_users", false));
        Button save = primaryButton("保存安全策略");
        save.setOnClickListener(view -> {
            JSONObject payload = adminPayload("update_security_settings");
            putJson(payload, "ban_root_users", banRoot.isChecked());
            putJson(payload, "ban_adb_users", banAdb.isChecked());
            putJson(payload, "ban_fake_location_users", banMock.isChecked());
            putJson(payload, "ban_accessibility_users", banAccessibility.isChecked());
            putJson(payload, "ban_packet_capture_users", banCapture.isChecked());
            putJson(payload, "ban_suspicious_packages_users", banSuspiciousPackages.isChecked());
            postAdminAction(payload, redirectPath);
        });
        card.addView(infoPanel("安全策略开启后，命中风险的用户会按服务端规则限制登录或上报。调试模式账号不受拦截影响。\n\nRoot/ADB/模拟定位/无障碍/抓包按对应检测项拦截；可疑包名会拦截安装列表中命中的 Magisk、Xposed、Reqable、HttpCanary、Charles 等风险包名。"), blockParams(12));
        card.addView(banRoot, blockParams(6));
        card.addView(banAdb, blockParams(6));
        card.addView(banMock, blockParams(6));
        card.addView(banAccessibility, blockParams(6));
        card.addView(banCapture, blockParams(6));
        card.addView(banSuspiciousPackages, blockParams(12));
        card.addView(save, blockParams(10));
        setScreen(card, false);
        setStatus("");
    }

    private CheckBox policyCheckBox(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(colorText());
        box.setChecked(checked);
        return box;
    }

    private void showGroupManager(JSONObject response, String redirectPath) {
        currentAdminTab = ADMIN_TAB_GROUPS;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
        LinearLayout card = screen("家庭组管理");
        EditText newGroupName = input("新家庭组显示名称");
        Button add = primaryButton("添加家庭组");
        add.setOnClickListener(view -> {
            JSONObject payload = adminPayload("add_family_group");
            putJson(payload, "group_name", newGroupName.getText().toString());
            postAdminAction(payload, redirectPath);
        });
        card.addView(newGroupName, blockParams(8));
        card.addView(add, blockParams(14));
        JSONArray groups = response.optJSONArray("groups");
        JSONArray memberships = response.optJSONArray("memberships");
        card.addView(sectionTitle("现有家庭组"), blockParams(8));
        if (groups == null || groups.length() == 0) {
            card.addView(infoPanel("暂无家庭组。"), blockParams(12));
        } else {
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group == null) {
                    continue;
                }
                int groupId = group.optInt("id", 0);
                String groupName = group.optString("group_name", "");
                int ownerUserIdValue = Math.max(0, group.optInt("owner_user_id", 0));
                EditText displayName = input("显示名称");
                displayName.setText(group.optString("display_name", groupName));
                card.addView(sectionTitle(group.optString("display_name", groupName)), blockParams(8));
                card.addView(infoPanel("标识：" + groupName
                    + "\n成员：" + group.optInt("member_count")
                    + " / 邀请码：" + (group.optString("group_code", "").isEmpty() ? "未生成" : group.optString("group_code", ""))
                    + "\n当前管理员：" + groupOwnerName(memberships, groupName, ownerUserIdValue)
                    + "\n可选管理员：" + groupMemberSummary(memberships, groupName)), blockParams(8));
                card.addView(displayName, blockParams(6));
                Button save = secondaryButton("保存家庭组");
                save.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("update_family_group");
                    putJson(payload, "group_id", groupId);
                    putJson(payload, "group_name", displayName.getText().toString());
                    postAdminAction(payload, redirectPath);
                });
                Button owner = secondaryButton("选择管理员：" + groupOwnerName(memberships, groupName, ownerUserIdValue));
                owner.setOnClickListener(view -> showGroupOwnerPicker(memberships, groupName, ownerUserIdValue, groupId, redirectPath));
                Button delete = secondaryButton("删除家庭组");
                delete.setOnClickListener(view -> confirmDanger("确定删除这个家庭组？组内定位记录会一并清除。", () -> {
                    JSONObject payload = adminPayload("delete_family_group");
                    putJson(payload, "group_id", groupId);
                    postAdminAction(payload, redirectPath);
                }));
                card.addView(buttonRow(save, owner), blockParams(8));
                card.addView(delete, blockParams(12));
            }
        }
        setScreen(card, false);
        setStatus("");
    }

    private String groupMemberSummary(JSONArray memberships, String groupName) {
        if (memberships == null || groupName == null || groupName.isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (int index = 0; index < memberships.length(); index += 1) {
            JSONObject membership = memberships.optJSONObject(index);
            if (membership == null || !groupName.equals(membership.optString("group_name", ""))) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(membership.optInt("user_id"))
                .append("=")
                .append(displayName(membership));
            count += 1;
            if (count >= 8) {
                builder.append("；…");
                break;
            }
        }
        return builder.length() == 0 ? "无" : builder.toString();
    }

    private String groupOwnerName(JSONArray memberships, String groupName, int ownerUserId) {
        if (ownerUserId <= 0) {
            return "无";
        }
        if (memberships != null) {
            for (int index = 0; index < memberships.length(); index += 1) {
                JSONObject membership = memberships.optJSONObject(index);
                if (membership == null || !groupName.equals(membership.optString("group_name", ""))) {
                    continue;
                }
                if (membership.optInt("user_id", 0) == ownerUserId) {
                    return displayName(membership);
                }
            }
        }
        return "用户 ID " + ownerUserId;
    }

    private void showGroupOwnerPicker(JSONArray memberships, String groupName, int currentOwnerUserId, int groupId, String redirectPath) {
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, "选择家庭组管理员");
        card.addView(compactInfoPanel("家庭组：" + groupName + "\n只能选择当前家庭组内成员；选择“无”表示不指定管理员。"), blockParams(10));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        addGroupOwnerOption(list, dialog, "无，不指定管理员", 0, currentOwnerUserId, groupId, redirectPath);

        int count = 0;
        if (memberships != null) {
            for (int index = 0; index < memberships.length(); index += 1) {
                JSONObject membership = memberships.optJSONObject(index);
                if (membership == null || !groupName.equals(membership.optString("group_name", ""))) {
                    continue;
                }
                int userId = membership.optInt("user_id", 0);
                String label = displayName(membership)
                    + "\nID：" + userId
                    + " / 身份：" + firstText(membership.optString("role_label", ""), membership.optString("role", ""), "未设置");
                addGroupOwnerOption(list, dialog, label, userId, currentOwnerUserId, groupId, redirectPath);
                count += 1;
            }
        }
        if (count == 0) {
            list.addView(infoPanel("当前家庭组暂无成员。"), blockParams(8));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = blockParams(12);
        scrollParams.height = Math.max(dp(160), Math.min(dp(360), getResources().getDisplayMetrics().heightPixels - topSafePadding() - dp(220)));
        card.addView(scroll, scrollParams);

        Button close = secondaryButton("关闭");
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, blockParams(0));
        showCardDialog(dialog);
    }

    private void addGroupOwnerOption(LinearLayout list, Dialog dialog, String label, int ownerUserId, int currentOwnerUserId, int groupId, String redirectPath) {
        Button option = secondaryButton((ownerUserId == currentOwnerUserId ? "✓ " : "") + label);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinHeight(dp(48));
        option.setOnClickListener(view -> {
            dialog.dismiss();
            JSONObject payload = adminPayload("update_group_owner");
            putJson(payload, "group_id", groupId);
            putJson(payload, "owner_user_id", ownerUserId);
            postAdminAction(payload, redirectPath);
        });
        list.addView(option, blockParams(8));
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
        card.addView(title, blockParams(10));
        card.addView(body, blockParams(10));
        card.addView(active, blockParams(12));
        card.addView(save, blockParams(10));
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
                EditText editNote = input("编辑备注");
                editNote.setText(invite.optString("note", ""));
                Button saveNote = secondaryButton("保存备注");
                saveNote.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("update_invite_note");
                    putJson(payload, "invite_id", inviteId);
                    putJson(payload, "note", editNote.getText().toString());
                    postAdminAction(payload, redirectPath);
                });
                Button toggle = secondaryButton(enabled ? "停用邀请码" : "启用邀请码");
                toggle.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("toggle_invite_code");
                    putJson(payload, "invite_id", inviteId);
                    putJson(payload, "next", !enabled);
                    postAdminAction(payload, redirectPath);
                });
                Button delete = secondaryButton("删除邀请码");
                delete.setOnClickListener(view -> confirmDanger("确定删除这个邀请码？", () -> {
                    JSONObject payload = adminPayload("delete_invite_code");
                    putJson(payload, "invite_id", inviteId);
                    postAdminAction(payload, redirectPath);
                }));
                card.addView(editNote, blockParams(6));
                card.addView(buttonRow(saveNote, toggle), blockParams(8));
                card.addView(delete, blockParams(10));
            }
        }
        setScreen(card, false);
        setStatus("");
    }

    private void showUserManager(JSONObject response, String redirectPath) {
        currentAdminTab = ADMIN_TAB_USERS;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
        LinearLayout card = screen("账号管理");
        JSONArray users = response.optJSONArray("users");
        JSONArray memberships = response.optJSONArray("memberships");
        JSONArray devices = response.optJSONArray("devices");

        card.addView(sectionTitle("新增账号"), blockParams(8));
        EditText addUsername = input("账号");
        EditText addPassword = input("密码");
        addPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText addDisplayName = input("显示名");
        EditText addGroupName = input("初始家庭组");
        EditText addInterval = input("上报间隔秒");
        addInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        addInterval.setText("300");
        CheckBox addMonitor = policyCheckBox("初始身份为监护端", true);
        Button addUser = primaryButton("新增账号");
        addUser.setOnClickListener(view -> {
            JSONObject payload = adminPayload("add_user");
            putJson(payload, "username", addUsername.getText().toString());
            putJson(payload, "password", addPassword.getText().toString());
            putJson(payload, "display_name", addDisplayName.getText().toString());
            putJson(payload, "group_name", addGroupName.getText().toString());
            putJson(payload, "role", addMonitor.isChecked() ? "monitor" : "guardian");
            putJson(payload, "report_interval_seconds", parseInt(addInterval.getText().toString(), 300));
            postAdminAction(payload, redirectPath);
        });
        card.addView(addUsername, blockParams(6));
        card.addView(addPassword, blockParams(6));
        card.addView(addDisplayName, blockParams(6));
        card.addView(addGroupName, blockParams(6));
        card.addView(addInterval, blockParams(6));
        card.addView(addMonitor, blockParams(8));
        card.addView(addUser, blockParams(16));

        card.addView(sectionTitle("现有账号"), blockParams(8));
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
                String summary = displayName(user)
                    + "\n状态：" + (active ? "启用" : "停用") + " / " + (user.optBoolean("online", false) ? "在线" : "离线")
                    + "\n家庭组：" + firstText(user.optString("group_name", ""), "无") + " / " + firstText(user.optString("role_label", ""), "未设置")
                    + "\n最后在线：" + firstText(user.optString("last_seen_at", ""), "无")
                    + "\n设备：" + userDeviceSummary(devices, userId);
                card.addView(compactInfoPanel(summary), blockParams(6));
                Button editUser = secondaryButton("编辑账号");
                editUser.setOnClickListener(view -> showUserEditDialog(user, memberships, redirectPath));
                Button deviceInfo = secondaryButton("设备信息");
                deviceInfo.setOnClickListener(view -> showUserDevicesDialog(user, devices, redirectPath));
                card.addView(buttonRow(editUser, deviceInfo), blockParams(10));
            }
        }
        setScreen(card, false);
        setStatus("");
    }

    private void showUserEditDialog(JSONObject user, JSONArray memberships, String redirectPath) {
        if (user == null) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, "编辑账号");
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        int userId = user.optInt("id", 0);
        boolean active = user.optBoolean("is_active", false);
        EditText username = input("账号");
        username.setText(user.optString("username", ""));
        EditText displayNameInput = input("显示名");
        displayNameInput.setText(user.optString("display_name", ""));
        EditText interval = input("上报间隔秒");
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setText(String.valueOf(Math.max(30, user.optInt("report_interval_seconds", 300))));
        CheckBox debug = policyCheckBox("调试模式", user.optBoolean("debug_mode", false));
        EditText newPassword = input("新密码：留空不重置");
        newPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        form.addView(compactInfoPanel("用户 ID：" + userId + "\n成员关系：" + userMembershipSummary(memberships, userId)), blockParams(8));
        form.addView(username, blockParams(6));
        form.addView(displayNameInput, blockParams(6));
        form.addView(interval, blockParams(6));
        form.addView(debug, blockParams(8));

        Button save = secondaryButton("保存账号");
        save.setOnClickListener(view -> {
            dialog.dismiss();
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
            dialog.dismiss();
            JSONObject payload = adminPayload("toggle_user");
            putJson(payload, "user_id", userId);
            putJson(payload, "next", !active);
            postAdminAction(payload, redirectPath);
        });
        form.addView(buttonRow(save, toggle), blockParams(8));

        Button reset = secondaryButton("重置密码");
        reset.setOnClickListener(view -> {
            dialog.dismiss();
            JSONObject payload = adminPayload("reset_password");
            putJson(payload, "user_id", userId);
            putJson(payload, "new_password", newPassword.getText().toString());
            postAdminAction(payload, redirectPath);
        });
        Button deleteUser = secondaryButton("删除账号");
        deleteUser.setOnClickListener(view -> {
            dialog.dismiss();
            confirmDanger("确定删除这个账号？相关设备、定位和工单会随账号清理。", () -> {
                JSONObject payload = adminPayload("delete_user");
                putJson(payload, "user_id", userId);
                postAdminAction(payload, redirectPath);
            });
        });
        form.addView(newPassword, blockParams(6));
        form.addView(buttonRow(reset, deleteUser), blockParams(12));

        form.addView(sectionTitle("家庭组身份"), blockParams(6));
        EditText newGroup = input("添加家庭组名称");
        CheckBox newGroupMonitor = policyCheckBox("身份为监护端", true);
        Button addMembership = secondaryButton("添加家庭组身份");
        addMembership.setOnClickListener(view -> {
            dialog.dismiss();
            JSONObject payload = adminPayload("add_membership");
            putJson(payload, "user_id", userId);
            putJson(payload, "group_name", newGroup.getText().toString());
            putJson(payload, "role", newGroupMonitor.isChecked() ? "monitor" : "guardian");
            postAdminAction(payload, redirectPath);
        });
        form.addView(newGroup, blockParams(6));
        form.addView(newGroupMonitor, blockParams(6));
        form.addView(addMembership, blockParams(8));
        addMembershipEditors(form, memberships, userId, redirectPath);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = blockParams(10);
        int availableHeight = getResources().getDisplayMetrics().heightPixels - topSafePadding() - dp(170);
        scrollParams.height = Math.max(dp(220), Math.min(dp(520), availableHeight));
        card.addView(scroll, scrollParams);

        Button close = secondaryButton("关闭");
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, blockParams(0));
        showCardDialog(dialog);
    }

    private void showUserDevicesDialog(JSONObject user, JSONArray devices, String redirectPath) {
        if (user == null) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, "设备信息");
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int userId = user.optInt("id", 0);
        list.addView(compactInfoPanel(displayName(user) + "\n" + userDeviceSummary(devices, userId)), blockParams(8));
        int shown = 0;
        if (devices != null) {
            for (int index = 0; index < devices.length(); index += 1) {
                JSONObject device = devices.optJSONObject(index);
                if (device == null || device.optInt("user_id", 0) != userId) {
                    continue;
                }
                shown += 1;
                int deviceId = device.optInt("id", 0);
                String line = "设备 #" + deviceId
                    + "\n设备指纹：" + firstText(device.optString("device_fingerprint", ""), "无")
                    + "\n浏览器指纹：" + firstText(device.optString("browser_fingerprint", ""), "无")
                    + "\n首次：" + firstText(device.optString("first_seen_at", ""), "无")
                    + "\n最近：" + firstText(device.optString("last_seen_at", ""), "无")
                    + "\nUA：" + firstText(device.optString("user_agent", ""), "无");
                list.addView(infoPanel(line), blockParams(6));
                Button delete = secondaryButton("解绑这台设备");
                delete.setOnClickListener(view -> {
                    dialog.dismiss();
                    confirmDanger("确定解绑这台设备？", () -> {
                        JSONObject payload = adminPayload("delete_user_device");
                        putJson(payload, "device_id", deviceId);
                        postAdminAction(payload, redirectPath);
                    });
                });
                list.addView(delete, blockParams(10));
            }
        }
        if (shown == 0) {
            list.addView(infoPanel("暂无绑定设备。"), blockParams(8));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = blockParams(10);
        int availableHeight = getResources().getDisplayMetrics().heightPixels - topSafePadding() - dp(170);
        scrollParams.height = Math.max(dp(180), Math.min(dp(500), availableHeight));
        card.addView(scroll, scrollParams);
        Button close = secondaryButton("关闭");
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, blockParams(0));
        showCardDialog(dialog);
    }

    private void addMembershipEditors(LinearLayout card, JSONArray memberships, int userId, String redirectPath) {
        if (memberships == null) {
            return;
        }
        for (int index = 0; index < memberships.length(); index += 1) {
            JSONObject membership = memberships.optJSONObject(index);
            if (membership == null || membership.optInt("user_id", 0) != userId) {
                continue;
            }
            int membershipId = membership.optInt("id", 0);
            EditText groupName = input("成员家庭组");
            groupName.setText(membership.optString("group_name", ""));
            CheckBox monitor = policyCheckBox("身份为监护端", "monitor".equals(membership.optString("role", "")));
            Button save = secondaryButton("保存成员关系");
            save.setOnClickListener(view -> {
                JSONObject payload = adminPayload("update_membership");
                putJson(payload, "membership_id", membershipId);
                putJson(payload, "group_name", groupName.getText().toString());
                putJson(payload, "role", monitor.isChecked() ? "monitor" : "guardian");
                postAdminAction(payload, redirectPath);
            });
            Button delete = secondaryButton("删除成员关系");
            delete.setOnClickListener(view -> confirmDanger("确定删除这条成员关系？", () -> {
                JSONObject payload = adminPayload("delete_membership");
                putJson(payload, "membership_id", membershipId);
                postAdminAction(payload, redirectPath);
            }));
            card.addView(infoPanel("成员关系 #" + membershipId + "：" + membership.optString("group_name", "") + " / " + membership.optString("role_label", "")), blockParams(6));
            card.addView(groupName, blockParams(6));
            card.addView(monitor, blockParams(6));
            card.addView(buttonRow(save, delete), blockParams(8));
        }
    }

    private void addDeviceEditors(LinearLayout card, JSONArray devices, int userId, String redirectPath) {
        if (devices == null) {
            return;
        }
        int shown = 0;
        for (int index = 0; index < devices.length(); index += 1) {
            JSONObject device = devices.optJSONObject(index);
            if (device == null || device.optInt("user_id", 0) != userId) {
                continue;
            }
            int deviceId = device.optInt("id", 0);
            String fingerprint = device.optString("device_fingerprint", "");
            if (fingerprint.length() > 16) {
                fingerprint = fingerprint.substring(0, 16) + "…";
            }
            card.addView(infoPanel("设备 #" + deviceId + "：" + fingerprint
                + "\n首次：" + device.optString("first_seen_at", "")
                + " / 最近：" + device.optString("last_seen_at", "")), blockParams(6));
            Button delete = secondaryButton("解绑设备");
            delete.setOnClickListener(view -> confirmDanger("确定解绑这台设备？", () -> {
                JSONObject payload = adminPayload("delete_user_device");
                putJson(payload, "device_id", deviceId);
                postAdminAction(payload, redirectPath);
            }));
            card.addView(delete, blockParams(8));
            shown += 1;
            if (shown >= 3) {
                break;
            }
        }
    }

    private String userMembershipSummary(JSONArray memberships, int userId) {
        if (memberships == null) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < memberships.length(); index += 1) {
            JSONObject membership = memberships.optJSONObject(index);
            if (membership == null || membership.optInt("user_id", 0) != userId) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(membership.optString("group_name", ""))
                .append("/")
                .append(membership.optString("role_label", ""));
        }
        return builder.length() == 0 ? "无" : builder.toString();
    }

    private String onlineUserSummary(JSONArray users) {
        if (users == null || users.length() == 0) {
            return "暂无在线用户。";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < users.length(); index += 1) {
            JSONObject user = users.optJSONObject(index);
            if (user == null || !user.optBoolean("online", false)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append("• ").append(displayName(user));
            String groupName = user.optString("group_name", "");
            if (!groupName.isEmpty()) {
                builder.append(" / ").append(groupName);
            }
            String lastSeen = user.optString("last_seen_at", "");
            if (!lastSeen.isEmpty()) {
                builder.append(" / ").append(lastSeen);
            }
        }
        return builder.length() == 0 ? "暂无在线用户。" : builder.toString();
    }

    private String userDeviceSummary(JSONArray devices, int userId) {
        if (devices == null) {
            return "无";
        }
        int count = 0;
        String latest = "";
        for (int index = 0; index < devices.length(); index += 1) {
            JSONObject device = devices.optJSONObject(index);
            if (device == null || device.optInt("user_id", 0) != userId) {
                continue;
            }
            count += 1;
            if (latest.isEmpty()) {
                latest = device.optString("last_seen_at", "");
            }
        }
        return count == 0 ? "无" : count + " 台 / 最近：" + latest;
    }

    private void showTicketManager(JSONObject response, String redirectPath) {
        currentAdminTab = ADMIN_TAB_TICKETS;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
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
        setScreen(card, false);
        setStatus("");
    }

    private void showLogManager(JSONObject response, String redirectPath) {
        currentAdminTab = ADMIN_TAB_LOGS;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
        LinearLayout card = screen("日志");
        JSONArray logs = response.optJSONArray("logs");
        if (logs == null || logs.length() == 0) {
            card.addView(infoPanel("暂无日志。"), blockParams(12));
        } else {
            int count = Math.min(logs.length(), 50);
            for (int index = 0; index < count; index += 1) {
                JSONObject log = logs.optJSONObject(index);
                if (log == null) {
                    continue;
                }
                String line = firstText(log.optString("message", ""), log.optString("event_type", "日志")) +
                    "\n用户：" + firstText(displayName(log), "系统/后台") +
                    " / 类型：" + log.optString("event_type", "") +
                    "\n家庭组：" + firstText(log.optString("group_name", ""), "无") +
                    " / IP：" + firstText(log.optString("ip", ""), "无") +
                    "\n时间：" + log.optString("created_at", "");
                card.addView(infoPanel(line), blockParams(6));
                Button detailLog = secondaryButton("查看详情");
                detailLog.setOnClickListener(view -> showLogDetail(log));
                card.addView(detailLog, blockParams(10));
            }
        }
        Button refresh = secondaryButton("刷新日志");
        refresh.setOnClickListener(view -> loadAdminSummary(redirectPath));
        card.addView(refresh, blockParams(0));
        setScreen(card, false);
        setStatus("");
    }


    private void confirmDanger(String message, Runnable action) {
        if (action == null) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, "确认操作");
        card.addView(infoPanel(message), blockParams(14));

        Button cancel = secondaryButton("取消");
        cancel.setOnClickListener(view -> dialog.dismiss());
        Button confirm = primaryButton("确认");
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        card.addView(buttonRow(cancel, confirm), blockParams(0));
        showCardDialog(dialog);
    }

    private void showLocationDetail(JSONObject location) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户：").append(displayName(location)).append('\n');
        builder.append("家庭组：").append(location.optString("group_name", "")).append('\n');
        builder.append("身份：").append(location.optString("role_label", "")).append('\n');
        builder.append("坐标：").append(formatCoordinate(location.optDouble("latitude")))
            .append(", ").append(formatCoordinate(location.optDouble("longitude"))).append('\n');
        builder.append("精度：").append(formatCoordinate(location.optDouble("accuracy"))).append(" 米\n");
        builder.append("时间：").append(location.optString("updated_at", "")).append("\n\n");

        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        if (diagnostics == null) {
            builder.append("地址诊断：无\n");
        } else {
            builder.append("首选来源：").append(diagnostics.optString("preferred_source", "")).append('\n');
            builder.append("首选地址：").append(firstText(diagnostics.optString("preferred_address", ""), locationAddress(location), "未解析")).append('\n');
            builder.append("首选城市：").append(diagnostics.optString("preferred_city", "")).append('\n');
            builder.append("检查时间：").append(diagnostics.optString("checked_at", "")).append('\n');
            builder.append("状态：").append(addressDiagnosticStatus(location, diagnostics)).append("\n\n");

            JSONArray sources = diagnostics.optJSONArray("sources");
            if (sources == null || sources.length() == 0) {
                builder.append("来源：无\n");
            } else {
                builder.append("来源与响应数据：\n");
                for (int index = 0; index < sources.length(); index += 1) {
                    JSONObject source = sources.optJSONObject(index);
                    if (source == null) {
                        continue;
                    }
                    builder.append('\n').append(index + 1).append(". ").append(locationSourceTitle(source)).append('\n');
                    appendIfPresent(builder, "类型", source.optString("type", ""));
                    appendIfPresent(builder, "地址", source.optString("address", ""));
                    appendIfPresent(builder, "城市", source.optString("city", ""));
                    appendIfPresent(builder, "省/地区", source.optString("region", ""));
                    appendIfPresent(builder, "国家", source.optString("country", ""));
                    appendIfPresent(builder, "IP", firstText(source.optString("ip", ""), source.optString("ipv4", ""), source.optString("ipv6", "")));
                    appendIfPresent(builder, "STUN", firstText(source.optString("stun_label", ""), source.optString("stun_server", "")));
                    if (!Double.isNaN(source.optDouble("latitude", Double.NaN)) && !Double.isNaN(source.optDouble("longitude", Double.NaN))) {
                        builder.append("坐标：").append(formatCoordinate(source.optDouble("latitude")))
                            .append(", ").append(formatCoordinate(source.optDouble("longitude"))).append('\n');
                    }
                    builder.append("保存响应：").append(prettyJson(source)).append('\n');
                }
            }
        }

        appendEnvironmentReportDetail(builder, location);

        showTextDetailDialog("定位详情", builder.toString());
    }

    private void appendEnvironmentReportDetail(StringBuilder builder, JSONObject location) {
        JSONObject report = location.optJSONObject("environment_report");
        if (report == null) {
            report = environmentReportForUser(location.optInt("user_id", 0));
        }
        builder.append("\n设备/环境报告：");
        if (report == null) {
            builder.append("无\n");
            return;
        }
        builder.append('\n');
        appendIfPresent(builder, "上报时间", report.optString("created_at", ""));
        JSONObject device = report.optJSONObject("device");
        if (device != null) {
            appendIfPresent(builder, "设备", firstText(device.optString("manufacturer", ""), device.optString("brand", ""))
                + " " + firstText(device.optString("model", ""), device.optString("device", "")));
            appendIfPresent(builder, "系统", firstText(device.optString("android_release", ""), "Android")
                + " / SDK " + firstText(device.optString("android_sdk", ""), "未知"));
            appendIfPresent(builder, "App", firstText(device.optString("app_version_name", ""), "未知")
                + " (" + firstText(device.optString("app_version_code", ""), "0") + ")");
            appendIfPresent(builder, "Root", boolLabel(device, "root_detected"));
            appendIfPresent(builder, "ADB", boolLabel(device, "adb_enabled"));
            appendIfPresent(builder, "模拟定位", boolLabel(device, "mock_location_enabled"));
        }
        JSONArray apps = report.optJSONArray("installed_apps");
        int appCount = report.optInt("installed_apps_count", apps == null ? 0 : apps.length());
        builder.append("安装应用：").append(appCount).append(" 个").append('\n');
        if (apps != null && apps.length() > 0) {
            int count = Math.min(appCount, Math.min(apps.length(), 40));
            for (int index = 0; index < count; index += 1) {
                JSONObject app = apps.optJSONObject(index);
                if (app == null) {
                    continue;
                }
                builder.append("  - ")
                    .append(firstText(app.optString("label", ""), app.optString("package_name", ""), "未知应用"));
                String packageName = app.optString("package_name", "");
                if (!packageName.isEmpty()) {
                    builder.append(" / ").append(packageName);
                }
                String versionName = app.optString("version_name", "");
                if (!versionName.isEmpty()) {
                    builder.append(" / ").append(versionName);
                }
                builder.append('\n');
            }
            if (apps.length() > count || appCount > count) {
                builder.append("  ... 仅显示前 ").append(count).append(" 个\n");
            }
        }
        JSONObject rawReport = report.optJSONObject("report");
        if (rawReport != null) {
            builder.append("环境报告 JSON：").append(prettyJson(rawReport)).append('\n');
        }
    }

    private JSONObject environmentReportForUser(int userId) {
        if (userId <= 0 || lastAdminSummary == null) {
            return null;
        }
        JSONArray reports = lastAdminSummary.optJSONArray("environment_reports");
        if (reports == null) {
            return null;
        }
        for (int index = 0; index < reports.length(); index += 1) {
            JSONObject report = reports.optJSONObject(index);
            if (report != null && report.optInt("user_id", 0) == userId) {
                return report;
            }
        }
        return null;
    }

    private String boolLabel(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return "";
        }
        return object.optBoolean(key, false) ? "是" : "否";
    }

    private void showLogDetail(JSONObject log) {
        StringBuilder builder = new StringBuilder();
        builder.append("日志 ID：").append(log.optInt("id", 0)).append('\n');
        builder.append("用户：").append(firstText(displayName(log), "系统/后台")).append('\n');
        builder.append("用户 ID：").append(log.optInt("user_id", 0)).append('\n');
        builder.append("家庭组：").append(firstText(log.optString("group_name", ""), "无")).append('\n');
        builder.append("类型：").append(log.optString("event_type", "")).append('\n');
        builder.append("消息：").append(log.optString("message", "")).append('\n');
        builder.append("IP：").append(log.optString("ip", "")).append('\n');
        builder.append("时间：").append(log.optString("created_at", "")).append('\n');
        builder.append("User-Agent：").append(log.optString("user_agent", "")).append("\n\n");
        JSONObject meta = log.optJSONObject("meta");
        builder.append("Meta：").append(meta == null ? "无" : prettyJson(meta));

        showTextDetailDialog("日志详情", builder.toString());
    }

    private LinearLayout dialogCard(Dialog dialog, String titleText) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(15), dp(15), dp(12));
        card.setBackground(cardBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(8));
        }
        TextView title = sectionTitle(titleText);
        title.setTextSize(17);
        card.addView(title, blockParams(12));
        dialog.setContentView(card);
        return card;
    }

    private void showTextDetailDialog(String titleText, String detailText) {
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, titleText);
        TextView detail = body(detailText);
        detail.setTextColor(colorText());
        detail.setPadding(dp(14), dp(12), dp(14), dp(12));
        detail.setBackground(panelBackground());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(detail, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = blockParams(12);
        int availableHeight = getResources().getDisplayMetrics().heightPixels - topSafePadding() - dp(170);
        scrollParams.height = Math.max(dp(180), Math.min(dp(460), availableHeight));
        card.addView(scroll, scrollParams);

        Button close = secondaryButton("关闭");
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, blockParams(0));
        showCardDialog(dialog);
    }

    private void showCardDialog(Dialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int dialogWidth = Math.min(screenWidth - dp(32), dp(520));
        window.setLayout(dialogWidth > 0 ? dialogWidth : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        animateDialog(window.getDecorView());
    }

    private void appendIfPresent(StringBuilder builder, String label, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) {
            builder.append(label).append("：").append(text).append('\n');
        }
    }

    private String locationSourceTitle(JSONObject source) {
        return firstText(source.optString("provider", ""), source.optString("source", ""), source.optString("name", ""), source.optString("type", "来源"));
    }

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty() && !"0".equals(text)) {
                return text;
            }
        }
        return "";
    }

    private String prettyJson(JSONObject object) {
        try {
            return object.toString(2);
        } catch (Exception ignored) {
            return object.toString();
        }
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

    private String addressDiagnosticStatus(JSONObject location, JSONObject diagnostics) {
        if (diagnostics == null || diagnostics.optJSONArray("sources") == null) {
            return location != null && location.optBoolean("address_mismatch", false)
                ? "位置信息不一致"
                : "位置信息一致或无法完整判断";
        }
        if (diagnosticsGpsMismatch(diagnostics)) {
            return "位置信息不一致";
        }
        if (diagnosticsNetworkDiffersFromGps(diagnostics)) {
            return "网络出口与定位不同，仅作 VPN/代理提示";
        }
        return "位置信息一致或无法完整判断";
    }

    private boolean diagnosticsGpsMismatch(JSONObject diagnostics) {
        JSONArray sources = diagnostics == null ? null : diagnostics.optJSONArray("sources");
        if (sources == null) {
            return false;
        }
        for (String field : new String[] {"country", "region", "city"}) {
            List<String> values = new ArrayList<>();
            for (int index = 0; index < sources.length(); index += 1) {
                JSONObject source = sources.optJSONObject(index);
                if (source == null || !"gps".equals(source.optString("type", ""))) {
                    continue;
                }
                addCompareValue(values, source.optString(field, ""));
            }
            if (values.size() > 1) {
                return true;
            }
        }
        return false;
    }

    private boolean diagnosticsNetworkDiffersFromGps(JSONObject diagnostics) {
        JSONArray sources = diagnostics == null ? null : diagnostics.optJSONArray("sources");
        if (sources == null) {
            return diagnostics != null && diagnostics.optBoolean("mobile_ip_uncertain", false);
        }
        JSONObject gps = null;
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source != null && "gps".equals(source.optString("type", ""))) {
                gps = source;
                break;
            }
        }
        if (gps == null) {
            return diagnostics.optBoolean("mobile_ip_uncertain", false);
        }
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null || "gps".equals(source.optString("type", ""))) {
                continue;
            }
            if (!"ip".equals(source.optString("type", "")) && !"webrtc".equals(source.optString("type", ""))) {
                continue;
            }
            for (String field : new String[] {"country", "region", "city"}) {
                String gpsValue = diagnosticsCompareValue(gps.optString(field, ""));
                String sourceValue = diagnosticsCompareValue(source.optString(field, ""));
                if (!gpsValue.isEmpty() && !sourceValue.isEmpty() && !gpsValue.equals(sourceValue)) {
                    return true;
                }
            }
        }
        return diagnostics.optBoolean("mobile_ip_uncertain", false);
    }

    private void addCompareValue(List<String> values, String value) {
        String normalized = diagnosticsCompareValue(value);
        if (!normalized.isEmpty() && !values.contains(normalized)) {
            values.add(normalized);
        }
    }

    private String diagnosticsCompareValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US).replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = normalized.replace("中华人民共和国", "中国");
        return normalized.replaceAll("(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|地区|省|市|盟|州)$", "");
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
        for (String value : values) {
            mergeCookieHeader(value);
        }
    }

    private void syncCookiesToWebView(String url) {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            for (String cookie : cookieHeader().split(";")) {
                String trimmed = cookie.trim();
                if (!trimmed.isEmpty()) {
                    cookieManager.setCookie(url, trimmed);
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.flush();
            }
        } catch (Exception exception) {
            Log.w(TAG, "Sync cookies to WebView failed: " + exception.getMessage());
        }
    }

    private void captureWebViewCookies(String url) {
        try {
            String cookies = CookieManager.getInstance().getCookie(url == null || url.trim().isEmpty() ? serverUrl() : url);
            mergeCookieHeader(cookies);
        } catch (Exception exception) {
            Log.w(TAG, "Capture WebView cookies failed: " + exception.getMessage());
        }
    }

    private void mergeCookieHeader(String header) {
        if (header == null || header.trim().isEmpty()) {
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
        for (String item : header.split(";")) {
            String cookie = item.trim();
            if (cookie.isEmpty() || !cookie.contains("=")) {
                continue;
            }
            String name = cookie.split("=", 2)[0].trim();
            if (name.isEmpty() || isCookieAttribute(name)) {
                continue;
            }
            for (int index = cookies.size() - 1; index >= 0; index -= 1) {
                if (cookies.get(index).startsWith(name + "=")) {
                    cookies.remove(index);
                }
            }
            cookies.add(cookie);
        }
        prefs().edit().putString(KEY_SESSION_COOKIE, joinCookies(cookies)).apply();
    }

    private boolean isCookieAttribute(String name) {
        String value = name == null ? "" : name.trim().toLowerCase(java.util.Locale.US);
        return "path".equals(value)
            || "domain".equals(value)
            || "expires".equals(value)
            || "max-age".equals(value)
            || "secure".equals(value)
            || "httponly".equals(value)
            || "samesite".equals(value);
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
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(cardBackground());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        title.setTextColor(colorText());
        card.addView(title, blockParams(8));
        statusView = body("");
        statusView.setPadding(dp(10), dp(7), dp(10), dp(7));
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
            root.setPadding(dp(12), topSafePadding(), dp(12), dp(12));
            root.setBackgroundColor(colorSurface());
            root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            animateScreen(root, true);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(12), topSafePadding(), dp(12), dp(12));
        root.setBackgroundColor(colorSurface());
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!adminLoggedIn) {
            setContentView(scroll);
            animateScreen(scroll, false);
            return;
        }

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(colorSurface());
        frame.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(adminBottomNavigation(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(frame);
        animateScreen(scroll, false);
    }

    private LinearLayout adminBottomNavigation() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(10), dp(4), dp(10), dp(7));
        outer.setBackgroundColor(colorSurface());

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(5), dp(5), dp(5), dp(5));
        nav.setBackground(bottomNavBackground());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            nav.setElevation(dp(12));
        }

        nav.addView(adminNavButton("⌂", "概览", ADMIN_TAB_OVERVIEW), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(adminNavButton("◎", "账号", ADMIN_TAB_USERS), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(adminNavButton("▦", "家庭组", ADMIN_TAB_GROUPS), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(adminNavButton("☏", "工单", ADMIN_TAB_TICKETS), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(adminNavButton("≡", "日志", ADMIN_TAB_LOGS), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        outer.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private View adminNavButton(String icon, String label, int tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(dp(48));
        item.setPadding(dp(3), dp(5), dp(3), dp(5));
        boolean active = currentAdminTab == tab;
        item.setBackground(active ? navActiveBackground() : transparentButtonBackground());
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> {
            animateTap(view);
            view.postDelayed(() -> switchAdminTab(tab), 45);
        });

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(active ? 19 : 17);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTypeface(Typeface.DEFAULT_BOLD);
        iconView.setTextColor(active ? Color.rgb(230, 76, 76) : colorMuted());

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(9);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        labelView.setTextColor(active ? Color.rgb(230, 76, 76) : colorMuted());

        item.addView(iconView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.addView(labelView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return item;
    }

    private int topSafePadding() {
        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        int insetTop = statusBarHeight;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                insetTop = Math.max(insetTop, insets.getSystemWindowInsetTop());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        insetTop = Math.max(insetTop, cutout.getSafeInsetTop());
                    }
                }
            }
        }
        return Math.max(dp(22), insetTop + dp(12));
    }

    private void animateScreen(View view, boolean center) {
        if (view == null) {
            return;
        }
        view.setAlpha(0.9f);
        view.setTranslationY(center ? dp(8) : dp(12));
        view.setScaleX(center ? 0.985f : 1f);
        view.setScaleY(center ? 0.985f : 1f);
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .start();
    }

    private void animateDialog(View view) {
        if (view == null) {
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(dp(10));
        view.setScaleX(0.97f);
        view.setScaleY(0.97f);
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(170)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .start();
    }

    private void animateTap(View view) {
        if (view == null) {
            return;
        }
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(45)
            .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(95).start())
            .start();
    }

    private void decorateButton(Button button) {
        button.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            }
            return false;
        });
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
        view.setTextSize(12);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.15f);
        view.setTextColor(colorMuted());
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        view.setTextColor(colorText());
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView infoPanel(String text) {
        TextView view = body(text);
        view.setTextColor(colorText());
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(panelBackground());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            view.setElevation(dp(1));
        }
        return view;
    }

    private TextView compactInfoPanel(String text) {
        TextView view = infoPanel(text);
        view.setTextSize(12);
        view.setPadding(dp(9), dp(7), dp(9), dp(7));
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setTextSize(13);
        view.setTextColor(colorText());
        view.setHintTextColor(colorMuted());
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(31));
        button.setIncludeFontPadding(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(buttonBackground(Color.rgb(110, 45, 45)));
        decorateButton(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorText());
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(31));
        button.setIncludeFontPadding(false);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(buttonBackground(isDarkMode() ? Color.rgb(50, 37, 37) : Color.rgb(237, 228, 228)));
        decorateButton(button);
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

    private GradientDrawable bottomNavBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(29, 17, 17) : Color.WHITE);
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), isDarkMode() ? Color.rgb(72, 45, 45) : Color.rgb(231, 217, 217));
        return drawable;
    }

    private GradientDrawable navActiveBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(68, 28, 28) : Color.rgb(252, 230, 230));
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private RippleDrawable transparentButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(16));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(32, 230, 76, 76)), drawable, null);
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> setStatus(message));
            return;
        }
        if (statusView != null) {
            String value = message == null ? "" : message.trim();
            statusView.setText(value);
            statusView.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void runBackground(Runnable runnable) {
        new Thread(() -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                Log.e(TAG, "Background task failed", throwable);
                runUi(() -> setStatus("后台任务失败：" + exceptionMessage(throwable)));
            }
        }, "loc-admin-native").start();
    }

    private void runUi(Runnable runnable) {
        mainHandler.post(() -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                Log.e(TAG, "UI task failed", throwable);
                try {
                    setStatus("界面任务失败：" + exceptionMessage(throwable));
                } catch (Throwable ignored) {
                    // Keep process alive even if status UI is unavailable.
                }
            }
        });
    }

    private String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String type = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return type;
        }
        return type + ": " + message.trim();
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

