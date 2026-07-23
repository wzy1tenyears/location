package com.familylocation.admin;

import com.familylocation.net.JsonApiClient;
import com.familylocation.net.Material3Tokens;

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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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
import android.text.Editable;
import android.text.TextWatcher;
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
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AdminActivity extends Activity {
    private static final class ChallengeCancelledException extends Exception {
    }

    private static final String PREFS = "family_location_admin";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String KEY_ADMIN_USERNAME = "admin_username";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String KEY_PENDING_UPDATE_INSTALL_ID = "pending_update_install_id";
    private static final String KEY_ACTIVE_UPDATE_DOWNLOAD_ID = "active_update_download_id";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String DEFAULT_SERVER_URL = "https://example.com/";
    private static final int APP_VERSION_CODE = 98;
    private static final String APP_VERSION_NAME = "2.3.3";
    private static final String ADMIN_APK_NAME = "location-admin-release.apk";
    private static final String ADMIN_UPDATE_PATH = "";
    private static final String USER_AGENT = "loc-admin-app/" + APP_VERSION_NAME + " loc-app/" + APP_VERSION_NAME;
    private static final JsonApiClient API_CLIENT = new JsonApiClient(USER_AGENT, 12_000, 12_000);
    private static final String TAG = "FamilyLocationAdmin";
    private static final long MAX_CACHE_BYTES = 50L * 1024L * 1024L;
    private static final long STARTUP_MIN_VISIBLE_MS = 720L;
    private static final int ADMIN_TAB_OVERVIEW = 0;
    private static final int ADMIN_TAB_USERS = 1;
    private static final int ADMIN_TAB_GROUPS = 2;
    private static final int ADMIN_TAB_TICKETS = 3;
    private static final int ADMIN_TAB_LOGS = 4;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger backgroundThreadIndex = new AtomicInteger();
    private final ExecutorService backgroundExecutor = new ThreadPoolExecutor(
        3,
        4,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(24),
        runnable -> new Thread(runnable, "loc-admin-" + backgroundThreadIndex.incrementAndGet()),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicBoolean adminWriteInFlight = new AtomicBoolean();
    private LinearLayout content;
    private TextView statusView;
    private JSONObject lastAdminSummary;
    private String currentRedirectPath = ADMIN_UPDATE_PATH;
    private int currentAdminTab = ADMIN_TAB_OVERVIEW;
    private boolean inviteManagerOpen;
    private int inviteManagerScrollY;
    private int adminDashboardScrollY;
    private ScrollView activeScrollView;
    private String logFilterGroup = "";
    private String logFilterType = "";
    private String logFilterKeyword = "";
    private int logFilterUserId = 0;
    private int logFilterLimit = 60;
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
    private long startupShownAtMs;
    private TextView startupStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
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
        String normalizedServerUrl = normalizeUrl(serverUrl);
        if (!normalizedServerUrl.equals(prefs().getString(KEY_SERVER_URL, ""))) {
            prefs().edit().putString(KEY_SERVER_URL, normalizedServerUrl).apply();
        }
        if (!prefs().getString(KEY_SESSION_COOKIE, "").trim().isEmpty()) {
            showStartupLoading();
            getWindow().getDecorView().post(this::checkStoredSessionThenUpdate);
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
        startupShownAtMs = System.currentTimeMillis();
        statusView = null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        card.setBackground(cardBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }

        card.addView(startupBadge("位置后台"), blockParams(14));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.addView(new StartupSignalView(this), new LinearLayout.LayoutParams(dp(164), dp(64)));

        TextView title = startupTitle("正在进入后台");
        title.setPadding(0, dp(16), 0, 0);
        hero.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView indicator = body("恢复登录状态、检查后台更新，完成后会自动进入后台首页。");
        indicator.setGravity(Gravity.CENTER_HORIZONTAL);
        indicator.setTextColor(colorMuted());
        indicator.setLineSpacing(dp(2), 1.15f);
        indicator.setPadding(0, dp(8), 0, 0);
        hero.addView(indicator, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(hero, blockParams(18));

        card.addView(startupChecklist(), blockParams(14));
        card.addView(detailDivider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        TextView progressLabel = sectionTitle("当前进度");
        progressLabel.setPadding(0, dp(12), 0, 0);
        card.addView(progressLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        startupStatusText = body("正在恢复后台会话…");
        startupStatusText.setTextColor(colorText());
        startupStatusText.setPadding(0, dp(8), 0, 0);
        card.addView(startupStatusText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setCenteredScreen(card);
    }
    private void checkStoredSessionThenUpdate() {
        runBackground(() -> {
            try {
                runUi(() -> updateStartupStatus("正在检查后台更新…"));
                checkAdminUpdateThenShowHome(ADMIN_UPDATE_PATH);
            } catch (Exception exception) {
                prefs().edit().remove(KEY_SESSION_COOKIE).apply();
                runUi(() -> finishStartupTransition(() -> showLogin("登录已失效，请重新登录。")));
            }
        });
    }

    private void showLogin(String message) {
        adminLoggedIn = false;
        lastAdminSummary = null;
        LinearLayout card = screen("后台登录");
        card.addView(compactInfoPanel("当前主题：" + themeModeLabel(themeMode())), blockParams(10));
        EditText username = input("管理员账号");
        EditText password = input("管理员密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button login = primaryButton("登录后台");
        login.setOnClickListener(view -> login(username.getText().toString(), password.getText().toString()));
        username.setText(loginDraftUsername.isEmpty() ? prefs().getString(KEY_ADMIN_USERNAME, "") : loginDraftUsername);
        password.setText(loginDraftPassword);
        card.addView(username, blockParams(12));
        card.addView(password, blockParams(12));
        card.addView(login, blockParams(0));
        setCenteredScreen(card);
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
        Log.i(TAG, "Admin login started.");
        runBackground(() -> {
            try {
                String challengeToken = completeAppChallenge("login");
                Log.i(TAG, "Admin login obtained challenge token. prefix=" + safeTokenPrefix(challengeToken));
                JSONObject payload = new JSONObject()
                    .put("username", username.trim())
                    .put("password", password)
                    .put("terms_accepted", true)
                    .put("cross_border_transfer_accepted", true)
                    .put("browser_fingerprint", deviceCookieValue())
                    .put("turnstile_token", challengeToken);
                JSONObject response = postJson(AdminApiPaths.LOGIN, payload);
                String redirect = response.optString("redirect", "");
                if (redirect.isEmpty()) {
                    throw new IllegalStateException("该账号不是后台管理员。");
                }
                String adminUsername = response.optString("admin_username", "").trim();
                prefs().edit().putString(KEY_ADMIN_USERNAME, adminUsername.isEmpty() ? username.trim() : adminUsername).apply();
                Log.i(TAG, "Admin login API returned ok. redirect=" + redirect);
                checkAdminUpdateThenShowHome(redirect);
            } catch (ChallengeCancelledException exception) {
                Log.w(TAG, "Admin login challenge cancelled.");
                runUi(() -> setStatus(""));
            } catch (Exception exception) {
                Log.e(TAG, "Admin login failed after challenge.", exception);
                runUi(() -> {
                    if (isAdminLoginExpired(exception)) {
                        handleAdminLoginExpired();
                        return;
                    }
                    setStatus(exception.getMessage());
                });
            }
        });
    }

    private String completeAppChallenge(String purpose) throws Exception {
        Log.i(TAG, "Admin app challenge started. purpose=" + purpose);
        JSONObject start = postJson(AdminApiPaths.APP_CHALLENGE, new JSONObject().put("purpose", purpose));
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
        CountDownLatch challengeCompleted = new CountDownLatch(1);
        String[] nativeTurnstileToken = new String[] {""};
        runUi(() -> showAppChallengeWebView(challengeUrl, challengeCompleted, nativeTurnstileToken, () -> cancelAppChallenge(generation)));

        long deadline = System.currentTimeMillis() + 300000L;
        while (System.currentTimeMillis() < deadline) {
            challengeCompleted.await(2500L, TimeUnit.MILLISECONDS);
            if (isChallengeCancelled(generation)) {
                throw new ChallengeCancelledException();
            }
            if (nativeTurnstileToken[0] != null && !nativeTurnstileToken[0].trim().isEmpty()) {
                Log.i(TAG, "Admin app challenge token received from WebView bridge. purpose=" + purpose + ", prefix=" + safeTokenPrefix(nativeTurnstileToken[0]));
                return nativeTurnstileToken[0].trim();
            }
            JSONObject poll = getJson(AdminApiPaths.APP_CHALLENGE + "?id=" + urlEncode(challengeId) + "&secret=" + urlEncode(challengeSecret));
            if (poll.optBoolean("verified", false)) {
                String token = poll.optString("app_challenge_token", "");
                if (!token.isEmpty()) {
                    Log.i(TAG, "Admin app challenge verified by server polling fallback. purpose=" + purpose + ", prefix=" + safeTokenPrefix(token));
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
        JSONObject update = getJson(AdminApiPaths.ADMIN_APP_UPDATE + "?version_code=" + APP_VERSION_CODE);
        if (update.optBoolean("update_required", false)) {
            String apkUrl = update.optString("apk_url", "").trim();
            if (apkUrl.isEmpty()) {
                throw new IllegalStateException("后台更新包地址为空。");
            }
            runUi(() -> {
                updateStartupStatus("检测到后台新版本，正在准备更新…");
                finishStartupTransition(() -> showAdminUpdate(update, redirectPath));
            });
            return;
        }
        runUi(() -> {
            updateStartupStatus("检查完成，正在进入后台首页…");
            finishStartupTransition(() -> showAdminHome(redirectPath));
        });
    }

    private void checkAdminUpdateManually(String redirectPath) {
        setStatus("正在检查后台更新。");
        runBackground(() -> {
            try {
                JSONObject update = getJson(AdminApiPaths.ADMIN_APP_UPDATE + "?version_code=" + APP_VERSION_CODE);
                if (update.optBoolean("update_required", false)) {
                    String apkUrl = update.optString("apk_url", "").trim();
                    if (apkUrl.isEmpty()) {
                        throw new IllegalStateException("后台更新包地址为空。");
                    }
                    runUi(() -> showAdminUpdate(update, redirectPath));
                    return;
                }
                runUi(() -> showTransientFeedback("后台 App 已是最新版本：" + APP_VERSION_NAME + "。"));
            } catch (Exception exception) {
                runUi(() -> {
                    if (isAdminLoginExpired(exception)) {
                        handleAdminLoginExpired();
                        return;
                    }
                    setStatus(exception.getMessage());
                });
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
        backgroundExecutor.shutdownNow();
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
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(cardBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }
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

    private void showAppChallengeWebView(String challengeUrl, CountDownLatch challengeCompleted, String[] nativeTurnstileToken, Runnable onBack) {
        LinearLayout card = challengeCard();
        if (!canLoadForegroundWebView()) {
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        WebView challengeView = managedWebView();
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(challengeView, true);
        }
        WebSettings settings = challengeView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(settings.getUserAgentString() + " " + USER_AGENT);
        challengeView.setVerticalScrollBarEnabled(false);
        challengeView.setHorizontalScrollBarEnabled(false);
        LinearLayout verificationStatePanel = simpleSummaryPanel("状态", "正在加载验证…");
        statusView = summaryPanelValueView(verificationStatePanel);
        challengeView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void complete() {
                Log.i(TAG, "Admin app challenge WebView complete() callback fired.");
                runUi(() -> setSummaryPanelValue(verificationStatePanel, "验证已完成，正在继续后台登录…"));
                challengeCompleted.countDown();
            }

            @JavascriptInterface
            public void completeToken(String token) {
                if (token == null || token.trim().isEmpty()) {
                    Log.w(TAG, "Admin app challenge WebView completeToken() received empty token.");
                    return;
                }
                nativeTurnstileToken[0] = token.trim();
                Log.i(TAG, "Admin app challenge WebView completeToken() received token. prefix=" + safeTokenPrefix(token));
                runUi(() -> setSummaryPanelValue(verificationStatePanel, "验证已完成，正在提交后台登录…"));
                challengeCompleted.countDown();
            }
        }, "LocChallenge");
        challengeView.setBackgroundColor(Color.TRANSPARENT);
        challengeView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        syncCookiesToWebView(challengeUrl);
        final int[] reloadAttempts = new int[] {0};
        Runnable challengeReloadNudge = new Runnable() {
            @Override
            public void run() {
                if (challengeCompleted.getCount() == 0) {
                    return;
                }
                if (reloadAttempts[0] < 2) {
                    reloadAttempts[0] += 1;
                    setSummaryPanelValue(verificationStatePanel, "正在重新加载验证…");
                    syncCookiesToWebView(challengeUrl);
                    challengeView.loadUrl(challengeUrl);
                    mainHandler.postDelayed(this, 8000L);
                    return;
                }
                setSummaryPanelValue(verificationStatePanel, "若验证仍未显示，请返回后重试。");
            }
        };
        challengeView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                setSummaryPanelValue(verificationStatePanel, "正在加载验证…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                captureWebViewCookies(url);
                if (challengeCompleted.getCount() > 0) {
                    setSummaryPanelValue(verificationStatePanel, "若验证未显示，可点“重新加载验证”。");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || request == null || request.isForMainFrame()) {
                    String description = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && error != null
                        ? String.valueOf(error.getDescription())
                        : "网络请求失败";
                    setSummaryPanelValue(verificationStatePanel, "验证加载失败：" + description);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                setSummaryPanelValue(verificationStatePanel, "验证视图已重启，请重新加载验证。");
                return handleWebViewRendererGone(view, "");
            }
        });
        card.addView(challengePrompt("请完成 Cloudflare 验证，完成后会自动继续后台登录。"), blockParams(8));
        card.addView(verificationStatePanel, blockParams(8));
        challengeView.loadUrl(challengeUrl);
        mainHandler.postDelayed(challengeReloadNudge, 8000L);
        LinearLayout.LayoutParams params = blockParams(8);
        params.height = dp(96);
        card.addView(challengeView, params);
        Button reload = secondaryButton("重新加载验证");
        reload.setOnClickListener(view -> {
            setSummaryPanelValue(verificationStatePanel, "正在重新加载验证…");
            syncCookiesToWebView(challengeUrl);
            challengeView.loadUrl(challengeUrl);
            mainHandler.postDelayed(challengeReloadNudge, 8000L);
        });
        Button back = secondaryButton("返回后台登录 / 修改密码");
        back.setOnClickListener(view -> {
            if (onBack != null) {
                onBack.run();
            }
        });
        card.addView(buttonRow(reload, back), blockParams(0));
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
                JSONObject response = getJson(adminSummaryEndpoint());
                runUi(() -> {
                    String adminUsername = response.optString("admin_username", "").trim();
                    if (!adminUsername.isEmpty()) {
                        prefs().edit().putString(KEY_ADMIN_USERNAME, adminUsername).apply();
                    }
                    lastAdminSummary = response;
                    currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
                    if (inviteManagerOpen) {
                        showInviteManager(response, currentRedirectPath);
                    } else {
                        showCurrentAdminTab(response, currentRedirectPath);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (isAdminLoginExpired(exception)) {
                        handleAdminLoginExpired();
                        return;
                    }
                    setStatus(exception.getMessage());
                });
            }
        });
    }

    private String adminSummaryEndpoint() throws Exception {
        if (currentAdminTab != ADMIN_TAB_LOGS) {
            return AdminApiPaths.ADMIN_SUMMARY;
        }
        String query = logFilterQuery();
        return query.isEmpty() ? AdminApiPaths.ADMIN_SUMMARY : AdminApiPaths.ADMIN_SUMMARY + "?" + query;
    }

    private String logFilterQuery() throws Exception {
        List<String> params = new ArrayList<>();
        appendQueryParam(params, "log_group", logFilterGroup);
        if (logFilterUserId > 0) {
            appendQueryParam(params, "log_user_id", String.valueOf(logFilterUserId));
        }
        appendQueryParam(params, "log_type", logFilterType);
        appendQueryParam(params, "log_keyword", logFilterKeyword);
        appendQueryParam(params, "log_limit", String.valueOf(logFilterLimit));
        StringBuilder builder = new StringBuilder();
        for (String param : params) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(param);
        }
        return builder.toString();
    }

    private void appendQueryParam(List<String> params, String key, String value) throws Exception {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return;
        }
        params.add(key + "=" + urlEncode(text));
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
        if (tab == ADMIN_TAB_LOGS) {
            setStatus("正在加载日志。");
            loadAdminSummary(currentRedirectPath);
            return;
        }
        if (lastAdminSummary == null) {
            showAdminHome(currentRedirectPath);
            return;
        }
        showCurrentAdminTab(lastAdminSummary, currentRedirectPath);
    }

    private void showAdminDashboard(JSONObject response, String redirectPath) {
        inviteManagerOpen = false;
        currentAdminTab = ADMIN_TAB_OVERVIEW;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
        LinearLayout card = screen("定位后台管理");
        JSONObject stats = response.optJSONObject("stats");
        JSONObject settings = response.optJSONObject("security_settings");
        TextView loadedAt = body("后台数据已加载：" + response.optString("server_time", ""));
        loadedAt.setTextColor(colorMuted());
        loadedAt.setIncludeFontPadding(false);
        card.addView(loadedAt, blockParams(12));
        Button inviteManager = primaryButton("邀请码管理");
        inviteManager.setOnClickListener(view -> openInviteManagerFromDashboard(response, redirectPath));
        card.addView(inviteManager, blockParams(14));
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

        card.addView(sectionHeading("在线用户", stats == null ? "" : stats.optInt("online_users") + " 在线"), blockParams(8));
        card.addView(compactInfoPanel(onlineUserSummary(response.optJSONArray("users"))), blockParams(16));

        card.addView(sectionTitle("安全策略"), blockParams(8));
        card.addView(buildSecuritySettingsPanel(settings, redirectPath, false), blockParams(16));

        card.addView(sectionTitle("公告管理"), blockParams(8));
        card.addView(buildAnnouncementManagerPanel(response, redirectPath, false), blockParams(16));

        card.addView(sectionTitle("邀请码管理"), blockParams(8));
        card.addView(buildInviteManagerPanel(response, redirectPath, 8, true), blockParams(16));

        card.addView(sectionTitle("系统工具"), blockParams(8));
        Button checkUpdate = secondaryButton("检查后台更新");
        checkUpdate.setOnClickListener(view -> checkAdminUpdateManually(redirectPath));
        Button refresh = secondaryButton("刷新后台数据");
        refresh.setOnClickListener(view -> showAdminHome(redirectPath));
        card.addView(checkUpdate, blockParams(10));
        card.addView(refresh, blockParams(10));
        setScreen(card, false);
        logViewBounds("UI_ADMIN_INVITE_ENTRY_BOUNDS", inviteManager);
        if (activeScrollView != null && adminDashboardScrollY > 0) {
            final int targetScrollY = adminDashboardScrollY;
            activeScrollView.post(() -> {
                activeScrollView.scrollTo(0, targetScrollY);
                Log.i(TAG, "UI_ADMIN_INVITE_STATE=dashboard,scroll=" + activeScrollView.getScrollY());
            });
        } else {
            Log.i(TAG, "UI_ADMIN_INVITE_STATE=dashboard,scroll=0");
        }
        setStatus("");
    }

    private void openInviteManagerFromDashboard(JSONObject response, String redirectPath) {
        adminDashboardScrollY = activeScrollView == null ? 0 : activeScrollView.getScrollY();
        showInviteManager(lastAdminSummary == null ? response : lastAdminSummary, redirectPath);
    }

    private String storedAdminUsername() {
        String username = loginDraftUsername == null ? "" : loginDraftUsername.trim();
        if (!username.isEmpty()) {
            return username;
        }
        String stored = prefs().getString(KEY_ADMIN_USERNAME, "");
        stored = stored == null ? "" : stored.trim();
        return stored.isEmpty() ? DEFAULT_ADMIN_USERNAME : stored;
    }

    private void logoutAdmin() {
        String sessionCookie = cookieHeader();
        prefs().edit().remove(KEY_SESSION_COOKIE).apply();
        loginDraftPassword = "";
        runBackground(() -> {
            try {
                API_CLIENT.post(serverUrl() + AdminApiPaths.LOGOUT, new JSONObject(), sessionCookie, null);
            } catch (Exception ignored) {
                // Local logout is enough if the network is unavailable.
            }
        });
        setStatus("");
        showLogin("已退出后台。");
    }

    private void showThemePicker() {
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, "切换主题");
        addThemeChoice(card, dialog, "system", "跟随系统");
        addThemeChoice(card, dialog, "light", "明亮");
        addThemeChoice(card, dialog, "dark", "暗色");
        showCardDialog(dialog);
    }

    private void addThemeChoice(LinearLayout card, Dialog dialog, String mode, String label) {
        Button button = secondaryButton((mode.equals(themeMode()) ? "✓ " : "") + label);
        button.setOnClickListener(view -> {
            dialog.dismiss();
            applyThemeMode(mode);
        });
        card.addView(button, blockParams(8));
    }

    private void applyThemeMode(String mode) {
        String normalized = normalizeThemeMode(mode);
        prefs().edit().putString(KEY_THEME_MODE, normalized).apply();
        configureWindow();
        rebuildCurrentScreen();
        setStatus("主题已切换：" + themeModeLabel(normalized));
    }

    private void rebuildCurrentScreen() {
        if (adminLoggedIn) {
            if (lastAdminSummary != null) {
                showCurrentAdminTab(lastAdminSummary, currentRedirectPath);
                return;
            }
            if (!prefs().getString(KEY_SESSION_COOKIE, "").trim().isEmpty()) {
                showStartupLoading();
                getWindow().getDecorView().post(this::checkStoredSessionThenUpdate);
                return;
            }
        }
        showLogin("");
    }

    private String themeMode() {
        return normalizeThemeMode(prefs().getString(KEY_THEME_MODE, "system"));
    }

    private String normalizeThemeMode(String mode) {
        String value = mode == null ? "" : mode.trim();
        if ("light".equals(value) || "dark".equals(value)) {
            return value;
        }
        return "system";
    }

    private String themeModeLabel(String mode) {
        if ("light".equals(mode)) {
            return "明亮";
        }
        if ("dark".equals(mode)) {
            return "暗色";
        }
        return "跟随系统";
    }



    private void showSecurityManager(JSONObject response, String redirectPath) {
        LinearLayout card = screen("安全策略");
        JSONObject settings = response.optJSONObject("security_settings");
        card.addView(buildSecuritySettingsPanel(settings, redirectPath, true), blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private LinearLayout buildSecuritySettingsPanel(JSONObject settings, String redirectPath, boolean includeDescription) {
        JSONObject safeSettings = settings == null ? new JSONObject() : settings;
        LinearLayout panel = detailListPanel();
        if (includeDescription) {
            TextView summary = body("安全策略开启后，命中风险的用户会按服务端规则限制登录或上报。调试模式账号不受拦截影响。\n\nRoot/ADB/模拟定位/无障碍/抓包按对应检测项拦截；可疑包名会拦截安装列表中命中的 Magisk、Xposed、Reqable、HttpCanary、Charles 等风险包名。");
            summary.setTextColor(colorMuted());
            summary.setPadding(0, dp(2), 0, dp(10));
            panel.addView(summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        CheckBox banRoot = policyCheckBox("禁止 Root 用户", safeSettings.optBoolean("ban_root_users", false));
        CheckBox banAdb = policyCheckBox("禁止 ADB 用户", safeSettings.optBoolean("ban_adb_users", false));
        CheckBox banMock = policyCheckBox("禁止模拟定位用户", safeSettings.optBoolean("ban_fake_location_users", false));
        CheckBox banAccessibility = policyCheckBox("禁止异常无障碍用户", safeSettings.optBoolean("ban_accessibility_users", false));
        CheckBox banCapture = policyCheckBox("禁止抓包工具用户", safeSettings.optBoolean("ban_packet_capture_users", false));
        CheckBox banSuspiciousPackages = policyCheckBox("禁止可疑包名用户", safeSettings.optBoolean("ban_suspicious_packages_users", false));
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
        if (includeDescription) {
            panel.addView(banRoot, blockParams(0));
            panel.addView(banAdb, blockParams(0));
            panel.addView(banMock, blockParams(0));
            panel.addView(banAccessibility, blockParams(0));
            panel.addView(banCapture, blockParams(0));
            panel.addView(banSuspiciousPackages, blockParams(8));
        } else {
            panel.addView(compactPolicyRow("禁止 Root 用户", banRoot), blockParams(0));
            panel.addView(compactPolicyRow("禁止 ADB 用户", banAdb), blockParams(0));
            panel.addView(compactPolicyRow("禁止模拟定位用户", banMock), blockParams(0));
            panel.addView(compactPolicyRow("禁止异常无障碍用户", banAccessibility), blockParams(0));
            panel.addView(compactPolicyRow("禁止抓包工具用户", banCapture), blockParams(0));
            panel.addView(compactPolicyRow("禁止可疑包名用户", banSuspiciousPackages), blockParams(8));
        }
        panel.addView(save, blockParams(0));
        return panel;
    }

    private CheckBox policyCheckBox(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(colorText());
        box.setTextSize(12);
        box.setChecked(checked);
        box.setButtonTintList(controlTint());
        box.setIncludeFontPadding(false);
        box.setMinHeight(0);
        box.setMinimumHeight(0);
        box.setPadding(0, dp(2), 0, dp(2));
        return box;
    }

    private LinearLayout compactPolicyRow(String text, CheckBox box) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(1), 0, dp(1));

        box.setText("");
        box.setMinWidth(0);
        box.setMinimumWidth(0);
        box.setScaleX(0.84f);
        box.setScaleY(0.84f);

        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkParams.setMargins(0, 0, dp(2), 0);
        row.addView(box, checkParams);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(11.5f);
        label.setTextColor(colorText());
        label.setIncludeFontPadding(false);
        label.setPadding(0, dp(1), 0, dp(1));
        row.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> box.setChecked(!box.isChecked()));
        return row;
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
                card.addView(sectionTitle(group.optString("display_name", groupName)), blockParams(8));
                card.addView(infoPanel("标识：" + groupName
                    + "\n成员：" + group.optInt("member_count")
                    + " / 组号：" + (group.optString("group_code", "").isEmpty() ? "未生成" : group.optString("group_code", ""))), blockParams(8));
                Button owner = secondaryButton("设置管理员");
                owner.setOnClickListener(view -> showGroupOwnerPicker(memberships, groupName, ownerUserIdValue, groupId, redirectPath));
                Button delete = secondaryButton("删除家庭组");
                delete.setOnClickListener(view -> confirmDanger("确定删除这个家庭组？组内定位记录会一并清除。", () -> {
                    JSONObject payload = adminPayload("delete_family_group");
                    putJson(payload, "group_id", groupId);
                    postAdminAction(payload, redirectPath);
                }));
                card.addView(owner, blockParams(8));
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

        EditText search = input("搜索成员名称、账号或用户 ID");
        card.addView(search, blockParams(8));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<Button> options = new ArrayList<>();
        List<String> searchKeys = new ArrayList<>();
        options.add(addGroupOwnerOption(list, dialog, "无，不指定管理员", 0, currentOwnerUserId, groupId, redirectPath));
        searchKeys.add("无 不指定 管理员");

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
                options.add(addGroupOwnerOption(list, dialog, label, userId, currentOwnerUserId, groupId, redirectPath));
                searchKeys.add((label + " " + membership.optString("username", "") + " " + userId).toLowerCase(java.util.Locale.ROOT));
                count += 1;
            }
        }
        if (count == 0) {
            list.addView(infoPanel("当前家庭组暂无成员。"), blockParams(8));
        }
        bindSearchFilter(search, options, searchKeys);

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

    private Button addGroupOwnerOption(LinearLayout list, Dialog dialog, String label, int ownerUserId, int currentOwnerUserId, int groupId, String redirectPath) {
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
        return option;
    }

    private void showAnnouncementManager(JSONObject response, String redirectPath) {
        LinearLayout card = screen("公告管理");
        card.addView(buildAnnouncementManagerPanel(response, redirectPath, false), blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private void showInviteManager(JSONObject response, String redirectPath) {
        inviteManagerOpen = true;
        LinearLayout card = screen("邀请码管理");
        Button back = secondaryButton("返回后台首页");
        back.setOnClickListener(view -> {
            inviteManagerScrollY = activeScrollView == null ? 0 : activeScrollView.getScrollY();
            inviteManagerOpen = false;
            showAdminDashboard(lastAdminSummary == null ? response : lastAdminSummary, redirectPath);
        });
        card.addView(back, blockParams(12));
        card.addView(buildInviteManagerPanel(response, redirectPath, 20, false), blockParams(0));
        setScreen(card, false);
        if (activeScrollView != null && inviteManagerScrollY > 0) {
            activeScrollView.post(() -> activeScrollView.scrollTo(0, inviteManagerScrollY));
        }
        logViewBounds("UI_ADMIN_INVITE_BACK_BOUNDS", back);
        Log.i(TAG, "UI_ADMIN_INVITE_STATE=manager");
        setStatus("");
    }

    private LinearLayout buildAnnouncementManagerPanel(JSONObject response, String redirectPath, boolean compactMode) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        JSONObject announcement = response.optJSONObject("announcement");
        if (compactMode) {
            panel.addView(infoPanel(announcementSummaryText(announcement)), blockParams(12));
            Button open = secondaryButton("打开公告管理");
            open.setOnClickListener(view -> showAnnouncementManager(lastAdminSummary == null ? response : lastAdminSummary, redirectPath));
            panel.addView(open, blockParams(0));
            return panel;
        }
        EditText title = input("公告标题");
        EditText body = input("公告内容");
        body.setSingleLine(false);
        body.setMinLines(5);
        CheckBox active = policyCheckBox("启用公告", false);
        if (announcement != null) {
            title.setText(announcement.optString("title", ""));
            body.setText(announcement.optString("body", ""));
            active.setChecked(announcement.optBoolean("is_active", false));
            panel.addView(infoPanel("当前版本：" + announcement.optInt("version", 1) + "\n更新时间：" + announcement.optString("updated_at", "")), blockParams(12));
        }
        Button save = primaryButton("保存公告");
        save.setOnClickListener(view -> {
            JSONObject payload = adminPayload("save_announcement");
            putJson(payload, "title", title.getText().toString());
            putJson(payload, "body", body.getText().toString());
            putJson(payload, "is_active", active.isChecked());
            postAdminAction(payload, redirectPath);
        });
        panel.addView(title, blockParams(10));
        panel.addView(body, blockParams(10));
        panel.addView(active, blockParams(12));
        panel.addView(save, blockParams(0));
        return panel;
    }

    private LinearLayout buildInviteManagerPanel(JSONObject response, String redirectPath, int maxItems, boolean compactMode) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        JSONArray invites = response.optJSONArray("invites");
        JSONArray groups = response.optJSONArray("groups");
        if (compactMode) {
            if (invites == null || invites.length() == 0) {
                panel.addView(infoPanel("暂无邀请码。"), blockParams(12));
            } else {
                int count = Math.min(invites.length(), Math.max(1, maxItems));
                for (int index = 0; index < count; index += 1) {
                    JSONObject invite = invites.optJSONObject(index);
                    if (invite == null) {
                        continue;
                    }
                    panel.addView(compactInfoPanel(inviteCompactSummary(invite)), blockParams(index == count - 1 ? 12 : 8));
                }
            }
            Button open = secondaryButton("打开邀请码管理");
            open.setOnClickListener(view -> openInviteManagerFromDashboard(response, redirectPath));
            panel.addView(open, blockParams(0));
            return panel;
        }
        EditText code = input("自定义邀请码（可留空）");
        code.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(64)});
        TextView codeRule = body("留空时自动生成 8 位英文字母+数字；自定义邀请码需为 4 至 64 位英文字母或数字。");
        codeRule.setTextColor(colorMuted());
        EditText note = input("备注");
        EditText maxUses = input("最大使用次数");
        maxUses.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxUses.setText("1");
        CheckBox groupCreate = policyCheckBox("创建家庭组邀请码", false);
        CheckBox allowOwner = policyCheckBox("注册人成为家庭组管理员", false);
        String[] selectedGroup = new String[] {firstGroupName(groups)};
        Button selectGroup = secondaryButton("");
        updateRequiredGroupButton(selectGroup, groups, selectedGroup[0]);
        selectGroup.setOnClickListener(view -> showRequiredGroupPicker(groups, selectedGroup, selectGroup));
        Button add = primaryButton("添加邀请码");
        add.setOnClickListener(view -> {
            String customCode = code.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (!customCode.isEmpty() && !customCode.matches("^[0-9a-z]{4,64}$")) {
                code.setError("自定义邀请码需为 4 至 64 位英文字母或数字");
                code.requestFocus();
                return;
            }
            code.setError(null);
            if (selectedGroup[0] == null || selectedGroup[0].trim().isEmpty()) {
                setStatus("请先选择邀请码绑定的家庭组。");
                return;
            }
            JSONObject payload = adminPayload("add_invite_code");
            putJson(payload, "code", customCode);
            putJson(payload, "note", note.getText().toString());
            putJson(payload, "invite_type", groupCreate.isChecked() ? "group_create" : "invite");
            putJson(payload, "allow_group_owner", allowOwner.isChecked());
            putJson(payload, "max_uses", parseInt(maxUses.getText().toString(), 1));
            putJson(payload, "assigned_group_name", selectedGroup[0]);
            postAdminAction(payload, redirectPath);
        });
        panel.addView(code, blockParams(4));
        panel.addView(codeRule, blockParams(8));
        panel.addView(note, blockParams(8));
        panel.addView(maxUses, blockParams(8));
        panel.addView(groupCreate, blockParams(6));
        panel.addView(allowOwner, blockParams(10));
        panel.addView(selectGroup, blockParams(10));
        panel.addView(add, blockParams(16));

        panel.addView(sectionTitle("最近邀请码"), blockParams(8));
        if (invites == null || invites.length() == 0) {
            panel.addView(infoPanel("暂无邀请码。"), blockParams(12));
        } else {
            int count = Math.min(invites.length(), Math.max(1, maxItems));
            for (int index = 0; index < count; index += 1) {
                JSONObject invite = invites.optJSONObject(index);
                if (invite == null) {
                    continue;
                }
                int inviteId = invite.optInt("id", 0);
                boolean enabled = invite.optBoolean("is_active", false);
                LinearLayout inviteRow = new LinearLayout(this);
                inviteRow.setOrientation(LinearLayout.VERTICAL);
                TextView inviteInfo = infoPanel(inviteSummaryText(invite));
                inviteRow.addView(inviteInfo, blockParams(6));
                String[] boundGroup = new String[] {invite.optString("assigned_group_name", "")};
                Button bindGroup = secondaryButton("");
                updateRequiredGroupButton(bindGroup, groups, boundGroup[0]);
                bindGroup.setOnClickListener(view -> showRequiredGroupPicker(groups, boundGroup, bindGroup));
                Button saveGroup = secondaryButton("保存绑定组");
                saveGroup.setOnClickListener(view -> {
                    if (boundGroup[0] == null || boundGroup[0].trim().isEmpty()) {
                        setStatus("请选择邀请码绑定的家庭组。");
                        return;
                    }
                    JSONObject payload = adminPayload("update_invite_group");
                    putJson(payload, "invite_id", inviteId);
                    putJson(payload, "assigned_group_name", boundGroup[0]);
                    saveGroup.setEnabled(false);
                    postAdminActionInline(payload, message -> {
                        putJson(invite, "assigned_group_name", boundGroup[0]);
                        inviteInfo.setText(inviteSummaryText(invite));
                        saveGroup.setEnabled(true);
                        showTransientFeedback(message);
                    }, error -> {
                        saveGroup.setEnabled(true);
                        setStatus(error);
                    });
                });
                Button editNote = secondaryButton("编辑备注");
                editNote.setOnClickListener(view -> showInviteNoteDialog(invite, inviteId, inviteInfo));
                Button toggle = secondaryButton(enabled ? "停用邀请码" : "启用邀请码");
                toggle.setOnClickListener(view -> {
                    boolean nextEnabled = !invite.optBoolean("is_active", false);
                    JSONObject payload = adminPayload("toggle_invite_code");
                    putJson(payload, "invite_id", inviteId);
                    putJson(payload, "next", nextEnabled);
                    toggle.setEnabled(false);
                    postAdminActionInline(payload, message -> {
                        putJson(invite, "is_active", nextEnabled);
                        inviteInfo.setText(inviteSummaryText(invite));
                        toggle.setText(nextEnabled ? "停用邀请码" : "启用邀请码");
                        toggle.setEnabled(true);
                        showTransientFeedback(message);
                    }, error -> {
                        toggle.setEnabled(true);
                        setStatus(error);
                    });
                });
                Button delete = secondaryButton("删除邀请码");
                delete.setOnClickListener(view -> confirmDanger("确定删除这个邀请码？", () -> {
                    JSONObject payload = adminPayload("delete_invite_code");
                    putJson(payload, "invite_id", inviteId);
                    delete.setEnabled(false);
                    postAdminActionInline(payload, message -> {
                        panel.removeView(inviteRow);
                        removeInviteFromArray(invites, inviteId);
                        showTransientFeedback(message);
                    }, error -> {
                        delete.setEnabled(true);
                        setStatus(error);
                    });
                }));
                inviteRow.addView(bindGroup, blockParams(6));
                inviteRow.addView(saveGroup, blockParams(8));
                inviteRow.addView(editNote, blockParams(8));
                inviteRow.addView(buttonRow(toggle, delete), blockParams(10));
                panel.addView(inviteRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        return panel;
    }

    private void removeInviteFromArray(JSONArray invites, int inviteId) {
        if (invites == null || inviteId <= 0) {
            return;
        }
        for (int index = 0; index < invites.length(); index += 1) {
            JSONObject invite = invites.optJSONObject(index);
            if (invite != null && invite.optInt("id", 0) == inviteId) {
                invites.remove(index);
                return;
            }
        }
    }

    private String announcementSummaryText(JSONObject announcement) {
        if (announcement == null) {
            return "暂无公告。";
        }
        String title = firstText(announcement.optString("title", ""), "未设置标题");
        String body = announcement.optString("body", "").trim();
        String status = announcement.optBoolean("is_active", false) ? "启用" : "停用";
        String updatedAt = firstText(announcement.optString("updated_at", ""), "无");
        if (body.length() > 60) {
            body = body.substring(0, 60) + "…";
        }
        return "状态：" + status
            + "\n标题：" + title
            + "\n内容：" + firstText(body, "暂无内容")
            + "\n更新时间：" + updatedAt;
    }

    private String inviteSummaryText(JSONObject invite) {
        boolean enabled = invite != null && invite.optBoolean("is_active", false);
        return (invite == null ? "" : invite.optString("code", "")) + " / " + (invite == null ? "" : invite.optString("invite_type_label", ""))
            + "\n状态：" + (enabled ? "启用" : "停用")
            + " / 使用：" + (invite == null ? 0 : invite.optInt("used_count")) + " / " + (invite == null ? 0 : invite.optInt("max_uses"))
            + "\n备注：" + firstText(invite == null ? "" : invite.optString("note", ""), "无")
            + "\n绑定家庭组：" + firstText(invite == null ? "" : invite.optString("assigned_group_name", ""), "无");
    }

    private void showInviteNoteDialog(JSONObject invite, int inviteId, TextView inviteInfo) {
        Dialog dialog = choiceDialog("编辑邀请码备注");
        LinearLayout body = choiceDialogBody(dialog);
        EditText note = input("备注");
        note.setText(invite == null ? "" : invite.optString("note", ""));
        note.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(120)});
        Button save = primaryButton("保存");
        Button cancel = secondaryButton("取消");
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            JSONObject payload = adminPayload("update_invite_note");
            putJson(payload, "invite_id", inviteId);
            putJson(payload, "note", note.getText().toString());
            postAdminActionInline(payload, message -> {
                if (invite != null) {
                    putJson(invite, "note", note.getText().toString().trim());
                }
                if (inviteInfo != null) {
                    inviteInfo.setText(inviteSummaryText(invite));
                }
                dialog.dismiss();
                showTransientFeedback(message);
            }, error -> {
                note.setError(error);
                setStatus(error);
            });
        });
        body.addView(note, blockParams(10));
        body.addView(buttonRow(save, cancel), blockParams(0));
        showChoiceDialog(dialog, body);
    }

    private String inviteCompactSummary(JSONObject invite) {
        boolean enabled = invite != null && invite.optBoolean("is_active", false);
        return firstText(invite == null ? "" : invite.optString("code", ""), "未命名邀请码")
            + "\n状态：" + (enabled ? "启用" : "停用")
            + " / 使用：" + (invite == null ? 0 : invite.optInt("used_count")) + " / " + (invite == null ? 0 : invite.optInt("max_uses"))
            + "\n备注：" + firstText(invite == null ? "" : invite.optString("note", ""), "无")
            + "\n类型：" + firstText(invite == null ? "" : invite.optString("invite_type_label", ""), "邀请码");
    }

    private String firstGroupName(JSONArray groups) {
        if (groups == null) {
            return "";
        }
        for (int index = 0; index < groups.length(); index += 1) {
            JSONObject group = groups.optJSONObject(index);
            if (group != null && !group.optString("group_name", "").trim().isEmpty()) {
                return group.optString("group_name", "").trim();
            }
        }
        return "";
    }

    private void updateRequiredGroupButton(Button button, JSONArray groups, String selectedGroup) {
        updateSelectedGroupButton(button, groups, selectedGroup, "绑定家庭组");
    }

    private void updateSelectedGroupButton(Button button, JSONArray groups, String selectedGroup, String labelPrefix) {
        String label = "选择" + labelPrefix;
        if (selectedGroup != null && !selectedGroup.trim().isEmpty() && groups != null) {
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group != null && selectedGroup.equals(group.optString("group_name", ""))) {
                    label = labelPrefix + "：" + firstText(group.optString("display_name", ""), selectedGroup);
                    break;
                }
            }
        }
        button.setText(label);
        button.setEnabled(groups != null && groups.length() > 0);
    }

    private void showRequiredGroupPicker(JSONArray groups, String[] selectedGroup, Button targetButton) {
        showGroupPicker(groups, selectedGroup, targetButton, "选择绑定家庭组", "绑定家庭组");
    }

    private void showGroupPicker(JSONArray groups, String[] selectedGroup, Button targetButton, String title, String labelPrefix) {
        Dialog dialog = choiceDialog(title);
        LinearLayout body = choiceDialogBody(dialog);
        if (groups == null || groups.length() == 0) {
            body.addView(infoPanel("暂无可选家庭组，请先创建家庭组。"), blockParams(0));
        } else {
            EditText search = input("搜索家庭组名称或组号");
            body.addView(search, blockParams(8));
            List<Button> options = new ArrayList<>();
            List<String> searchKeys = new ArrayList<>();
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group == null) {
                    continue;
                }
                String groupName = group.optString("group_name", "").trim();
                if (groupName.isEmpty()) {
                    continue;
                }
                String displayName = firstText(group.optString("display_name", ""), groupName);
                String groupCode = group.optString("group_code", "").trim();
                String suffix = groupCode.isEmpty() ? "" : "（" + groupCode + "）";
                Button option = secondaryButton((groupName.equals(selectedGroup[0]) ? "✓ " : "") + displayName + suffix);
                option.setOnClickListener(view -> {
                    selectedGroup[0] = groupName;
                    updateSelectedGroupButton(targetButton, groups, selectedGroup[0], labelPrefix);
                    dialog.dismiss();
                });
                options.add(option);
                searchKeys.add((displayName + " " + groupName + " " + groupCode).toLowerCase(java.util.Locale.ROOT));
                body.addView(option, blockParams(8));
            }
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence value, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence value, int start, int before, int count) {
                    String query = value == null ? "" : value.toString().trim().toLowerCase(java.util.Locale.ROOT);
                    for (int index = 0; index < options.size(); index += 1) {
                        options.get(index).setVisibility(query.isEmpty() || searchKeys.get(index).contains(query) ? View.VISIBLE : View.GONE);
                    }
                }

                @Override
                public void afterTextChanged(Editable value) {
                }
            });
        }
        showChoiceDialog(dialog, body);
    }

    private void showUserManager(JSONObject response, String redirectPath) {
        currentAdminTab = ADMIN_TAB_USERS;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
        LinearLayout card = screen("账号管理");
        JSONArray users = response.optJSONArray("users");
        JSONArray memberships = response.optJSONArray("memberships");
        JSONArray devices = response.optJSONArray("devices");
        JSONArray environmentReports = response.optJSONArray("environment_reports");

        card.addView(sectionTitle("新增账号"), blockParams(8));
        EditText addUsername = input("账号");
        EditText addPassword = input("密码");
        addPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText addDisplayName = input("显示名");
        JSONArray groups = response.optJSONArray("groups");
        String[] selectedInitialGroup = new String[] {firstGroupName(groups)};
        Button addGroup = secondaryButton("");
        updateSelectedGroupButton(addGroup, groups, selectedInitialGroup[0], "初始家庭组");
        addGroup.setOnClickListener(view -> showGroupPicker(groups, selectedInitialGroup, addGroup, "选择初始家庭组", "初始家庭组"));
        EditText addInterval = input("上报间隔秒");
        addInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        addInterval.setText("300");
        Spinner addRole = roleSpinner("monitor");
        CheckBox addDebug = policyCheckBox("调试模式", false);
        Button addUser = primaryButton("新增账号");
        addUser.setOnClickListener(view -> {
            JSONObject payload = adminPayload("add_user");
            putJson(payload, "username", addUsername.getText().toString());
            putJson(payload, "password", addPassword.getText().toString());
            putJson(payload, "display_name", addDisplayName.getText().toString());
            putJson(payload, "group_name", selectedInitialGroup[0]);
            putJson(payload, "role", roleValue(addRole));
            putJson(payload, "report_interval_seconds", parseInt(addInterval.getText().toString(), 300));
            putJson(payload, "debug_mode", addDebug.isChecked());
            postAdminAction(payload, redirectPath);
        });
        card.addView(addUsername, blockParams(6));
        card.addView(addPassword, blockParams(6));
        card.addView(addDisplayName, blockParams(6));
        card.addView(addGroup, blockParams(6));
        card.addView(addInterval, blockParams(6));
        card.addView(addRole, blockParams(4));
        card.addView(addDebug, blockParams(8));
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
                editUser.setOnClickListener(view -> showUserEditDialog(user, memberships, groups, redirectPath));
                Button deviceInfo = secondaryButton("设备信息");
                deviceInfo.setOnClickListener(view -> showUserDevicesDialog(user, devices, environmentReports, redirectPath));
                card.addView(buttonRow(editUser, deviceInfo), blockParams(10));
            }
        }
        setScreen(card, false);
        setStatus("");
    }

    private void showUserEditDialog(JSONObject user, JSONArray memberships, JSONArray groups, String redirectPath) {
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
        String[] selectedMembershipGroup = new String[] {firstGroupName(groups)};
        Button newGroup = secondaryButton("");
        updateSelectedGroupButton(newGroup, groups, selectedMembershipGroup[0], "家庭组");
        newGroup.setOnClickListener(view -> showGroupPicker(groups, selectedMembershipGroup, newGroup, "选择家庭组", "家庭组"));
        Spinner newGroupRole = roleSpinner("monitor");
        Button addMembership = secondaryButton("添加家庭组身份");
        addMembership.setOnClickListener(view -> {
            dialog.dismiss();
            JSONObject payload = adminPayload("add_membership");
            putJson(payload, "user_id", userId);
            putJson(payload, "group_name", selectedMembershipGroup[0]);
            putJson(payload, "role", roleValue(newGroupRole));
            postAdminAction(payload, redirectPath);
        });
        form.addView(newGroup, blockParams(6));
        form.addView(newGroupRole, blockParams(6));
        form.addView(addMembership, blockParams(8));
        addMembershipEditors(form, memberships, groups, userId, redirectPath);

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

    private void showUserDevicesDialog(JSONObject user, JSONArray devices, JSONArray environmentReports, String redirectPath) {
        if (user == null) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, "设备信息");
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int userId = user.optInt("id", 0);
        LinearLayout userSummary = detailListPanel();
        addDetailRow(userSummary, "账号", displayName(user));
        addDetailRow(userSummary, "设备", userDeviceSummary(devices, userId));
        list.addView(userSummary, blockParams(8));
        JSONObject environmentReport = environmentReportForUser(environmentReports, userId);
        addUserDeviceReportViews(list, environmentReport);
        addInstalledAppViews(list, environmentReport);

        list.addView(sectionTitle("设备绑定"), blockParams(8));
        int shown = 0;
        if (devices != null) {
            for (int index = 0; index < devices.length(); index += 1) {
                JSONObject device = devices.optJSONObject(index);
                if (device == null || device.optInt("user_id", 0) != userId) {
                    continue;
                }
                shown += 1;
                int deviceId = device.optInt("id", 0);
                LinearLayout devicePanel = detailListPanel();
                addDetailRow(devicePanel, "设备", "设备 #" + deviceId);
                addDetailRow(devicePanel, "设备指纹", firstText(device.optString("device_fingerprint", ""), "无"));
                addDetailRow(devicePanel, "浏览器指纹", firstText(device.optString("browser_fingerprint", ""), "无"));
                addDetailRow(devicePanel, "首次", firstText(device.optString("first_seen_at", ""), "无"));
                addDetailRow(devicePanel, "最近", firstText(device.optString("last_seen_at", ""), "无"));
                addDetailRow(devicePanel, "UA", firstText(device.optString("user_agent", ""), "无"));
                list.addView(devicePanel, blockParams(6));
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

    private void addUserDeviceReportViews(LinearLayout list, JSONObject report) {
        list.addView(sectionTitle("设备基础信息"), blockParams(8));
        if (report == null) {
            list.addView(infoPanel("暂无设备基础信息。用户需要登录一次新版客户端后才会上报。"), blockParams(8));
            return;
        }
        JSONObject device = report.optJSONObject("device");
        if (device == null) {
            list.addView(infoPanel("暂无设备基础信息。"), blockParams(8));
            return;
        }
        LinearLayout panel = detailListPanel();
        addDetailRow(panel, "上报时间", firstText(report.optString("device_report_created_at", ""), report.optString("created_at", "")));
        addDetailRow(panel, "设备", compactDeviceName(device));
        addDetailRow(panel, "系统", compactAndroidVersion(device));
        addDetailRow(panel, "App", compactAppVersion(device));
        addDetailRow(panel, "包名", device.optString("package_name", ""));
        addDetailRow(panel, "构建标识", joinTexts(" / ", device.optString("brand", ""), device.optString("device", ""), device.optString("product", "")));
        addDetailRow(panel, "硬件", joinTexts(" / ", device.optString("board", ""), device.optString("hardware", "")));
        addDetailRow(panel, "ABI", jsonArrayText(device.optJSONArray("supported_abis"), 6));
        addDetailRow(panel, "屏幕", compactScreen(device));
        addDetailRow(panel, "语言/时区", joinTexts(" / ", device.optString("locale", ""), device.optString("timezone", "")));
        addDetailRow(panel, "可见包数量", positiveIntText(device.optInt("installed_package_count", 0)));
        addDetailRow(panel, "内存", compactBytePair(device.optLong("memory_available_bytes", 0), device.optLong("memory_total_bytes", 0)));
        addDetailRow(panel, "存储", compactBytePair(device.optLong("storage_available_bytes", 0), device.optLong("storage_total_bytes", 0)));
        addDetailRow(panel, "ADB", boolLabel(device, "adb_enabled"));
        addDetailRow(panel, "Root", boolLabel(device, "root_detected"));
        addDetailRow(panel, "模拟定位风险", firstText(boolLabel(device, "mock_location_risk"), boolLabel(device, "mock_location_enabled")));
        addDetailRow(panel, "无障碍风险", boolLabel(device, "accessibility_risk"));
        addDetailRow(panel, "电池优化白名单", boolLabel(device, "battery_optimization_ignored"));
        addDetailRow(panel, "系统指纹", device.optString("fingerprint", ""));
        list.addView(panel, blockParams(10));
    }

    private void addInstalledAppViews(LinearLayout list, JSONObject report) {
        list.addView(sectionTitle("已安装软件"), blockParams(8));
        if (report == null) {
            list.addView(infoPanel("暂无安装软件上报。用户同意环境数据后才会上传应用列表。"), blockParams(8));
            return;
        }
        JSONArray apps = report.optJSONArray("installed_apps");
        int appCount = report.optInt("installed_apps_count", apps == null ? 0 : apps.length());
        if (apps == null || apps.length() == 0) {
            list.addView(infoPanel("暂无安装软件上报。用户同意环境数据后才会上传应用列表。"), blockParams(8));
            return;
        }
        LinearLayout summary = detailListPanel();
        list.addView(summary, blockParams(6));

        CheckBox showSystem = policyCheckBox("显示系统应用", false);
        list.addView(showSystem, blockParams(6));

        LinearLayout appList = detailListPanel();
        list.addView(appList, blockParams(10));
        showSystem.setOnCheckedChangeListener((buttonView, checked) -> renderInstalledAppList(appList, summary, apps, appCount, report.optString("apps_report_created_at", ""), checked));
        renderInstalledAppList(appList, summary, apps, appCount, report.optString("apps_report_created_at", ""), false);
    }

    private void renderInstalledAppList(LinearLayout appList, LinearLayout summary, JSONArray apps, int appCount, String reportedAt, boolean showSystem) {
        appList.removeAllViews();
        summary.removeAllViews();
        int visible = 0;
        int systemCount = 0;
        int limit = 100;
        for (int index = 0; index < apps.length(); index += 1) {
            JSONObject app = apps.optJSONObject(index);
            if (app == null) {
                continue;
            }
            boolean system = app.optBoolean("system", false);
            if (system) {
                systemCount += 1;
            }
            if (system && !showSystem) {
                continue;
            }
            String packageName = app.optString("package_name", "").trim();
            if (packageName.isEmpty()) {
                continue;
            }
            if (visible >= limit) {
                continue;
            }
            visible += 1;
            String value = joinTexts(" / ",
                app.optString("label", ""),
                app.optString("version_name", ""),
                system ? "系统应用" : "用户应用"
            );
            addDetailRow(appList, packageName, value);
        }
        if (visible == 0) {
            addDetailRow(appList, "暂无用户应用", showSystem ? "没有可显示的应用。" : "勾选显示系统应用可查看系统包。");
        }
        if (visible >= limit && apps.length() > visible) {
            addDetailRow(appList, "显示限制", "仅显示前 " + limit + " 个。");
        }
        int userAppCount = Math.max(0, appCount - systemCount);
        String time = firstText(reportedAt, "未知时间");
        addDetailRow(summary, "上报时间", time);
        addDetailRow(summary, "当前显示", visible + " 个");
        addDetailRow(summary, "应用统计", "用户应用 " + userAppCount + " 个，系统应用 " + systemCount + " 个。");
    }

    private LinearLayout detailListPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(6), dp(12), dp(6));
        panel.setBackground(panelBackground());
        return panel;
    }

    private void addDetailRow(LinearLayout panel, String label, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return;
        }
        if (panel.getChildCount() > 0) {
            panel.addView(detailDivider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView title = new TextView(this);
        title.setText(label == null ? "" : label);
        title.setTextColor(colorText());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(13.5f);
        title.setIncludeFontPadding(false);
        row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(colorMuted());
        body.setTextSize(12f);
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(0, dp(5), 0, 0);
        row.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View detailDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(colorOutline());
        return divider;
    }

    private void addMembershipEditors(LinearLayout card, JSONArray memberships, JSONArray groups, int userId, String redirectPath) {
        if (memberships == null) {
            return;
        }
        for (int index = 0; index < memberships.length(); index += 1) {
            JSONObject membership = memberships.optJSONObject(index);
            if (membership == null || membership.optInt("user_id", 0) != userId) {
                continue;
            }
            int membershipId = membership.optInt("id", 0);
            String[] selectedGroup = new String[] {membership.optString("group_name", "")};
            Button groupName = secondaryButton("");
            updateSelectedGroupButton(groupName, groups, selectedGroup[0], "成员家庭组");
            groupName.setOnClickListener(view -> showGroupPicker(groups, selectedGroup, groupName, "选择成员家庭组", "成员家庭组"));
            Spinner role = roleSpinner(membership.optString("role", ""));
            Button save = secondaryButton("保存成员关系");
            save.setOnClickListener(view -> {
                JSONObject payload = adminPayload("update_membership");
                putJson(payload, "membership_id", membershipId);
                putJson(payload, "group_name", selectedGroup[0]);
                putJson(payload, "role", roleValue(role));
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
            card.addView(role, blockParams(6));
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
                LinearLayout ticketPanel = detailListPanel();
                renderAdminTicketSummary(ticketPanel, ticket);
                card.addView(ticketPanel, blockParams(8));
                EditText reply = input("回复内容");
                reply.setSingleLine(false);
                reply.setMinLines(3);
                Button send = secondaryButton("回复工单");
                send.setOnClickListener(view -> {
                    JSONObject payload = adminPayload("reply_ticket");
                    putJson(payload, "ticket_id", ticketId);
                    putJson(payload, "reply", reply.getText().toString());
                    send.setEnabled(false);
                    postAdminActionInline(payload, message -> {
                        putJson(ticket, "last_message", reply.getText().toString().trim());
                        putJson(ticket, "updated_at", "刚刚");
                        renderAdminTicketSummary(ticketPanel, ticket);
                        reply.setText("");
                        send.setEnabled(true);
                        showTransientFeedback(message);
                    }, error -> {
                        send.setEnabled(true);
                        reply.setError(error);
                        setStatus(error);
                    });
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

    private void renderAdminTicketSummary(LinearLayout ticketPanel, JSONObject ticket) {
        if (ticketPanel == null || ticket == null) {
            return;
        }
        ticketPanel.removeAllViews();
        addDetailRow(ticketPanel, "用户", joinTexts(" / ", displayName(ticket), ticket.optString("group_name", "")));
        addDetailRow(ticketPanel, "状态", ticket.optString("status_label", ""));
        addDetailRow(ticketPanel, "更新时间", ticket.optString("updated_at", ""));
        addDetailRow(ticketPanel, "最后消息", ticket.optString("last_message", ""));
    }

    private void showLogManager(JSONObject response, String redirectPath) {
        currentAdminTab = ADMIN_TAB_LOGS;
        lastAdminSummary = response;
        currentRedirectPath = redirectPath == null ? ADMIN_UPDATE_PATH : redirectPath;
        LinearLayout card = screen("日志");
        JSONArray logs = response.optJSONArray("logs");
        int total = response.optInt("log_total", logs == null ? 0 : logs.length());
        card.addView(compactInfoPanel(logFilterSummary(total, logs == null ? 0 : logs.length())), blockParams(8));
        Button filter = secondaryButton("筛选日志");
        filter.setOnClickListener(view -> showLogFilterDialog(response, redirectPath));
        Button clear = secondaryButton("清空筛选");
        clear.setOnClickListener(view -> {
            resetLogFilters();
            loadAdminSummary(redirectPath);
        });
        card.addView(buttonRow(filter, clear), blockParams(12));
        if (logs == null || logs.length() == 0) {
            card.addView(infoPanel(hasLogFilters() ? "暂无符合筛选的日志。" : "暂无日志。"), blockParams(12));
        } else {
            int count = Math.min(logs.length(), 50);
            for (int index = 0; index < count; index += 1) {
                JSONObject log = logs.optJSONObject(index);
                if (log == null) {
                    continue;
                }
                LinearLayout logPanel = detailListPanel();
                addDetailRow(logPanel, "消息", firstText(log.optString("message", ""), log.optString("event_type", "日志")));
                addDetailRow(logPanel, "用户", joinTexts(" / ", firstText(displayName(log), "系统/后台"), log.optString("event_type", "")));
                addDetailRow(logPanel, "家庭组", firstText(log.optString("group_name", ""), "无"));
                addDetailRow(logPanel, "IP", firstText(log.optString("ip", ""), "无"));
                addDetailRow(logPanel, "时间", log.optString("created_at", ""));
                card.addView(logPanel, blockParams(6));
                Button detailLog = secondaryButton("查看详情");
                detailLog.setOnClickListener(view -> showLogDetail(log, response.optJSONObject("log_locations")));
                card.addView(detailLog, blockParams(10));
            }
        }
        Button refresh = secondaryButton("刷新日志");
        refresh.setOnClickListener(view -> loadAdminSummary(redirectPath));
        card.addView(refresh, blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private String logFilterSummary(int total, int visible) {
        List<String> parts = new ArrayList<>();
        if (!logFilterGroup.trim().isEmpty()) {
            parts.add("家庭组 " + logFilterGroup.trim());
        }
        if (logFilterUserId > 0) {
            parts.add("用户 ID " + logFilterUserId);
        }
        if (!logFilterType.trim().isEmpty()) {
            parts.add("类型 " + ("session".equals(logFilterType.trim()) ? "上线/下线" : logFilterType.trim()));
        }
        if (!logFilterKeyword.trim().isEmpty()) {
            parts.add("关键字 " + logFilterKeyword.trim());
        }
        String filterText = parts.isEmpty() ? "全部日志" : joinParts(parts, " / ");
        return "筛选：" + filterText + "\n显示：" + visible + " / " + total + " 条";
    }

    private boolean hasLogFilters() {
        return !logFilterGroup.trim().isEmpty()
            || logFilterUserId > 0
            || !logFilterType.trim().isEmpty()
            || !logFilterKeyword.trim().isEmpty();
    }

    private void resetLogFilters() {
        logFilterGroup = "";
        logFilterType = "";
        logFilterKeyword = "";
        logFilterUserId = 0;
        logFilterLimit = 60;
    }

    private void showLogFilterDialog(JSONObject response, String redirectPath) {
        Dialog dialog = new Dialog(this);
        LinearLayout form = dialogCard(dialog, "筛选日志");

        final String[] nextGroup = new String[] {logFilterGroup};
        final int[] nextUserId = new int[] {logFilterUserId};
        final String[] nextType = new String[] {logFilterType};

        Button group = secondaryButton("");
        updateLogGroupFilterButton(group, response.optJSONArray("groups"), nextGroup[0]);
        group.setOnClickListener(view -> showLogGroupPicker(response.optJSONArray("groups"), nextGroup, group));

        Button userId = secondaryButton("");
        updateLogUserFilterButton(userId, response.optJSONArray("users"), nextUserId[0]);
        userId.setOnClickListener(view -> showLogUserPicker(response.optJSONArray("users"), nextUserId, userId));

        Button type = secondaryButton("");
        updateLogTypeFilterButton(type, response.optJSONArray("log_types"), nextType[0]);
        type.setOnClickListener(view -> showLogTypePicker(response.optJSONArray("log_types"), nextType, type));

        EditText keyword = input("关键字");
        keyword.setText(logFilterKeyword);
        EditText limit = input("显示条数");
        limit.setInputType(InputType.TYPE_CLASS_NUMBER);
        limit.setText(String.valueOf(logFilterLimit));

        form.addView(group, blockParams(6));
        form.addView(userId, blockParams(6));
        form.addView(type, blockParams(6));
        form.addView(keyword, blockParams(6));
        form.addView(limit, blockParams(12));

        Button cancel = secondaryButton("取消");
        cancel.setOnClickListener(view -> dialog.dismiss());
        Button apply = primaryButton("应用筛选");
        apply.setOnClickListener(view -> {
            logFilterGroup = nextGroup[0] == null ? "" : nextGroup[0].trim();
            logFilterUserId = Math.max(0, nextUserId[0]);
            logFilterType = nextType[0] == null ? "" : nextType[0].trim();
            logFilterKeyword = keyword.getText().toString().trim();
            logFilterLimit = boundedInt(parseInt(limit.getText().toString(), 60), 20, 200);
            dialog.dismiss();
            loadAdminSummary(redirectPath);
        });
        form.addView(buttonRow(cancel, apply), blockParams(0));
        showCardDialog(dialog);
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
        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);

        detail.addView(sectionTitle("定位信息"), blockParams(6));
        LinearLayout locationPanel = detailListPanel();
        addDetailRow(locationPanel, "用户", displayName(location));
        addDetailRow(locationPanel, "家庭组", location.optString("group_name", ""));
        addDetailRow(locationPanel, "身份", location.optString("role_label", ""));
        addDetailRow(locationPanel, "位置", formatCoordinate(location.optDouble("latitude")) + ", " + formatCoordinate(location.optDouble("longitude")));
        addDetailRow(locationPanel, "时间", location.optString("updated_at", ""));
        detail.addView(locationPanel, blockParams(8));

        detail.addView(sectionTitle("地址诊断"), blockParams(8));
        LinearLayout addressPanel = detailListPanel();
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        if (diagnostics == null) {
            addDetailRow(addressPanel, "状态", "无");
        } else {
            addDetailRow(addressPanel, "首选来源", diagnostics.optString("preferred_source", ""));
            addDetailRow(addressPanel, "首选地址", firstText(diagnostics.optString("preferred_address", ""), locationAddress(location), "未解析"));
            addDetailRow(addressPanel, "首选城市", diagnostics.optString("preferred_city", ""));
            addDetailRow(addressPanel, "检查时间", diagnostics.optString("checked_at", ""));
            addDetailRow(addressPanel, "状态", addressDiagnosticStatus(location, diagnostics));
        }
        detail.addView(addressPanel, blockParams(8));

        if (diagnostics != null) {
            JSONArray sources = diagnostics.optJSONArray("sources");
            if (sources == null || sources.length() == 0) {
                LinearLayout sourcePanel = detailListPanel();
                addDetailRow(sourcePanel, "来源", "无");
                detail.addView(sourcePanel, blockParams(8));
            } else {
                detail.addView(sectionTitle("来源与响应数据"), blockParams(8));
                for (int index = 0; index < sources.length(); index += 1) {
                    JSONObject source = sources.optJSONObject(index);
                    if (source == null) {
                        continue;
                    }
                    LinearLayout sourcePanel = detailListPanel();
                    addDetailRow(sourcePanel, "来源", (index + 1) + ". " + locationSourceTitle(source));
                    addDetailRow(sourcePanel, "类型", source.optString("type", ""));
                    addDetailRow(sourcePanel, "地址", source.optString("address", ""));
                    addDetailRow(sourcePanel, "城市", source.optString("city", ""));
                    addDetailRow(sourcePanel, "省/地区", source.optString("region", ""));
                    addDetailRow(sourcePanel, "国家", source.optString("country", ""));
                    addDetailRow(sourcePanel, "IP", firstText(source.optString("ip", ""), source.optString("ipv4", ""), source.optString("ipv6", "")));
                    addDetailRow(sourcePanel, "STUN", firstText(source.optString("stun_label", ""), source.optString("stun_server", "")));
                    if (!Double.isNaN(source.optDouble("latitude", Double.NaN)) && !Double.isNaN(source.optDouble("longitude", Double.NaN))) {
                        addDetailRow(sourcePanel, "位置", formatCoordinate(source.optDouble("latitude")) + ", " + formatCoordinate(source.optDouble("longitude")));
                    }
                    addDetailRow(sourcePanel, "保存响应", prettyJson(source));
                    detail.addView(sourcePanel, blockParams(8));
                }
            }
        }

        addEnvironmentReportDetail(detail, location);

        showStructuredDetailDialog("定位详情", detail);
    }

    private void addEnvironmentReportDetail(LinearLayout detail, JSONObject location) {
        JSONObject report = location.optJSONObject("environment_report");
        if (report == null) {
            report = environmentReportForUser(location.optInt("user_id", 0));
        }
        detail.addView(sectionTitle("设备/环境报告"), blockParams(8));
        LinearLayout reportPanel = detailListPanel();
        if (report == null) {
            addDetailRow(reportPanel, "状态", "无");
            detail.addView(reportPanel, blockParams(8));
            return;
        }
        addDetailRow(reportPanel, "上报时间", report.optString("created_at", ""));
        JSONObject device = report.optJSONObject("device");
        if (device != null) {
            addDetailRow(reportPanel, "设备", compactDeviceName(device));
            addDetailRow(reportPanel, "系统", compactAndroidVersion(device));
            addDetailRow(reportPanel, "App", compactAppVersion(device));
            addDetailRow(reportPanel, "Root", boolLabel(device, "root_detected"));
            addDetailRow(reportPanel, "ADB", boolLabel(device, "adb_enabled"));
            addDetailRow(reportPanel, "模拟定位", boolLabel(device, "mock_location_enabled"));
        }
        JSONArray apps = report.optJSONArray("installed_apps");
        int appCount = report.optInt("installed_apps_count", apps == null ? 0 : apps.length());
        addDetailRow(reportPanel, "安装应用", appCount + " 个");
        detail.addView(reportPanel, blockParams(8));
        if (apps != null && apps.length() > 0) {
            detail.addView(sectionTitle("安装应用样本"), blockParams(8));
            LinearLayout appsPanel = detailListPanel();
            int count = Math.min(appCount, Math.min(apps.length(), 40));
            for (int index = 0; index < count; index += 1) {
                JSONObject app = apps.optJSONObject(index);
                if (app == null) {
                    continue;
                }
                String packageName = app.optString("package_name", "");
                String versionName = app.optString("version_name", "");
                addDetailRow(appsPanel, firstText(app.optString("label", ""), packageName, "未知应用"), joinTexts(" / ", packageName, versionName));
            }
            if (apps.length() > count || appCount > count) {
                addDetailRow(appsPanel, "显示限制", "仅显示前 " + count + " 个");
            }
            detail.addView(appsPanel, blockParams(8));
        }
        JSONObject rawReport = report.optJSONObject("report");
        if (rawReport != null) {
            detail.addView(sectionTitle("环境报告 JSON"), blockParams(8));
            LinearLayout rawPanel = detailListPanel();
            addDetailRow(rawPanel, "JSON", prettyJson(rawReport));
            detail.addView(rawPanel, blockParams(8));
        }
    }

    private JSONObject environmentReportForUser(int userId) {
        if (userId <= 0 || lastAdminSummary == null) {
            return null;
        }
        return environmentReportForUser(lastAdminSummary.optJSONArray("environment_reports"), userId);
    }

    private JSONObject environmentReportForUser(JSONArray reports, int userId) {
        if (userId <= 0 || reports == null) {
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

    private String compactDeviceName(JSONObject device) {
        if (device == null) {
            return "";
        }
        return joinTexts(" ", firstText(device.optString("manufacturer", ""), device.optString("brand", "")),
            firstText(device.optString("model", ""), device.optString("device", "")));
    }

    private String compactAndroidVersion(JSONObject device) {
        if (device == null) {
            return "";
        }
        String release = device.optString("android_release", "").trim();
        String sdk = device.optString("android_sdk", "").trim();
        if (release.isEmpty() && sdk.isEmpty()) {
            return "";
        }
        return "Android " + firstText(release, "未知") + " / SDK " + firstText(sdk, "未知");
    }

    private String compactAppVersion(JSONObject device) {
        if (device == null) {
            return "";
        }
        String name = device.optString("app_version_name", "").trim();
        String code = device.optString("app_version_code", "").trim();
        if (name.isEmpty() && code.isEmpty()) {
            return "";
        }
        return firstText(name, "未知") + " (" + firstText(code, "0") + ")";
    }

    private String compactScreen(JSONObject device) {
        if (device == null) {
            return "";
        }
        int width = device.optInt("screen_width_px", 0);
        int height = device.optInt("screen_height_px", 0);
        int density = device.optInt("screen_density_dpi", 0);
        if (width <= 0 && height <= 0 && density <= 0) {
            return "";
        }
        return Math.max(0, width) + " x " + Math.max(0, height) + " / " + Math.max(0, density) + " dpi";
    }

    private String compactBytes(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        double gb = bytes / 1024d / 1024d / 1024d;
        if (gb >= 1d) {
            return String.format(java.util.Locale.US, "%.1f GB", gb);
        }
        return String.format(java.util.Locale.US, "%.0f MB", bytes / 1024d / 1024d);
    }

    private String compactBytePair(long availableBytes, long totalBytes) {
        String available = compactBytes(availableBytes);
        String total = compactBytes(totalBytes);
        if (available.isEmpty() && total.isEmpty()) {
            return "";
        }
        if (available.isEmpty()) {
            return total + " 总量";
        }
        if (total.isEmpty()) {
            return available + " 可用";
        }
        return available + " 可用 / " + total + " 总量";
    }

    private String positiveIntText(int value) {
        return value > 0 ? String.valueOf(value) : "";
    }

    private String jsonArrayText(JSONArray array, int limit) {
        if (array == null || array.length() == 0) {
            return "";
        }
        List<String> values = new ArrayList<>();
        int count = Math.min(array.length(), Math.max(1, limit));
        for (int index = 0; index < count; index += 1) {
            String value = array.optString(index, "").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return joinParts(values, ", ");
    }

    private String joinTexts(String delimiter, String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String text = value == null ? "" : value.trim();
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
        }
        return joinParts(parts, delimiter);
    }

    private String boolLabel(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return "";
        }
        return object.optBoolean(key, false) ? "是" : "否";
    }

    private void showLogDetail(JSONObject log, JSONObject logLocations) {
        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        LinearLayout panel = detailListPanel();
        addDetailRow(panel, "日志 ID", String.valueOf(log.optInt("id", 0)));
        addDetailRow(panel, "用户", firstText(displayName(log), "系统/后台"));
        addDetailRow(panel, "用户 ID", String.valueOf(log.optInt("user_id", 0)));
        addDetailRow(panel, "家庭组", firstText(log.optString("group_name", ""), "无"));
        addDetailRow(panel, "类型", log.optString("event_type", ""));
        addDetailRow(panel, "消息", log.optString("message", ""));
        addDetailRow(panel, "IP", log.optString("ip", ""));
        addDetailRow(panel, "时间", log.optString("created_at", ""));
        addDetailRow(panel, "User-Agent", log.optString("user_agent", ""));
        JSONObject meta = log.optJSONObject("meta");
        addDetailRow(panel, "Meta", meta == null ? "无" : prettyJson(meta));
        detail.addView(panel, blockParams(8));

        JSONObject relatedLocation = locationForLog(log, logLocations);
        if (relatedLocation != null) {
            detail.addView(sectionTitle("上报位置"), blockParams(6));
            LinearLayout locationPanel = detailListPanel();
            addDetailRow(locationPanel, "位置 ID", String.valueOf(relatedLocation.optInt("id", 0)));
            addDetailRow(locationPanel, "具体位置", firstText(locationAddress(relatedLocation), "未解析"));
            addDetailRow(locationPanel, "坐标", formatCoordinate(relatedLocation.optDouble("latitude")) + ", " + formatCoordinate(relatedLocation.optDouble("longitude")));
            addDetailRow(locationPanel, "时间", firstText(relatedLocation.optString("created_at", ""), relatedLocation.optString("updated_at", "")));
            detail.addView(locationPanel, blockParams(8));
            Button openLocation = secondaryButton("查看定位详情");
            openLocation.setOnClickListener(view -> showLocationDetail(relatedLocation));
            detail.addView(openLocation, blockParams(8));
        }

        showStructuredDetailDialog("日志详情", detail);
    }

    private JSONObject locationForLog(JSONObject log, JSONObject logLocations) {
        if (log == null || logLocations == null) {
            return null;
        }
        JSONObject meta = log.optJSONObject("meta");
        int locationId = meta == null ? 0 : meta.optInt("location_id", 0);
        if (locationId <= 0) {
            return null;
        }
        return logLocations.optJSONObject(String.valueOf(locationId));
    }

    private Dialog choiceDialog(String title) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setId(0x4c0c101);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView heading = sectionTitle(title);
        card.addView(heading, blockParams(12));
        dialog.setContentView(card);
        return dialog;
    }

    private LinearLayout choiceDialogBody(Dialog dialog) {
        LinearLayout card = dialog.findViewById(0x4c0c101);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    private void showChoiceDialog(Dialog dialog, LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        ViewGroup parent = body.getParent() instanceof ViewGroup ? (ViewGroup) body.getParent() : null;
        if (parent != null) {
            parent.removeView(body);
            scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            parent.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(420), (int) (getResources().getDisplayMetrics().heightPixels * 0.56f))));
            Button close = secondaryButton("关闭");
            close.setOnClickListener(view -> dialog.dismiss());
            parent.addView(close, blockParams(0));
        }
        showCardDialog(dialog);
    }

    private void updateLogGroupFilterButton(Button button, JSONArray groups, String selectedGroup) {
        String label = "家庭组：全部";
        if (selectedGroup != null && !selectedGroup.trim().isEmpty() && groups != null) {
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group != null && selectedGroup.equals(group.optString("group_name", ""))) {
                    label = "家庭组：" + firstText(group.optString("display_name", ""), group.optString("group_name", ""), selectedGroup);
                    break;
                }
            }
        }
        button.setText(label);
    }

    private void updateLogUserFilterButton(Button button, JSONArray users, int selectedUserId) {
        String label = "用户：全部";
        if (selectedUserId > 0 && users != null) {
            for (int index = 0; index < users.length(); index += 1) {
                JSONObject user = users.optJSONObject(index);
                if (user != null && user.optInt("id", 0) == selectedUserId) {
                    label = "用户：" + firstText(displayName(user), user.optString("username", ""), String.valueOf(selectedUserId));
                    break;
                }
            }
        }
        button.setText(label);
    }

    private void updateLogTypeFilterButton(Button button, JSONArray logTypes, String selectedType) {
        String value = selectedType == null ? "" : selectedType.trim();
        String label = "事件类型：全部";
        if (!value.isEmpty()) {
            label = "事件类型：" + ("session".equals(value) ? "上线/下线" : value);
        }
        button.setText(label);
    }

    private void showLogGroupPicker(JSONArray groups, String[] selectedGroup, Button targetButton) {
        Dialog dialog = choiceDialog("选择家庭组");
        LinearLayout body = choiceDialogBody(dialog);
        EditText search = input("搜索家庭组名称或组号");
        body.addView(search, blockParams(8));
        List<Button> options = new ArrayList<>();
        List<String> searchKeys = new ArrayList<>();
        Button all = secondaryButton((selectedGroup[0] == null || selectedGroup[0].trim().isEmpty() ? "✓ " : "") + "全部家庭组");
        all.setOnClickListener(view -> {
            selectedGroup[0] = "";
            updateLogGroupFilterButton(targetButton, groups, selectedGroup[0]);
            dialog.dismiss();
        });
        body.addView(all, blockParams(8));
        options.add(all);
        searchKeys.add("全部 家庭组");
        if (groups != null) {
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group == null) {
                    continue;
                }
                String groupName = group.optString("group_name", "");
                String displayName = firstText(group.optString("display_name", ""), groupName);
                Button option = secondaryButton((groupName.equals(selectedGroup[0]) ? "✓ " : "") + displayName);
                option.setOnClickListener(view -> {
                    selectedGroup[0] = groupName;
                    updateLogGroupFilterButton(targetButton, groups, selectedGroup[0]);
                    dialog.dismiss();
                });
                body.addView(option, blockParams(8));
                options.add(option);
                searchKeys.add((displayName + " " + groupName + " " + group.optString("group_code", "")).toLowerCase(java.util.Locale.ROOT));
            }
        }
        bindSearchFilter(search, options, searchKeys);
        showChoiceDialog(dialog, body);
    }

    private void showLogUserPicker(JSONArray users, int[] selectedUserId, Button targetButton) {
        Dialog dialog = choiceDialog("选择用户");
        LinearLayout body = choiceDialogBody(dialog);
        EditText search = input("搜索显示名、账号或用户 ID");
        body.addView(search, blockParams(8));
        List<Button> options = new ArrayList<>();
        List<String> searchKeys = new ArrayList<>();
        Button all = secondaryButton(selectedUserId[0] <= 0 ? "✓ 全部用户" : "全部用户");
        all.setOnClickListener(view -> {
            selectedUserId[0] = 0;
            updateLogUserFilterButton(targetButton, users, selectedUserId[0]);
            dialog.dismiss();
        });
        body.addView(all, blockParams(8));
        options.add(all);
        searchKeys.add("全部 用户");
        if (users != null) {
            for (int index = 0; index < users.length(); index += 1) {
                JSONObject user = users.optJSONObject(index);
                if (user == null) {
                    continue;
                }
                int userId = user.optInt("id", 0);
                Button option = secondaryButton((userId == selectedUserId[0] ? "✓ " : "") + firstText(displayName(user), user.optString("username", ""), String.valueOf(userId)));
                option.setOnClickListener(view -> {
                    selectedUserId[0] = userId;
                    updateLogUserFilterButton(targetButton, users, selectedUserId[0]);
                    dialog.dismiss();
                });
                body.addView(option, blockParams(8));
                options.add(option);
                searchKeys.add((displayName(user) + " " + user.optString("username", "") + " " + userId).toLowerCase(java.util.Locale.ROOT));
            }
        }
        bindSearchFilter(search, options, searchKeys);
        showChoiceDialog(dialog, body);
    }

    private void bindSearchFilter(EditText search, List<Button> options, List<String> searchKeys) {
        if (search == null) {
            return;
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                String query = value == null ? "" : value.toString().trim().toLowerCase(java.util.Locale.ROOT);
                int optionCount = Math.min(options.size(), searchKeys.size());
                for (int index = 0; index < optionCount; index += 1) {
                    options.get(index).setVisibility(query.isEmpty() || searchKeys.get(index).contains(query) ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
    }

    private void showLogTypePicker(JSONArray logTypes, String[] selectedType, Button targetButton) {
        Dialog dialog = choiceDialog("选择事件类型");
        LinearLayout body = choiceDialogBody(dialog);
        Button all = secondaryButton((selectedType[0] == null || selectedType[0].trim().isEmpty() ? "✓ " : "") + "全部事件");
        all.setOnClickListener(view -> {
            selectedType[0] = "";
            updateLogTypeFilterButton(targetButton, logTypes, selectedType[0]);
            dialog.dismiss();
        });
        body.addView(all, blockParams(8));

        Button session = secondaryButton("session".equals(selectedType[0]) ? "✓ 上线/下线" : "上线/下线");
        session.setOnClickListener(view -> {
            selectedType[0] = "session";
            updateLogTypeFilterButton(targetButton, logTypes, selectedType[0]);
            dialog.dismiss();
        });
        body.addView(session, blockParams(8));

        if (logTypes != null) {
            for (int index = 0; index < logTypes.length(); index += 1) {
                String type = logTypes.optString(index, "").trim();
                if (type.isEmpty() || "online".equals(type) || "offline".equals(type)) {
                    continue;
                }
                Button option = secondaryButton((type.equals(selectedType[0]) ? "✓ " : "") + type);
                option.setOnClickListener(view -> {
                    selectedType[0] = type;
                    updateLogTypeFilterButton(targetButton, logTypes, selectedType[0]);
                    dialog.dismiss();
                });
                body.addView(option, blockParams(8));
            }
        }
        showChoiceDialog(dialog, body);
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
        title.setTextSize(16);
        card.addView(title, blockParams(12));
        dialog.setContentView(card);
        return card;
    }

    private void showStructuredDetailDialog(String titleText, LinearLayout detailContent) {
        Dialog dialog = new Dialog(this);
        LinearLayout card = dialogCard(dialog, titleText);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(detailContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = blockParams(12);
        int availableHeight = getResources().getDisplayMetrics().heightPixels - topSafePadding() - dp(170);
        scrollParams.height = Math.max(dp(180), Math.min(dp(460), availableHeight));
        card.addView(scroll, scrollParams);

        Button close = secondaryButton("关闭");
        close.setOnClickListener(view -> dialog.dismiss());
        card.addView(close, blockParams(0));
        showCardDialog(dialog);
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
        if (!adminWriteInFlight.compareAndSet(false, true)) {
            setStatus("后台操作正在提交，请勿重复点击。");
            return;
        }
        if (inviteManagerOpen && activeScrollView != null) {
            inviteManagerScrollY = activeScrollView.getScrollY();
        }
        setStatus("正在提交后台操作。");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject response = postJson(AdminApiPaths.ADMIN_MANAGE, payload);
                String message = response.optString("message", "后台操作已完成。");
                runUi(() -> {
                    showTransientFeedback(message);
                    loadAdminSummary(redirectPath);
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (isAdminLoginExpired(exception)) {
                        handleAdminLoginExpired();
                        return;
                    }
                    setStatus(exception.getMessage());
                });
            } finally {
                adminWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            adminWriteInFlight.set(false);
        }
    }

    private void postAdminActionInline(JSONObject payload, java.util.function.Consumer<String> onSuccess, java.util.function.Consumer<String> onError) {
        if (!adminWriteInFlight.compareAndSet(false, true)) {
            String message = "后台操作正在提交，请勿重复点击。";
            setStatus(message);
            if (onError != null) {
                onError.accept(message);
            }
            return;
        }
        setStatus("正在提交后台操作。");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject response = postJson(AdminApiPaths.ADMIN_MANAGE, payload);
                String message = response.optString("message", "后台操作已完成。");
                runUi(() -> {
                    if (onSuccess != null) {
                        onSuccess.accept(message);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (isAdminLoginExpired(exception)) {
                        handleAdminLoginExpired();
                        return;
                    }
                    if (onError != null) {
                        onError.accept(exception.getMessage());
                    } else {
                        setStatus(exception.getMessage());
                    }
                });
            } finally {
                adminWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            adminWriteInFlight.set(false);
            String message = "请求过多，请稍候再试。";
            if (onError != null) {
                onError.accept(message);
            }
        }
    }

    private void showTransientFeedback(String message) {
        String text = firstText(message, "操作已完成。");
        setStatus(text);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
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

    private int boundedInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String joinParts(List<String> parts, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(delimiter);
            }
            builder.append(part.trim());
        }
        return builder.toString();
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
            return "网络出口与定位不同，可能为 VPN/代理";
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
        return API_CLIENT.get(serverUrl() + endpoint, cookieHeader(), this::mergeCookieHeader);
    }

    private JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        return API_CLIENT.post(serverUrl() + endpoint, payload, cookieHeader(), this::mergeCookieHeader);
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
        List<String> cookies = sessionCookieEntries();
        for (String cookie : parseCookieEntries(header, false)) {
            mergeCookieEntry(cookies, cookie);
        }
        saveSessionCookies(cookies);
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
        List<String> cookies = sessionCookieEntries();
        mergeCookieEntry(cookies, DEVICE_COOKIE_NAME + "=" + deviceCookieValue());
        return joinCookies(cookies);
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

    private List<String> sessionCookieEntries() {
        return parseCookieEntries(readSessionCookie(), false);
    }

    private String readSessionCookie() {
        String stored = prefs().getString(KEY_SESSION_COOKIE, "");
        String normalized = joinCookies(parseCookieEntries(stored, false));
        if (!normalized.equals(stored == null ? "" : stored)) {
            prefs().edit().putString(KEY_SESSION_COOKIE, normalized).apply();
        }
        return normalized;
    }

    private void saveSessionCookies(List<String> cookies) {
        prefs().edit().putString(KEY_SESSION_COOKIE, joinCookies(cookies)).apply();
    }

    private List<String> parseCookieEntries(String header, boolean includeDeviceCookie) {
        List<String> cookies = new ArrayList<>();
        if (header == null || header.trim().isEmpty()) {
            return cookies;
        }
        for (String item : header.split(";")) {
            String cookie = item.trim();
            if (cookie.isEmpty() || !cookie.contains("=")) {
                continue;
            }
            String name = cookieName(cookie);
            if (name.isEmpty() || isCookieAttribute(name)) {
                continue;
            }
            if (!includeDeviceCookie && DEVICE_COOKIE_NAME.equalsIgnoreCase(name)) {
                continue;
            }
            mergeCookieEntry(cookies, cookie);
        }
        return cookies;
    }

    private void mergeCookieEntry(List<String> cookies, String cookie) {
        String name = cookieName(cookie);
        if (name.isEmpty()) {
            return;
        }
        for (int index = cookies.size() - 1; index >= 0; index -= 1) {
            if (name.equalsIgnoreCase(cookieName(cookies.get(index)))) {
                cookies.remove(index);
            }
        }
        cookies.add(cookie);
    }

    private String cookieName(String cookie) {
        if (cookie == null || !cookie.contains("=")) {
            return "";
        }
        return cookie.split("=", 2)[0].trim();
    }

    private String safeTokenPrefix(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty()) {
            return "empty";
        }
        int end = Math.min(16, value.length());
        return value.substring(0, end) + "(len=" + value.length() + ")";
    }

    private LinearLayout screen(String titleText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(cardBackground());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        title.setTextColor(colorText());
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button theme = secondaryButton("主题");
        theme.setOnClickListener(view -> showThemePicker());
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        themeParams.setMargins(0, 0, adminLoggedIn ? dp(8) : 0, 0);
        actions.addView(theme, themeParams);

        if (adminLoggedIn) {
            Button logout = secondaryButton("退出");
            logout.setOnClickListener(view -> logoutAdmin());
            actions.addView(logout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        header.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header, blockParams(8));

        if (adminLoggedIn) {
            TextView account = new TextView(this);
            account.setText("已登录：" + storedAdminUsername());
            account.setTextSize(11.5f);
            account.setTextColor(colorMuted());
            account.setIncludeFontPadding(false);
            card.addView(account, blockParams(8));
        }

        statusView = body("");
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusView.setBackground(pillBackground());
        statusView.setVisibility(View.GONE);
        card.addView(statusView, blockParams(10));
        return card;
    }

    private void setScreen(LinearLayout card) {
        setScreen(card, false);
    }

    private void setCenteredScreen(LinearLayout card) {
        setScreen(card, true);
    }

    private void setScreen(LinearLayout card, boolean center) {
        content = card;
        if (center) {
            activeScrollView = null;
            LinearLayout root = baseScreenRoot(Gravity.CENTER);
            root.addView(card, centeredCardLayoutParams());
            setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            animateScreen(root, true);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        activeScrollView = scroll;
        LinearLayout root = baseScreenRoot(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
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

    private LinearLayout baseScreenRoot(int gravity) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(gravity);
        root.setPadding(dp(14), topSafePadding(), dp(14), dp(12));
        root.setBackgroundColor(colorSurface());
        return root;
    }

    private LinearLayout.LayoutParams centeredCardLayoutParams() {
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(24);
        int preferredWidth = Math.min(dp(420), availableWidth);
        int minimumWidth = Math.min(dp(280), availableWidth);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Math.max(preferredWidth, minimumWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    private LinearLayout adminBottomNavigation() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(14), dp(4), dp(14), dp(8));
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

    private void logViewBounds(String marker, View view) {
        if (marker == null || marker.trim().isEmpty() || view == null) {
            return;
        }
        view.post(() -> {
            if (!view.isAttachedToWindow() || view.getWidth() <= 0 || view.getHeight() <= 0) {
                return;
            }
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            Log.i(TAG, marker + "="
                + location[0] + "," + location[1] + ","
                + (location[0] + view.getWidth()) + ","
                + (location[1] + view.getHeight()));
        });
    }

    private View adminNavButton(String icon, String label, int tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(dp(46));
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
        iconView.setTextSize(active ? 18 : 16);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTypeface(Typeface.DEFAULT_BOLD);
        iconView.setTextColor(active ? colorPrimary() : colorMuted());

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(9);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        labelView.setTextColor(active ? colorPrimary() : colorMuted());

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
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        view.setTextColor(colorText());
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView startupBadge(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(11.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        view.setTextColor(colorPrimary());
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        view.setBackground(pillBackground());
        return view;
    }

    private TextView startupTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(19);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setIncludeFontPadding(false);
        view.setTextColor(colorText());
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView startupSubtitle(String text) {
        TextView view = body(text);
        view.setTextColor(colorMuted());
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(2), 1.15f);
        return view;
    }

    private LinearLayout startupChecklist() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        addStartupChecklistItem(panel, "登录状态", "自动恢复上次后台会话");
        addStartupChecklistItem(panel, "后台更新", "进入前检查是否需要下载新版本");
        addStartupChecklistItem(panel, "管理入口", "通过后直接进入后台首页");
        return panel;
    }

    private void addStartupChecklistItem(LinearLayout panel, String titleText, String detailText) {
        if (panel.getChildCount() > 0) {
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.setMargins(0, dp(10), 0, dp(10));
            panel.addView(detailDivider(), dividerParams);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        View marker = new View(this);
        marker.setBackground(roundedDrawable(withAlpha(colorPrimary(), 58), dp(999)));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        markerParams.setMargins(0, dp(6), dp(12), 0);
        row.addView(marker, markerParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);

        TextView title = sectionTitle(titleText);
        title.setPadding(0, 0, 0, 0);
        copy.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView detail = body(detailText);
        detail.setTextColor(colorMuted());
        detail.setPadding(0, dp(6), 0, 0);
        copy.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout sectionHeading(String titleText, String badgeText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = sectionTitle(titleText);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, titleParams);

        String badgeValue = badgeText == null ? "" : badgeText.trim();
        if (!badgeValue.isEmpty()) {
            TextView badge = new TextView(this);
            badge.setText(badgeValue);
            badge.setTextSize(11);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(colorPrimary());
            badge.setIncludeFontPadding(false);
            badge.setPadding(dp(10), dp(5), dp(10), dp(5));
            badge.setBackground(pillBackground());
            row.addView(badge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    private final class StartupSignalView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();
        private final RectF beam = new RectF();
        private float phase;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                phase = (phase + 0.032f) % 1f;
                invalidate();
                postDelayed(this, 16L);
            }
        };

        StartupSignalView(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(resolveSize(dp(164), widthMeasureSpec), dp(64));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float radius = dp(18);
            float centerY = height * 0.58f;
            float trackLeft = dp(24);
            float trackRight = width - dp(24);

            frame.set(dp(1), dp(1), width - dp(1), height - dp(1));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isDarkMode() ? Color.rgb(19, 29, 27) : Color.rgb(246, 250, 249));
            canvas.drawRoundRect(frame, radius, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(colorOutline());
            canvas.drawRoundRect(frame, radius, radius, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(colorPrimary(), isDarkMode() ? 46 : 34));
            beam.set(trackLeft, centerY - dp(4), trackRight, centerY + dp(4));
            canvas.drawRoundRect(beam, dp(999), dp(999), paint);

            float scanWidth = dp(48);
            float scanLeft = trackLeft + (trackRight - trackLeft - scanWidth) * phase;
            beam.set(scanLeft, centerY - dp(5), scanLeft + scanWidth, centerY + dp(5));
            paint.setColor(withAlpha(colorPrimary(), isDarkMode() ? 128 : 110));
            canvas.drawRoundRect(beam, dp(999), dp(999), paint);

            float[] stations = new float[] {0.16f, 0.5f, 0.84f};
            for (int index = 0; index < stations.length; index += 1) {
                float x = trackLeft + (trackRight - trackLeft) * stations[index];
                float pulse = 0.45f + (((float) Math.sin((phase * Math.PI * 2f) + (index * 0.95f)) + 1f) * 0.275f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(colorPrimary(), Math.round(255f * pulse)));
                canvas.drawCircle(x, centerY, dp(4), paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(withAlpha(colorPrimary(), Math.round(150f * pulse)));
                canvas.drawCircle(x, centerY, dp(8), paint);
            }

            float labelTop = dp(14);
            float barWidth = dp(10);
            float barGap = dp(6);
            float startX = (width - ((barWidth * 4) + (barGap * 3))) / 2f;
            paint.setStyle(Paint.Style.FILL);
            for (int index = 0; index < 4; index += 1) {
                float progress = ((phase + (index * 0.12f)) % 1f);
                float barHeight = dp(6) + (dp(10) * (0.35f + progress * 0.65f));
                float left = startX + index * (barWidth + barGap);
                frame.set(left, labelTop, left + barWidth, labelTop + barHeight);
                paint.setColor(withAlpha(colorPrimary(), 170 + (index * 14)));
                canvas.drawRoundRect(frame, dp(3), dp(3), paint);
            }
        }
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

    private LinearLayout simpleSummaryPanel(String label, String value) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackground(panelBackground());

        TextView title = sectionTitle(label);
        title.setPadding(0, 0, 0, 0);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(colorOutline());
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(8), 0, dp(8));
        panel.addView(divider, dividerParams);

        TextView body = body(value);
        body.setTextColor(colorMuted());
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private void setSummaryPanelValue(LinearLayout panel, String value) {
        TextView last = summaryPanelValueView(panel);
        if (last != null) {
            last.setText(value == null ? "" : value);
        }
    }

    private TextView summaryPanelValueView(LinearLayout panel) {
        if (panel == null || panel.getChildCount() == 0) {
            return null;
        }
        View last = panel.getChildAt(panel.getChildCount() - 1);
        return last instanceof TextView ? (TextView) last : null;
    }


    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setTextSize(13);
        view.setTextColor(colorText());
        view.setHintTextColor(colorMuted());
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setMinHeight(dp(44));
        view.setBackground(strokedDrawable(colorSurfaceContainer(), dp(10), colorOutline(), dp(1)));
        return view;
    }

    private Spinner roleSpinner(String selectedRole) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[] {"监护端", "被监护端"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection("guardian".equals(selectedRole) ? 1 : 0);
        spinner.setMinimumHeight(dp(44));
        spinner.setPadding(dp(14), 0, dp(14), 0);
        spinner.setBackground(strokedDrawable(colorSurfaceContainer(), dp(10), colorOutline(), dp(1)));
        return spinner;
    }

    private String roleValue(Spinner spinner) {
        return spinner != null && spinner.getSelectedItemPosition() == 1 ? "guardian" : "monitor";
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorOnPrimary());
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(42));
        button.setIncludeFontPadding(false);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(buttonBackground(colorPrimary()));
        decorateButton(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorPrimary());
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(42));
        button.setIncludeFontPadding(false);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(strokedButtonBackground(colorSurfaceContainer(), colorOutline()));
        decorateButton(button);
        return button;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(colorSurfaceContainer());
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private GradientDrawable panelBackground() {
        return strokedDrawable(colorSurfaceContainer(), dp(8), colorOutline(), dp(1));
    }

    private GradientDrawable pillBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Material3Tokens.primaryContainer(isDarkMode()));
        drawable.setCornerRadius(dp(999));
        return drawable;
    }

    private GradientDrawable bottomNavBackground() {
        return strokedDrawable(colorSurfaceContainer(), dp(28), colorOutline(), dp(1));
    }

    private GradientDrawable navActiveBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Material3Tokens.primaryContainer(isDarkMode()));
        drawable.setCornerRadius(dp(20));
        return drawable;
    }

    private RippleDrawable transparentButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(16));
        return new RippleDrawable(ColorStateList.valueOf(colorRipple()), drawable, null);
    }

    private RippleDrawable buttonBackground(int color) {
        return new RippleDrawable(ColorStateList.valueOf(colorRipple()), roundedDrawable(color, dp(10)), null);
    }

    private RippleDrawable strokedButtonBackground(int color, int strokeColor) {
        return new RippleDrawable(ColorStateList.valueOf(colorRipple()), strokedDrawable(color, dp(10), strokeColor, dp(1)), null);
    }

    private GradientDrawable roundedDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable strokedDrawable(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = roundedDrawable(color, radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
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
        return Material3Tokens.surface(isDarkMode());
    }

    private int colorSurfaceContainer() {
        return Material3Tokens.surfaceContainer(isDarkMode());
    }

    private int colorText() {
        return Material3Tokens.onSurface(isDarkMode());
    }

    private int colorMuted() {
        return Material3Tokens.onSurfaceVariant(isDarkMode());
    }

    private int colorPrimary() {
        return Material3Tokens.primary(isDarkMode());
    }

    private int colorPrimarySoft() {
        return isDarkMode() ? Color.argb(120, 54, 182, 156) : Color.argb(92, 13, 95, 84);
    }

    private int colorOnPrimary() {
        return Material3Tokens.onPrimary(isDarkMode());
    }

    private int colorOutline() {
        return Material3Tokens.outline(isDarkMode());
    }

    private int colorRipple() {
        return Material3Tokens.ripple(isDarkMode());
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
            Math.max(0, Math.min(255, alpha)),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        );
    }

    private ColorStateList controlTint() {
        return new ColorStateList(
            new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked},
            },
            new int[]{colorPrimary(), colorMuted()}
        );
    }

    private boolean isDarkMode() {
        String mode = themeMode();
        if ("dark".equals(mode)) {
            return true;
        }
        if ("light".equals(mode)) {
            return false;
        }
        int systemMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return systemMode == Configuration.UI_MODE_NIGHT_YES;
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

    private boolean isAdminLoginExpired(Exception exception) {
        String message = exception == null ? "" : String.valueOf(exception.getMessage()).trim();
        return message.contains("请先登录后台");
    }

    private void handleAdminLoginExpired() {
        prefs().edit().remove(KEY_SESSION_COOKIE).apply();
        loginDraftPassword = "";
        setStatus("");
        showLogin("后台登录已失效，请重新登录。");
    }

    private void updateStartupStatus(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> updateStartupStatus(message));
            return;
        }
        if (startupStatusText != null) {
            startupStatusText.setText(message == null ? "" : message);
        }
    }

    private void finishStartupTransition(Runnable action) {
        Runnable complete = () -> {
            startupStatusText = null;
            startupShownAtMs = 0L;
            if (action != null) {
                action.run();
            }
        };
        if (startupShownAtMs <= 0L) {
            complete.run();
            return;
        }
        long elapsed = System.currentTimeMillis() - startupShownAtMs;
        long remaining = Math.max(0L, STARTUP_MIN_VISIBLE_MS - elapsed);
        if (remaining == 0L) {
            complete.run();
            return;
        }
        mainHandler.postDelayed(complete, remaining);
    }

    private boolean runBackground(Runnable runnable) {
        try {
            backgroundExecutor.execute(() -> {
                try {
                    runnable.run();
                } catch (Throwable throwable) {
                    Log.e(TAG, "Background task failed", throwable);
                    runUi(() -> setStatus("后台任务失败：" + exceptionMessage(throwable)));
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            setStatus("请求过多，请稍候再试。");
            return false;
        }
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
