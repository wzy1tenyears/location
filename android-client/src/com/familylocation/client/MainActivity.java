package com.familylocation.client;

import com.familylocation.net.JsonApiClient;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final class ChallengeCancelledException extends Exception {
    }

    private static final class OrderedLocationRecord {
        final JSONObject record;
        final long reportedAtMillis;
        final int sourceIndex;

        OrderedLocationRecord(JSONObject record, long reportedAtMillis, int sourceIndex) {
            this.record = record;
            this.reportedAtMillis = reportedAtMillis;
            this.sourceIndex = sourceIndex;
        }
    }

    private static final class IpGeoProbeResult {
        final int order;
        final String label;
        final JSONObject candidate;
        final String failureReason;

        IpGeoProbeResult(int order, String label, JSONObject candidate, String failureReason) {
            this.order = order;
            this.label = label;
            this.candidate = candidate;
            this.failureReason = failureReason == null ? "" : failureReason;
        }
    }

    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_NOTIFICATION = 1002;
    private static final int REQUEST_BACKGROUND_LOCATION = 1003;
    private static final int APP_VERSION_CODE = 146;
    private static final String APP_VERSION_NAME = "2.3.2";
    private static final JsonApiClient API_CLIENT = new JsonApiClient("loc-app/" + APP_VERSION_NAME, 12_000, 12_000);
    private static final JsonApiClient DIAGNOSTIC_API_CLIENT = new JsonApiClient("loc-app/" + APP_VERSION_NAME + " diagnostics", 1_500, 2_500);
    private static final JsonApiClient REPORT_API_CLIENT = new JsonApiClient("loc-app/" + APP_VERSION_NAME, 500, 750);
    private static final String PREFS = "family_location";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_GROUP_NAME = "group_name";
    private static final String KEY_GROUP_SESSIONS = "group_sessions_json";
    private static final String KEY_CROSS_GROUP_SYNC = "cross_group_sync_json";
    private static final String KEY_REPORT_INTERVAL_SECONDS = "report_interval_seconds";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_PENDING_UPDATE_INSTALL_ID = "pending_update_install_id";
    private static final String KEY_ACTIVE_UPDATE_DOWNLOAD_ID = "active_update_download_id";
    private static final String KEY_BACKGROUND_LOCATION_PROMPT_SHOWN = "background_location_prompt_shown";
    private static final String KEY_PRECISE_LOCATION_PROMPT_SHOWN = "precise_location_prompt_shown";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String KEY_SEEN_ANNOUNCEMENT_PREFIX = "announcement_seen_";
    private static final String KEY_ENVIRONMENT_REPORT_LAST_UPLOAD_PREFIX = "environment_report_last_upload_";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
    private static final String TAG = "FamilyLocationNative";
    private static final String UPDATE_APK_NAME = "location-release.apk";
    private static final long MAX_CACHE_BYTES = 50L * 1024L * 1024L;
    private static final long ENVIRONMENT_REPORT_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long REPORT_WATCHDOG_MS = 2_900L;
    private static final long ADDRESS_DIAGNOSTICS_BUDGET_MS = 300L;
    private static final long ADDRESS_ENRICHMENT_BUDGET_MS = 5_000L;
    private static final long LOGIN_PROBE_STARTUP_DEFER_MS = 300L;
    private static final long STARTUP_CONTENT_DEFER_MS = 32L;
    private static final String VIEW_TAG_DYNAMIC = "dynamic";
    private static final String VIEW_TAG_HOME_HISTORY = "home_history";
    private static final int TAB_POSITION = 0;
    private static final int TAB_GROUPS = 1;
    private static final int TAB_HELP = 2;
    private static final int TAB_MINE = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger backgroundThreadIndex = new AtomicInteger();
    private final ExecutorService backgroundExecutor = new ThreadPoolExecutor(
        4,
        6,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),
        runnable -> new Thread(runnable, "loc-native-" + backgroundThreadIndex.incrementAndGet()),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicInteger addressProbeThreadIndex = new AtomicInteger();
    private final ExecutorService addressProbeExecutor = new ThreadPoolExecutor(
        5,
        5,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(10),
        runnable -> new Thread(runnable, "loc-address-" + addressProbeThreadIndex.incrementAndGet()),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicInteger ipProviderThreadIndex = new AtomicInteger();
    private final ExecutorService ipProviderExecutor = new ThreadPoolExecutor(
        10,
        10,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(20),
        runnable -> new Thread(runnable, "loc-ip-provider-" + ipProviderThreadIndex.incrementAndGet()),
        new ThreadPoolExecutor.AbortPolicy()
    );
    private final Object ipGeocodeCacheLock = new Object();
    private final LinkedHashMap<String, JSONObject> ipGeocodeCache = new LinkedHashMap<>();
    private final LatestRequestGate historyRequestGate = new LatestRequestGate();
    private final LatestRequestGate ticketListRequestGate = new LatestRequestGate();
    private final LatestRequestGate ticketThreadRequestGate = new LatestRequestGate();
    private final LatestRequestGate p2pRequestGate = new LatestRequestGate();
    private final AtomicReference<JsonApiClient.RequestHandle> activeLocationRequest = new AtomicReference<>();
    private final AtomicReference<JsonApiClient.RequestHandle> activeHistoryRequest = new AtomicReference<>();
    private final AtomicReference<JsonApiClient.RequestHandle> activeTicketListRequest = new AtomicReference<>();
    private final AtomicReference<JsonApiClient.RequestHandle> activeTicketThreadRequest = new AtomicReference<>();
    private final AtomicBoolean ticketWriteInFlight = new AtomicBoolean();
    private final AtomicBoolean groupWriteInFlight = new AtomicBoolean();
    private LinearLayout content;
    private View activeChallengeCard;
    private WebView eventStreamWebView;
    private TextView statusView;
    private Button reportButton;
    private Button refreshButton;
    private JSONObject currentUser;
    private JSONObject legalDocuments;
    private String selectedGroupName = "";
    private int historyPage = 1;
    private int historyPageSize = 20;
    private int historyUserId = 0;
    private int historyRangeHours = 24;
    private int currentTab = TAB_POSITION;
    private boolean reporting;
    private final ReportAttemptGate reportAttemptGate = new ReportAttemptGate();
    private android.location.LocationManager reportLocationManager;
    private android.location.LocationListener reportLocationListener;
    private long reportLocationListenerToken;
    private Runnable reportWatchdog;
    private long reportStartedAtElapsedMs;
    private long updateDownloadId = -1L;
    private long pendingInstallDownloadId = -1L;
    private long installingDownloadId = -1L;
    private BroadcastReceiver updateReceiver;
    private WebView homeMapWebView;
    private boolean restoreHomeMapOnResume;
    private long locationRefreshGeneration;
    private long screenGeneration;
    private long lastNavigationActionAtElapsedMs;
    private JSONArray homeMapBaseRecords = new JSONArray();
    private JSONArray shareableLocationRecords = new JSONArray();
    private ScrollView activeScrollView;
    private FrontendRuntimeController frontendRuntime;
    private ClientUiStyle uiStyle;
    private final LoginScreenSessionProbe loginScreenSessionProbe = new LoginScreenSessionProbe();
    private final BackgroundLocationController backgroundLocationController = new BackgroundLocationController();
    private boolean batteryOptimizationPromptShown;
    private boolean exactAlarmPromptShown;
    private boolean notificationPermissionRequestInFlight;
    private boolean locationPermissionRequestInFlight;
    private boolean backgroundLocationPermissionRequestInFlight;
    private boolean accessibilitySettingsLaunched;
    private volatile int challengeGeneration;
    private volatile boolean challengeCancelled;
    private volatile boolean environmentReportUploading;
    private final AtomicBoolean announcementEventFetchInFlight = new AtomicBoolean(false);
    private volatile boolean manualAuthenticationStarted;
    private boolean loginScreenSessionProbeStarted;
    private String loginDraftUsername = "";
    private String loginDraftPassword = "";
    private boolean loginDraftTerms;
    private boolean registerDraftActive;
    private String registerDraftUsername = "";
    private String registerDraftDisplayName = "";
    private String registerDraftPassword = "";
    private String registerDraftPasswordConfirm = "";
    private String registerDraftInviteCode = "";
    private String registerDraftGroupCode = "";
    private boolean registerDraftTerms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long startupStartedAt = android.os.SystemClock.elapsedRealtime();
        super.onCreate(savedInstanceState);
        restoreRegisterDraft(savedInstanceState);
        uiStyle = new ClientUiStyle(this, this::isDarkModeFromPreference);
        frontendRuntime = new FrontendRuntimeController(this);
        showStartupSurface();
        mainHandler.postDelayed(() -> {
            try {
                startApp();
                Log.i(TAG, "PERF_COLD_START_FULL_UI_MS=" + Math.max(
                    0L,
                    android.os.SystemClock.elapsedRealtime() - startupStartedAt
                ));
            } catch (Throwable throwable) {
                showStartupCrash(throwable);
            }
        }, STARTUP_CONTENT_DEFER_MS);
    }

    private void restoreRegisterDraft(Bundle state) {
        if (state == null) {
            return;
        }
        registerDraftActive = state.getBoolean("register_draft_active", false);
        registerDraftUsername = state.getString("register_draft_username", "");
        registerDraftDisplayName = state.getString("register_draft_display_name", "");
        registerDraftInviteCode = state.getString("register_draft_invite_code", "");
        registerDraftGroupCode = state.getString("register_draft_group_code", "");
        registerDraftTerms = state.getBoolean("register_draft_terms", false);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("register_draft_active", registerDraftActive);
        state.putString("register_draft_username", registerDraftUsername);
        state.putString("register_draft_display_name", registerDraftDisplayName);
        state.putString("register_draft_invite_code", registerDraftInviteCode);
        state.putString("register_draft_group_code", registerDraftGroupCode);
        state.putBoolean("register_draft_terms", registerDraftTerms);
    }

    private void showStartupSurface() {
        TextView launchView = new TextView(this);
        launchView.setText("位置");
        launchView.setTextColor(uiStyle.colorPrimary());
        launchView.setTextSize(28f);
        launchView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        launchView.setGravity(Gravity.CENTER);
        launchView.setBackgroundColor(uiStyle.colorSurface());
        setContentView(launchView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void startApp() {
        configureWindow();

        String serverUrl = getStoredServerUrl();
        if (serverUrl.isEmpty()) {
            serverUrl = readAssetServerUrl();
        }

        if (serverUrl.isEmpty()) {
            showServerSetup();
            return;
        }

        String normalizedServerUrl = normalizeUrl(serverUrl);
        if (!normalizedServerUrl.equals(prefs().getString(KEY_SERVER_URL, ""))) {
            prefs().edit().putString(KEY_SERVER_URL, normalizedServerUrl).apply();
        }
        if (registerDraftActive) {
            showRegister();
        } else {
            showLogin();
        }
    }

    private void showStartupCrash(Throwable throwable) {
        Log.e(TAG, "Startup failed", throwable);
        String message = exceptionMessage(throwable);
        try {
            LinearLayout card = screen("启动失败");
            card.addView(body("App 启动时遇到异常，请截图发给开发者。"), blockParams(8));
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isDarkMode()) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void startLoginScreenSessionProbe() {
        if (loginScreenSessionProbeStarted) {
            return;
        }
        loginScreenSessionProbeStarted = true;
        runBackground(() -> {
            LoginScreenSessionProbe.Result result = loginScreenSessionProbe.run(new LoginScreenSessionProbe.Runtime() {
                @Override
                public JSONObject getJson(String endpoint) throws Exception {
                    return MainActivity.this.getJson(endpoint);
                }

                @Override
                public boolean hasStoredSession() {
                    return hasStoredSessionCookie();
                }
            }, APP_VERSION_CODE);
            runUi(() -> applyLoginScreenSessionProbeResult(result));
        });
    }

    private void applyLoginScreenSessionProbeResult(LoginScreenSessionProbe.Result result) {
        if (result.outcome == LoginScreenSessionProbe.Outcome.UPDATE_REQUIRED) {
            showUpdateRequired(result.versionName, result.apkUrl);
            return;
        }

        if (manualAuthenticationStarted) {
            return;
        }

        if (result.outcome == LoginScreenSessionProbe.Outcome.SESSION_RESTORED && result.user != null) {
            currentUser = result.user;
            persistUserSession(result.user, result.reportIntervalSeconds);
            showHome();
            uploadDailyEnvironmentReportIfDue();
            refreshLocations();
            return;
        }

        if (result.outcome == LoginScreenSessionProbe.Outcome.LOGIN_REQUIRED && hasStoredSessionCookie()) {
            clearStoredSessionState();
            setStatus("登录已失效，请重新登录。");
            return;
        }

        setStatus("");
    }

    private boolean hasStoredSessionCookie() {
        String sessionCookie = prefs().getString(KEY_SESSION_COOKIE, "");
        return sessionCookie != null && !sessionCookie.trim().isEmpty();
    }

    private void showServerSetup() {
        LinearLayout card = screen("服务器地址");
        LinearLayout description = simpleSummaryPanel("说明", "填写 HTTPS 服务器地址后继续使用。开发者也可以在 android-client/assets/server-url.txt 预置。");
        EditText input = input("https://example.com/");
        input.setSingleLine(true);
        Button save = primaryButton("保存并继续");
        save.setOnClickListener(view -> {
            String url = normalizeUrl(input.getText().toString());
            if (url.isEmpty() || !url.startsWith("https://")) {
                setStatus("请输入 HTTPS 服务器地址。");
                return;
            }
            prefs().edit().putString(KEY_SERVER_URL, url).apply();
            showLogin();
        });
        card.addView(description, blockParams(16));
        card.addView(input, blockParams(12));
        card.addView(save, blockParams(0));
        setScreen(card, true);
    }

    private void showLogin() {
        resetLoginScreenProbe();
        showLoginWithMessage("");
    }

    private void resetLoginScreenProbe() {
        loginScreenSessionProbeStarted = false;
        manualAuthenticationStarted = false;
    }

    private void showLoginWithMessage(String messageText) {
        LinearLayout card = authScreen("登录位置");
        EditText username = input("账号");
        EditText password = input("密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox terms = termsCheckBox();
        username.setText(loginDraftUsername);
        password.setText(loginDraftPassword);
        terms.setChecked(loginDraftTerms);

        Button login = primaryButton("登录");
        login.setOnClickListener(view -> login(username.getText().toString(), password.getText().toString(), terms.isChecked()));
        Button register = secondaryButton("注册账号");
        register.setOnClickListener(view -> showRegister());

        card.addView(username, blockParams(12));
        card.addView(password, blockParams(12));
        card.addView(termsRow(terms), blockParams(12));
        card.addView(login, blockParams(10));
        card.addView(register, blockParams(0));
        setScreen(card, true);
        String statusMessage = messageText == null ? "" : messageText.trim();
        if (statusMessage.isEmpty() && hasStoredSessionCookie()) {
            statusMessage = "正在检查登录状态…";
        }
        setStatus(statusMessage);
        mainHandler.postDelayed(this::startLoginScreenSessionProbe, LOGIN_PROBE_STARTUP_DEFER_MS);
    }

    private void showRegister() {
        showRegisterWithMessage("");
    }

    private void showRegisterWithMessage(String messageText) {
        registerDraftActive = true;
        LinearLayout card = authScreen("注册账号");
        EditText username = input("账号");
        EditText displayName = input("显示名称");
        EditText password = input("密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText passwordConfirm = input("再次输入密码");
        passwordConfirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText inviteCode = input("邀请码");
        EditText groupCode = input("家庭组号（部分邀请码可免填）");
        groupCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        CheckBox terms = termsCheckBox();
        username.setText(registerDraftUsername);
        displayName.setText(registerDraftDisplayName);
        password.setText(registerDraftPassword);
        passwordConfirm.setText(registerDraftPasswordConfirm);
        inviteCode.setText(registerDraftInviteCode);
        groupCode.setText(registerDraftGroupCode);
        terms.setChecked(registerDraftTerms);
        bindRegisterDraft(username, displayName, password, passwordConfirm, inviteCode, groupCode, terms);

        Button pasteInvite = secondaryButton("从剪贴板填入邀请码");
        pasteInvite.setOnClickListener(view -> offerClipboardInvite(inviteCode));
        Button submit = primaryButton("完成注册");
        submit.setOnClickListener(view -> register(
            username.getText().toString(),
            displayName.getText().toString(),
            password.getText().toString(),
            passwordConfirm.getText().toString(),
            inviteCode.getText().toString(),
            groupCode.getText().toString(),
            terms.isChecked()
        ));
        Button back = secondaryButton("返回登录");
        back.setOnClickListener(view -> {
            clearRegisterDraft();
            showLogin();
        });

        card.addView(username, blockParams(12));
        card.addView(displayName, blockParams(12));
        card.addView(password, blockParams(12));
        card.addView(passwordConfirm, blockParams(12));
        card.addView(inviteCode, blockParams(10));
        card.addView(pasteInvite, blockParams(12));
        card.addView(groupCode, blockParams(12));
        card.addView(termsRow(terms), blockParams(12));
        card.addView(submit, blockParams(10));
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus(messageText == null ? "" : messageText.trim());
    }

    private void offerClipboardInvite(EditText inviteCode) {
        String code = inviteCodeFromClipboardText(readClipboardText());
        if (code.isEmpty()) {
            setStatus("剪贴板中没有可识别的邀请码。");
            return;
        }
        showPopupDialog(
            "检测到邀请码",
            new String[][] {new String[] {"剪贴板", "是否把剪贴板中的邀请码填入注册表单？邀请码只会在你提交注册时发送。"}},
            "填入邀请码",
            () -> applyConfirmedClipboardInvite(code, inviteCode),
            "取消"
        );
    }

    private void applyConfirmedClipboardInvite(String approvedCode, EditText inviteCode) {
        if (!inviteCode.getText().toString().trim().isEmpty()) {
            return;
        }
        String code = sanitizeInviteCode(approvedCode);
        if (code.isEmpty() || !code.equals(approvedCode)) {
            return;
        }
        inviteCode.setText(code);
    }

    private void bindRegisterDraft(EditText username, EditText displayName, EditText password, EditText passwordConfirm, EditText inviteCode, EditText groupCode, CheckBox terms) {
        addDraftWatcher(username, value -> registerDraftUsername = value);
        addDraftWatcher(displayName, value -> registerDraftDisplayName = value);
        addDraftWatcher(password, value -> registerDraftPassword = value);
        addDraftWatcher(passwordConfirm, value -> registerDraftPasswordConfirm = value);
        addDraftWatcher(inviteCode, value -> registerDraftInviteCode = value);
        addDraftWatcher(groupCode, value -> registerDraftGroupCode = value);
        terms.setOnCheckedChangeListener((button, checked) -> registerDraftTerms = checked);
    }

    private void addDraftWatcher(EditText field, java.util.function.Consumer<String> consumer) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                consumer.accept(value == null ? "" : value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
    }

    private void clearRegisterDraft() {
        registerDraftActive = false;
        registerDraftUsername = "";
        registerDraftDisplayName = "";
        registerDraftPassword = "";
        registerDraftPasswordConfirm = "";
        registerDraftInviteCode = "";
        registerDraftGroupCode = "";
        registerDraftTerms = false;
    }

    private String readClipboardText() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) {
                return "";
            }
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0) == null) {
                return "";
            }
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return text == null ? "" : text.toString();
        } catch (Exception exception) {
            return "";
        }
    }

    private String inviteCodeFromClipboardText(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.matches("^[0-9a-zA-Z]{1,255}$")) {
            return sanitizeInviteCode(raw);
        }
        Matcher matcher = Pattern.compile("(?:邀请码|invite(?:\\s*code)?)[^0-9a-zA-Z]{0,12}([0-9a-zA-Z]{1,255})", Pattern.CASE_INSENSITIVE).matcher(raw);
        return matcher.find() ? sanitizeInviteCode(matcher.group(1)) : "";
    }

    private String sanitizeInviteCode(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z]", "");
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private CheckBox termsCheckBox() {
        CheckBox terms = new CheckBox(this);
        terms.setText("");
        terms.setMinWidth(0);
        terms.setMinimumWidth(0);
        terms.setPadding(0, 0, dp(6), 0);
        uiStyle.styleCheckBox(terms, denseUi());
        return terms;
    }

    private LinearLayout termsRow(CheckBox terms) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setBaselineAligned(false);
        row.addView(terms, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView textView = termsTextView();
        row.addView(textView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView termsTextView() {
        String text = "\u6211\u5df2\u540c\u610f\u7528\u6237\u534f\u8bae\u3001\u9690\u79c1\u6761\u7ea6\u548c\u7528\u6237\u6570\u636e\u8de8\u5883\u52a0\u5bc6\u4f20\u8f93\u534f\u8bae";
        TextView view = body("");
        SpannableString spannable = new SpannableString(text);
        addLegalLink(spannable, text, "\u7528\u6237\u534f\u8bae", "user_agreement", "\u7528\u6237\u534f\u8bae");
        addLegalLink(spannable, text, "\u9690\u79c1\u6761\u7ea6", "privacy_policy", "\u9690\u79c1\u6761\u7ea6");
        addLegalLink(spannable, text, "\u7528\u6237\u6570\u636e\u8de8\u5883\u52a0\u5bc6\u4f20\u8f93\u534f\u8bae", "cross_border_transfer", "\u7528\u6237\u6570\u636e\u8de8\u5883\u52a0\u5bc6\u4f20\u8f93\u534f\u8bae");
        view.setText(spannable);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setHighlightColor(Color.TRANSPARENT);
        view.setLinkTextColor(Color.rgb(13, 95, 84));
        view.setTextColor(colorMuted());
        view.setTextSize(denseUi() ? 13.5f : 13f);
        view.setLineSpacing(dp(2), 1.12f);
        return view;
    }

    private void addLegalLink(SpannableString spannable, String fullText, String label, String documentKey, String fallbackTitle) {
        int start = fullText.indexOf(label);
        if (start < 0) {
            return;
        }
        int end = start + label.length();
        spannable.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                openLegalDocument(documentKey, fallbackTitle);
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(Color.rgb(13, 95, 84));
                ds.setUnderlineText(false);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void openLegalDocument(String documentKey, String fallbackTitle) {
        setStatus("\u6b63\u5728\u52a0\u8f7d" + fallbackTitle);
        runBackground(() -> {
            try {
                JSONObject documents = legalDocuments;
                if (documents == null) {
                    JSONObject response = getJson(ApiPaths.LEGAL_DOCUMENTS);
                    documents = response.optJSONObject("documents");
                    if (documents == null) {
                        throw new IllegalStateException("\u670d\u52a1\u7aef\u672a\u8fd4\u56de\u534f\u8bae\u6587\u6863\u3002");
                    }
                    legalDocuments = documents;
                }
                JSONObject document = documents.optJSONObject(documentKey);
                if (document == null) {
                    throw new IllegalStateException("\u670d\u52a1\u7aef\u7f3a\u5c11" + fallbackTitle + "\u3002");
                }
                String title = document.optString("title", fallbackTitle);
                String[][] sections = parseLegalSections(document.optJSONArray("sections"));
                runUi(() -> {
                    setStatus("");
                    showPopupDialog(title, sections, "\u5173\u95ed", null, null);
                });
            } catch (Exception exception) {
                runUi(() -> {
                    setStatus("");
                    showPopupDialog(fallbackTitle, new String[][] {
                        {"加载失败", "协议文档需要从服务器读取，当前请求失败：" + exception.getMessage(), "请检查服务器地址、网络连接或 Cloudflare 规则后重试。"}
                    }, "关闭", null, null);
                });
            }        });
    }

    private String[][] parseLegalSections(JSONArray sectionsJson) {
        List<String[]> sections = new ArrayList<>();
        if (sectionsJson == null) {
            return new String[0][0];
        }
        for (int index = 0; index < sectionsJson.length(); index += 1) {
            JSONObject section = sectionsJson.optJSONObject(index);
            if (section == null) {
                continue;
            }
            JSONArray paragraphs = section.optJSONArray("paragraphs");
            int paragraphCount = paragraphs == null ? 0 : paragraphs.length();
            String[] items = new String[paragraphCount + 1];
            items[0] = section.optString("title", "");
            for (int paragraphIndex = 0; paragraphIndex < paragraphCount; paragraphIndex += 1) {
                items[paragraphIndex + 1] = paragraphs.optString(paragraphIndex, "");
            }
            sections.add(items);
        }
        return sections.toArray(new String[0][]);
    }

    private void register(String username, String displayName, String password, String passwordConfirm, String inviteCode, String groupCode, boolean termsAccepted) {
        String usernameValue = username.trim();
        String inviteCodeValue = inviteCode.trim();
        String groupCodeValue = GroupCodePolicy.normalize(groupCode);
        if (usernameValue.isEmpty() || password.isEmpty() || passwordConfirm.isEmpty() || inviteCodeValue.isEmpty()) {
            setStatus("请填写账号、密码、确认密码和邀请码。");
            return;
        }
        if (!usernameValue.matches("^[A-Za-z0-9_]{6,64}$")) {
            setStatus("账号需为 6 至 64 位英文字母、数字或下划线。");
            return;
        }
        if (!groupCodeValue.isEmpty() && !GroupCodePolicy.isAcceptedExisting(groupCodeValue)) {
            setStatus("家庭组号必须是 8 位字母或数字；已有的 32 位旧组号仍可加入。");
            return;
        }
        if (!termsAccepted) {
            setStatus("请先同意协议。");
            return;
        }

        manualAuthenticationStarted = true;
        registerDraftActive = true;
        registerDraftUsername = usernameValue;
        registerDraftDisplayName = displayName;
        registerDraftPassword = password;
        registerDraftPasswordConfirm = passwordConfirm;
        registerDraftInviteCode = inviteCodeValue;
        registerDraftGroupCode = groupCodeValue;
        registerDraftTerms = termsAccepted;
        setStatus("正在注册");
        runBackground(() -> {
            try {
                String challengeToken = completeAppChallenge("register");
                JSONObject payload = new JSONObject()
                    .put("username", usernameValue)
                    .put("display_name", displayName.trim())
                    .put("password", password)
                    .put("password_confirm", passwordConfirm)
                    .put("invite_code", inviteCodeValue)
                    .put("group_code", groupCodeValue)
                    .put("terms_accepted", true)
                    .put("cross_border_transfer_accepted", true)
                    .put("browser_fingerprint", deviceCookieValue())
                    .put("turnstile_token", challengeToken);
                JSONObject response = postJson(ApiPaths.REGISTER, payload);
                JSONObject user = response.optJSONObject("user");
                if (user == null) {
                    throw new IllegalStateException("注册成功但服务器未返回用户信息。");
                }

                int reportIntervalSeconds = response.optInt("report_interval_seconds", 300);
                runUi(() -> {
                    clearRegisterDraft();
                    currentUser = user;
                    persistUserSession(user, reportIntervalSeconds);
                    showHome();
                    uploadLoginDeviceReport();
                    uploadDailyEnvironmentReportIfDue();
                    refreshLocations();
                });
            } catch (ChallengeCancelledException exception) {
                Log.i(TAG, "Registration challenge cancelled.");
            } catch (Exception exception) {
                runUi(() -> showRegisterWithMessage(exception.getMessage()));
            }
        });
    }

    private void login(String username, String password, boolean termsAccepted) {
        if (username.trim().isEmpty() || password.isEmpty()) {
            setStatus("请输入账号和密码。");
            return;
        }
        if (!termsAccepted) {
            setStatus("请先同意协议。");
            return;
        }

        loginDraftUsername = username.trim();
        loginDraftPassword = password;
        loginDraftTerms = termsAccepted;
        manualAuthenticationStarted = true;
        setStatus("正在登录");
        Log.i(TAG, "User login started. username=" + username.trim() + ", termsAccepted=" + termsAccepted);
        runBackground(() -> {
            try {
                String challengeToken = completeAppChallenge("login");
                Log.i(TAG, "User login obtained challenge token. prefix=" + safeTokenPrefix(challengeToken));
                JSONObject payload = new JSONObject()
                    .put("username", username.trim())
                    .put("password", password)
                    .put("terms_accepted", true)
                    .put("cross_border_transfer_accepted", true)
                    .put("browser_fingerprint", deviceCookieValue())
                    .put("turnstile_token", challengeToken);
                JSONObject response = postJson(ApiPaths.LOGIN, payload);
                JSONObject user = response.optJSONObject("user");
                if (user == null) {
                    throw new IllegalStateException("登录成功但服务器未返回用户信息。");
                }

                Log.i(TAG, "User login API returned ok. userId=" + user.optInt("id", 0));
                int reportIntervalSeconds = response.optInt("report_interval_seconds", 300);
                runUi(() -> {
                    currentUser = user;
                    persistUserSession(user, reportIntervalSeconds);
                    showHome();
                    uploadLoginDeviceReport();
                    uploadDailyEnvironmentReportIfDue();
                    refreshLocations();
                });
            } catch (ChallengeCancelledException exception) {
                Log.w(TAG, "User login challenge cancelled.");
                runUi(() -> setStatus(""));
            } catch (Exception exception) {
                Log.e(TAG, "User login failed after challenge.", exception);
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private String completeAppChallenge(String purpose) throws Exception {
        Log.i(TAG, "App challenge started. purpose=" + purpose);
        JSONObject start = postJson(ApiPaths.APP_CHALLENGE, new JSONObject().put("purpose", purpose));
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
        runUi(() -> showAppChallengeWebView(purpose, challengeUrl, challengeCompleted, nativeTurnstileToken, () -> cancelAppChallenge(generation, purpose)));

        long deadline = System.currentTimeMillis() + 300000L;
        while (System.currentTimeMillis() < deadline) {
            challengeCompleted.await(2500L, TimeUnit.MILLISECONDS);
            if (isChallengeCancelled(generation)) {
                throw new ChallengeCancelledException();
            }
            if (nativeTurnstileToken[0] != null && !nativeTurnstileToken[0].trim().isEmpty()) {
                Log.i(TAG, "App challenge token received from WebView bridge. purpose=" + purpose + ", prefix=" + safeTokenPrefix(nativeTurnstileToken[0]));
                return nativeTurnstileToken[0].trim();
            }
            JSONObject poll = getJson(ApiPaths.APP_CHALLENGE + "?id=" + urlEncode(challengeId) + "&secret=" + urlEncode(challengeSecret));
            if (poll.optBoolean("verified", false)) {
                String token = poll.optString("app_challenge_token", "");
                if (!token.isEmpty()) {
                    Log.i(TAG, "App challenge verified by server polling fallback. purpose=" + purpose + ", prefix=" + safeTokenPrefix(token));
                    return token;
                }
            }
        }

        throw new IllegalStateException("Cloudflare 质询超时，请重新登录。");
    }

    private void cancelAppChallenge(int generation, String purpose) {
        challengeCancelled = true;
        if (challengeGeneration == generation) {
            challengeGeneration += 1;
        }
        destroyManagedWebViews();
        if ("register".equals(purpose)) {
            showRegisterWithMessage("");
        } else {
            showLoginWithMessage("");
        }
    }

    private boolean isChallengeCancelled(int generation) {
        return challengeCancelled || challengeGeneration != generation;
    }

    private LinearLayout challengeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
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

    private void setChallengePromptStatus(TextView prompt, String instruction, String status) {
        prompt.setText(instruction + "\n" + status);
    }

    private void showAppChallengeWebView(String purpose, String challengeUrl, CountDownLatch challengeCompleted, String[] nativeTurnstileToken, Runnable onBack) {
        boolean registering = "register".equals(purpose);
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
        settings.setUserAgentString(settings.getUserAgentString() + " loc-app/" + APP_VERSION_NAME);
        challengeView.setVerticalScrollBarEnabled(false);
        challengeView.setHorizontalScrollBarEnabled(false);
        String challengeInstruction = registering
            ? "请完成 Cloudflare 验证，完成后会自动继续注册。"
            : "请完成 Cloudflare 验证，完成后会自动继续登录。";
        TextView challengeStatusView = challengePrompt("");
        setChallengePromptStatus(challengeStatusView, challengeInstruction, "正在加载验证…");
        statusView = challengeStatusView;
        challengeView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void complete() {
                Log.i(TAG, "App challenge WebView complete() callback fired.");
                runUi(() -> setChallengePromptStatus(challengeStatusView, challengeInstruction, registering ? "验证已完成，正在继续注册…" : "验证已完成，正在继续登录…"));
                challengeCompleted.countDown();
            }

            @JavascriptInterface
            public void completeToken(String token) {
                if (token == null || token.trim().isEmpty()) {
                    Log.w(TAG, "App challenge WebView completeToken() received empty token.");
                    return;
                }
                nativeTurnstileToken[0] = token.trim();
                Log.i(TAG, "App challenge WebView completeToken() received token. prefix=" + safeTokenPrefix(token));
                runUi(() -> setChallengePromptStatus(challengeStatusView, challengeInstruction, registering ? "验证已完成，正在提交注册…" : "验证已完成，正在提交登录…"));
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
                    setChallengePromptStatus(challengeStatusView, challengeInstruction, "正在重新加载验证…");
                    syncCookiesToWebView(challengeUrl);
                    challengeView.loadUrl(challengeUrl);
                    mainHandler.postDelayed(this, 8000L);
                    return;
                }
                setChallengePromptStatus(challengeStatusView, challengeInstruction, "若验证仍未显示，请重新加载验证。");
            }
        };
        challengeView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                setChallengePromptStatus(challengeStatusView, challengeInstruction, "正在加载验证…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                captureWebViewCookies(url);
                if (challengeCompleted.getCount() > 0) {
                    setChallengePromptStatus(challengeStatusView, challengeInstruction, "若验证未显示，可点“重新加载验证”。");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || request == null || request.isForMainFrame()) {
                    String description = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && error != null
                        ? String.valueOf(error.getDescription())
                        : "网络请求失败";
                    setChallengePromptStatus(challengeStatusView, challengeInstruction, "验证加载失败：" + description);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                setChallengePromptStatus(challengeStatusView, challengeInstruction, "验证视图已重启，请重新加载验证。");
                return handleWebViewRendererGone(view, "");
            }
        });
        card.addView(challengeStatusView, blockParams(8));
        challengeView.loadUrl(challengeUrl);
        mainHandler.postDelayed(challengeReloadNudge, 8000L);
        LinearLayout.LayoutParams params = blockParams(8);
        params.height = dp(96);
        card.addView(challengeView, params);
        Button reload = secondaryButton("重新加载验证");
        reload.setOnClickListener(view -> {
            setChallengePromptStatus(challengeStatusView, challengeInstruction, "正在重新加载验证…");
            syncCookiesToWebView(challengeUrl);
            challengeView.loadUrl(challengeUrl);
            mainHandler.postDelayed(challengeReloadNudge, 8000L);
        });
        if (registering) {
            Button back = secondaryButton("返回注册");
            back.setOnClickListener(view -> {
                if (onBack != null) {
                    onBack.run();
                }
            });
            card.addView(buttonRow(reload, back), blockParams(0));
        } else {
            card.addView(reload, blockParams(0));
        }
        if (activeChallengeCard != null && activeChallengeCard.getParent() instanceof ViewGroup) {
            ((ViewGroup) activeChallengeCard.getParent()).removeView(activeChallengeCard);
        }
        activeChallengeCard = card;
        if (content != null && currentUser == null) {
            content.addView(card, blockParams(12));
            if (activeScrollView != null) {
                activeScrollView.post(() -> activeScrollView.smoothScrollTo(0, card.getBottom()));
            }
        } else {
            setScreen(card, true);
        }
    }


    private void showHome() {
        currentTab = TAB_POSITION;
        LinearLayout card = screenWithAction(homeScreenTitle(currentUser), homeHeaderLine(currentUser), announcementIconButton());
        reportButton = null;
        refreshButton = null;
        setScreen(card, false);
        requestStartupPermissions();
        syncKeepAliveService();
        maybeAutoShowAnnouncement();
        startEventStreamIfNeeded();
    }

    private String currentUserRole() {
        if (currentUser == null) {
            return "";
        }
        return normalizeRole(currentUser.optString("role", prefs().getString(KEY_USER_ROLE, "")));
    }

    private String continuousReportButtonText() {
        return guardianContinuousEnabled(currentGroupName()) ? "关闭持续上报" : "持续上报";
    }

    private void toggleGuardianContinuousReport() {
        if (!"guardian".equals(currentUserRole())) {
            setStatus("只有监护端需要手动开启持续上报。");
            return;
        }
        boolean enabled = !guardianContinuousEnabled(currentGroupName());
        saveGuardianContinuous(enabled);
        showHome();
        refreshLocations();
    }

    private int userGroupCount() {
        JSONArray groups = currentUser == null ? null : currentUser.optJSONArray("groups");
        return groups == null ? 0 : groups.length();
    }

    private JSONArray userGroups() {
        JSONArray groups = currentUser == null ? null : currentUser.optJSONArray("groups");
        return groups == null ? new JSONArray() : groups;
    }

    private String currentGroupName() {
        return selectedGroupName.isEmpty() && currentUser != null
            ? currentUser.optString("group_name", "")
            : selectedGroupName;
    }

    private String crossGroupSyncStorageKey() {
        int userId = currentUser == null ? 0 : currentUser.optInt("id", 0);
        return KEY_CROSS_GROUP_SYNC + "_" + userId;
    }

    private List<String> selectedCrossSyncGroups() {
        List<String> result = new ArrayList<>();
        JSONArray groups = userGroups();
        if (groups.length() == 0) {
            return result;
        }

        List<String> available = new ArrayList<>();
        for (int index = 0; index < groups.length(); index += 1) {
            JSONObject group = groups.optJSONObject(index);
            if (group != null) {
                String groupName = group.optString("group_name", "");
                if (!groupName.isEmpty()) {
                    available.add(groupName);
                }
            }
        }

        String saved = prefs().getString(crossGroupSyncStorageKey(), "[]");
        try {
            JSONArray values = new JSONArray(saved == null || saved.trim().isEmpty() ? "[]" : saved);
            for (int index = 0; index < values.length(); index += 1) {
                String groupName = values.optString(index, "");
                if (!groupName.isEmpty() && available.contains(groupName) && !result.contains(groupName)) {
                    result.add(groupName);
                }
            }
        } catch (Exception ignored) {
            return result;
        }
        return result;
    }

    private void saveSelectedCrossSyncGroups(List<String> groupNames) {
        JSONArray values = new JSONArray();
        for (String groupName : groupNames) {
            if (groupName != null && !groupName.trim().isEmpty()) {
                values.put(groupName.trim());
            }
        }
        prefs().edit().putString(crossGroupSyncStorageKey(), values.toString()).apply();
    }

    private void showCrossGroupSync() {
        currentTab = TAB_POSITION;
        JSONArray groups = userGroups();
        String currentGroup = currentGroupName();
        if (groups.length() <= 1) {
            showPopupDialog(
                "跨组同步",
                new String[][] {new String[] {"提示", "当前账号没有其他家庭组。"}},
                "关闭",
                null,
                null
            );
            return;
        }

        Dialog dialog = choiceDialog("跨组同步");
        LinearLayout body = choiceDialogBody(dialog);
        LinearLayout description = simpleSummaryPanel("说明", "手动上报时，可把同一定位同时同步到勾选的其他家庭组；自动/持续上报不会跨组同步。");
        body.addView(description, blockParams(14));

        List<CheckBox> checks = new ArrayList<>();
        List<String> selected = selectedCrossSyncGroups();
        for (int index = 0; index < groups.length(); index += 1) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) {
                continue;
            }
            String groupName = group.optString("group_name", "");
            if (groupName.isEmpty() || groupName.equals(currentGroup)) {
                continue;
            }
            CheckBox check = new CheckBox(this);
            check.setText(group.optString("display_name", groupName) + " / " + group.optString("role_label", ""));
            check.setTag(groupName);
            uiStyle.styleCheckBox(check, denseUi());
            check.setChecked(selected.contains(groupName));
            checks.add(check);
            body.addView(check, blockParams(8));
        }

        if (checks.isEmpty()) {
            LinearLayout empty = simpleSummaryPanel("提示", "当前账号没有其他可同步家庭组。");
            body.addView(empty, blockParams(12));
        }

        Button save = primaryButton("保存跨组同步设置");
        save.setOnClickListener(view -> {
            List<String> next = new ArrayList<>();
            for (CheckBox check : checks) {
                if (check.isChecked()) {
                    Object tag = check.getTag();
                    if (tag != null) {
                        next.add(String.valueOf(tag));
                    }
                }
            }
            saveSelectedCrossSyncGroups(next);
            dialog.dismiss();
            setStatus("跨组同步设置已保存：" + next.size() + " 个家庭组");
        });
        body.addView(save, blockParams(0));
        showChoiceDialog(dialog, body);
        setStatus("请选择需要同步的家庭组。");
    }

    private int normalizedHistoryPageSize(int value) {
        return value == 50 || value == 100 ? value : 20;
    }

    private int normalizedHistoryRangeHours(int value) {
        return value == 1 || value == 168 || value == 720 ? value : 24;
    }

    private String historyRangeLabel(int hours) {
        int normalized = normalizedHistoryRangeHours(hours);
        if (normalized == 1) {
            return "最近 1 小时";
        }
        if (normalized == 168) {
            return "最近 7 天";
        }
        if (normalized == 720) {
            return "最近 30 天";
        }
        return "最近 1 天";
    }

    private void showHistoryRangePicker() {
        Dialog dialog = choiceDialog("历史时间范围");
        LinearLayout body = choiceDialogBody(dialog);
        int[] ranges = new int[] {1, 24, 168, 720};
        for (int range : ranges) {
            Button button = secondaryButton((range == historyRangeHours ? "✓ " : "") + historyRangeLabel(range));
            button.setOnClickListener(view -> {
                dialog.dismiss();
                historyRangeHours = range;
                historyPage = 1;
                loadHomeHistorySummary();
            });
            body.addView(button, blockParams(8));
        }
        showChoiceDialog(dialog, body);
    }

    private String memberLabel(JSONObject member) {
        String name = member.optString("display_name", member.optString("username", "成员"));
        String role = member.optString("role_label", "");
        return role.isEmpty() ? name : name + " / " + role;
    }




    private String historyMemberLabel(JSONArray members, int userId) {
        if (userId <= 0 || members == null) {
            return "全部成员";
        }
        for (int index = 0; index < members.length(); index += 1) {
            JSONObject member = members.optJSONObject(index);
            if (member != null && member.optInt("user_id", 0) == userId) {
                return memberLabel(member);
            }
        }
        return "全部成员";
    }
    private interface HistorySizeAction {
        void apply(int size);
    }

    private void showHistoryMemberPicker(JSONArray members) {
        if (members == null || members.length() <= 1) {
            setStatus("当前只有一个成员，无需筛选。");
            return;
        }
        Dialog dialog = choiceDialog("筛选成员");
        LinearLayout body = choiceDialogBody(dialog);
        addHistoryMemberChoice(body, dialog, "全部成员", 0);
        for (int index = 0; index < members.length(); index += 1) {
            JSONObject member = members.optJSONObject(index);
            if (member == null) {
                continue;
            }
            int memberId = member.optInt("user_id", 0);
            addHistoryMemberChoice(body, dialog, memberLabel(member), memberId);
        }
        showChoiceDialog(dialog, body);
    }

    private void addHistoryMemberChoice(LinearLayout body, Dialog dialog, String label, int memberId) {
        Button button = secondaryButton((memberId == historyUserId ? "✓ " : "") + label);
        button.setOnClickListener(view -> {
            dialog.dismiss();
            historyUserId = Math.max(0, memberId);
            historyPage = 1;
            loadHomeHistorySummary();
        });
        body.addView(button, blockParams(8));
    }
    private void showHistorySizePicker(String title, int currentSize, HistorySizeAction action) {
        Dialog dialog = choiceDialog(title);
        LinearLayout body = choiceDialogBody(dialog);
        int[] sizes = new int[] {20, 50, 100};
        for (int size : sizes) {
            Button button = secondaryButton((currentSize == size ? "✓ " : "") + size + " 条");
            button.setOnClickListener(view -> {
                dialog.dismiss();
                action.apply(size);
            });
            body.addView(button, blockParams(8));
        }
        showChoiceDialog(dialog, body);
    }
    private Dialog choiceDialog(String title) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setId(0x4c0c001);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(dp(16), dp(16), dp(16), dp(8));
        TextView heading = sectionTitle(title);
        card.addView(heading, blockParams(12));
        dialog.setContentView(card);
        return dialog;
    }

    private LinearLayout choiceDialogBody(Dialog dialog) {
        LinearLayout card = dialog.findViewById(0x4c0c001);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    private void showChoiceDialog(Dialog dialog, LinearLayout body) {
        showChoiceDialog(dialog, body, null);
    }

    private void showChoiceDialog(Dialog dialog, LinearLayout body, Button primaryAction) {
        ScrollView scroll = new ScrollView(this);
        ViewGroup parent = body.getParent() instanceof ViewGroup ? (ViewGroup) body.getParent() : null;
        if (parent != null) {
            parent.removeView(body);
            scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            parent.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(420), (int) (getResources().getDisplayMetrics().heightPixels * 0.56f))));
            Button close = secondaryButton("关闭");
            close.setOnClickListener(view -> dialog.dismiss());
            if (primaryAction == null) {
                parent.addView(close, blockParams(0));
            } else {
                parent.addView(buttonRow(primaryAction, close), blockParams(0));
            }
        }
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            shownWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            shownWindow.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams params = shownWindow.getAttributes();
            params.dimAmount = 0.58f;
            shownWindow.setAttributes(params);
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(44), dp(520));
            shownWindow.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            animateDialog(shownWindow.getDecorView());
        }
    }
    private WebView locationMapWebView(JSONArray records) {
        return locationMapWebView(records, null);
    }

    private WebView locationMapWebView(JSONArray records, Runnable onMapReady) {
        WebView map = managedWebView();
        map.setTag("dynamic");
        WebSettings settings = map.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(settings.getUserAgentString() + " loc-app/" + APP_VERSION_NAME);
        map.setBackgroundColor(isDarkMode() ? Color.rgb(17, 29, 26) : Color.WHITE);
        map.setVerticalScrollBarEnabled(false);
        map.setHorizontalScrollBarEnabled(false);
        map.setOnTouchListener((view, event) -> {
            ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
            if (parent != null) {
                int action = event.getActionMasked();
                parent.requestDisallowInterceptTouchEvent(action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE);
            }
            return false;
        });
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        String baseUrl = serverUrl();
        cookieManager.setCookie(baseUrl, cookieHeader());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.flush();
        }
        String recordsJson = mapDisplayRecords(records).toString();
        if (onMapReady != null) {
            map.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void ready() {
                    runUi(onMapReady);
                }
            }, "LocMapCapture");
        }
        map.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                renderMapRecords(view, recordsJson);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && request.isForMainFrame()) {
                    int statusCode = errorResponse == null ? 0 : errorResponse.getStatusCode();
                    showMapWebViewError(view, "地图服务返回 HTTP " + statusCode + "，请检查服务器反代和 Cloudflare 规则。");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || request == null || request.isForMainFrame()) {
                    String description = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && error != null ? String.valueOf(error.getDescription()) : "网络请求失败";
                    showMapWebViewError(view, "地图加载失败：" + description);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return handleWebViewRendererGone(view, "地图 WebView 已释放，请刷新后重试。");
            }
        });
        map.loadUrl(baseUrl + ApiPaths.HISTORY_MAP);
        return map;
    }

    private void renderMapRecords(WebView view, JSONArray records) {
        if (view == null || records == null) {
            return;
        }
        renderMapRecords(view, mapDisplayRecords(records).toString());
    }

    private void renderMapRecords(WebView view, String recordsJson) {
        if (view == null || recordsJson == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view.evaluateJavascript("window.renderLocHistoryMap(" + recordsJson + ")", null);
        } else {
            view.loadUrl("javascript:window.renderLocHistoryMap(" + Uri.encode(recordsJson) + ")");
        }
    }

    private void showMapWebViewError(WebView view, String message) {
        String safeMessage = htmlEscape(message == null || message.trim().isEmpty() ? "地图加载失败，请稍后重试。" : message.trim());
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;height:100%;background:#eef3f1;font-family:sans-serif;color:#5c6f6a}.empty{height:100%;display:grid;place-items:center;text-align:center;padding:18px;box-sizing:border-box}</style></head><body><div class=\"empty\">" + safeMessage + "</div></body></html>";
        view.loadDataWithBaseURL(serverUrl(), html, "text/html", "UTF-8", null);
    }

    private String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private JSONArray mapDisplayRecords(JSONArray records) {
        JSONArray result = new JSONArray();
        if (records == null) {
            return result;
        }
        for (int index = 0; index < records.length(); index += 1) {
            JSONObject record = records.optJSONObject(index);
            if (record == null) {
                continue;
            }
            try {
                JSONObject mapRecord = new JSONObject(record.toString());
                mapRecord.remove("address");
                mapRecord.remove("location_address");
                mapRecord.remove("city");
                mapRecord.remove("region");
                mapRecord.remove("country");
                JSONObject diagnostics = record.optJSONObject("address_diagnostics");
                JSONObject mapDiagnostics = new JSONObject();
                JSONArray gpsSources = new JSONArray();
                JSONArray sources = diagnostics == null ? null : diagnostics.optJSONArray("sources");
                if (sources != null) {
                    for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex += 1) {
                        JSONObject source = sources.optJSONObject(sourceIndex);
                        if (source != null && "gps".equals(source.optString("type", "").trim().toLowerCase(Locale.ROOT))) {
                            gpsSources.put(new JSONObject(source.toString()));
                        }
                    }
                }
                mapDiagnostics.put("sources", gpsSources);
                mapRecord.put("address_diagnostics", mapDiagnostics);
                result.put(mapRecord);
            } catch (Exception exception) {
                Log.w(TAG, "Skipping invalid map record: " + exception.getMessage());
            }
        }
        return result;
    }

    private JSONArray displayableLocations(JSONArray locations) {
        JSONArray records = new JSONArray();
        if (locations == null) {
            return records;
        }
        for (int index = 0; index < locations.length(); index += 1) {
            JSONObject location = locations.optJSONObject(index);
            if (hasUsableCoordinates(location)) {
                records.put(location);
            }
        }
        return records;
    }

    private void appendHistoryRow(JSONObject location, String viewTag) {
        if (location == null) {
            return;
        }

        LinearLayout panel = historySummaryCard(location, viewTag);
        content.addView(panel, blockParams(6));
    }

    private LinearLayout historySummaryCard(JSONObject location, String viewTag) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(historyCardBackground(false));
        panel.setTag(viewTag);
        panel.setTag(0x4c0c010, false);

        LinearLayout header = summaryCardHeader(
            location.optString("display_name", location.optString("username", "成员")),
            location.optString("role_label", ""),
            location.optString("role", "")
        );
        View dot = new View(this);
        dot.setBackground(roundedDrawable(historyRoleColor(location.optString("role", "")), dp(999)));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMargins(0, 0, dp(8), 0);
        header.addView(dot, 0, dotParams);
        panel.addView(header, blockParams(4));

        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        TextView position = body("位置： " + locationDisplayText(location, diagnostics, diagnostics == null ? "" : diagnostics.optString("preferred_address", "")));
        position.setTextColor(colorMuted());
        position.setTextSize(uiStyle.compactBodyTextSize(denseUi()));
        position.setLineSpacing(dp(2), 1f);
        panel.addView(position, blockParams(4));

        TextView time = body(historyTimeLabel(location) + "： " + historyTimeText(location));
        time.setTextColor(colorMuted());
        time.setTextSize(uiStyle.compactBodyTextSize(denseUi()));
        time.setLineSpacing(dp(2), 1f);
        panel.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String status = historyAddressStatus(location, diagnostics);
        if (shouldShowStatusText(status)) {
            TextView statusLine = body("状态： " + status);
            statusLine.setTextColor(colorMuted());
            statusLine.setTextSize(uiStyle.compactBodyTextSize(denseUi()));
            statusLine.setLineSpacing(dp(2), 1f);
            statusLine.setPadding(0, dp(4), 0, 0);
            panel.addView(statusLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        panel.setTag(0x4c0c011, panel.getChildCount());
        panel.setClickable(true);
        panel.setOnClickListener(view -> toggleHistoryDetail(panel, location));
        return panel;
    }

    private void toggleHistoryDetail(LinearLayout panel, JSONObject location) {
        Object expanded = panel.getTag(0x4c0c010);
        Object summaryCountTag = panel.getTag(0x4c0c011);
        int summaryChildCount = summaryCountTag instanceof Integer ? (Integer) summaryCountTag : 3;
        if (Boolean.TRUE.equals(expanded)) {
            while (panel.getChildCount() > summaryChildCount) {
                panel.removeViewAt(summaryChildCount);
            }
            panel.setTag(0x4c0c010, false);
            panel.setBackground(historyCardBackground(false));
            return;
        }
        panel.addView(detailDivider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        LinearLayout detail = historyDetailPanel(location);
        panel.addView(detail, blockParams(8));
        Button map = historyControlButton("查看地图");
        map.setOnClickListener(view -> openMapLocation(location, location.optString("display_name", location.optString("username", "成员"))));
        panel.addView(map, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.setTag(0x4c0c010, true);
        panel.setBackground(historyCardBackground(true));
    }

    private LinearLayout historyDetailPanel(JSONObject location) {
        LinearLayout panel = detailListPanel(false);
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        String address = diagnostics == null ? "" : diagnostics.optString("preferred_address", "");
        addDetailRow(panel, "位置", locationDisplayText(location, diagnostics, address));
        addDetailRow(panel, historyTimeLabel(location), historyTimeText(location));
        if (hasStayAggregation(location)) {
            addDetailRow(panel, "上报次数", Math.max(1, location.optInt("report_count", 1)) + " 次");
        }
        String preferredCity = preferredCityText(diagnostics);
        if (hasMeaningfulText(preferredCity)) {
            addDetailRow(panel, "城市", preferredCity);
        }
        addDetailRow(panel, "状态", historyAddressStatus(location, diagnostics));
        appendHistoryAddressSourceRows(panel, diagnostics);
        return panel;
    }

    private String historyPositionLine(JSONObject location) {
        if (location == null || location.optBoolean("encrypted_unreadable", false)) {
            return "位置： 暂无";
        }
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        return "位置： " + locationDisplayText(location, diagnostics, diagnostics == null ? "" : diagnostics.optString("preferred_address", ""));
    }

    private String locationNumberText(JSONObject location, String key, String suffix, int decimals) {
        if (location == null || !location.has(key) || location.isNull(key)) {
            return "";
        }
        double value = location.optDouble(key, Double.NaN);
        if (!Double.isFinite(value)) {
            return "";
        }
        String formatted = decimals <= 0
            ? String.valueOf(Math.round(value))
            : String.format(java.util.Locale.US, "%." + decimals + "f", value);
        return formatted + suffix;
    }

    private void appendHistoryNumeric(StringBuilder builder, JSONObject location, String key, String label, String suffix, int decimals) {
        String text = locationNumberText(location, key, suffix, decimals);
        if (!text.isEmpty()) {
            builder.append("\n").append(label).append("：").append(text);
        }
    }

    private String historyAddressStatus(JSONObject location, JSONObject diagnostics) {
        if (diagnostics == null || diagnostics.optJSONArray("sources") == null) {
            return location != null && location.optBoolean("address_mismatch", false)
                ? "位置信息不一致"
                : "位置信息一致或无法完整判断";
        }
        if (diagnosticsGpsMismatch(diagnostics)) {
            return "位置信息不一致";
        }
        if (diagnosticsMobileIpCityUncertain(diagnostics)) {
            return "移动网络出口城市与定位不同，仅作参考";
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

    private void appendHistoryAddressSources(StringBuilder builder, JSONObject diagnostics) {
        if (diagnostics == null) {
            return;
        }
        JSONArray sources = diagnostics.optJSONArray("sources");
        if (sources == null || sources.length() == 0) {
            return;
        }
        builder.append("\n地址来源：");
        boolean locationAddressShown = false;
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null) {
                continue;
            }
            source = mostPreciseDiagnosticSource(source);
            if ("gps".equals(source.optString("type", ""))) {
                continue;
            }
        String label = source.optString("name", source.optString("type", "地址"));
            if (isLocationAddressSource(source, label)) {
                if (locationAddressShown) {
                    continue;
                }
                locationAddressShown = true;
            }
            String sourceAddress = diagnosticSourceAddress(source);
            String city = source.optString("city", "");
            builder.append("\n- ").append(label).append("：").append(sourceAddress);
            if (!city.isEmpty()) {
                builder.append(" / 城市：").append(city);
            }
            if (diagnosticsMobileIpCityUncertain(diagnostics, source)) {
                builder.append(" / 移动网络出口城市不一致");
            }
            String attempts = providerAttemptSummary(source);
            if (!attempts.isEmpty()) {
                builder.append(" / 探测：").append(attempts);
            }
        }
    }

    private void appendHistoryAddressSourceRows(LinearLayout panel, JSONObject diagnostics) {
        if (diagnostics == null) {
            return;
        }
        JSONArray sources = diagnostics.optJSONArray("sources");
        if (sources == null || sources.length() == 0) {
            return;
        }
        boolean locationAddressShown = false;
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null) {
                continue;
            }
            source = mostPreciseDiagnosticSource(source);
            String label = source.optString("name", source.optString("type", "地址"));
            if (isLocationAddressSource(source, label)) {
                if (locationAddressShown) {
                    continue;
                }
                locationAddressShown = true;
            }
            String sourceAddress = diagnosticSourceAddress(source);
            String detail = sourceAddress;
            String city = source.optString("city", "");
            if (!city.isEmpty()) {
                detail += "\n城市：" + city;
            }
            if (diagnosticsMobileIpCityUncertain(diagnostics, source)) {
                detail += "\n移动网络出口城市不一致";
            }
            String attempts = providerAttemptSummary(source);
            if (!attempts.isEmpty()) {
                detail += "\n供应商探测：" + attempts;
            }
            addDetailRow(panel, label, detail);
        }
    }

    private boolean hasStayAggregation(JSONObject location) {
        return location != null
            && (location.has("first_reported_at")
                || location.has("last_reported_at")
                || location.has("stay_duration_seconds")
                || location.optInt("report_count", 1) > 1);
    }

    private String historyTimeLabel(JSONObject location) {
        return hasStayAggregation(location) ? "停留时间" : "上报时间";
    }

    private String historyTimeText(JSONObject location) {
        if (location == null) {
            return "暂无";
        }
        if (!hasStayAggregation(location)) {
            return firstText(location.optString("created_at", ""), location.optString("updated_at", ""), "暂无");
        }
        String first = firstText(
            location.optString("first_reported_at", ""),
            location.optString("created_at", ""),
            location.optString("updated_at", ""),
            "暂无"
        );
        String last = firstText(
            location.optString("last_reported_at", ""),
            location.optString("updated_at", ""),
            location.optString("created_at", ""),
            first
        );
        long durationSeconds = Math.max(0L, location.optLong("stay_duration_seconds", 0L));
        return first + " 至 " + last + "（" + formatStayDuration(durationSeconds) + "）";
    }

    private String formatStayDuration(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        long days = safeSeconds / 86400L;
        long hours = (safeSeconds % 86400L) / 3600L;
        long minutes = (safeSeconds % 3600L) / 60L;
        long remainingSeconds = safeSeconds % 60L;
        if (days > 0L) {
            return days + " 天 " + hours + " 小时";
        }
        if (hours > 0L) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        if (minutes > 0L) {
            return minutes + " 分钟 " + remainingSeconds + " 秒";
        }
        return remainingSeconds + " 秒";
    }

    private boolean isLocationAddressSource(JSONObject source, String label) {
        if (source == null) {
            return false;
        }
        String type = source.optString("type", "");
        String name = label == null ? "" : label.trim();
        return "gps".equals(type) || "定位地址".equals(name);
    }

    private boolean diagnosticsMobileIpCityUncertain(JSONObject diagnostics) {
        JSONArray sources = diagnostics == null ? null : diagnostics.optJSONArray("sources");
        if (sources == null) {
            return false;
        }
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (diagnosticsMobileIpCityUncertain(diagnostics, source)) {
                return true;
            }
        }
        return false;
    }

    private boolean diagnosticsMobileIpCityUncertain(JSONObject diagnostics, JSONObject ipSource) {
        if (diagnostics == null || ipSource == null || !"ip".equals(ipSource.optString("type", ""))) {
            return false;
        }
        if (!ipSource.optBoolean("mobile_network_uncertain", false) || !diagnosticsSourceIsMobileNetwork(ipSource)) {
            return false;
        }
        JSONObject gps = firstDiagnosticsSource(diagnostics, "gps");
        if (gps == null) {
            return false;
        }
        for (String field : new String[] {"country", "region"}) {
            String gpsValue = diagnosticsCompareValue(gps.optString(field, ""));
            String ipValue = diagnosticsCompareValue(ipSource.optString(field, ""));
            if (!gpsValue.isEmpty() && !ipValue.isEmpty() && !gpsValue.equals(ipValue)) {
                return false;
            }
        }
        String gpsCity = diagnosticsCompareValue(gps.optString("city", ""));
        String ipCity = diagnosticsCompareValue(ipSource.optString("city", ""));
        return !gpsCity.isEmpty() && !ipCity.isEmpty() && !gpsCity.equals(ipCity);
    }

    private JSONObject firstDiagnosticsSource(JSONObject diagnostics, String type) {
        JSONArray sources = diagnostics == null ? null : diagnostics.optJSONArray("sources");
        if (sources == null) {
            return null;
        }
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source != null && type.equals(source.optString("type", ""))) {
                return source;
            }
        }
        return null;
    }

    private boolean diagnosticsSourceIsMobileNetwork(JSONObject source) {
        if (source == null) {
            return false;
        }
        if (source.optBoolean("mobile_network", false)) {
            return true;
        }
        String network = (
            source.optString("asn", "") + " " +
            source.optString("isp", "") + " " +
            source.optString("org", "") + " " +
            source.optString("carrier", "") + " " +
            source.optString("provider", "")
        ).toLowerCase(java.util.Locale.US);
        return network.contains("china mobile")
            || network.contains("cmnet")
            || network.contains("cmi")
            || network.contains("中国移动")
            || network.contains("移动");
    }



    private void maybeAutoShowAnnouncement() {
        final long targetScreenGeneration = screenGeneration;
        runBackground(() -> {
            try {
                JSONObject response = getJson(ApiPaths.ANNOUNCEMENT);
                JSONObject announcement = response.optJSONObject("announcement");
                if (announcement == null || !shouldAutoShowAnnouncement(announcement)) {
                    return;
                }
                runUiIfScreenCurrent(targetScreenGeneration, () -> showAnnouncementPopup(announcement, true));
            } catch (Exception exception) {
                Log.w(TAG, "Auto announcement check failed: " + exception.getMessage());
            }
        });
    }

    private boolean shouldAutoShowAnnouncement(JSONObject announcement) {
        String key = announcementSeenKey(announcement);
        return !key.isEmpty() && prefs().getInt(key, 0) != 1;
    }

    private String announcementSeenKey(JSONObject announcement) {
        int id = announcement == null ? 0 : announcement.optInt("id", 0);
        int version = announcement == null ? 0 : announcement.optInt("version", 0);
        if (id <= 0 || version <= 0) {
            return "";
        }
        return KEY_SEEN_ANNOUNCEMENT_PREFIX + id + "_" + version;
    }

    private void showAnnouncementPopup(JSONObject announcement, boolean markSeen) {
        if (announcement == null) {
            return;
        }
        String bodyText = announcement.optString("body", "").trim();
        if (bodyText.isEmpty()) {
            return;
        }
        String key = markSeen ? announcementSeenKey(announcement) : "";
        if (!key.isEmpty()) {
            prefs().edit().putInt(key, 1).apply();
        }
        String updatedAt = announcement.optString("updated_at", "").trim();
        List<String[]> sections = new ArrayList<>();
        sections.add(new String[] {"公告内容", bodyText});
        if (!updatedAt.isEmpty()) {
            sections.add(new String[] {"更新时间", updatedAt});
        }
        showPopupDialog(
            announcement.optString("title", "公告"),
            sections.toArray(new String[0][]),
            "知道了",
            null,
            null
        );
    }

    private void showLatestAnnouncementPopup() {
        final long targetScreenGeneration = screenGeneration;
        setStatus("正在加载公告");
        runBackground(() -> {
            try {
                JSONObject response = getJson(ApiPaths.ANNOUNCEMENT);
                JSONObject announcement = response.optJSONObject("announcement");
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    if (announcement == null) {
                        showPopupDialog(
                            "公告",
                            new String[][] {new String[] {"公告内容", "暂无公告。"}},
                            "知道了",
                            null,
                            null
                        );
                        setStatus("暂无公告");
                        return;
                    }
                    showAnnouncementPopup(announcement, false);
                    setStatus("公告已加载");
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showTickets() {
        currentTab = TAB_HELP;
        LinearLayout card = screen("帮助");
        LinearLayout intro = simpleSummaryPanel("说明", "遇到账号、家庭组、位置异常或后台操作问题，可以在这里提交工单。");
        Button refresh = primaryButton("刷新工单");
        Button create = secondaryButton("新建工单");
        refresh.setOnClickListener(view -> loadTickets());
        create.setOnClickListener(view -> showCreateTicket());
        card.addView(intro, blockParams(10));
        card.addView(buttonRow(refresh, create), blockParams(16));
        setScreen(card, false);
        loadTickets();
    }

    private void loadTickets() {
        final LinearLayout targetContent = content;
        final long targetScreenGeneration = screenGeneration;
        final long requestToken = ticketListRequestGate.begin();
        final JsonApiClient.RequestHandle requestHandle = new JsonApiClient.RequestHandle();
        replaceActiveRequest(activeTicketListRequest, requestHandle);
        setStatus("正在加载工单");
        runBackground(() -> {
            try {
                JSONObject response = getJson(ApiPaths.TICKETS, requestHandle);
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    if (!isCurrentTicketListRequest(requestToken, targetScreenGeneration, targetContent)) {
                        return;
                    }
                    renderTickets(response);
                });
            } catch (Exception exception) {
                if (!isCancelledRequest(exception)) {
                    runUi(() -> {
                        if (isCurrentTicketListRequest(requestToken, targetScreenGeneration, targetContent)) {
                            setStatus(exception.getMessage());
                        }
                    });
                }
            } finally {
                activeTicketListRequest.compareAndSet(requestHandle, null);
            }
        });
    }

    private void startEventStreamIfNeeded() {
        if (currentUser == null || eventStreamWebView != null || !canLoadForegroundWebView()) {
            return;
        }
        syncCookiesToWebView(serverUrl());
        WebView stream = managedWebView();
        eventStreamWebView = stream;
        attachEventStreamToContent();
        WebSettings settings = stream.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setUserAgentString(settings.getUserAgentString() + " loc-app/" + APP_VERSION_NAME);
        stream.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onEvent(String json) {
                try {
                    JSONObject event = new JSONObject(json == null ? "{}" : json);
                    if ("announcement".equals(event.optString("type", ""))) {
                        fetchAnnouncementForEvent();
                    }
                } catch (Exception exception) {
                    Log.w(TAG, "Invalid WSS event payload.");
                }
            }

            @JavascriptInterface
            public void onState(String state) {
                Log.i(TAG, "WSS_EVENT_STATE=" + (state == null ? "unknown" : state));
            }
        }, "LocEvents");
        stream.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == eventStreamWebView) {
                    eventStreamWebView = null;
                }
                return handleWebViewRendererGone(view, "");
            }
        });
        String html = "<!doctype html><meta charset=\"utf-8\"><script>" +
            "(()=>{let ws=null,retry=0,timer=0;" +
            "const tell=s=>{try{LocEvents.onState(s)}catch(e){}};" +
            "const next=()=>{clearTimeout(timer);const base=Math.min(30000,1000*Math.pow(2,Math.min(retry++,5)));timer=setTimeout(connect,base+Math.floor(Math.random()*700));};" +
            "const connect=()=>{try{const scheme=location.protocol==='https:'?'wss:':'ws:';ws=new WebSocket(scheme+'//'+location.host+'/" + ApiPaths.EVENTS + "');" +
            "ws.onopen=()=>{retry=0;tell('open')};ws.onmessage=e=>{try{LocEvents.onEvent(String(e.data))}catch(x){}};" +
            "ws.onerror=()=>tell('error');ws.onclose=()=>{tell('closed');next()};}catch(e){tell('failed');next()}};" +
            "setInterval(()=>{try{if(ws&&ws.readyState===1)ws.send('heartbeat')}catch(e){}},25000);connect();})();</script>";
        stream.loadDataWithBaseURL(serverUrl(), html, "text/html", "UTF-8", null);
    }

    private void fetchAnnouncementForEvent() {
        if (!announcementEventFetchInFlight.compareAndSet(false, true)) {
            return;
        }
        runBackground(() -> {
            try {
                JSONObject response = getJson(ApiPaths.ANNOUNCEMENT);
                JSONObject announcement = response.optJSONObject("announcement");
                runUi(() -> {
                    if (currentUser != null && announcement != null && shouldAutoShowAnnouncement(announcement)) {
                        showAnnouncementPopup(announcement, true);
                    }
                });
            } catch (Exception exception) {
                Log.w(TAG, "WSS announcement refresh failed: " + exception.getMessage());
            } finally {
                announcementEventFetchInFlight.set(false);
            }
        });
    }

    private boolean isCurrentTicketListRequest(long requestToken, long targetScreenGeneration, LinearLayout targetContent) {
        return ticketListRequestGate.isCurrent(requestToken)
            && screenGeneration == targetScreenGeneration
            && currentTab == TAB_HELP
            && content == targetContent;
    }

    private void renderTickets(JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONArray tickets = response.optJSONArray("tickets");
        if (tickets == null || tickets.length() == 0) {
            LinearLayout empty = simpleSummaryPanel("提示", "暂无工单。", true);
            empty.setTag("dynamic");
            content.addView(empty, blockParams(10));
            setStatus("暂无工单");
            return;
        }

        content.addView(dynamicSectionTitle("最近工单"), blockParams(8));
        for (int index = 0; index < tickets.length(); index += 1) {
            JSONObject ticket = tickets.optJSONObject(index);
            if (ticket == null) {
                continue;
            }
            LinearLayout item = ticketSummaryCard(ticket);
            item.setTag(VIEW_TAG_DYNAMIC);
            content.addView(item, blockParams(8));
        }
        setStatus("工单已加载：" + tickets.length());
    }

    private LinearLayout ticketSummaryCard(JSONObject ticket) {
        int ticketId = ticket.optInt("id", 0);
        String subject = ticket.optString("subject", "工单");
        String updatedAt = firstText(ticket.optString("updated_at", ""), ticket.optString("created_at", ""), "暂无");
        String lastMessage = firstText(ticket.optString("latest_admin_reply", ""), "暂无回复");
        LinearLayout panel = detailListPanel(true);
        panel.addView(summaryCardHeader("#" + ticketId + " " + subject, ticket.optString("status_label", ""), ticket.optString("status", "")), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addDetailRow(panel, "更新时间", updatedAt);
        addDetailRow(panel, "最新回复", lastMessage);
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(view -> showTicketThread(ticketId));
        return panel;
    }

    private void showCreateTicket() {
        Dialog dialog = choiceDialog("新建工单");
        LinearLayout body = choiceDialogBody(dialog);
        EditText subject = input("标题");
        EditText message = multiLineInput("内容");
        Button submit = primaryButton("提交工单");
        submit.setOnClickListener(view -> createTicket(subject.getText().toString(), message.getText().toString(), dialog));
        body.addView(subject, blockParams(12));
        body.addView(message, blockParams(12));
        body.addView(submit, blockParams(0));
        showChoiceDialog(dialog, body);
        setStatus("请填写工单标题和内容。");
    }

    private void createTicket(String subject, String message, Dialog dialog) {
        if (subject.trim().isEmpty() || message.trim().isEmpty()) {
            setStatus("请填写标题和内容。");
            return;
        }
        if (!ticketWriteInFlight.compareAndSet(false, true)) {
            setStatus("工单操作正在提交，请勿重复点击。");
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("正在提交工单");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "create")
                    .put("group_name", selectedGroupName)
                    .put("subject", subject.trim())
                    .put("message", message.trim());
                JSONObject response = postJson(ApiPaths.TICKETS, payload);
                int ticketId = response.optInt("ticket_id", 0);
                runUi(() -> {
                    if (screenGeneration != targetScreenGeneration
                        || currentTab != TAB_HELP
                        || dialog == null
                        || !dialog.isShowing()) {
                        return;
                    }
                    dialog.dismiss();
                    ticketListRequestGate.invalidate();
                    cancelActiveRequest(activeTicketListRequest);
                    showTicketThread(ticketId);
                    setStatus("工单已提交");
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (screenGeneration == targetScreenGeneration && currentTab == TAB_HELP) {
                        setStatus(exception.getMessage());
                    }
                });
            } finally {
                ticketWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            ticketWriteInFlight.set(false);
        }
    }

    private void showTicketThread(int ticketId) {
        if (ticketId <= 0) {
            setStatus("工单编号无效。");
            return;
        }

        Dialog dialog = choiceDialog("工单详情");
        LinearLayout body = choiceDialogBody(dialog);
        body.addView(simpleSummaryPanel("状态", "正在加载工单详情…"), blockParams(0));
        dialog.setOnDismissListener(ignored -> {
            ticketThreadRequestGate.invalidate();
            cancelActiveRequest(activeTicketThreadRequest);
        });
        showChoiceDialog(dialog, body);
        loadTicketThread(ticketId, dialog, body);
    }

    private void loadTicketThread(int ticketId, Dialog dialog, LinearLayout target) {
        final long requestToken = ticketThreadRequestGate.begin();
        final JsonApiClient.RequestHandle requestHandle = new JsonApiClient.RequestHandle();
        replaceActiveRequest(activeTicketThreadRequest, requestHandle);
        setStatus("正在加载工单详情");
        runBackground(() -> {
            try {
                JSONObject response = getJson(ApiPaths.TICKETS + "?ticket_id=" + ticketId, requestHandle);
                runUi(() -> {
                    if (isCurrentTicketThreadRequest(requestToken, dialog, target)) {
                        renderTicketThread(ticketId, response, dialog, target);
                    }
                });
            } catch (Exception exception) {
                if (!isCancelledRequest(exception)) {
                    runUi(() -> {
                        if (isCurrentTicketThreadRequest(requestToken, dialog, target)) {
                            setStatus(exception.getMessage());
                        }
                    });
                }
            } finally {
                activeTicketThreadRequest.compareAndSet(requestHandle, null);
            }
        });
    }

    private boolean isCurrentTicketThreadRequest(long requestToken, Dialog dialog, LinearLayout target) {
        return ticketThreadRequestGate.isCurrent(requestToken)
            && currentTab == TAB_HELP
            && dialog != null
            && dialog.isShowing()
            && target != null;
    }
    private void renderTicketThread(int ticketId, JSONObject response, Dialog dialog, LinearLayout target) {
        if (target == null) {
            return;
        }

        target.removeAllViews();
        JSONObject ticket = response.optJSONObject("ticket");
        if (ticket == null) {
            target.addView(simpleSummaryPanel("提示", "工单不存在。"), blockParams(0));
            setStatus("工单不存在。");
            return;
        }

        LinearLayout header = detailListPanel(false);
        header.addView(summaryCardHeader(
            "#" + ticket.optInt("id", ticketId) + " " + ticket.optString("subject", "工单"),
            ticket.optString("status_label", ""),
            ticket.optString("status", "")
        ), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addDetailRow(header, "创建时间", firstText(ticket.optString("created_at", ""), "暂无"));
        target.addView(header, blockParams(10));

        Button refresh = secondaryButton("刷新详情");
        refresh.setOnClickListener(view -> loadTicketThread(ticketId, dialog, target));
        target.addView(refresh, blockParams(10));

        JSONArray messages = response.optJSONArray("messages");
        if (messages != null) {
            target.addView(sectionTitle("消息"), blockParams(8));
            for (int index = 0; index < messages.length(); index += 1) {
                JSONObject message = messages.optJSONObject(index);
                if (message == null) {
                    continue;
                }
                LinearLayout row = detailListPanel(false);
                row.addView(summaryCardHeader(
                    message.optString("sender_label", ""),
                    message.optString("created_at", ""),
                    ""
                ), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                addDetailRow(row, "内容", firstText(message.optString("message", ""), "暂无内容"));
                target.addView(row, blockParams(8));
            }
        }

        if (!"closed".equals(ticket.optString("status", ""))) {
            EditText reply = multiLineInput("输入回复");
            Button submit = primaryButton("发送回复");
            submit.setOnClickListener(view -> replyTicket(ticketId, reply.getText().toString(), dialog, target));
            Button close = secondaryButton("关闭工单");
            close.setOnClickListener(view -> confirmCloseTicket(ticketId, dialog, target));
            target.addView(reply, blockParams(10));
            target.addView(buttonRow(submit, close), blockParams(0));
        } else {
            LinearLayout closed = detailListPanel(false);
            closed.addView(summaryCardHeader("工单已关闭", "", ""), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addDetailRow(closed, "说明", "不能继续回复。如需继续处理，请新建工单。");
            target.addView(closed, blockParams(0));
        }
        setStatus("工单详情已加载");
    }

    private void confirmCloseTicket(int ticketId, Dialog dialog, LinearLayout target) {
        showPopupDialog(
            "关闭工单",
            new String[][] {
                new String[] {"确认操作", "确定关闭这个工单？关闭后不能继续回复，如需处理请新建工单。"}
            },
            "确认关闭",
            () -> closeTicket(ticketId, dialog, target),
            "取消"
        );
    }

    private void closeTicket(int ticketId, Dialog dialog, LinearLayout target) {
        if (ticketId <= 0) {
            setStatus("工单信息不完整。");
            return;
        }
        if (!ticketWriteInFlight.compareAndSet(false, true)) {
            setStatus("工单操作正在提交，请勿重复点击。");
            return;
        }
        final long targetScreenGeneration = screenGeneration;
        setStatus("正在关闭工单");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "close")
                    .put("ticket_id", ticketId);
                JSONObject response = postJson(ApiPaths.TICKETS, payload);
                String successMessage = firstText(response.optString("message", ""), "工单已关闭");
                runUi(() -> {
                    if (screenGeneration == targetScreenGeneration && currentTab == TAB_HELP && dialog != null && dialog.isShowing()) {
                        showTransientFeedback(successMessage);
                        loadTicketThread(ticketId, dialog, target);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (screenGeneration == targetScreenGeneration && currentTab == TAB_HELP) {
                        setStatus(exception.getMessage());
                    }
                });
            } finally {
                ticketWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            ticketWriteInFlight.set(false);
        }
    }

    private void replyTicket(int ticketId, String message, Dialog dialog, LinearLayout target) {
        if (message.trim().isEmpty()) {
            setStatus("回复内容不能为空。");
            return;
        }
        if (!ticketWriteInFlight.compareAndSet(false, true)) {
            setStatus("工单操作正在提交，请勿重复点击。");
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("正在发送回复");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "reply")
                    .put("ticket_id", ticketId)
                    .put("message", message.trim());
                JSONObject response = postJson(ApiPaths.TICKETS, payload);
                String successMessage = firstText(response.optString("message", ""), "回复成功");
                runUi(() -> {
                    if (screenGeneration == targetScreenGeneration && currentTab == TAB_HELP && dialog != null && dialog.isShowing()) {
                        showTransientFeedback(successMessage);
                        loadTicketThread(ticketId, dialog, target);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (screenGeneration == targetScreenGeneration && currentTab == TAB_HELP) {
                        setStatus(exception.getMessage());
                    }
                });
            } finally {
                ticketWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            ticketWriteInFlight.set(false);
        }
    }

    private void showGroups() {
        currentTab = TAB_GROUPS;
        LinearLayout card = screen("家庭组管理");
        EditText joinCode = input("输入 8 位字母或数字家庭组号");
        Button join = primaryButton("加入家庭组");
        Button refresh = secondaryButton("刷新家庭组");
        Button leave = secondaryButton("退出当前家庭组");

        join.setOnClickListener(view -> joinGroupByCode(joinCode.getText().toString()));
        refresh.setOnClickListener(view -> renderGroups());
        leave.setOnClickListener(view -> confirmLeaveCurrentGroup());

        card.addView(joinCode, blockParams(10));
        card.addView(join, blockParams(10));
        card.addView(buttonRow(refresh, leave), blockParams(16));
        setScreen(card, false);
        renderGroups();
    }

    private void renderGroups() {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONArray groups = currentUser == null ? null : currentUser.optJSONArray("groups");
        if (groups == null || groups.length() == 0) {
            LinearLayout empty = simpleSummaryPanel("提示", "暂无家庭组，请通过组号加入。", true);
            empty.setTag("dynamic");
            content.addView(empty, blockParams(10));
            setStatus("暂无家庭组");
            return;
        }

        content.addView(dynamicSectionTitle("我的家庭组"), blockParams(8));
        int currentUserId = currentUser == null ? 0 : currentUser.optInt("id", 0);
        for (int index = 0; index < groups.length(); index += 1) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) {
                continue;
            }
            String groupName = group.optString("group_name", "");
            String displayName = group.optString("display_name", groupName);
            boolean selected = groupName.equals(selectedGroupName) || (selectedGroupName.isEmpty() && currentUser != null && groupName.equals(currentUser.optString("group_name", "")));
            boolean owner = group.optInt("owner_user_id", 0) == currentUserId;

            LinearLayout row = groupSummaryCard(group, selected, owner);
            row.setTag(VIEW_TAG_DYNAMIC);
            content.addView(row, blockParams(8));

            Button select = secondaryButton(selected ? "当前家庭组" : "设为当前组");
            select.setTag(VIEW_TAG_DYNAMIC);
            select.setOnClickListener(view -> {
                if (selected) {
                    setStatus("当前家庭组：" + displayName);
                    return;
                }
                selectedGroupName = groupName;
                persistSelectedGroup(group);
                renderGroups();
                setStatus("已切换当前家庭组：" + displayName);
            });

            Button action = secondaryButton(owner ? "更多操作" : "端到端加密状态");
            action.setTag(VIEW_TAG_DYNAMIC);
            if (owner) {
                action.setOnClickListener(view -> showGroupMoreActions(group));
            } else {
                action.setOnClickListener(view -> showP2PStatus(groupName));
            }
            content.addView(buttonRow(select, action), blockParams(10));
        }
        setStatus("家庭组已加载：" + groups.length());
    }

    private LinearLayout groupSummaryCard(JSONObject group, boolean selected, boolean owner) {
        String groupName = group.optString("group_name", "");
        String displayName = group.optString("display_name", groupName);
        String groupCode = firstText(group.optString("group_code", ""), "未生成");
        String roleLabel = group.optString("role_label", "");
        LinearLayout panel = detailListPanel(true);
        panel.addView(summaryCardHeader(displayName, selected ? "当前" : roleLabel, selected ? "guardian" : group.optString("role", "")), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addDetailRow(panel, "组名", groupName);
        addDetailRow(panel, "组号", groupCode);
        if (!selected && hasMeaningfulText(roleLabel)) {
            addDetailRow(panel, "身份", roleLabel);
        }
        if (group.optBoolean("p2p_enabled", false)) {
            addDetailRow(panel, "端到端加密", "已开启");
        }
        if (owner) {
            addDetailRow(panel, "管理", "可改名、管理成员和端到端加密");
        }
        return panel;
    }
    private void showGroupMoreActions(JSONObject group) {
        currentTab = TAB_GROUPS;
        String groupName = group.optString("group_name", "");
        String displayName = group.optString("display_name", groupName);
        int groupId = group.optInt("id", 0);
        Dialog dialog = choiceDialog(displayName + " 更多操作");
        LinearLayout body = choiceDialogBody(dialog);
        String groupCode = group.optString("group_code", "");

        LinearLayout summary = detailListPanel(false);
        summary.addView(summaryCardHeader(displayName, group.optString("role_label", ""), group.optString("role", "")), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addDetailRow(summary, "组名", groupName);
        addDetailRow(summary, "组号", firstText(groupCode, "未生成"));
        body.addView(summary, blockParams(12));

        body.addView(sectionTitle("家庭组名称"), blockParams(8));
        EditText rename = input("新的家庭组显示名");
        rename.setText(displayName);
        Button save = primaryButton("保存名称");
        save.setOnClickListener(view -> {
            dialog.dismiss();
            renameGroup(groupId, rename.getText().toString());
        });
        body.addView(rename, blockParams(8));
        body.addView(save, blockParams(12));

        Button p2p = secondaryButton("端到端加密状态");
        p2p.setOnClickListener(view -> {
            dialog.dismiss();
            showP2PStatus(groupName);
        });
        body.addView(p2p, blockParams(14));

        appendOwnedGroupMembers(body, group, dialog);
        showChoiceDialog(dialog, body);
        setStatus("正在管理：" + displayName);
    }

    private void joinGroupByCode(String groupCode) {
        String code = GroupCodePolicy.normalize(groupCode);
        if (!GroupCodePolicy.isAcceptedExisting(code)) {
            setStatus("请输入 8 位字母或数字家庭组号；已有的 32 位旧组号仍可使用。");
            return;
        }
        if (!beginGroupWrite()) {
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("正在加入家庭组");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject response = postJson(ApiPaths.GROUPS, new JSONObject()
                    .put("action", "join_by_code")
                    .put("group_code", code));
                applyUserResponse(response);
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    renderGroups();
                    setStatus("已加入家庭组");
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            } finally {
                groupWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            groupWriteInFlight.set(false);
        }
    }

    private void confirmLeaveCurrentGroup() {
        String groupName = selectedGroupName.isEmpty() && currentUser != null ? currentUser.optString("group_name", "") : selectedGroupName;
        if (groupName.isEmpty()) {
            setStatus("当前没有可退出的家庭组。");
            return;
        }
        showPopupDialog(
            "退出家庭组",
            new String[][] {
                new String[] {"确认操作", "确定退出当前家庭组？退出后将无法继续查看该家庭组位置，除非重新通过组号加入。"}
            },
            "确认退出",
            () -> leaveCurrentGroup(groupName),
            "取消"
        );
    }

    private void leaveCurrentGroup(String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            setStatus("当前没有可退出的家庭组。");
            return;
        }
        if (!beginGroupWrite()) {
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("正在退出家庭组");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject response = postJson(ApiPaths.GROUPS, new JSONObject()
                    .put("action", "leave_group")
                    .put("group_name", groupName.trim()));
                applyUserResponse(response);
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    renderGroups();
                    setStatus("已退出家庭组");
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            } finally {
                groupWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            groupWriteInFlight.set(false);
        }
    }

    private void renameGroup(int groupId, String displayName) {
        String value = displayName.trim();
        if (groupId <= 0 || value.isEmpty()) {
            setStatus("请填写家庭组显示名。");
            return;
        }
        if (!beginGroupWrite()) {
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("正在保存家庭组名称");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject response = postJson(ApiPaths.GROUPS, new JSONObject()
                    .put("action", "rename_group")
                    .put("group_id", groupId)
                    .put("group_name", value));
                applyUserResponse(response);
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    showGroups();
                    setStatus("家庭组名称已保存");
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            } finally {
                groupWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            groupWriteInFlight.set(false);
        }
    }

    private void appendOwnedGroupMembers(JSONObject group) {
        appendOwnedGroupMembers(content, group, null);
    }
    private void appendOwnedGroupMembers(LinearLayout target, JSONObject group, Dialog parentDialog) {
        if (target == null) {
            return;
        }
        JSONArray members = group.optJSONArray("members");
        if (members == null || members.length() == 0) {
            return;
        }

        String groupName = group.optString("group_name", "");
        TextView title = sectionTitle("成员管理");
        if (target == content) {
            title.setTag(VIEW_TAG_DYNAMIC);
        }
        target.addView(title, blockParams(6));
        int currentUserId = currentUser == null ? 0 : currentUser.optInt("id", 0);
        for (int index = 0; index < members.length(); index += 1) {
            JSONObject member = members.optJSONObject(index);
            if (member == null) {
                continue;
            }
            int memberId = member.optInt("user_id", 0);
            String memberLabel = userDisplayName(member);
            LinearLayout memberCard = detailListPanel(true);
            memberCard.addView(summaryCardHeader(memberLabel, member.optString("role_label", ""), member.optString("role", "")), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addDetailRow(memberCard, "账号", member.optString("username", ""));
            if (target == content) {
                memberCard.setTag(VIEW_TAG_DYNAMIC);
            }
            target.addView(memberCard, blockParams(6));

            if (memberId > 0 && memberId != currentUserId) {
                Button reset = secondaryButton("重置密码");
                if (target == content) {
                    reset.setTag(VIEW_TAG_DYNAMIC);
                }
                reset.setOnClickListener(view -> {
                    if (parentDialog != null) {
                        parentDialog.dismiss();
                    }
                    showMemberPasswordReset(groupName, memberId, memberLabel);
                });
                Button remove = secondaryButton("移出成员");
                if (target == content) {
                    remove.setTag(VIEW_TAG_DYNAMIC);
                }
                remove.setOnClickListener(view -> {
                    if (parentDialog != null) {
                        parentDialog.dismiss();
                    }
                    confirmRemoveMember(groupName, memberId, memberLabel);
                });
                target.addView(buttonRow(reset, remove), blockParams(10));
            }
        }
    }

    private void confirmRemoveMember(String groupName, int memberId, String memberLabel) {
        showPopupDialog(
            "\u79fb\u51fa\u6210\u5458",
            new String[][] {
                new String[] {"\u786e\u8ba4\u64cd\u4f5c", "\u786e\u8ba4\u5c06 " + memberLabel + " \u79fb\u51fa\u5f53\u524d\u5bb6\u5ead\u7ec4\uff1f\u79fb\u51fa\u540e\u8be5\u6210\u5458\u5c06\u65e0\u6cd5\u67e5\u770b\u8fd9\u4e2a\u5bb6\u5ead\u7ec4\u7684\u4f4d\u7f6e\u3002"}
            },
            "\u786e\u8ba4\u79fb\u51fa",
            () -> removeMember(groupName, memberId),
            "\u53d6\u6d88"
        );
    }

    private void removeMember(String groupName, int memberId) {
        if (groupName == null || groupName.trim().isEmpty() || memberId <= 0) {
            setStatus("\u6210\u5458\u4fe1\u606f\u4e0d\u5b8c\u6574\u3002");
            return;
        }
        if (!beginGroupWrite()) {
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("\u6b63\u5728\u79fb\u51fa\u6210\u5458");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject response = postJson(ApiPaths.GROUPS, new JSONObject()
                    .put("action", "remove_member")
                    .put("group_name", groupName)
                    .put("target_user_id", memberId));
                applyUserResponse(response);
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    showGroups();
                    setStatus("\u6210\u5458\u5df2\u79fb\u51fa\u5bb6\u5ead\u7ec4");
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            } finally {
                groupWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            groupWriteInFlight.set(false);
        }
    }

    private void showMemberPasswordReset(String groupName, int memberId, String memberLabel) {
        Dialog dialog = choiceDialog("\u91cd\u7f6e\u6210\u5458\u5bc6\u7801");
        LinearLayout body = choiceDialogBody(dialog);
        LinearLayout warning = detailListPanel(false);
        warning.addView(summaryCardHeader(memberLabel, "成员", ""), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addDetailRow(warning, "重置说明", "仅当该成员只属于当前家庭组时可直接重置。若成员属于多个家庭组，请走工单。");
        EditText newPassword = input("\u65b0\u5bc6\u7801\uff0c\u81f3\u5c11 6 \u4f4d");
        newPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPasswordConfirm = input("\u518d\u6b21\u8f93\u5165\u65b0\u5bc6\u7801");
        newPasswordConfirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox confirm = new CheckBox(this);
        confirm.setText("\u6211\u786e\u8ba4\u8981\u91cd\u7f6e\u8be5\u6210\u5458\u5bc6\u7801");
        uiStyle.styleCheckBox(confirm, denseUi());
        Button submit = primaryButton("\u786e\u8ba4\u91cd\u7f6e\u5bc6\u7801");
        submit.setOnClickListener(view -> resetMemberPassword(
            groupName,
            memberId,
            newPassword.getText().toString(),
            newPasswordConfirm.getText().toString(),
            confirm.isChecked(),
            dialog
        ));

        body.addView(warning, blockParams(14));
        body.addView(newPassword, blockParams(10));
        body.addView(newPasswordConfirm, blockParams(10));
        body.addView(confirm, blockParams(10));
        body.addView(submit, blockParams(0));
        showChoiceDialog(dialog, body);
    }

    private void resetMemberPassword(String groupName, int memberId, String newPassword, String newPasswordConfirm, boolean confirmed) {
        resetMemberPassword(groupName, memberId, newPassword, newPasswordConfirm, confirmed, null);
    }

    private void resetMemberPassword(String groupName, int memberId, String newPassword, String newPasswordConfirm, boolean confirmed, Dialog dialog) {
        if (!confirmed) {
            setStatus("\u8bf7\u5148\u52fe\u9009\u786e\u8ba4\u91cd\u7f6e\u64cd\u4f5c\u3002");
            return;
        }
        if (newPassword.trim().length() < 6 || !newPassword.equals(newPasswordConfirm)) {
            setStatus("\u8bf7\u586b\u5199\u4e24\u904d\u4e00\u81f4\u4e14\u81f3\u5c11 6 \u4f4d\u7684\u65b0\u5bc6\u7801\u3002");
            return;
        }
        if (!beginGroupWrite()) {
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("\u6b63\u5728\u91cd\u7f6e\u6210\u5458\u5bc6\u7801");
        boolean scheduled = runBackground(() -> {
            try {
                JSONObject response = postJson(ApiPaths.GROUPS, new JSONObject()
                    .put("action", "reset_member_password")
                    .put("group_name", groupName)
                    .put("target_user_id", memberId)
                    .put("new_password", newPassword)
                    .put("new_password_confirm", newPasswordConfirm)
                    .put("confirm", true));
                applyUserResponse(response);
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    if (dialog != null && dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    showGroups();
                    setStatus("\u6210\u5458\u5bc6\u7801\u5df2\u91cd\u7f6e");
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            } finally {
                groupWriteInFlight.set(false);
            }
        });
        if (!scheduled) {
            groupWriteInFlight.set(false);
        }
    }

    private void applyUserResponse(JSONObject response) {
        JSONObject user = response.optJSONObject("user");
        if (user == null) {
            return;
        }
        currentUser = user;
        JSONObject selected = selectedGroupForSession(user);
        selectedGroupName = selected.optString("group_name", "");
        persistUserSession(user, response.optInt("report_interval_seconds", prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300)));
    }

    private void showP2PStatus(String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            setStatus("家庭组无效。");
            return;
        }

        Dialog dialog = choiceDialog("端到端加密");
        LinearLayout body = choiceDialogBody(dialog);
        body.addView(simpleSummaryPanel("状态", "正在加载端到端加密状态…"), blockParams(0));
        dialog.setOnDismissListener(ignored -> p2pRequestGate.invalidate());
        showChoiceDialog(dialog, body);
        loadP2PStatus(groupName, dialog, body);
    }

    private void loadP2PStatus(String groupName, Dialog dialog, LinearLayout target) {
        final long targetScreenGeneration = screenGeneration;
        final long requestToken = p2pRequestGate.begin();
        setStatus("正在加载端到端加密状态");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.status(this::postJson, groupName);
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        renderP2PStatus(groupName, response, dialog, target);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        setStatus(exception.getMessage());
                    }
                });
            }
        });
    }

    private boolean isCurrentP2PRequest(long requestToken, long targetScreenGeneration, Dialog dialog, LinearLayout target) {
        return p2pRequestGate.isCurrent(requestToken)
            && screenGeneration == targetScreenGeneration
            && dialog != null
            && dialog.isShowing()
            && target != null;
    }

    private void renderP2PStatus(String groupName, JSONObject response, Dialog dialog, LinearLayout target) {
        if (target == null) {
            return;
        }

        target.removeAllViews();
        LinearLayout summary = detailListPanel(false);
        boolean enabled = response.optBoolean("enabled", false);
        boolean owner = response.optBoolean("is_owner", false);
        summary.addView(summaryCardHeader(groupName, enabled ? "已开启" : "未开启", enabled ? "guardian" : ""), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addDetailRow(summary, "密钥版本", String.valueOf(response.optInt("key_version", 0)));
        addDetailRow(summary, "组主权限", owner ? "是" : "否");
        if (response.optBoolean("needs_key_distribution", false)) {
            addDetailRow(summary, "提示", "有成员等待组主补发密钥。");
        }
        if (enabled && response.optString("wrapped_group_key", "").isEmpty()) {
            addDetailRow(summary, "提示", "本机没有组密钥，开启后无法解密或上报，请让组主补发。");
        }
        target.addView(summary, blockParams(10));

        Button refresh = secondaryButton("刷新状态");
        refresh.setOnClickListener(view -> loadP2PStatus(groupName, dialog, target));
        Button consent = secondaryButton("同意并发布本机公钥");
        consent.setOnClickListener(view -> showP2PPasswordStepUp(groupName, dialog, target));
        target.addView(buttonRow(refresh, consent), blockParams(10));

        JSONArray members = response.optJSONArray("members");
        boolean allMembersReady = members != null && members.length() > 0;
        if (members != null && members.length() > 0) {
            target.addView(sectionTitle("成员准备状态"), blockParams(8));
            for (int index = 0; index < members.length(); index += 1) {
                JSONObject member = members.optJSONObject(index);
                if (member == null) {
                    continue;
                }
                boolean memberReady = member.optBoolean("consented", false) && member.optBoolean("has_public_key", false);
                allMembersReady = allMembersReady && memberReady;
                LinearLayout row = detailListPanel(false);
                row.addView(summaryCardHeader(
                    member.optString("display_name", member.optString("username", "成员")),
                    member.optString("role_label", ""),
                    member.optString("role", "")
                ), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                addDetailRow(row, "已同意", member.optBoolean("consented", false) ? "是" : "否");
                addDetailRow(row, "已发布公钥", member.optBoolean("has_public_key", false) ? "是" : "否");
                addDetailRow(row, "已有组密钥", member.optBoolean("has_wrapped_key", false) ? "是" : "否");
                target.addView(row, blockParams(8));
            }
        }

        if (response.optBoolean("is_owner", false) && !response.optBoolean("enabled", false)) {
            if (allMembersReady) {
                Button enable = primaryButton("开启端到端加密");
                enable.setOnClickListener(view -> enableP2PGroup(groupName, dialog, target));
                target.addView(enable, blockParams(10));
            } else {
                LinearLayout hint = simpleSummaryPanel("提示", "等待所有成员同意并发布公钥后，组主即可开启端到端加密。");
                target.addView(hint, blockParams(10));
            }
        }
        if (response.optBoolean("is_owner", false) && response.optBoolean("needs_key_distribution", false)) {
            Button distribute = primaryButton("补发组密钥");
            distribute.setOnClickListener(view -> distributeP2PGroupKey(groupName, dialog, target));
            target.addView(distribute, blockParams(10));
        }
        setStatus("端到端加密状态已加载");
    }



    private void showP2PPasswordStepUp(String groupName, Dialog statusDialog, LinearLayout target) {
        Dialog passwordDialog = choiceDialog("验证当前密码");
        LinearLayout body = choiceDialogBody(passwordDialog);
        body.addView(simpleSummaryPanel("安全验证", "首次发布或更换本机端到端加密公钥前，需要验证当前账号密码。密码不会保存。"), blockParams(10));
        EditText currentPassword = input("当前密码");
        currentPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button confirm = primaryButton("验证并发布公钥");
        confirm.setOnClickListener(view -> {
            String password = currentPassword.getText().toString();
            currentPassword.setText("");
            if (password.isEmpty()) {
                setStatus("请输入当前密码。");
                return;
            }
            passwordDialog.dismiss();
            consentP2P(groupName, statusDialog, target, password);
        });
        body.addView(currentPassword, blockParams(10));
        body.addView(confirm, blockParams(0));
        showChoiceDialog(passwordDialog, body);
    }

    private void consentP2P(String groupName, Dialog dialog, LinearLayout target, String currentPassword) {
        final long targetScreenGeneration = screenGeneration;
        final long requestToken = p2pRequestGate.begin();
        setStatus("正在发布本机公钥");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.setConsent(this::postJson, this, groupName, currentPassword, true);
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        renderP2PStatus(groupName, response, dialog, target);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        setStatus("公钥发布失败：" + exception.getMessage());
                    }
                });
            }
        });
    }

    private void enableP2PGroup(String groupName, Dialog dialog, LinearLayout target) {
        final long targetScreenGeneration = screenGeneration;
        final long requestToken = p2pRequestGate.begin();
        setStatus("正在开启端到端加密");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.enableGroup(this::postJson, this, groupName);
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        renderP2PStatus(groupName, response, dialog, target);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        setStatus(exception.getMessage());
                    }
                });
            }
        });
    }

    private void distributeP2PGroupKey(String groupName, Dialog dialog, LinearLayout target) {
        final long targetScreenGeneration = screenGeneration;
        final long requestToken = p2pRequestGate.begin();
        setStatus("正在补发组密钥");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.distributeGroupKey(this::postJson, this, groupName);
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        renderP2PStatus(groupName, response, dialog, target);
                    }
                });
            } catch (Exception exception) {
                runUi(() -> {
                    if (isCurrentP2PRequest(requestToken, targetScreenGeneration, dialog, target)) {
                        setStatus(exception.getMessage());
                    }
                });
            }
        });
    }

    private void showSettings() {
        currentTab = TAB_MINE;
        LinearLayout card = screen("我的");
        LinearLayout account = accountSummaryPanel(currentUser);
        boolean accessibilityKeepAliveEnabled = KeepAliveAccessibilityService.isEnabled(this);
        LinearLayout accessibilityKeepAlive = simpleSummaryPanel(
            "无障碍保活",
            accessibilityKeepAliveEnabled
                ? "已开启。系统正在绑定位置保活辅助服务。"
                : "未开启。需由你在系统无障碍设置中手动授权。"
        );
        Button accessibilityKeepAliveSettings = secondaryButton(
            accessibilityKeepAliveEnabled ? "查看无障碍设置" : "开启无障碍保活"
        );
        accessibilityKeepAliveSettings.setOnClickListener(view -> showAccessibilityKeepAliveSettings());
        CheckBox environmentConsent = new CheckBox(this);
        environmentConsent.setText("同意上传环境诊断数据");
        uiStyle.styleCheckBox(environmentConsent, denseUi());
        environmentConsent.setChecked(currentUser != null && currentUser.optBoolean("environment_data_consent", false));
        Button uploadEnvironment = secondaryButton("立即上报环境信息");
        uploadEnvironment.setEnabled(environmentConsent.isChecked());
        environmentConsent.setOnCheckedChangeListener((button, checked) -> {
            uploadEnvironment.setEnabled(checked);
            saveEnvironmentConsent(checked);
        });
        uploadEnvironment.setOnClickListener(view -> {
            if (!environmentConsent.isChecked()) {
                setStatus("请先勾选环境数据上报。");
                return;
            }
            uploadEnvironmentReport(true, true, true);
        });
        Button changePassword = secondaryButton("修改密码");
        changePassword.setOnClickListener(view -> showPasswordChange());
        Button checkUpdate = secondaryButton("检查更新");
        checkUpdate.setOnClickListener(view -> checkAppUpdateManually());
        Button logout = secondaryButton("退出登录");
        logout.setOnClickListener(view -> logout());

        card.addView(sectionTitle("账号信息"), blockParams(8));
        card.addView(account, blockParams(14));
        appendMySharesSection(card);
        LinearLayout themeSummary = simpleSummaryPanel("当前主题", themeModeLabel(themeMode()));
        Button changeTheme = secondaryButton("切换主题");
        changeTheme.setOnClickListener(view -> showThemePicker());
        card.addView(sectionTitle("界面主题"), blockParams(8));
        card.addView(themeSummary, blockParams(8));
        card.addView(changeTheme, blockParams(14));
        card.addView(sectionTitle("运行与保活"), blockParams(8));
        card.addView(accessibilityKeepAlive, blockParams(8));
        card.addView(accessibilityKeepAliveSettings, blockParams(14));
        card.addView(sectionTitle("隐私与上报"), blockParams(8));
        card.addView(environmentConsent, blockParams(8));
        card.addView(uploadEnvironment, blockParams(12));
        card.addView(sectionTitle("账号安全"), blockParams(8));
        card.addView(simpleSummaryPanel("修改说明", "验证当前密码后修改，修改成功后继续保持当前登录状态。"), blockParams(8));
        card.addView(changePassword, blockParams(14));
        card.addView(sectionTitle("应用更新"), blockParams(8));
        card.addView(checkUpdate, blockParams(14));
        card.addView(logout, blockParams(0));
        setScreen(card, false);
        setStatus("");
    }

    private void checkAppUpdateManually() {
        final long targetScreenGeneration = screenGeneration;
        setStatus("正在检查更新");
        runBackground(() -> {
            try {
                JSONObject update = getJson(ApiPaths.APP_UPDATE + "?version_code=" + APP_VERSION_CODE);
                boolean required = update.optBoolean("update_required", false);
                String apkUrl = update.optString("apk_url", "").trim();
                String versionName = update.optString("latest_version_name", "").trim();
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    if (required && !apkUrl.isEmpty()) {
                        showUpdateRequired(firstText(versionName, "新版本"), apkUrl);
                        return;
                    }
                    String message = "位置 App 已是最新版本：" + APP_VERSION_NAME + "。";
                    setStatus(message);
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus("检查更新失败：" + exception.getMessage()));
            }
        });
    }

    private void showAccessibilityKeepAliveSettings() {
        showPopupDialog(
            "无障碍保活",
            new String[][] {
                new String[] {
                    "用途",
                    "开启后，Android 会持续绑定本应用的保活辅助服务，以降低位置上报进程在后台被回收的概率。"
                },
                new String[] {
                    "隐私边界",
                    "该服务仅监听本应用窗口状态，事件回调不会读取或保存内容；它不能查看其他应用、读取窗口内容、截图或执行手势。"
                },
                new String[] {
                    "如何开启",
                    "进入系统页面后选择“位置保活辅助服务”并开启。你可随时回到同一页面关闭。"
                }
            },
            "前往系统设置",
            this::launchAccessibilityKeepAliveSettings,
            "取消"
        );
    }

    private void launchAccessibilityKeepAliveSettings() {
        accessibilitySettingsLaunched = true;
        ComponentName component = new ComponentName(this, KeepAliveAccessibilityService.class);
        try {
            Intent details = new Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
            details.putExtra(Intent.EXTRA_COMPONENT_NAME, component);
            details.setData(Uri.parse("package:" + getPackageName()));
            if (details.resolveActivity(getPackageManager()) != null) {
                startActivity(details);
                return;
            }
        } catch (Exception exception) {
            Log.w(TAG, "Accessibility details settings unavailable: " + exception.getMessage());
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception exception) {
            accessibilitySettingsLaunched = false;
            setStatus("系统无障碍设置不可用：" + exceptionMessage(exception));
        }
    }

    private void appendMySharesSection(LinearLayout card) {
        LinearLayout recentShares = new LinearLayout(this);
        recentShares.setOrientation(LinearLayout.VERTICAL);
        recentShares.addView(compactInfoPanel("正在加载我的分享…", false), blockParams(6));

        final JSONArray[] loadedShares = new JSONArray[] {null};
        final String[] loadError = new String[] {""};
        Button expand = secondaryButton("展开更多");
        expand.setOnClickListener(view -> showMySharesDialog(loadedShares[0], loadError[0]));

        card.addView(sectionTitle("我的分享"), blockParams(8));
        card.addView(recentShares, blockParams(6));
        card.addView(expand, blockParams(14));

        String groupName = currentGroupName();
        runBackground(() -> {
            try {
                JSONArray shares = loadAllMyShares(groupName);
                runUi(() -> {
                    if (currentTab != TAB_MINE || content != card) {
                        return;
                    }
                    loadedShares[0] = shares;
                    loadError[0] = "";
                    renderRecentMyShares(recentShares, shares);
                });
            } catch (Exception exception) {
                String message = firstText(exception.getMessage(), "暂时无法加载分享记录");
                runUi(() -> {
                    if (currentTab != TAB_MINE || content != card) {
                        return;
                    }
                    loadedShares[0] = new JSONArray();
                    loadError[0] = message;
                    recentShares.removeAllViews();
                    recentShares.addView(compactInfoPanel("分享记录加载失败：" + message, false), blockParams(6));
                });
            }
        });
    }

    private JSONArray loadAllMyShares(String groupName) throws Exception {
        JSONArray result = new JSONArray();
        int offset = 0;
        final int pageSize = 100;
        while (true) {
            String endpoint = ApiPaths.SHARE
                + "?group_name=" + urlEncode(groupName)
                + "&limit=" + pageSize
                + "&offset=" + offset;
            JSONArray page = myShareItems(getJson(endpoint));
            for (int index = 0; index < page.length(); index += 1) {
                JSONObject share = page.optJSONObject(index);
                if (share != null) {
                    result.put(share);
                }
            }
            if (page.length() < pageSize) {
                return result;
            }
            offset += page.length();
        }
    }

    private JSONArray myShareItems(JSONObject response) {
        if (response == null) {
            return new JSONArray();
        }
        JSONArray shares = response.optJSONArray("shares");
        if (shares == null) {
            shares = response.optJSONArray("items");
        }
        return shares == null ? new JSONArray() : shares;
    }

    private void renderRecentMyShares(LinearLayout target, JSONArray shares) {
        target.removeAllViews();
        if (shares == null || shares.length() == 0) {
            target.addView(compactInfoPanel("当前家庭组还没有位置分享。", false), blockParams(6));
            return;
        }
        int count = Math.min(3, shares.length());
        for (int index = 0; index < count; index += 1) {
            JSONObject share = shares.optJSONObject(index);
            if (share != null) {
                target.addView(myShareSummaryPanel(share, false, null), blockParams(index + 1 < count ? 6 : 0));
            }
        }
    }

    private void showMySharesDialog(JSONArray shares, String loadError) {
        Dialog dialog = choiceDialog("我的分享");
        LinearLayout body = choiceDialogBody(dialog);
        if (shares == null) {
            body.addView(compactInfoPanel("正在加载分享记录…", false), blockParams(0));
        } else if (shares.length() == 0) {
            String message = loadError == null || loadError.trim().isEmpty()
                ? "当前家庭组还没有位置分享。"
                : "分享记录加载失败：" + loadError.trim();
            body.addView(compactInfoPanel(message, false), blockParams(0));
        } else {
            for (int index = 0; index < shares.length(); index += 1) {
                JSONObject share = shares.optJSONObject(index);
                if (share != null) {
                    LinearLayout summary = myShareSummaryPanel(share, true, () -> {
                        dialog.dismiss();
                        showMyShareDetailsDialog(share);
                    });
                    body.addView(summary, blockParams(index + 1 < shares.length() ? 8 : 0));
                }
            }
        }
        showChoiceDialog(dialog, body);
    }

    private void showHistoryMemberPicker(JSONObject historyResponse, JSONArray members) {
        boolean selectionLimited = historyResponse != null
            && (historyResponse.optBoolean("selection_limited", false)
                || historyResponse.optBoolean("members_truncated", false));
        if (selectionLimited) {
            loadHistoryMemberPickerPage(1);
            return;
        }
        showHistoryMemberPicker(members);
    }

    private void loadHistoryMemberPickerPage(int requestedPage) {
        final int page = Math.max(1, requestedPage);
        final String targetGroupName = currentGroupName();
        final String targetSelectedGroupName = selectedGroupName;
        final LinearLayout targetContent = content;
        final long requestToken = historyRequestGate.begin();
        final JsonApiClient.RequestHandle requestHandle = new JsonApiClient.RequestHandle();
        replaceActiveRequest(activeHistoryRequest, requestHandle);
        setStatus("正在加载成员列表");
        runBackground(() -> {
            try {
                String endpoint = ApiPaths.HISTORY_MEMBERS
                    + "?group_name=" + urlEncode(targetGroupName)
                    + "&page=" + page;
                JSONObject response = getJson(endpoint, requestHandle);
                runUi(() -> {
                    if (!historyRequestGate.isCurrent(requestToken)
                        || content != targetContent
                        || currentTab != TAB_POSITION
                        || !targetSelectedGroupName.equals(selectedGroupName)) {
                        return;
                    }
                    renderHistoryMemberPickerPage(response, page);
                });
            } catch (Exception exception) {
                if (!isCancelledRequest(exception)) {
                    runUi(() -> {
                        if (historyRequestGate.isCurrent(requestToken)
                            && content == targetContent
                            && currentTab == TAB_POSITION
                            && targetSelectedGroupName.equals(selectedGroupName)) {
                            setStatus("加载成员列表失败：" + exception.getMessage());
                        }
                    });
                }
            } finally {
                activeHistoryRequest.compareAndSet(requestHandle, null);
            }
        });
    }

    private void renderHistoryMemberPickerPage(JSONObject response, int requestedPage) {
        JSONArray members = response == null ? null : response.optJSONArray("members");
        JSONObject pagination = response == null ? null : response.optJSONObject("pagination");
        int page = pagination == null ? Math.max(1, requestedPage) : Math.max(1, pagination.optInt("page", requestedPage));
        int totalPages = pagination == null ? page : Math.max(page, pagination.optInt("total_pages", page));
        Dialog dialog = choiceDialog("筛选成员");
        LinearLayout body = choiceDialogBody(dialog);
        body.addView(simpleSummaryPanel("成员范围", "成员较多，请先选择一名成员查看历史。第 " + page + " / " + totalPages + " 页。"), blockParams(10));
        if (members == null || members.length() == 0) {
            body.addView(simpleSummaryPanel("提示", "这一页没有可选成员。"), blockParams(10));
        } else {
            for (int index = 0; index < members.length(); index += 1) {
                JSONObject member = members.optJSONObject(index);
                if (member == null) {
                    continue;
                }
                addHistoryMemberChoice(body, dialog, memberLabel(member), member.optInt("user_id", 0));
            }
        }
        Button previous = secondaryButton("上一页");
        Button next = secondaryButton("下一页");
        previous.setEnabled(page > 1);
        next.setEnabled(page < totalPages);
        previous.setOnClickListener(view -> {
            dialog.dismiss();
            loadHistoryMemberPickerPage(page - 1);
        });
        next.setOnClickListener(view -> {
            dialog.dismiss();
            loadHistoryMemberPickerPage(page + 1);
        });
        body.addView(buttonRow(previous, next), blockParams(0));
        showChoiceDialog(dialog, body);
        setStatus("请选择要查看历史的成员。");
    }

    private LinearLayout myShareSummaryPanel(JSONObject share, boolean showLink, Runnable openDetails) {
        LinearLayout panel = detailListPanel(false);
        String createdAt = firstText(share.optString("created_at", ""), "未知");
        String shareUrl = firstText(share.optString("share_url", ""), share.optString("url", ""));
        panel.addView(summaryCardHeader(
            createdAt,
            myShareStatus(share),
            isMyShareActive(share) ? "guardian" : "monitor"
        ), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (showLink) {
            addCopyableMyShareLink(panel, shareUrl);
        }
        if (openDetails != null) {
            panel.setClickable(true);
            panel.setFocusable(true);
            panel.setContentDescription("查看分享详情，创建时间 " + createdAt);
            panel.setOnClickListener(view -> openDetails.run());
        }
        return panel;
    }

    private void showMyShareDetailsDialog(JSONObject share) {
        Dialog dialog = choiceDialog("分享详情");
        LinearLayout body = choiceDialogBody(dialog);
        body.addView(myShareDetailsPanel(share), blockParams(0));
        showChoiceDialog(dialog, body);
    }

    private LinearLayout myShareDetailsPanel(JSONObject share) {
        LinearLayout panel = detailListPanel(false);
        String shareUrl = firstText(share.optString("share_url", ""), share.optString("url", ""));
        String accessCode = firstText(share.optString("access_code", ""), share.optString("share_code", ""));
        String expiresAt = firstText(share.optString("expires_at", ""), "未知");
        addDetailRow(panel, "状态", myShareStatus(share));
        addDetailRow(panel, "创建时间", firstText(share.optString("created_at", ""), "未知"));
        addDetailRow(panel, "有效期至", expiresAt);
        addDetailRow(panel, "位置数", myShareLocationCount(share) + " 条");
        addCopyableMyShareLink(panel, shareUrl);
        addDetailRow(panel, "分享码", firstText(accessCode, "暂不可用"));
        if (isMyShareActive(share) && !shareUrl.isEmpty() && !accessCode.isEmpty()) {
            Button resend = secondaryButton("重新分享");
            resend.setOnClickListener(view -> shareExistingLocationLink(shareUrl, accessCode, expiresAt));
            panel.addView(resend, blockParams(6));
        }
        return panel;
    }

    private void addCopyableMyShareLink(LinearLayout panel, String shareUrl) {
        String value = firstText(shareUrl, "暂不可用");
        addDetailRow(panel, "分享链接", value);
        if (shareUrl == null || shareUrl.trim().isEmpty() || panel.getChildCount() == 0) {
            return;
        }
        View linkRow = panel.getChildAt(panel.getChildCount() - 1);
        linkRow.setClickable(true);
        linkRow.setFocusable(true);
        linkRow.setContentDescription("复制分享链接 " + shareUrl);
        linkRow.setOnClickListener(view -> {
            try {
                copyLocationShareLink(shareUrl);
                setStatus("分享链接已复制");
                Toast.makeText(this, "分享链接已复制", Toast.LENGTH_SHORT).show();
            } catch (Exception exception) {
                Toast.makeText(this, firstText(exception.getMessage(), "复制失败"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String myShareStatus(JSONObject share) {
        String label = firstText(share.optString("status_label", ""));
        if (!label.isEmpty()) {
            return label;
        }
        String status = share.optString("status", "").trim().toLowerCase(Locale.US);
        if ("expired".equals(status) || "inactive".equals(status) || "已过期".equals(status)) {
            return "已过期";
        }
        if ("active".equals(status) || "valid".equals(status) || "有效".equals(status)) {
            return "有效";
        }
        if ((share.has("expired") && share.optBoolean("expired", false))
                || (share.has("is_expired") && share.optBoolean("is_expired", false))
                || (share.has("active") && !share.optBoolean("active", true))) {
            return "已过期";
        }
        return status.isEmpty() ? "有效" : status;
    }

    private boolean isMyShareActive(JSONObject share) {
        if (share.has("active")) {
            return share.optBoolean("active", false);
        }
        if ((share.has("expired") && share.optBoolean("expired", false))
                || (share.has("is_expired") && share.optBoolean("is_expired", false))) {
            return false;
        }
        String status = share.optString("status", "").trim().toLowerCase(Locale.US);
        return !"expired".equals(status) && !"inactive".equals(status) && !"已过期".equals(status);
    }

    private int myShareLocationCount(JSONObject share) {
        if (share.has("location_count")) {
            return Math.max(0, share.optInt("location_count", 0));
        }
        JSONArray locationIds = share.optJSONArray("location_ids");
        return locationIds == null ? 0 : locationIds.length();
    }

    private void shareExistingLocationLink(String shareUrl, String accessCode, String expiresAt) {
        try {
            copyLocationShareLink(shareUrl);
            setStatus("分享链接已复制");
            startActivity(Intent.createChooser(LocationShareSupport.linkIntent(shareUrl, accessCode, expiresAt), "分享位置链接"));
        } catch (Exception exception) {
            setStatus("打开分享面板失败：" + exception.getMessage());
        }
    }

    private void copyLocationShareLink(String shareUrl) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            throw new IllegalStateException("系统剪贴板不可用。");
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("位置分享链接", shareUrl));
    }

    private LinearLayout accountSummaryPanel(JSONObject user) {
        LinearLayout panel = detailListPanel(false);
        String displayName = user == null ? "未登录" : firstText(user.optString("display_name", ""), user.optString("username", ""), "未登录");
        String role = user == null ? "" : user.optString("role_label", "");
        String roleKey = user == null ? "" : user.optString("role", "");
        panel.addView(summaryCardHeader(displayName, role, roleKey), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (user != null) {
            String username = user.optString("username", "").trim();
            String display = user.optString("display_name", "").trim();
            if (!username.isEmpty() && !username.equals(display)) {
                addDetailRow(panel, "账号", username);
            }
            addDetailRow(panel, "家庭组", firstText(currentGroupName(), "未选择"));
        }
        return panel;
    }

    private LinearLayout simpleSummaryPanel(String label, String value) {
        return simpleSummaryPanel(label, value, false);
    }

    private LinearLayout simpleSummaryPanel(String label, String value, boolean dynamic) {
        LinearLayout panel = detailListPanel(dynamic);
        addDetailRow(panel, label, value);
        return panel;
    }

    private void setSummaryPanelValue(LinearLayout panel, String value) {
        TextView textView = summaryPanelValueView(panel);
        if (textView != null) {
            textView.setText(value == null ? "" : value);
        }
    }

    private TextView summaryPanelValueView(LinearLayout panel) {
        if (panel == null || panel.getChildCount() == 0) {
            return null;
        }
        View rowView = panel.getChildAt(panel.getChildCount() - 1);
        if (!(rowView instanceof LinearLayout)) {
            return null;
        }
        LinearLayout row = (LinearLayout) rowView;
        if (row.getChildCount() < 2 || !(row.getChildAt(1) instanceof TextView)) {
            return null;
        }
        return (TextView) row.getChildAt(1);
    }


    private void showThemePicker() {
        Dialog dialog = choiceDialog("切换主题");
        LinearLayout body = choiceDialogBody(dialog);
        addThemeChoice(body, dialog, "system", "跟随系统");
        addThemeChoice(body, dialog, "light", "明亮");
        addThemeChoice(body, dialog, "dark", "暗色");
        showChoiceDialog(dialog, body);
    }

    private void addThemeChoice(LinearLayout body, Dialog dialog, String mode, String label) {
        Button button = secondaryButton((mode.equals(themeMode()) ? "✓ " : "") + label);
        button.setOnClickListener(view -> {
            dialog.dismiss();
            applyThemeMode(mode);
        });
        body.addView(button, blockParams(8));
    }

    private void applyThemeMode(String mode) {
        String normalized = normalizeThemeMode(mode);
        prefs().edit().putString(KEY_THEME_MODE, normalized).apply();
        configureWindow();
        showSettings();
        setStatus("主题已切换：" + themeModeLabel(normalized));
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

    private void saveEnvironmentConsent(boolean enabled) {
        final long targetScreenGeneration = screenGeneration;
        setStatus("正在保存环境数据设置");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("group_name", selectedGroupName)
                    .put("environment_data_consent", enabled);
                JSONObject response = postJson(ApiPaths.SETTINGS, payload);
                JSONObject user = response.optJSONObject("user");
                if (user != null) {
                    currentUser = user;
                    persistUserSession(user, response.optInt("report_interval_seconds", prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300)));
                }
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(enabled ? "环境数据设置已保存，正在上传诊断。" : "环境数据设置已保存"));
                if (enabled) {
                    uploadEnvironmentReport(true, true, true, true);
                }
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            }
        });
    }

    private void saveGuardianContinuous(boolean enabled) {
        if (!"guardian".equals(currentUserRole())) {
            setStatus("只有监护端可以开启持续上报。");
            return;
        }
        String groupName = selectedGroupName.isEmpty() && currentUser != null ? currentUser.optString("group_name", "") : selectedGroupName;
        SharedPreferences.Editor editor = prefs().edit();
        if (!groupName.isEmpty()) {
            editor.putBoolean("guardian_continuous_reporting_" + groupName, enabled);
        }
        editor.apply();
        syncKeepAliveService();
        setStatus(enabled ? "监护端持续上报已开启" : "监护端持续上报已关闭");
    }

    private void showPasswordChange() {
        Dialog dialog = choiceDialog("修改密码");
        LinearLayout body = choiceDialogBody(dialog);
        LinearLayout help = simpleSummaryPanel("说明", "请输入当前密码，并设置至少 6 位的新密码。");
        EditText currentPassword = input("当前密码");
        currentPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPassword = input("新密码");
        newPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPasswordConfirm = input("再次输入新密码");
        newPasswordConfirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button save = primaryButton("保存新密码");
        save.setOnClickListener(view -> changePassword(
            currentPassword.getText().toString(),
            newPassword.getText().toString(),
            newPasswordConfirm.getText().toString(),
            dialog
        ));
        body.addView(help, blockParams(12));
        body.addView(currentPassword, blockParams(10));
        body.addView(newPassword, blockParams(10));
        body.addView(newPasswordConfirm, blockParams(12));
        body.addView(save, blockParams(0));
        showChoiceDialog(dialog, body);
        setStatus("");
    }

    private void changePassword(String currentPassword, String newPassword, String newPasswordConfirm, Dialog dialog) {
        if (currentPassword.isEmpty() || newPassword.isEmpty() || newPasswordConfirm.isEmpty()) {
            setStatus("请填写完整密码信息。");
            return;
        }

        final long targetScreenGeneration = screenGeneration;
        setStatus("正在修改密码");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "change_password")
                    .put("group_name", selectedGroupName)
                    .put("current_password", currentPassword)
                    .put("new_password", newPassword)
                    .put("new_password_confirm", newPasswordConfirm);
                JSONObject response = postJson(ApiPaths.SETTINGS, payload);
                JSONObject user = response.optJSONObject("user");
                if (user != null) {
                    currentUser = user;
                    persistUserSession(user, response.optInt("report_interval_seconds", prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300)));
                }
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    if (dialog != null && dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    showSettings();
                    setStatus("密码已修改");
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus(exception.getMessage()));
            }
        });
    }

    private void refreshLocations() {
        refreshLocations(false);
    }

    private void refreshLocations(boolean showSuccessFeedback) {
        final long refreshGeneration = ++locationRefreshGeneration;
        final LinearLayout targetContent = content;
        final Button targetRefreshButton = refreshButton;
        final String targetGroupName = selectedGroupName;
        final JsonApiClient.RequestHandle requestHandle = new JsonApiClient.RequestHandle();
        replaceActiveRequest(activeLocationRequest, requestHandle);
        if (targetRefreshButton != null) {
            targetRefreshButton.setEnabled(false);
        }
        setStatus("正在刷新位置");
        runBackground(() -> {
            try {
                String endpoint = ApiPaths.LOCATIONS;
                if (!targetGroupName.isEmpty()) {
                    endpoint += "?group_name=" + urlEncode(targetGroupName);
                }
                JSONObject response = getJson(endpoint, requestHandle);
                decryptLocationsResponse(response);
                runUi(() -> {
                    if (!isCurrentHomeRefresh(refreshGeneration, targetContent, targetGroupName)) {
                        return;
                    }
                    JSONObject user = response.optJSONObject("user");
                    if (user != null) {
                        currentUser = user;
                        persistUserSession(user, response.optInt("report_interval_seconds", 300));
                    }
                    renderLocations(response);
                    if (showSuccessFeedback) {
                        showTransientFeedback("位置已刷新");
                    }
                });
            } catch (Exception exception) {
                if (!isCancelledRequest(exception)) {
                    runUi(() -> {
                        if (isCurrentHomeRefresh(refreshGeneration, targetContent, targetGroupName)) {
                            setStatus(exception.getMessage());
                        }
                    });
                }
            } finally {
                activeLocationRequest.compareAndSet(requestHandle, null);
                runUi(() -> {
                    if (refreshGeneration == locationRefreshGeneration && refreshButton == targetRefreshButton && targetRefreshButton != null) {
                        targetRefreshButton.setEnabled(true);
                    }
                });
            }
        });
    }

    private boolean isCurrentHomeRefresh(long refreshGeneration, LinearLayout targetContent, String targetGroupName) {
        return refreshGeneration == locationRefreshGeneration
            && currentTab == TAB_POSITION
            && content == targetContent
            && targetGroupName.equals(selectedGroupName);
    }

    private void renderLocations(JSONObject response) {
        if (content == null || currentTab != TAB_POSITION) {
            return;
        }

        removeDynamicRows();
        homeMapBaseRecords = new JSONArray();
        shareableLocationRecords = new JSONArray();
        JSONArray groups = currentUser == null ? new JSONArray() : currentUser.optJSONArray("groups");
        appendHomeOverviewPanel(groups);

        appendMapPreview(response);
        mergeShareableLocations(latestMapLocations(response));
        appendHomeActionPanel();
        appendLocationSection("我的云端位置", response.optJSONObject("mine"));
        appendLocationArray("监测端云端位置", response.optJSONArray("monitors"));
        appendLocationArray("监护端云端位置", response.optJSONArray("guardians"));
        setStatus("位置已刷新");
        loadHomeHistorySummary();
    }

    private void appendHomeActionPanel() {
        reportButton = primaryButton("上报当前位置");
        refreshButton = secondaryButton("刷新位置");
        Button crossGroupSyncButton = secondaryButton("同步上报");
        Button continuousReportButton = secondaryButton(continuousReportButtonText());
        Button shareButton = secondaryButton("分享位置");

        reportButton.setTag("dynamic");
        refreshButton.setTag("dynamic");
        crossGroupSyncButton.setTag("dynamic");
        continuousReportButton.setTag("dynamic");
        shareButton.setTag("dynamic");

        uiStyle.styleHomePrimaryButton(reportButton, denseUi());
        uiStyle.styleHomeSecondaryButton(refreshButton, denseUi());
        uiStyle.styleHomeSecondaryButton(crossGroupSyncButton, denseUi());
        uiStyle.styleHomeSecondaryButton(continuousReportButton, denseUi());
        uiStyle.styleHomeSecondaryButton(shareButton, denseUi());

        reportButton.setOnClickListener(view -> reportCurrentLocation());
        refreshButton.setOnClickListener(view -> refreshLocations(true));
        crossGroupSyncButton.setOnClickListener(view -> showCrossGroupSync());
        continuousReportButton.setOnClickListener(view -> toggleGuardianContinuousReport());
        shareButton.setOnClickListener(view -> showLocationSharePicker());

        boolean guardian = "guardian".equals(currentUserRole());
        boolean multiGroup = userGroupCount() > 1;
        syncReportButtonState();

        addHomeActionRow(reportButton, refreshButton, 6);
        if (guardian && multiGroup) {
            addHomeActionRow(continuousReportButton, crossGroupSyncButton, 6);
        } else if (guardian) {
            addHomeActionSingleButton(continuousReportButton, 6);
        } else if (multiGroup) {
            addHomeActionSingleButton(crossGroupSyncButton, 6);
        }
        addHomeActionSingleButton(shareButton, 6);
    }

    private void addHomeActionRow(Button left, Button right, int bottomMarginDp) {
        View row = buttonRow(left, right);
        row.setTag(VIEW_TAG_DYNAMIC);
        content.addView(row, blockParams(bottomMarginDp));
    }

    private void addHomeActionSingleButton(Button button, int bottomMarginDp) {
        button.setTag(VIEW_TAG_DYNAMIC);
        content.addView(button, blockParams(bottomMarginDp));
    }

    private String currentGroupDisplayName(JSONArray groups) {
        String current = currentGroupName();
        if (groups != null) {
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group != null && current.equals(group.optString("group_name", ""))) {
                    return group.optString("display_name", current);
                }
            }
        }
        return current.isEmpty() ? "未选择" : current;
    }

    private void appendHomeOverviewPanel(JSONArray groups) {
        if (content == null) {
            return;
        }

        LinearLayout panel = detailListPanel(true);
        panel.setTag(VIEW_TAG_DYNAMIC);
        boolean hasGroup = groups != null && groups.length() > 0;
        if (hasGroup) {
            panel.addView(homeOverviewRow(
                "家庭组",
                currentGroupDisplayName(groups),
                userGroupCount() > 1 ? "切换" : "管理",
                () -> {
                    if (userGroupCount() > 1) {
                        showHomeGroupPicker();
                    } else {
                        showGroups();
                    }
                }
            ), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (panel.getChildCount() > 0) {
            content.addView(panel, blockParams(8));
        }
    }

    private void loadHomeHistorySummary() {
        if (content == null || currentTab != TAB_POSITION) {
            return;
        }
        final int page = Math.max(1, historyPage);
        final int perPage = normalizedHistoryPageSize(historyPageSize);
        final int userId = Math.max(0, historyUserId);
        final LinearLayout targetContent = content;
        final String targetGroupName = selectedGroupName;
        final long requestToken = historyRequestGate.begin();
        final JsonApiClient.RequestHandle requestHandle = new JsonApiClient.RequestHandle();
        replaceActiveRequest(activeHistoryRequest, requestHandle);
        appendHomeHistoryLoading();
        runBackground(() -> {
            try {
                final int mapPerUser = 20;
                JSONObject request = new JSONObject()
                    .put("group_name", targetGroupName)
                    .put("page", page)
                    .put("per_page", perPage)
                    .put("map_per_user", mapPerUser)
                    .put("user_id", userId)
                    .put("range_hours", historyRangeHours)
                    .put("client_merge_snapshot", true);
                JSONObject response = postJson(ApiPaths.HISTORY, request, requestHandle);
                decryptHistoryResponse(response, page, perPage, mapPerUser, userId);
                runUi(() -> {
                    if (isCurrentHistoryRequest(requestToken, targetContent, targetGroupName)) {
                        renderHomeHistorySummary(response, requestToken, targetContent, targetGroupName);
                    }
                });
            } catch (Exception exception) {
                if (isCancelledRequest(exception)) {
                    return;
                }
                String errorMessage = firstText(exception.getMessage(), "历史位置加载失败。");
                boolean requiresMemberSelection = errorMessage.contains("家庭组成员过多，请选择单个成员查看历史");
                runUi(() -> {
                    if (!isCurrentHistoryRequest(requestToken, targetContent, targetGroupName)) {
                        return;
                    }
                    removeHomeHistoryRows();
                    TextView title = dynamicSectionTitle("历史位置");
                    title.setTag(VIEW_TAG_HOME_HISTORY);
                    content.addView(title, blockParams(8));
                    LinearLayout error = simpleSummaryPanel("加载失败", errorMessage, true);
                    error.setTag(VIEW_TAG_HOME_HISTORY);
                    content.addView(error, blockParams(12));
                    if (requiresMemberSelection) {
                        loadHistoryMemberPickerPage(1);
                    }
                });
            } finally {
                activeHistoryRequest.compareAndSet(requestHandle, null);
            }
        });
    }

    private boolean isCurrentHistoryRequest(long requestToken, LinearLayout targetContent, String targetGroupName) {
        return historyRequestGate.isCurrent(requestToken)
            && content == targetContent
            && currentTab == TAB_POSITION
            && targetGroupName.equals(selectedGroupName);
    }

    private void appendHomeHistoryLoading() {
        removeHomeHistoryRows();
        TextView title = dynamicSectionTitle("历史位置");
        title.setTag(VIEW_TAG_HOME_HISTORY);
        content.addView(title, blockParams(8));
        LinearLayout loading = simpleSummaryPanel("状态", "正在加载历史位置…", true);
        loading.setTag(VIEW_TAG_HOME_HISTORY);
        content.addView(loading, blockParams(12));
    }

    private void renderHomeHistorySummary(JSONObject response, long requestToken, LinearLayout targetContent, String targetGroupName) {
        if (content == null || currentTab != TAB_POSITION) {
            return;
        }
        removeHomeHistoryRows();
        JSONObject pagination = response.optJSONObject("pagination");
        if (pagination != null) {
            historyPage = Math.max(1, pagination.optInt("page", historyPage));
            historyPageSize = normalizedHistoryPageSize(pagination.optInt("per_page", historyPageSize));
            historyUserId = Math.max(0, pagination.optInt("user_id", historyUserId));
        }
        historyRangeHours = normalizedHistoryRangeHours(response.optInt("range_hours", historyRangeHours));

        JSONArray history = response.optJSONArray("history");
        mergeShareableLocations(history);
        int total = pagination == null ? (history == null ? 0 : history.length()) : pagination.optInt("total", history == null ? 0 : history.length());
        int totalPages = pagination == null ? 1 : Math.max(1, pagination.optInt("total_pages", 1));
        TextView title = dynamicSectionTitle("历史位置");
        title.setTag(VIEW_TAG_HOME_HISTORY);
        content.addView(title, blockParams(8));

        renderHomeHistoryControls(response, total, totalPages);
        if (response.optBoolean("selection_required", false)) {
            LinearLayout prompt = simpleSummaryPanel("请选择成员", "家庭组成员较多，请先打开成员列表并选择一名成员查看历史。", true);
            prompt.setTag(VIEW_TAG_HOME_HISTORY);
            content.addView(prompt, blockParams(12));
            return;
        }
        updateHomeMapWithHistory(response.optJSONArray("map_history"));
        if (history == null || history.length() == 0) {
            LinearLayout empty = simpleSummaryPanel("提示", "暂无历史定位记录。", true);
            empty.setTag(VIEW_TAG_HOME_HISTORY);
            content.addView(empty, blockParams(12));
            return;
        }

        appendHistoryRowsInBatches(history, 0, requestToken, targetContent, targetGroupName);
    }

    private void appendHistoryRowsInBatches(JSONArray history, int startIndex, long requestToken, LinearLayout targetContent, String targetGroupName) {
        if (history == null || !isCurrentHistoryRequest(requestToken, targetContent, targetGroupName)) {
            return;
        }
        int endIndex = Math.min(history.length(), startIndex + 6);
        for (int index = startIndex; index < endIndex; index += 1) {
            appendHistoryRow(history.optJSONObject(index), VIEW_TAG_HOME_HISTORY);
        }
        if (endIndex < history.length()) {
            targetContent.postOnAnimation(() -> appendHistoryRowsInBatches(
                history,
                endIndex,
                requestToken,
                targetContent,
                targetGroupName
            ));
        }
    }

    private void renderHomeHistoryControls(JSONObject response, int total, int totalPages) {
        JSONArray members = response.optJSONArray("members");
        boolean selectionRequired = response.optBoolean("selection_required", false);
        String memberText = selectionRequired ? "请选择成员" : historyMemberLabel(members, historyUserId);
        LinearLayout pageInfo = detailListPanel(true);
        addDetailRow(pageInfo, "成员", memberText);
        addDetailRow(pageInfo, "分页", "第 " + historyPage + " / " + totalPages + " 页，共 " + total + " 条");
        addDetailRow(pageInfo, "每页", historyPageSize + " 条");
        addDetailRow(pageInfo, "时间范围", historyRangeLabel(historyRangeHours));
        pageInfo.setTag(VIEW_TAG_HOME_HISTORY);
        content.addView(pageInfo, blockParams(4));

        Button memberButton = historyControlButton("成员： " + memberText);
        memberButton.setTag(VIEW_TAG_HOME_HISTORY);
        boolean selectionLimited = selectionRequired || response.optBoolean("selection_limited", false)
            || response.optBoolean("members_truncated", false);
        memberButton.setEnabled(selectionLimited || (members != null && members.length() > 1));
        memberButton.setOnClickListener(view -> showHistoryMemberPicker(response, members));

        Button pageSizeButton = historyControlButton("每页 " + historyPageSize + " 条");
        pageSizeButton.setTag(VIEW_TAG_HOME_HISTORY);
        pageSizeButton.setOnClickListener(view -> showHistorySizePicker("每页历史条数", historyPageSize, size -> {
            historyPageSize = size;
            historyPage = 1;
            loadHomeHistorySummary();
        }));
        Button rangeButton = historyControlButton("范围：" + historyRangeLabel(historyRangeHours));
        rangeButton.setTag(VIEW_TAG_HOME_HISTORY);
        rangeButton.setOnClickListener(view -> showHistoryRangePicker());
        content.addView(buttonRow(memberButton, rangeButton), blockParams(6));
        content.addView(pageSizeButton, blockParams(6));

        Button previous = historyControlButton("上一页");
        Button next = historyControlButton("下一页");
        previous.setTag(VIEW_TAG_HOME_HISTORY);
        next.setTag(VIEW_TAG_HOME_HISTORY);
        previous.setEnabled(historyPage > 1);
        next.setEnabled(historyPage < totalPages);
        previous.setOnClickListener(view -> {
            if (historyPage > 1) {
                historyPage -= 1;
                loadHomeHistorySummary();
            }
        });
        next.setOnClickListener(view -> {
            if (historyPage < totalPages) {
                historyPage += 1;
                loadHomeHistorySummary();
            }
        });
        View pager = buttonRow(previous, next);
        pager.setTag(VIEW_TAG_HOME_HISTORY);
        content.addView(pager, blockParams(6));
    }

    private void appendMapPreview(JSONObject response) {
        JSONArray records = latestMapLocations(response);
        if (!canLoadForegroundWebView()) {
            restoreHomeMapOnResume = currentUser != null && currentTab == TAB_POSITION;
            LinearLayout placeholder = simpleSummaryPanel("说明", "地图将在回到前台后加载，后台状态不启动 WebView。", true);
            placeholder.setTag("dynamic");
            content.addView(placeholder, blockParams(8));
            return;
        }
        WebView map = homeMapWebView;
        if (map == null) {
            map = locationMapWebView(records);
            homeMapWebView = map;
        } else {
            ViewGroup parent = map.getParent() instanceof ViewGroup ? (ViewGroup) map.getParent() : null;
            if (parent != null) {
                parent.removeView(map);
            }
            map.setTag(VIEW_TAG_DYNAMIC);
            renderMapRecords(map, records);
        }
        restoreHomeMapOnResume = false;
        homeMapBaseRecords = records;
        LinearLayout.LayoutParams params = blockParams(8);
        params.height = uiStyle.mapPreviewHeight(denseUi());
        content.addView(map, params);
    }

    private void updateHomeMapWithHistory(JSONArray mapHistory) {
        if (homeMapWebView == null) {
            return;
        }
        JSONArray combined = new JSONArray();
        appendMapRecords(combined, homeMapBaseRecords);
        appendMapRecords(combined, displayableLocations(mapHistory));
        if (combined.length() > 0) {
            renderMapRecords(homeMapWebView, combined);
        }
    }

    private void appendMapRecords(JSONArray target, JSONArray records) {
        if (target == null || records == null) {
            return;
        }
        for (int index = 0; index < records.length(); index += 1) {
            JSONObject record = records.optJSONObject(index);
            if (record != null) {
                target.put(record);
            }
        }
    }

    private JSONArray latestMapLocations(JSONObject response) {
        JSONArray records = new JSONArray();
        if (response == null) {
            return records;
        }
        appendLatestMapLocation(records, response.optJSONObject("mine"));
        appendLatestMapLocations(records, response.optJSONArray("monitors"));
        appendLatestMapLocations(records, response.optJSONArray("guardians"));
        return records;
    }

    private void appendLatestMapLocations(JSONArray target, JSONArray locations) {
        if (locations == null) {
            return;
        }
        for (int index = 0; index < locations.length(); index += 1) {
            appendLatestMapLocation(target, locations.optJSONObject(index));
        }
    }

    private void appendLatestMapLocation(JSONArray target, JSONObject location) {
        if (hasUsableCoordinates(location)) {
            target.put(location);
        }
    }

    private void decryptLocationsResponse(JSONObject response) {
        String groupName = selectedGroupName.isEmpty() && currentUser != null
            ? currentUser.optString("group_name", "")
            : selectedGroupName;
        JSONObject mine = response.optJSONObject("mine");
        if (mine != null) {
            try {
                response.put("mine", P2PCryptoSupport.decryptRecord(this::postJson, this, groupName, mine));
            } catch (Exception exception) {
                Log.w(TAG, "P2P latest decrypt failed: " + exception.getMessage());
            }
        }
        decryptLocationArray(response.optJSONArray("monitors"), groupName);
        decryptLocationArray(response.optJSONArray("guardians"), groupName);
    }

    private void decryptHistoryResponse(JSONObject response, int requestedPage, int perPage, int mapPerUser, int userId) {
        JSONObject selectedGroup = response.optJSONObject("selected_group");
        String groupName = selectedGroup == null ? selectedGroupName : selectedGroup.optString("group_name", selectedGroupName);
        JSONArray clientMergeHistory = response.optJSONArray("client_merge_history");
        if (response.optBoolean("client_merge_complete", false) && clientMergeHistory != null) {
            decryptLocationArray(clientMergeHistory, groupName);
            JSONArray merged = mergeHistoryStays(clientMergeHistory, true);
            merged = filterMergedHistoryRange(merged, response.optString("range_start", ""));
            applyClientMergeHistorySnapshot(response, merged, requestedPage, perPage, mapPerUser, userId);
            return;
        }
        JSONArray history = response.optJSONArray("history");
        JSONArray mapHistory = response.optJSONArray("map_history");
        decryptLocationArray(history, groupName);
        decryptLocationArray(mapHistory, groupName);
        try {
            if (history != null) {
                response.put("history", mergeHistoryStays(history, false));
            }
            if (mapHistory != null) {
                response.put("map_history", mergeHistoryStays(mapHistory, false));
            }
        } catch (Exception exception) {
            Log.w(TAG, "P2P stay merge failed: " + exception.getMessage());
        }
    }

    private JSONArray filterMergedHistoryRange(JSONArray merged, String rangeStartText) {
        if (merged == null || merged.length() == 0) {
            return merged == null ? new JSONArray() : merged;
        }
        long rangeStartMillis = parseReportTimeMillis(rangeStartText);
        if (rangeStartMillis <= 0L) {
            return merged;
        }
        JSONArray filtered = new JSONArray();
        for (int index = 0; index < merged.length(); index += 1) {
            JSONObject location = merged.optJSONObject(index);
            if (location == null || effectiveReportTimeMillis(location) < rangeStartMillis) {
                continue;
            }
            long firstReportedAt = parseReportTimeMillis(location.optString("first_reported_at", ""));
            if (firstReportedAt > 0L && firstReportedAt < rangeStartMillis) {
                long lastReportedAt = parseReportTimeMillis(firstText(
                    location.optString("last_reported_at", ""),
                    location.optString("created_at", ""),
                    location.optString("updated_at", "")
                ));
                try {
                    location.put("first_reported_at", rangeStartText)
                        .put("stay_duration_seconds", Math.max(0L, (lastReportedAt - rangeStartMillis) / 1000L))
                        .put("report_count", Math.max(1, location.optInt("report_count", 1) - 1));
                } catch (Exception exception) {
                    Log.w(TAG, "History range clipping failed: " + exception.getMessage());
                }
            }
            filtered.put(location);
        }
        return filtered;
    }

    private void applyClientMergeHistorySnapshot(JSONObject response, JSONArray merged, int requestedPage, int perPage, int mapPerUser, int userId) {
        int total = merged == null ? 0 : merged.length();
        LocationHistorySnapshotPolicy.PageWindow window = LocationHistorySnapshotPolicy.pageWindow(total, requestedPage, perPage);
        JSONArray pageHistory = new JSONArray();
        List<String> partitionKeys = new ArrayList<>();
        for (int index = 0; index < total; index += 1) {
            JSONObject location = merged.optJSONObject(index);
            if (location == null) {
                partitionKeys.add("record:" + index);
                continue;
            }
            partitionKeys.add(firstText(stayPartitionKey(location), "record:" + index));
            if (index >= window.startIndex() && index < window.endIndex()) {
                pageHistory.put(location);
            }
        }

        JSONArray mapHistory = new JSONArray();
        for (int index : LocationHistorySnapshotPolicy.mapIndices(partitionKeys, mapPerUser)) {
            JSONObject location = merged.optJSONObject(index);
            if (location != null) {
                mapHistory.put(location);
            }
        }

        try {
            JSONObject pagination = response.optJSONObject("pagination");
            if (pagination == null) {
                pagination = new JSONObject();
            }
            pagination.put("page", window.page())
                .put("per_page", window.perPage())
                .put("total", window.total())
                .put("total_pages", window.totalPages())
                .put("user_id", Math.max(0, userId));
            response.put("history", pageHistory)
                .put("map_history", mapHistory)
                .put("pagination", pagination);
            response.remove("client_merge_history");
        } catch (Exception exception) {
            throw new IllegalStateException("无法整理端到端加密历史快照。", exception);
        }
    }

    private void decryptLocationArray(JSONArray locations, String groupName) {
        if (locations == null) {
            return;
        }
        P2PCryptoSupport.decryptRecords(this::postJson, this, groupName, locations);
    }

    private void appendLocationArray(String title, JSONArray locations) {
        if (locations == null || locations.length() == 0) {
            return;
        }

        content.addView(dynamicSectionTitle(title), blockParams(6));
        for (int index = 0; index < locations.length(); index += 1) {
            appendLocationSection("", locations.optJSONObject(index));
        }
    }

    private void appendLocationSection(String label, JSONObject location) {
        if (location == null) {
            if (!label.isEmpty()) {
                LinearLayout empty = simpleSummaryPanel(label, "暂无定位", true);
                empty.setTag("dynamic");
                content.addView(empty, blockParams(8));
            }
            return;
        }

        String name = label.isEmpty()
            ? location.optString("display_name", location.optString("username", "成员"))
            : label;
        String role = location.optString("role_label", "");
        String roleKey = location.optString("role", "");
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        String address = diagnostics == null ? "" : diagnostics.optString("preferred_address", "");

        appendLocationInfoPanels(name, roleKey, role, location, diagnostics, address);
    }
    private void appendLocationInfoPanels(String name, String roleKey, String role, JSONObject location, JSONObject diagnostics, String address) {
        LinearLayout panel = detailListPanel(true);
        String memberName = firstText(name, "成员");
        String memberRole = firstText(role);
        String updatedAt = firstText(location.optString("updated_at", ""), "暂无");
        String displayLocation = locationDisplayText(location, diagnostics, address);
        String city = preferredCityText(diagnostics);
        String status = historyAddressStatus(location, diagnostics);

        panel.addView(summaryCardHeader(memberName, memberRole, roleKey), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(detailDivider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        panel.addView(homeLatestLine(displayLocation, false), blockParams(4));
        panel.addView(homeLatestLine("更新时间：" + updatedAt, false), blockParams(0));
        if (hasMeaningfulText(city)) {
            panel.addView(homeLatestLine("城市：" + city, false), blockParams(0));
        }
        if (shouldShowStatusText(status)) {
            panel.addView(homeLatestLine("状态：" + status, false), blockParams(0));
        }
        appendHomeNetworkAddressRows(panel, diagnostics);

        attachMapOpenAction(panel, location, name);
        content.addView(panel, blockParams(8));
    }

    private void appendHomeNetworkAddressRows(LinearLayout panel, JSONObject diagnostics) {
        JSONArray sources = diagnostics == null ? null : diagnostics.optJSONArray("sources");
        if (panel == null || sources == null) {
            return;
        }
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null || !DiagnosticSourcePolicy.isNetworkProbeType(source.optString("type", ""))) {
                continue;
            }
            String key = diagnosticSourceMergeKey(source, index);
            JSONObject precise = mostPreciseDiagnosticSource(source);
            JSONObject selected = rows.get(key);
            if (diagnosticCandidateIsMorePrecise(precise, selected)) {
                rows.put(key, precise);
            }
        }
        for (JSONObject source : rows.values()) {
            String type = source.optString("type", "").trim().toLowerCase(Locale.ROOT);
            if ("failed".equals(source.optString("probe_status", ""))) {
                String reason = diagnosticFailureReasonLabel(source.optString("failure_reason", ""));
                panel.addView(homeLatestLine(
                    ("webrtc".equals(type) ? "WebRTC 探测" : "IP 探测") + "：失败"
                        + (reason.isEmpty() ? "" : "（" + reason + "）"),
                    false
                ), blockParams(0));
                continue;
            }
            String label = "ip".equals(type) ? "IP 探测地址" : "WebRTC 探测地址";
            String provider = source.optString("provider", "").trim();
            String suffix = provider.isEmpty() ? "" : "（" + provider + "）";
            panel.addView(homeLatestLine(label + "：" + diagnosticSourceAddress(source) + suffix, false), blockParams(0));
            String attempts = providerAttemptSummary(source);
            if (!attempts.isEmpty()) {
                panel.addView(homeLatestLine("供应商探测：" + attempts, false), blockParams(0));
            }
        }
    }

    private String providerAttemptSummary(JSONObject source) {
        JSONArray attempts = source == null ? null : source.optJSONArray("provider_attempts");
        if (attempts == null || attempts.length() == 0) {
            return "";
        }
        List<String> items = new ArrayList<>();
        for (int index = 0; index < attempts.length(); index += 1) {
            JSONObject attempt = attempts.optJSONObject(index);
            if (attempt == null) {
                continue;
            }
            String provider = firstText(attempt.optString("provider", ""), "unknown");
            String status = "success".equals(attempt.optString("status", "")) ? "成功" : "失败";
            String precision = attempt.optString("precision", "none");
            String observedIp = attempt.optString("ip", "").trim();
            String reason = "成功".equals(status) ? "" : diagnosticFailureReasonLabel(attempt.optString("reason", ""));
            items.add(provider + "=" + status
                + ("成功".equals(status)
                    ? "(" + precision + (observedIp.isEmpty() ? "" : "," + observedIp) + ")"
                    : (reason.isEmpty() ? "" : "(" + reason + ")")));
        }
        return joinTexts("，", items.toArray(new String[0]));
    }

    private String diagnosticFailureReasonLabel(String reason) {
        switch (reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT)) {
            case "insecure_transport_disabled": return "不支持 HTTPS，已停用";
            case "not_configured": return "未配置";
            case "unsupported_provider": return "不支持";
            case "no_result": return "无可用结果";
            case "quota_unconfigured": return "配额未配置";
            case "rate_limited": return "请求过多";
            case "provider_busy": return "服务繁忙";
            case "upstream_error": return "上游请求失败";
            case "timeout": return "请求超时";
            case "network_error": return "网络错误";
            case "invalid_response": return "响应无效";
            case "cancelled": return "已取消";
            case "foreground_unavailable": return "App 不在前台";
            case "probe_timeout": return "STUN 探测超时";
            case "no_public_candidate": return "未取得公网候选";
            case "webrtc_unavailable": return "设备 WebRTC 不可用";
            case "server_unavailable": return "服务地址不可用";
            default: return "";
        }
    }

    private void mergeShareableLocations(JSONArray records) {
        if (records == null) {
            return;
        }
        for (int index = 0; index < records.length(); index += 1) {
            JSONObject record = records.optJSONObject(index);
            if (!hasUsableCoordinates(record)) {
                continue;
            }
            String key = shareLocationKey(record);
            boolean exists = false;
            for (int current = 0; current < shareableLocationRecords.length(); current += 1) {
                JSONObject existing = shareableLocationRecords.optJSONObject(current);
                if (existing != null && key.equals(shareLocationKey(existing))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                try {
                    shareableLocationRecords.put(new JSONObject(record.toString()));
                } catch (Exception ignored) {
                    shareableLocationRecords.put(record);
                }
            }
        }
    }

    private JSONArray mergeHistoryStays(JSONArray locations, boolean mergePlainRecords) {
        if (locations == null || locations.length() == 0) {
            return locations == null ? new JSONArray() : locations;
        }
        List<LocationStayMergePolicy.Point> points = new ArrayList<>();
        for (int index = 0; index < locations.length(); index += 1) {
            JSONObject location = locations.optJSONObject(index);
            if (location == null) {
                continue;
            }
            boolean p2p = isP2PLocationRecord(location);
            String partitionKey = stayPartitionKey(location);
            long reportedAt = parseReportTimeMillis(firstText(
                location.optString("created_at", ""),
                location.optString("updated_at", "")
            ));
            if (partitionKey.isEmpty() || reportedAt <= 0L) {
                continue;
            }
            points.add(new LocationStayMergePolicy.Point(
                index,
                partitionKey,
                locationCoordinateSystem(location),
                location.optDouble("latitude", Double.NaN),
                location.optDouble("longitude", Double.NaN),
                reportedAt,
                location.optLong("id", -index),
                "location",
                p2p || mergePlainRecords
            ));
        }

        boolean[] consumed = new boolean[locations.length()];
        List<OrderedLocationRecord> output = new ArrayList<>();
        for (LocationStayMergePolicy.Cluster cluster : LocationStayMergePolicy.merge(points)) {
            for (LocationStayMergePolicy.Point point : cluster.points()) {
                consumed[point.sourceIndex] = true;
            }
            JSONObject latest = locations.optJSONObject(cluster.last().sourceIndex);
            if (latest == null) {
                continue;
            }
            boolean clusterContainsP2P = clusterContainsP2PLocation(locations, cluster);
            if (!cluster.mergeEligible()) {
                output.add(new OrderedLocationRecord(latest, cluster.last().reportedAtMillis, cluster.last().sourceIndex));
                continue;
            }
            JSONObject merged;
            try {
                merged = new JSONObject(latest.toString());
                JSONObject first = locations.optJSONObject(cluster.first().sourceIndex);
                String firstReportedAt = first == null ? "" : firstText(first.optString("created_at", ""), first.optString("updated_at", ""));
                String lastReportedAt = firstText(latest.optString("created_at", ""), latest.optString("updated_at", ""), firstReportedAt);
                merged.put("first_reported_at", firstReportedAt)
                    .put("last_reported_at", lastReportedAt)
                    .put("stay_duration_seconds", cluster.durationSeconds())
                    .put("report_count", cluster.reportCount());
                if (clusterContainsP2P) {
                    merged.put("contains_p2p", true).put("p2p_decrypted", true);
                }
                copyLatestAvailableDiagnostics(locations, cluster, merged);
            } catch (Exception exception) {
                if (clusterContainsP2P) {
                    try {
                        merged = new JSONObject(latest.toString())
                            .put("contains_p2p", true)
                            .put("p2p_decrypted", true);
                    } catch (Exception privacyException) {
                        continue;
                    }
                } else {
                    merged = latest;
                }
            }
            output.add(new OrderedLocationRecord(merged, cluster.last().reportedAtMillis, cluster.last().sourceIndex));
        }

        for (int index = 0; index < locations.length(); index += 1) {
            if (consumed[index]) {
                continue;
            }
            JSONObject location = locations.optJSONObject(index);
            if (location != null) {
                output.add(new OrderedLocationRecord(location, effectiveReportTimeMillis(location), index));
            }
        }
        output.sort((left, right) -> {
            int timeOrder = Long.compare(right.reportedAtMillis, left.reportedAtMillis);
            return timeOrder != 0 ? timeOrder : Integer.compare(left.sourceIndex, right.sourceIndex);
        });
        JSONArray mergedLocations = new JSONArray();
        for (OrderedLocationRecord item : output) {
            mergedLocations.put(item.record);
        }
        return mergedLocations;
    }

    private boolean clusterContainsP2PLocation(JSONArray locations, LocationStayMergePolicy.Cluster cluster) {
        boolean containsP2P = false;
        for (LocationStayMergePolicy.Point point : cluster.points()) {
            JSONObject location = locations.optJSONObject(point.sourceIndex);
            containsP2P = LocationShareSecurityPolicy.accumulateContainsP2P(
                containsP2P,
                location == null ? "" : location.optString("encryption_mode", ""),
                location != null && location.optBoolean("p2p_decrypted", false),
                location != null && location.optBoolean("contains_p2p", false)
            );
        }
        return containsP2P;
    }

    private void copyLatestAvailableDiagnostics(JSONArray locations, LocationStayMergePolicy.Cluster cluster, JSONObject target) throws Exception {
        JSONObject bestDiagnostics = null;
        JSONObject bestPreferredCandidate = null;
        Map<String, JSONObject> bestSources = new LinkedHashMap<>();
        List<LocationStayMergePolicy.Point> points = cluster.points();
        for (int index = points.size() - 1; index >= 0; index -= 1) {
            JSONObject record = locations.optJSONObject(points.get(index).sourceIndex);
            JSONObject diagnostics = record == null ? null : record.optJSONObject("address_diagnostics");
            if (diagnostics == null) {
                continue;
            }
            String preferredAddress = diagnosticsPreferredAddress(diagnostics);
            JSONObject preferredCandidate = preferredAddressCandidate(diagnostics, preferredAddress);
            if (bestDiagnostics == null || diagnosticCandidateIsMorePrecise(preferredCandidate, bestPreferredCandidate)) {
                bestDiagnostics = new JSONObject(diagnostics.toString());
                bestPreferredCandidate = preferredCandidate;
            }
            JSONArray sources = diagnostics.optJSONArray("sources");
            if (sources == null) {
                continue;
            }
            for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex += 1) {
                JSONObject source = sources.optJSONObject(sourceIndex);
                if (source == null) {
                    continue;
                }
                String key = diagnosticSourceMergeKey(source, sourceIndex);
                bestSources.put(key, mergeDiagnosticSourceEvidence(bestSources.get(key), source));
            }
        }
        if (bestDiagnostics == null) {
            return;
        }
        JSONArray mergedSources = new JSONArray();
        for (JSONObject source : bestSources.values()) {
            mergedSources.put(source);
        }
        bestDiagnostics.put("sources", mergedSources);
        target.put("address_diagnostics", bestDiagnostics);
    }

    private String diagnosticSourceMergeKey(JSONObject source, int fallbackIndex) {
        return DiagnosticSourcePolicy.sourceMergeKey(
            source == null ? "" : source.optString("type", ""),
            source == null ? "" : source.optString("ip", ""),
            source == null ? "" : source.optString("server_ip", ""),
            source == null ? "" : source.optString("ipv4", ""),
            source == null ? "" : source.optString("ipv6", ""),
            source == null ? "" : source.optString("provider", ""),
            source == null ? "" : source.optString("source", ""),
            source == null ? "" : source.optString("name", ""),
            source == null ? "" : source.optString("stun_server", ""),
            diagnosticCoordinateIdentity(source),
            fallbackIndex
        );
    }

    private JSONObject mergeDiagnosticSourceEvidence(JSONObject selected, JSONObject candidate) {
        if (selected == null) {
            return cloneDiagnosticObject(candidate);
        }
        if (candidate == null) {
            return cloneDiagnosticObject(selected);
        }
        boolean candidateIsPrimary = diagnosticCandidateIsMorePrecise(candidate, selected);
        JSONObject primary = cloneDiagnosticObject(candidateIsPrimary ? candidate : selected);
        JSONObject secondary = candidateIsPrimary ? selected : candidate;
        if (primary == null) {
            return cloneDiagnosticObject(secondary);
        }
        String sourceType = primary.optString("type", "").trim().toLowerCase(Locale.ROOT);
        for (String nestedKey : new String[] {"variants", "candidates"}) {
            JSONArray merged = mergeDiagnosticNestedEvidence(
                selected.optJSONArray(nestedKey),
                candidate.optJSONArray(nestedKey),
                primary,
                ("ip".equals(sourceType) && "variants".equals(nestedKey))
                    || ("webrtc".equals(sourceType) && "candidates".equals(nestedKey))
                    ? new JSONObject[] {selected, candidate}
                    : new JSONObject[0]
            );
            if (merged.length() > 0) {
                try {
                    primary.put(nestedKey, merged);
                } catch (Exception ignored) {
                }
            }
        }
        return primary;
    }

    private JSONObject cloneDiagnosticObject(JSONObject source) {
        if (source == null) {
            return null;
        }
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return source;
        }
    }

    private JSONObject diagnosticSourceSnapshot(JSONObject source) {
        JSONObject snapshot = new JSONObject();
        if (source == null) {
            return snapshot;
        }
        for (String field : new String[] {
            "type", "name", "label", "provider", "source", "source_region", "address", "country", "region", "city",
            "district", "street", "detail", "poi", "postal_code", "ip", "server_ip", "ipv4", "ipv6",
            "stun_server", "stun_label", "stun_scope", "candidate_type", "asn", "isp", "org", "carrier"
        }) {
            if (source.has(field) && !source.isNull(field)) {
                try {
                    snapshot.put(field, source.opt(field));
                } catch (Exception ignored) {
                }
            }
        }
        for (String field : new String[] {"domestic_source", "mobile_network", "mobile_network_uncertain"}) {
            if (source.has(field) && !source.isNull(field)) {
                try {
                    snapshot.put(field, source.opt(field));
                } catch (Exception ignored) {
                }
            }
        }
        if (hasUsableCoordinates(source)) {
            try {
                snapshot.put("latitude", source.optDouble("latitude"));
                snapshot.put("longitude", source.optDouble("longitude"));
                for (String field : new String[] {"coordinate_system", "accuracy"}) {
                    if (source.has(field) && !source.isNull(field)) {
                        snapshot.put(field, source.opt(field));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return snapshot;
    }

    private JSONArray mergeDiagnosticNestedEvidence(
        JSONArray selected,
        JSONArray candidate,
        JSONObject parent,
        JSONObject[] sourceSnapshots
    ) {
        Map<String, JSONObject> evidence = new LinkedHashMap<>();
        for (JSONObject snapshot : sourceSnapshots) {
            appendDiagnosticSourceSnapshotEvidence(evidence, snapshot, parent);
        }
        appendDiagnosticNestedEvidence(evidence, selected, parent);
        appendDiagnosticNestedEvidence(evidence, candidate, parent);
        JSONArray merged = new JSONArray();
        for (JSONObject item : evidence.values()) {
            merged.put(item);
        }
        return merged;
    }

    private void appendDiagnosticSourceSnapshotEvidence(Map<String, JSONObject> target, JSONObject source, JSONObject parent) {
        if (source == null) {
            return;
        }
        JSONObject snapshot = diagnosticSourceSnapshot(source);
        String key = diagnosticNestedEvidenceKey(parent, snapshot);
        if (!target.containsKey(key)) {
            target.put(key, snapshot);
        }
    }

    private void appendDiagnosticNestedEvidence(Map<String, JSONObject> target, JSONArray items, JSONObject parent) {
        if (items == null) {
            return;
        }
        for (int index = 0; index < items.length(); index += 1) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                continue;
            }
            JSONObject clone = cloneDiagnosticObject(item);
            String key = diagnosticNestedEvidenceKey(parent, clone);
            if (!target.containsKey(key)) {
                target.put(key, clone);
            }
        }
    }

    private String diagnosticNestedEvidenceKey(JSONObject parent, JSONObject evidence) {
        String identity = diagnosticSourceIdentity(evidence);
        if (identity.isEmpty() && diagnosticSourceIdentitiesCompatible(parent, evidence)) {
            identity = diagnosticSourceIdentity(parent);
        }
        return DiagnosticSourcePolicy.evidenceKey(
            identity,
            evidence == null ? "" : evidence.optString("provider", ""),
            evidence == null ? "" : evidence.optString("source", ""),
            evidence == null ? "" : evidence.optString("source_region", ""),
            evidence == null ? "" : evidence.optString("stun_server", ""),
            evidence == null ? "" : evidence.optString("stun_label", ""),
            evidence == null ? "" : evidence.optString("stun_scope", ""),
            evidence == null ? "" : evidence.optString("candidate_type", ""),
            evidence == null ? "" : evidence.optString("address", ""),
            evidence == null ? "" : evidence.optString("country", ""),
            evidence == null ? "" : evidence.optString("region", ""),
            evidence == null ? "" : evidence.optString("city", ""),
            evidence == null ? "" : evidence.optString("district", ""),
            evidence == null ? "" : evidence.optString("street", ""),
            evidence == null ? "" : evidence.optString("detail", ""),
            evidence == null ? "" : evidence.optString("poi", ""),
            evidence == null ? "" : evidence.optString("postal_code", ""),
            evidence == null ? "" : evidence.optString("asn", ""),
            evidence == null ? "" : evidence.optString("isp", ""),
            evidence == null ? "" : evidence.optString("org", ""),
            evidence == null ? "" : evidence.optString("carrier", ""),
            evidence == null ? "" : String.valueOf(evidence.optBoolean("mobile_network", false)),
            diagnosticCoordinateIdentity(evidence),
            evidence == null || !evidence.has("accuracy") ? "" : String.valueOf(evidence.opt("accuracy"))
        );
    }

    private String diagnosticCoordinateIdentity(JSONObject source) {
        if (!hasUsableCoordinates(source)) {
            return "";
        }
        return source.optString("coordinate_system", "") + ":"
            + Double.toString(source.optDouble("latitude")) + ","
            + Double.toString(source.optDouble("longitude"));
    }

    private JSONObject mostPreciseDiagnosticSource(JSONObject source) {
        if (source == null) {
            return null;
        }
        JSONObject best;
        try {
            best = new JSONObject(source.toString());
        } catch (Exception exception) {
            return source;
        }
        for (String nestedKey : new String[] {"variants", "candidates"}) {
            JSONArray nested = source.optJSONArray(nestedKey);
            if (nested == null) {
                continue;
            }
            for (int index = 0; index < nested.length(); index += 1) {
                JSONObject candidate = inheritedDiagnosticSource(source, nested.optJSONObject(index));
                if (diagnosticCandidateIsMorePrecise(candidate, best)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private JSONObject inheritedDiagnosticSource(JSONObject parent, JSONObject child) {
        if (parent == null || child == null) {
            return null;
        }
        try {
            boolean inheritParent = diagnosticSourceIdentitiesCompatible(parent, child);
            JSONObject merged = new JSONObject(child.toString());
            if (!hasMeaningfulText(merged.optString("type", ""))) {
                merged.put("type", parent.optString("type", ""));
            }
            if (!hasMeaningfulText(merged.optString("name", ""))) {
                merged.put("name", parent.optString("name", ""));
            }
            if (inheritParent && diagnosticSourceIdentity(child).isEmpty()) {
                for (String field : new String[] {"ip", "server_ip", "ipv4", "ipv6"}) {
                    if (!hasMeaningfulText(merged.optString(field, "")) && hasMeaningfulText(parent.optString(field, ""))) {
                        merged.put(field, parent.opt(field));
                    }
                }
            }
            if (inheritParent && sameDiagnosticCoordinate(parent, child)) {
                for (String field : new String[] {"coordinate_system", "accuracy"}) {
                    if (!merged.has(field) && parent.has(field) && !parent.isNull(field)) {
                        merged.put(field, parent.opt(field));
                    }
                }
            }
            return merged;
        } catch (Exception exception) {
            return child;
        }
    }

    private boolean diagnosticCandidateHasBetterCoordinates(JSONObject candidate, JSONObject selected) {
        boolean candidateCoordinates = hasUsableCoordinates(candidate);
        boolean selectedCoordinates = hasUsableCoordinates(selected);
        if (candidateCoordinates != selectedCoordinates) {
            return candidateCoordinates;
        }
        double candidateAccuracy = candidate == null ? Double.NaN : candidate.optDouble("accuracy", Double.NaN);
        double selectedAccuracy = selected == null ? Double.NaN : selected.optDouble("accuracy", Double.NaN);
        return Double.isFinite(candidateAccuracy)
            && candidateAccuracy >= 0.0d
            && (!Double.isFinite(selectedAccuracy) || selectedAccuracy < 0.0d || candidateAccuracy < selectedAccuracy);
    }

    private boolean diagnosticCandidateIsMorePrecise(JSONObject candidate, JSONObject selected) {
        if (candidate == null) {
            return false;
        }
        if (selected == null) {
            return true;
        }
        int candidateScore = geocodeIpScore(candidate);
        int selectedScore = geocodeIpScore(selected);
        if (candidateScore != selectedScore) {
            return candidateScore > selectedScore;
        }
        int candidatePriority = addressProviderPriority(candidate.optString("provider", ""));
        int selectedPriority = addressProviderPriority(selected.optString("provider", ""));
        if (candidatePriority != selectedPriority) {
            return candidatePriority < selectedPriority;
        }
        int candidateLength = diagnosticSourceAddress(candidate).replaceAll("\\s+", "").length();
        int selectedLength = diagnosticSourceAddress(selected).replaceAll("\\s+", "").length();
        if (candidateLength != selectedLength) {
            return candidateLength > selectedLength;
        }
        return diagnosticCandidateHasBetterCoordinates(candidate, selected);
    }

    private boolean diagnosticSourceIdentitiesCompatible(JSONObject parent, JSONObject child) {
        String parentIdentity = diagnosticSourceIdentity(parent);
        String childIdentity = diagnosticSourceIdentity(child);
        boolean webRtc = "webrtc".equals(parent == null ? "" : parent.optString("type", ""))
            || "webrtc".equals(child == null ? "" : child.optString("type", ""));
        return DiagnosticSourcePolicy.identitiesCompatible(
            parentIdentity,
            childIdentity,
            firstText(
                parent == null ? "" : parent.optString("stun_server", ""),
                webRtc && parent != null ? parent.optString("source", "") : ""
            ),
            firstText(
                child == null ? "" : child.optString("stun_server", ""),
                webRtc && child != null ? child.optString("source", "") : ""
            ),
            webRtc
        );
    }

    private String diagnosticSourceIdentity(JSONObject source) {
        return firstText(
            source == null ? "" : source.optString("ip", ""),
            source == null ? "" : source.optString("server_ip", ""),
            source == null ? "" : source.optString("ipv4", ""),
            source == null ? "" : source.optString("ipv6", "")
        ).trim().toLowerCase(Locale.ROOT);
    }

    private boolean sameDiagnosticCoordinate(JSONObject left, JSONObject right) {
        return hasUsableCoordinates(left)
            && hasUsableCoordinates(right)
            && Double.compare(left.optDouble("latitude"), right.optDouble("latitude")) == 0
            && Double.compare(left.optDouble("longitude"), right.optDouble("longitude")) == 0;
    }

    private String stayPartitionKey(JSONObject location) {
        if (location == null) {
            return "";
        }
        String groupKey = firstText(location.optString("group_id", ""), location.optString("group_name", ""));
        String userKey = firstText(
            location.optString("user_id", ""),
            location.optString("member_id", ""),
            location.optString("username", "")
        );
        return groupKey.isEmpty() || userKey.isEmpty() ? "" : groupKey + ":" + userKey;
    }

    private String locationCoordinateSystem(JSONObject location) {
        JSONObject meta = location == null ? null : location.optJSONObject("location_meta");
        return LocationCoordinateSystemPolicy.resolve(
            location == null ? "" : location.optString("location_coordinate_system", ""),
            meta == null ? "" : meta.optString("coordinate_system", ""),
            gpsDiagnosticCoordinateSystem(location)
        );
    }

    private String gpsDiagnosticCoordinateSystem(JSONObject location) {
        JSONObject diagnostics = location == null ? null : location.optJSONObject("address_diagnostics");
        JSONArray sources = diagnostics == null ? null : diagnostics.optJSONArray("sources");
        if (sources == null) {
            return "";
        }
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source != null && "gps".equals(source.optString("type", ""))) {
                return source.optString("coordinate_system", "");
            }
        }
        return "";
    }

    private long effectiveReportTimeMillis(JSONObject location) {
        return parseReportTimeMillis(firstText(
            location == null ? "" : location.optString("last_reported_at", ""),
            location == null ? "" : location.optString("updated_at", ""),
            location == null ? "" : location.optString("created_at", "")
        ));
    }

    private long parseReportTimeMillis(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return 0L;
        }
        if (text.matches("^[0-9]{10,13}$")) {
            try {
                long numeric = Long.parseLong(text);
                return text.length() <= 10 ? numeric * 1000L : numeric;
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        String normalized = text.indexOf('T') >= 0 ? text : text.replace(' ', 'T');
        try {
            return Instant.parse(normalized).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String shareLocationKey(JSONObject record) {
        long id = record == null ? 0L : record.optLong("id", 0L);
        if (id > 0L) {
            return "id:" + id;
        }
        return "point:" + firstText(
            record == null ? "" : record.optString("user_id", ""),
            record == null ? "" : record.optString("display_name", "")
        ) + ":" + firstText(
            record == null ? "" : record.optString("created_at", ""),
            record == null ? "" : record.optString("updated_at", "")
        ) + ":" + (record == null ? "" : record.optString("latitude", ""))
            + ":" + (record == null ? "" : record.optString("longitude", ""));
    }

    private void showLocationSharePicker() {
        JSONArray candidates = displayableLocations(shareableLocationRecords);
        if (candidates.length() == 0) {
            setStatus("暂无可分享的定位。");
            return;
        }
        Dialog dialog = choiceDialog("选择分享的定位");
        LinearLayout body = choiceDialogBody(dialog);
        List<CheckBox> checks = new ArrayList<>();
        List<JSONObject> choices = new ArrayList<>();
        for (int index = 0; index < candidates.length(); index += 1) {
            JSONObject location = candidates.optJSONObject(index);
            if (location == null) {
                continue;
            }
            CheckBox check = new CheckBox(this);
            check.setText(shareLocationChoiceText(location));
            check.setTextColor(colorText());
            check.setTextSize(uiStyle.compactBodyTextSize(denseUi()));
            check.setLineSpacing(dp(2), 1f);
            check.setPadding(dp(4), dp(7), dp(4), dp(7));
            check.setChecked(checks.isEmpty());
            checks.add(check);
            choices.add(location);
            body.addView(check, blockParams(2));
        }
        TextView validation = body("");
        validation.setTextColor(colorMuted());
        validation.setVisibility(View.GONE);
        body.addView(validation, blockParams(4));
        Button next = primaryButton("下一步");
        next.setOnClickListener(view -> {
            JSONArray selected = new JSONArray();
            for (int index = 0; index < checks.size(); index += 1) {
                if (checks.get(index).isChecked()) {
                    selected.put(choices.get(index));
                }
            }
            if (selected.length() == 0) {
                validation.setText("请至少选择一条定位。");
                validation.setVisibility(View.VISIBLE);
                return;
            }
            if (selected.length() > 30) {
                validation.setText("一次最多分享 30 条定位。");
                validation.setVisibility(View.VISIBLE);
                return;
            }
            dialog.dismiss();
            showLocationShareFormatPicker(selected);
        });
        showChoiceDialog(dialog, body, next);
    }

    private String shareLocationChoiceText(JSONObject location) {
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        String name = firstText(location.optString("display_name", ""), location.optString("username", ""), "成员");
        String time = firstText(location.optString("created_at", ""), location.optString("updated_at", ""), "时间未知");
        return name + " · " + time + "\n" + locationDisplayText(location, diagnostics, diagnostics == null ? "" : diagnostics.optString("preferred_address", ""));
    }

    private void showLocationShareFormatPicker(JSONArray selected) {
        Dialog dialog = choiceDialog("分享位置");
        LinearLayout body = choiceDialogBody(dialog);
        LinearLayout summary = simpleSummaryPanel("已选择", selected.length() + " 条定位");
        body.addView(summary, blockParams(10));
        Button image = primaryButton("生成地图图片");
        Button link = secondaryButton("生成分享链接");
        boolean containsP2P = containsP2PLocationRecord(selected);
        link.setEnabled(!containsP2P);
        if (containsP2P) {
            body.addView(simpleSummaryPanel("端到端加密", "所选定位包含端到端加密记录，只能在本机生成地图图片；公开链接需要可由服务器验证的普通定位记录。"), blockParams(8));
        }
        image.setOnClickListener(view -> {
            dialog.dismiss();
            generateSharedMapImage(selected);
        });
        link.setOnClickListener(view -> {
            dialog.dismiss();
            showShareLinkSettings(selected);
        });
        body.addView(image, blockParams(6));
        body.addView(link, blockParams(0));
        showChoiceDialog(dialog, body);
    }

    private void showShareLinkSettings(JSONArray selected) {
        if (containsP2PLocationRecord(selected)) {
            setStatus("端到端加密定位不能生成公开链接，请改用本地地图图片。");
            return;
        }
        Dialog dialog = choiceDialog("分享链接设置");
        LinearLayout body = choiceDialogBody(dialog);
        RadioGroup expiration = new RadioGroup(this);
        expiration.setOrientation(RadioGroup.VERTICAL);
        int[] hours = new int[] {1, 24, 168, 720};
        String[] labels = new String[] {"1 小时", "24 小时", "7 天", "30 天"};
        for (int index = 0; index < hours.length; index += 1) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(labels[index]);
            option.setTextColor(colorText());
            option.setTag(hours[index]);
            option.setChecked(hours[index] == 168);
            expiration.addView(option, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView expirationTitle = sectionTitle("有效期");
        body.addView(expirationTitle, blockParams(4));
        body.addView(expiration, blockParams(10));

        EditText accessCode = input("4–16 位分享码");
        accessCode.setSingleLine(true);
        accessCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        body.addView(accessCode, blockParams(10));

        TextView validation = body("");
        validation.setTextColor(colorMuted());
        validation.setVisibility(View.GONE);
        body.addView(validation, blockParams(4));

        Button create = primaryButton("生成分享链接");
        create.setOnClickListener(view -> {
            String code = accessCode.getText().toString().trim();
            int codeLength = code.codePointCount(0, code.length());
            if (codeLength < 4 || codeLength > 16) {
                validation.setText("分享码需要 4–16 个字符。");
                validation.setVisibility(View.VISIBLE);
                return;
            }
            RadioButton selectedExpiration = expiration.findViewById(expiration.getCheckedRadioButtonId());
            int expiresHours = selectedExpiration == null ? 168 : (int) selectedExpiration.getTag();
            dialog.dismiss();
            createLocationShareLink(selected, expiresHours, code);
        });
        showChoiceDialog(dialog, body, create);
    }

    private void createLocationShareLink(JSONArray selected, int expiresHours, String accessCode) {
        if (containsP2PLocationRecord(selected)) {
            setStatus("端到端加密定位不能生成公开链接，请改用本地地图图片。");
            return;
        }
        JSONArray locationIds = new JSONArray();
        JSONArray locationSnapshots = new JSONArray();
        for (int index = 0; index < selected.length(); index += 1) {
            JSONObject location = selected.optJSONObject(index);
            long locationId = location == null ? 0L : location.optLong("id", 0L);
            if (locationId <= 0L) {
                setStatus("所选定位还没有可分享的历史记录 ID，请刷新后重试。");
                return;
            }
            locationIds.put(locationId);
            locationSnapshots.put(locationShareSnapshot(location));
        }
        final long targetScreenGeneration = screenGeneration;
        setStatus("正在生成分享链接");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("group_name", currentGroupName())
                    .put("location_ids", locationIds)
                    .put("location_snapshots", locationSnapshots)
                    .put("expires_hours", expiresHours)
                    .put("access_code", accessCode);
                JSONObject response = postJson(ApiPaths.SHARE, payload);
                String shareUrl = response.optString("share_url", "").trim();
                if (shareUrl.isEmpty()) {
                    throw new IllegalStateException("服务器没有返回分享链接。");
                }
                String expiresAt = response.optString("expires_at", "");
                runUiIfScreenCurrent(targetScreenGeneration, () -> {
                    shareExistingLocationLink(shareUrl, accessCode, expiresAt);
                });
            } catch (Exception exception) {
                runUiIfScreenCurrent(targetScreenGeneration, () -> setStatus("生成分享链接失败：" + exception.getMessage()));
            }
        });
    }

    private JSONObject locationShareSnapshot(JSONObject location) {
        if (isP2PLocationRecord(location)) {
            throw new IllegalStateException("端到端加密定位不能生成公开链接快照。");
        }
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        JSONObject meta = location.optJSONObject("location_meta");
        JSONObject minimalDiagnostics = new JSONObject();
        JSONObject minimalMeta = new JSONObject();
        try {
            if (diagnostics != null) {
                for (String key : new String[] {"preferred_address", "preferred_city", "preferred_coordinate_system"}) {
                    if (diagnostics.has(key)) {
                        minimalDiagnostics.put(key, diagnostics.opt(key));
                    }
                }
            }
            if (meta != null) {
                for (String key : new String[] {"coordinate_system", "mock_provider"}) {
                    if (meta.has(key)) {
                        minimalMeta.put(key, meta.opt(key));
                    }
                }
            }
            return new JSONObject()
                .put("id", location.optLong("id", 0L))
                .put("latitude", location.optDouble("latitude", 0.0d))
                .put("longitude", location.optDouble("longitude", 0.0d))
                .put("address", location.optString("address", ""))
                .put("city", location.optString("city", ""))
                .put("address_diagnostics", minimalDiagnostics)
                .put("location_meta", minimalMeta);
        } catch (Exception exception) {
            throw new IllegalStateException("无法整理分享位置。", exception);
        }
    }

    private boolean containsP2PLocationRecord(JSONArray locations) {
        if (locations == null) {
            return false;
        }
        for (int index = 0; index < locations.length(); index += 1) {
            if (isP2PLocationRecord(locations.optJSONObject(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean isP2PLocationRecord(JSONObject location) {
        return location != null && !LocationShareSecurityPolicy.allowsPublicLink(
            location.optString("encryption_mode", ""),
            location.optBoolean("p2p_decrypted", false),
            location.optBoolean("contains_p2p", false)
        );
    }

    private void generateSharedMapImage(JSONArray selected) {
        if (!canLoadForegroundWebView()) {
            setStatus("请回到前台后再生成地图图片。");
            return;
        }
        Dialog dialog = choiceDialog("生成地图图片");
        LinearLayout body = choiceDialogBody(dialog);
        TextView status = body("正在加载地图…");
        status.setTextColor(colorMuted());
        final WebView[] mapReference = new WebView[1];
        final Runnable[] captureTask = new Runnable[1];
        AtomicBoolean captureStarted = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable onMapReady = () -> {
            WebView map = mapReference[0];
            if (map == null || !dialog.isShowing() || !captureStarted.compareAndSet(false, true)) {
                return;
            }
            status.setText("地图已就绪，正在截取…");
            captureTask[0] = () -> captureAndShareMap(dialog, map, status, finished, 0);
            map.postDelayed(captureTask[0], 250L);
        };
        WebView map = locationMapWebView(selected, onMapReady);
        mapReference[0] = map;
        map.setTag(null);
        map.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        LinearLayout.LayoutParams params = blockParams(8);
        params.height = dp(360);
        body.addView(map, params);
        body.addView(status, blockParams(0));
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                status.setText("地图等待超时，请检查网络后重试。");
            }
        };
        dialog.setOnDismissListener(dialogInterface -> {
            finished.set(true);
            mainHandler.removeCallbacks(timeout);
            if (captureTask[0] != null) {
                map.removeCallbacks(captureTask[0]);
            }
            destroyManagedWebView(map);
        });
        showChoiceDialog(dialog, body);
        mainHandler.postDelayed(timeout, 12_000L);
    }

    private void captureAndShareMap(Dialog dialog, WebView map, TextView status, AtomicBoolean finished, int attempt) {
        try {
            if (finished.get() || !dialog.isShowing() || !canLoadForegroundWebView()) {
                return;
            }
            if (map.getWidth() <= 0 || map.getHeight() <= 0) {
                throw new IllegalStateException("地图还没有完成布局。");
            }
            Window sourceWindow = dialog.getWindow();
            if (sourceWindow == null) {
                throw new IllegalStateException("地图窗口不可用。");
            }
            int[] location = new int[2];
            map.getLocationInWindow(location);
            Rect sourceArea = new Rect(
                location[0],
                location[1],
                location[0] + map.getWidth(),
                location[1] + map.getHeight()
            );
            Bitmap bitmap = Bitmap.createBitmap(map.getWidth(), map.getHeight(), Bitmap.Config.ARGB_8888);
            PixelCopy.request(sourceWindow, sourceArea, bitmap, result -> {
                if (finished.get() || !dialog.isShowing()) {
                    bitmap.recycle();
                    return;
                }
                if (result != PixelCopy.SUCCESS || isLikelyBlankMap(bitmap)) {
                    bitmap.recycle();
                    if (attempt == 0) {
                        status.setText("地图画面未完成，正在重试…");
                        map.postDelayed(() -> captureAndShareMap(dialog, map, status, finished, 1), 450L);
                    } else if (finished.compareAndSet(false, true)) {
                        status.setText(result == PixelCopy.SUCCESS
                            ? "地图画面仍为空白，请检查网络后重试。"
                            : "生成地图图片失败：无法读取地图画面（" + result + "）。");
                    }
                    return;
                }
                writeAndShareMapImage(dialog, bitmap, status, finished);
            }, mainHandler);
        } catch (Exception exception) {
            if (finished.compareAndSet(false, true)) {
                status.setText("生成地图图片失败：" + exception.getMessage());
            }
        }
    }

    private boolean isLikelyBlankMap(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return true;
        }
        int minRed = 255;
        int minGreen = 255;
        int minBlue = 255;
        int maxRed = 0;
        int maxGreen = 0;
        int maxBlue = 0;
        int opaqueSamples = 0;
        int xSamples = Math.min(12, bitmap.getWidth());
        int ySamples = Math.min(12, bitmap.getHeight());
        for (int y = 0; y < ySamples; y += 1) {
            int pixelY = Math.min(bitmap.getHeight() - 1, (y * bitmap.getHeight()) / ySamples + bitmap.getHeight() / (ySamples * 2));
            for (int x = 0; x < xSamples; x += 1) {
                int pixelX = Math.min(bitmap.getWidth() - 1, (x * bitmap.getWidth()) / xSamples + bitmap.getWidth() / (xSamples * 2));
                int color = bitmap.getPixel(pixelX, pixelY);
                if (Color.alpha(color) < 32) {
                    continue;
                }
                opaqueSamples += 1;
                minRed = Math.min(minRed, Color.red(color));
                minGreen = Math.min(minGreen, Color.green(color));
                minBlue = Math.min(minBlue, Color.blue(color));
                maxRed = Math.max(maxRed, Color.red(color));
                maxGreen = Math.max(maxGreen, Color.green(color));
                maxBlue = Math.max(maxBlue, Color.blue(color));
            }
        }
        int totalSamples = xSamples * ySamples;
        return opaqueSamples < totalSamples / 2
            || (maxRed - minRed < 8 && maxGreen - minGreen < 8 && maxBlue - minBlue < 8);
    }

    private void writeAndShareMapImage(Dialog dialog, Bitmap bitmap, TextView status, AtomicBoolean finished) {
        try {
            Uri imageUri = LocationShareSupport.writeMapImageToGallery(this, bitmap);
            bitmap.recycle();
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            dialog.dismiss();
            setStatus("地图图片已保存到相册");
            Intent chooser = Intent.createChooser(LocationShareSupport.imageIntent(imageUri), "分享地图图片");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception exception) {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (dialog.isShowing()) {
                finished.set(true);
                status.setText("生成地图图片失败：" + exception.getMessage());
            } else {
                setStatus("打开分享面板失败：" + exception.getMessage());
            }
        }
    }

    private LinearLayout summaryCardHeader(String name, String roleLabel, String roleKey) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(firstText(name, "成员"));
        title.setTextColor(colorText());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(denseUi() ? 14f : 15f);
        title.setIncludeFontPadding(false);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (hasMeaningfulText(roleLabel)) {
            TextView badge = new TextView(this);
            badge.setText(roleLabel);
            badge.setTextColor(colorOnPrimary());
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextSize(11f);
            badge.setIncludeFontPadding(false);
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            badge.setBackground(roundedDrawable(historyRoleColor(roleKey), dp(999)));
            header.addView(badge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return header;
    }

    private TextView homeLatestLine(String text, boolean emphasis) {
        TextView view = body(text);
        view.setTextColor(emphasis ? colorText() : colorMuted());
        view.setTypeface(emphasis ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        view.setTextSize(emphasis ? 13f : uiStyle.bodyTextSize(denseUi()));
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private String locationDisplayText(JSONObject location, JSONObject diagnostics, String preferredAddress) {
        String address = firstText(
            diagnosticsPreferredAddress(diagnostics),
            preferredAddress,
            location == null ? "" : location.optString("address", ""),
            location == null ? "" : location.optString("location_address", "")
        );
        if (hasResolvedAddress(address)) {
            return address.trim();
        }
        if (hasUsableCoordinates(location)) {
            return formatCoordinate(location.optDouble("latitude", 0)) + ", " + formatCoordinate(location.optDouble("longitude", 0));
        }
        return "暂无";
    }

    private String diagnosticsPreferredAddress(JSONObject diagnostics) {
        if (diagnostics == null) {
            return "";
        }
        String bestAddress = firstText(
            diagnostics.optString("preferred_address", ""),
            composeAddress(
                diagnostics.optString("preferred_country", ""),
                diagnostics.optString("preferred_region", ""),
                diagnostics.optString("preferred_city", ""),
                diagnostics.optString("preferred_district", ""),
                diagnostics.optString("preferred_street", ""),
                diagnostics.optString("preferred_detail", ""),
                diagnostics.optString("preferred_poi", "")
            )
        );
        int bestScore = addressPrecisionScore(preferredAddressCandidate(diagnostics, bestAddress));
        JSONArray sources = diagnostics.optJSONArray("sources");
        if (sources == null) {
            return bestAddress;
        }
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null) {
                continue;
            }
            source = mostPreciseDiagnosticSource(source);
            String sourceType = source.optString("type", "");
            if ("ip".equals(sourceType) || "webrtc".equals(sourceType)) {
                continue;
            }
            String candidateAddress = diagnosticSourceAddress(source);
            int candidateScore = addressPrecisionScore(source);
            if (hasResolvedAddress(candidateAddress) && candidateScore > bestScore) {
                bestAddress = candidateAddress;
                bestScore = candidateScore;
            }
        }
        return bestAddress;
    }

    private JSONObject preferredAddressCandidate(JSONObject diagnostics, String address) {
        JSONObject candidate = new JSONObject();
        if (diagnostics == null) {
            return candidate;
        }
        try {
            candidate.put("address", address);
            for (String field : new String[] {"country", "region", "city", "district", "street", "detail", "poi"}) {
                candidate.put(field, diagnostics.optString("preferred_" + field, ""));
            }
        } catch (Exception ignored) {
        }
        return candidate;
    }

    private String diagnosticSourceAddress(JSONObject source) {
        if (source == null) {
            return "未知";
        }
        String structured = composeAddress(
            source.optString("country", ""),
            source.optString("region", ""),
            source.optString("city", ""),
            source.optString("district", ""),
            source.optString("street", ""),
            source.optString("detail", ""),
            source.optString("poi", "")
        );
        String explicit = cleanupComposedAddress(source.optString("address", ""));
        String ip = firstText(
            source.optString("ip", ""),
            source.optString("server_ip", ""),
            source.optString("ipv4", ""),
            source.optString("ipv6", "")
        );
        boolean structuredHasLocalDetail = hasMeaningfulText(source.optString("district", ""))
            || hasMeaningfulText(source.optString("street", ""))
            || hasMeaningfulText(source.optString("detail", ""))
            || hasMeaningfulText(source.optString("poi", ""));
        return AddressDisplayPolicy.mostPrecise(explicit, structured, ip, structuredHasLocalDetail);
    }

    private String preferredCityText(JSONObject diagnostics) {
        if (diagnostics == null) {
            return "";
        }
        String preferred = firstText(diagnostics.optString("preferred_city", ""));
        if (hasMeaningfulText(preferred)) {
            return preferred;
        }
        JSONArray sources = diagnostics.optJSONArray("sources");
        if (sources == null) {
            return "";
        }
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null) {
                continue;
            }
            String city = firstText(source.optString("city", ""));
            if (hasMeaningfulText(city)) {
                return city;
            }
        }
        return "";
    }

    private boolean hasResolvedAddress(String address) {
        String value = address == null ? "" : address.trim();
        return !value.isEmpty() && !"未解析".equals(value);
    }

    private boolean hasMeaningfulText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private boolean shouldShowStatusText(String status) {
        return hasMeaningfulText(status) && !"位置信息一致或无法完整判断".equals(status);
    }

    private void attachMapOpenAction(View row, JSONObject location, String label) {
        if (!hasUsableCoordinates(location)) {
            return;
        }
        row.setClickable(true);
        row.setOnClickListener(view -> openMapLocation(location, label));
    }

    private boolean hasUsableCoordinates(JSONObject location) {
        if (location == null || !location.has("latitude") || !location.has("longitude") || location.isNull("latitude") || location.isNull("longitude")) {
            return false;
        }
        double latitude = location.optDouble("latitude", 0);
        double longitude = location.optDouble("longitude", 0);
        return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180 && !(latitude == 0 && longitude == 0);
    }

    private void openMapLocation(JSONObject location, String label) {
        String safeLabel = label == null || label.trim().isEmpty() ? "位置" : label.trim();
        try {
            JSONObject record = new JSONObject(location.toString());
            if (record.optString("display_name", "").isEmpty()) {
                record.put("display_name", safeLabel);
            }
            JSONArray records = new JSONArray().put(record);
            if (currentTab == TAB_POSITION && homeMapWebView != null) {
                JSONArray combined = new JSONArray();
                combined.put(record);
                appendMapRecords(combined, homeMapBaseRecords);
                renderMapRecords(homeMapWebView, combined);
                if (activeScrollView != null) {
                    activeScrollView.post(() -> activeScrollView.smoothScrollTo(0, 0));
                }
                setStatus("已定位到顶部地图：" + safeLabel);
                return;
            }
            Dialog dialog = choiceDialog(safeLabel);
            LinearLayout body = choiceDialogBody(dialog);
            WebView map = locationMapWebView(records);
            map.setTag(null);
            LinearLayout.LayoutParams params = blockParams(0);
            params.height = dp(360);
            body.addView(map, params);
            dialog.setOnDismissListener(dialogInterface -> destroyManagedWebView(map));
            showChoiceDialog(dialog, body);
            setStatus("已打开位置地图：" + safeLabel);
        } catch (Exception exception) {
            setStatus("打开地图失败：" + exception.getMessage());
        }
    }

    private void removeDynamicRows() {
        for (int index = content.getChildCount() - 1; index >= 0; index -= 1) {
            View child = content.getChildAt(index);
            if (isDynamicRowTag(child.getTag())) {
                content.removeViewAt(index);
                if (child instanceof WebView && child != homeMapWebView) {
                    destroyManagedWebView((WebView) child);
                }
            }
        }
    }

    private void removeHomeHistoryRows() {
        if (content == null) {
            return;
        }
        for (int index = content.getChildCount() - 1; index >= 0; index -= 1) {
            View child = content.getChildAt(index);
            if (VIEW_TAG_HOME_HISTORY.equals(child.getTag())) {
                content.removeViewAt(index);
                if (child instanceof WebView) {
                    destroyManagedWebView((WebView) child);
                }
            }
        }
    }

    private boolean isDynamicRowTag(Object tag) {
        return VIEW_TAG_DYNAMIC.equals(tag) || VIEW_TAG_HOME_HISTORY.equals(tag);
    }

    private void reportCurrentLocation() {
        if (reportAttemptGate.activeToken() > 0L) {
            setStatus("当前位置正在上报，请稍候。");
            return;
        }

        if (!hasFineLocationPermission()) {
            if (hasCoarseLocationPermission()) {
                setStatus("当前仅授予了模糊定位，无法可靠上报当前位置。请在系统设置中开启精确位置后重试。");
            } else {
                setStatus("请先授予精确定位权限后再上报当前位置。");
            }
            requestForegroundLocationPermissionIfNeeded();
            return;
        }

        long attemptToken = beginReportAttempt();
        setStatus("正在读取定位");

        android.location.LocationManager manager = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            finishReport(attemptToken, "读取定位失败：系统定位服务不可用。");
            return;
        }
        android.location.Location location;
        try {
            location = bestLastKnownLocation(manager);
        } catch (Throwable throwable) {
            finishReport(attemptToken, "读取定位失败：" + safeThrowableMessage(throwable));
            return;
        }
        if (location != null) {
            Log.i(TAG, "PERF_LOCATION_ACQUIRE_MS=" + Math.max(0L, android.os.SystemClock.elapsedRealtime() - reportStartedAtElapsedMs));
            submitLocation(attemptToken, location);
            return;
        }

        try {
            List<String> providers = manager.getProviders(true);
            if (providers == null || providers.isEmpty()) {
                finishReport(attemptToken, "无法读取定位：请先开启系统定位服务。");
                return;
            }
            String provider = fastestLocationProvider(providers);
            android.location.LocationListener listener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(android.location.Location newLocation) {
                    if (!reportAttemptGate.isActive(attemptToken)) {
                        return;
                    }
                    clearReportLocationListener(attemptToken);
                    Log.i(TAG, "PERF_LOCATION_ACQUIRE_MS=" + Math.max(0L, android.os.SystemClock.elapsedRealtime() - reportStartedAtElapsedMs));
                    submitLocation(attemptToken, newLocation);
                }

                @Override
                public void onProviderEnabled(String providerName) {
                }

                @Override
                public void onProviderDisabled(String providerName) {
                    if (reportLocationListenerToken == attemptToken && reportAttemptGate.isActive(attemptToken)) {
                        finishReport(attemptToken, "定位源已关闭，请开启系统定位服务后重试。");
                    }
                }

                @Override
                public void onStatusChanged(String providerName, int status, Bundle extras) {
                    if (reportLocationListenerToken == attemptToken
                        && reportAttemptGate.isActive(attemptToken)
                        && (status == android.location.LocationProvider.OUT_OF_SERVICE
                            || status == android.location.LocationProvider.TEMPORARILY_UNAVAILABLE)) {
                        finishReport(attemptToken, "定位源暂时不可用，请稍后重试。");
                    }
                }
            };
            reportLocationManager = manager;
            reportLocationListener = listener;
            reportLocationListenerToken = attemptToken;
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
        } catch (Throwable throwable) {
            finishReport(attemptToken, "读取定位失败：" + safeThrowableMessage(throwable));
        }
    }

    private long beginReportAttempt() {
        long attemptToken = reportAttemptGate.begin();
        reportStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "PERF_LOCATION_REPORT_START");
        reporting = true;
        syncReportButtonState();
        if (reportWatchdog != null) {
            mainHandler.removeCallbacks(reportWatchdog);
        }
        reportWatchdog = () -> finishReport(attemptToken, "上报超时，已解除按钮锁定；请检查定位和网络后重试。");
        mainHandler.postDelayed(reportWatchdog, REPORT_WATCHDOG_MS);
        return attemptToken;
    }

    private void syncReportButtonState() {
        reporting = reportAttemptGate.activeToken() > 0L;
        if (reportButton != null) {
            reportButton.setEnabled(!reporting);
        }
    }

    private void clearReportLocationListener(long attemptToken) {
        if (reportLocationListenerToken != attemptToken) {
            return;
        }
        android.location.LocationManager manager = reportLocationManager;
        android.location.LocationListener listener = reportLocationListener;
        reportLocationManager = null;
        reportLocationListener = null;
        reportLocationListenerToken = 0L;
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener);
            } catch (Throwable ignored) {
            }
        }
    }

    private android.location.Location bestLastKnownLocation(android.location.LocationManager manager) {
        if (manager == null) {
            return null;
        }

        android.location.Location best = null;
        try {
            for (String provider : manager.getProviders(true)) {
                android.location.Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) {
                    continue;
                }
                if (best == null || candidate.getAccuracy() < best.getAccuracy()) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return best;
    }

    private String fastestLocationProvider(List<String> providers) {
        if (providers.contains(android.location.LocationManager.NETWORK_PROVIDER)) {
            return android.location.LocationManager.NETWORK_PROVIDER;
        }
        if (providers.contains("fused")) {
            return "fused";
        }
        if (providers.contains(android.location.LocationManager.GPS_PROVIDER)) {
            return android.location.LocationManager.GPS_PROVIDER;
        }
        return providers.get(0);
    }

    private void submitLocation(long attemptToken, android.location.Location location) {
        if (!reportAttemptGate.isActive(attemptToken)) {
            return;
        }
        setStatus("正在上报位置");
        runBackground(() -> {
            try {
                if (!reportAttemptGate.isActive(attemptToken)) {
                    return;
                }
                String reportGroupName = currentGroupName();
                JSONObject addressDiagnostics = buildAddressDiagnostics(attemptToken, location);
                if (!reportAttemptGate.isActive(attemptToken)) {
                    return;
                }
                JSONObject payload = locationReportPayload(reportGroupName, location, addressDiagnostics);
                JSONObject response = postLocationReport(reportGroupName, payload);
                if (!reportAttemptGate.isActive(attemptToken)) {
                    return;
                }
                List<String> extraGroupNames = selectedCrossSyncGroups();
                extraGroupNames.remove(reportGroupName);
                int pendingSyncCount = extraGroupNames.size();
                runUi(() -> {
                    String message = response.optString("message", "");
                    if (pendingSyncCount > 0) {
                        message += " 正在后台同步 " + pendingSyncCount + " 个家庭组。";
                    }
                    if (finishReport(attemptToken, message)) {
                        refreshLocations();
                    }
                });
                scheduleLocationDiagnosticsEnrichment(
                    reportGroupName,
                    response.optInt("location_id", 0),
                    location
                );
                if (extraGroupNames.isEmpty()) {
                    return;
                }
                int synced = 0;
                List<String> failed = new ArrayList<>();
                for (String groupName : extraGroupNames) {
                    try {
                        postLocationReport(groupName, locationReportPayload(groupName, location, addressDiagnostics));
                        synced += 1;
                    } catch (Exception syncException) {
                        failed.add(groupName);
                    }
                }
                final int syncedCount = synced;
                final int failedCount = failed.size();
                runUi(() -> {
                    String message = response.optString("message", "位置已上报。");
                    if (syncedCount > 0) {
                        message += " 跨组同步 " + syncedCount + " 个家庭组。";
                    }
                    if (failedCount > 0) {
                        message += " " + failedCount + " 个家庭组同步失败，请检查端到端加密密钥或权限。";
                    }
                    setStatus(message);
                    refreshLocations();
                });
            } catch (Throwable throwable) {
                runUi(() -> finishReport(attemptToken, safeThrowableMessage(throwable)));
            }
        });
    }

    private JSONObject locationReportPayload(String groupName, android.location.Location location, JSONObject addressDiagnostics) throws Exception {
        boolean mockProvider = isMockLocation(location);
        JSONObject payload = new JSONObject()
            .put("group_name", groupName)
            .put("latitude", location.getLatitude())
            .put("longitude", location.getLongitude())
            .put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL)
            .put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL)
            .put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL)
            .put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL)
            .put("location_provider", location.getProvider())
            .put("location_time", String.valueOf(location.getTime()))
            .put("location_mock_provider", mockProvider)
            .put("location_coordinate_system", "wgs84");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            payload.put("vertical_accuracy", location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : JSONObject.NULL);
            payload.put("bearing_accuracy", location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : JSONObject.NULL);
            payload.put("speed_accuracy", location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : JSONObject.NULL);
        }
        if (addressDiagnostics != null) {
            payload.put("address_diagnostics", addressDiagnostics);
        }
        if (currentUser != null && currentUser.optBoolean("environment_data_consent", false)) {
            payload.put("device_report", buildDeviceEnvironmentReport(false));
        }
        return payload;
    }

    private boolean isMockLocation(android.location.Location location) {
        if (location == null) {
            return false;
        }
        try {
            return location.isFromMockProvider();
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject buildAddressDiagnostics(long attemptToken, android.location.Location location) {
        return buildAddressDiagnostics(attemptToken, location, ADDRESS_DIAGNOSTICS_BUDGET_MS);
    }

    private JSONObject buildAddressDiagnostics(long attemptToken, android.location.Location location, long budgetMs) {
        if (!reportProbeActive(attemptToken)) {
            return null;
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        JSONObject fallback;
        try {
            fallback = addressSource("gps", "定位地址", "坐标", "", formatCoordinate(latitude) + ", " + formatCoordinate(longitude), "", "", "", latitude, longitude);
            fallback.put("coordinate_system", "wgs84");
        } catch (Exception exception) {
            return null;
        }
        List<JSONObject> addressCandidates = collectAddressProbeCandidates(attemptToken, latitude, longitude, budgetMs);
        JSONArray sources = new JSONArray();
        JSONObject best = fallback;
        int bestScore = 0;
        int bestPriority = Integer.MAX_VALUE;
        for (JSONObject candidate : addressCandidates) {
            if (candidate == null) {
                continue;
            }
            String type = candidate.optString("type", "");
            if ("ip".equals(type) || "webrtc".equals(type)) {
                sources.put(candidate);
                continue;
            }
            if (isUsefulAddressSource(candidate, fallback)) {
                sources.put(candidate);
                int score = addressPrecisionScore(candidate);
                int priority = addressProviderPriority(candidate.optString("provider", ""));
                if (score > bestScore || (score == bestScore && priority < bestPriority)) {
                    best = candidate;
                    bestScore = score;
                    bestPriority = priority;
                }
            }
        }
        if (sources.length() == 0) {
            sources.put(fallback);
        }

        JSONObject diagnostics = new JSONObject();
        try {
            diagnostics.put("complete", true)
                .put("mismatch", false)
                .put("preferred_source", "gps")
                .put("preferred_address", cleanupComposedAddress(best.optString("address", fallback.optString("address", ""))))
                .put("preferred_country", best.optString("country", ""))
                .put("preferred_region", best.optString("region", ""))
                .put("preferred_city", normalizeCityPart(best.optString("city", fallback.optString("city", ""))))
                .put("preferred_district", best.optString("district", ""))
                .put("preferred_street", best.optString("street", ""))
                .put("preferred_detail", best.optString("detail", ""))
                .put("preferred_poi", firstText(best.optString("poi", ""), best.optString("detail", "")))
                .put("preferred_latitude", latitude)
                .put("preferred_longitude", longitude)
                .put("preferred_coordinate_system", "wgs84")
                .put("sources", sources);
        } catch (Exception ignored) {
            return null;
        }
        return diagnostics;
    }

    private List<JSONObject> collectAddressProbeCandidates(long attemptToken, double latitude, double longitude, long budgetMs) {
        CompletionService<JSONObject> completion = new ExecutorCompletionService<>(addressProbeExecutor);
        List<Future<JSONObject>> futures = new ArrayList<>();
        submitAddressProbe(completion, futures, () -> reportProbeActive(attemptToken) ? reverseAddressByAmapWebView(latitude, longitude, attemptToken) : null);
        submitAddressProbe(completion, futures, () -> reportProbeActive(attemptToken) ? reverseAddressByMeituan(latitude, longitude) : null);
        submitAddressProbe(completion, futures, () -> reportProbeActive(attemptToken) ? reverseAddressByBigDataCloud(latitude, longitude) : null);
        submitAddressProbe(completion, futures, () -> reportProbeActive(attemptToken) ? probeIpAddressSource(attemptToken) : null);
        submitAddressProbe(completion, futures, () -> reportProbeActive(attemptToken) ? probeWebRtcAddressSource(attemptToken) : null);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, budgetMs));
        List<JSONObject> candidates = new ArrayList<>();
        try {
            for (int completed = 0; completed < futures.size() && reportProbeActive(attemptToken); completed += 1) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                try {
                    Future<JSONObject> future = completion.poll(remainingNanos, TimeUnit.NANOSECONDS);
                    if (future == null) {
                        break;
                    }
                    JSONObject candidate = future.get();
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                } catch (Throwable exception) {
                    Log.w(TAG, "Address probe candidate failed: " + exception.getMessage());
                }
            }
        } finally {
            for (Future<JSONObject> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
        return candidates;
    }

    private void submitAddressProbe(CompletionService<JSONObject> completion, List<Future<JSONObject>> futures, Callable<JSONObject> task) {
        try {
            futures.add(completion.submit(task));
        } catch (RejectedExecutionException exception) {
            Log.w(TAG, "Address probe queue is busy; deferred enrichment will retry later.");
        }
    }

    private boolean reportProbeActive(long attemptToken) {
        return attemptToken <= 0L || reportAttemptGate.isActive(attemptToken);
    }

    private void scheduleLocationDiagnosticsEnrichment(
        String groupName,
        int locationId,
        android.location.Location location
    ) {
        if (locationId <= 0 || location == null || groupUsesP2P(groupName)) {
            return;
        }
        android.location.Location snapshot = new android.location.Location(location);
        runBackground(() -> {
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                JSONObject diagnostics = buildAddressDiagnostics(0L, snapshot, ADDRESS_ENRICHMENT_BUDGET_MS);
                if (diagnostics == null) {
                    return;
                }
                String fallbackAddress = formatCoordinate(snapshot.getLatitude()) + ", "
                    + formatCoordinate(snapshot.getLongitude());
                JSONArray sources = diagnostics.optJSONArray("sources");
                if (fallbackAddress.equals(diagnostics.optString("preferred_address", ""))
                    && (sources == null || sources.length() <= 1)) {
                    return;
                }
                JSONObject payload = new JSONObject()
                    .put("group_name", groupName)
                    .put("location_id", locationId)
                    .put("address_diagnostics", diagnostics);
                postReportJson(ApiPaths.REPORT_LOCATION, payload);
                long elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt;
                Log.i(TAG, "PERF_LOCATION_ENRICH_MS=" + Math.max(0L, elapsedMs));
                runUi(() -> {
                    if (currentUser != null
                        && groupName.equals(currentGroupName())
                        && canLoadForegroundWebView()) {
                        refreshLocations();
                    }
                });
            } catch (Throwable throwable) {
                Log.w(TAG, "Deferred location diagnostics failed: " + safeThrowableMessage(throwable));
            }
        });
    }

    private JSONObject reverseAddressByAmapWebView(double latitude, double longitude, long attemptToken) {
        return reverseAddressByAmapWebView(latitude, longitude, attemptToken, 12_000L);
    }

    private JSONObject reverseAddressByAmapWebView(double latitude, double longitude, long attemptToken, long waitMs) {
        String base = serverUrl();
        if (base.isEmpty()) {
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<WebView> webViewRef = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        String url;
        try {
            url = base + ApiPaths.AMAP_REVERSE + "?lat=" + urlEncode(formatCoordinate(latitude))
                + "&lng=" + urlEncode(formatCoordinate(longitude))
                + "&coord=wgs84";
        } catch (Exception exception) {
            return null;
        }

        runUi(() -> {
            if (!reportProbeActive(attemptToken) || !canLoadForegroundWebView()) {
                done.set(true);
                latch.countDown();
                return;
            }
            WebView webView = managedWebView();
            webViewRef.set(webView);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(settings.getUserAgentString() + " loc-app/" + APP_VERSION_NAME);
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onAmapReverse(String json) {
                    if (done.compareAndSet(false, true)) {
                        try {
                            result.set(new JSONObject(json));
                        } catch (Exception ignored) {
                            result.set(null);
                        }
                        latch.countDown();
                    }
                }

                @JavascriptInterface
                public void onAmapReverseError(String message) {
                    if (done.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                }
            }, "locApp");
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.M || request == null || request.isForMainFrame()) && done.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    if (done.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                    return handleWebViewRendererGone(view, "");
                }
            });
            webView.loadUrl(url);
        });

        try {
            latch.await(Math.max(1L, waitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            done.set(true);
            runUi(() -> {
                WebView webView = webViewRef.get();
                if (webView != null) {
                    destroyManagedWebView(webView);
                }
            });
        }
        return result.get();
    }

    private JSONObject reverseAddressByMeituan(double latitude, double longitude) {
        try {
            String url = "https://apimobile.meituan.com/group/v1/city/latlng/"
                + urlEncode(formatMeituanCoordinate(latitude)) + "," + urlEncode(formatMeituanCoordinate(longitude)) + "?tag=0";
            JSONObject response = requestDiagnosticOpenJson(url);
            JSONObject data = response.optJSONObject("data");
            if (data == null) {
                return null;
            }
            String country = firstText(data.optString("country", "中国"), "中国");
            String region = data.optString("province", "");
            String city = normalizeCityPart(firstText(data.optString("city", ""), data.optString("openCityName", "")));
            String district = data.optString("district", "");
            String street = firstText(data.optString("street", ""), data.optString("township", ""), data.optString("areaName", ""));
            String detail = firstText(data.optString("detail", ""), data.optString("name", ""), data.optString("address", ""));
            String address = composeAddress(country, region, city, district, street, detail);
            return addressSource("gps", "定位地址", "美团", "meituan", address, city, region, country, latitude, longitude)
                .put("district", district)
                .put("street", street)
                .put("detail", detail);
        } catch (Exception exception) {
            return null;
        }
    }

    private JSONObject reverseAddressByBigDataCloud(double latitude, double longitude) {
        try {
            String url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                + urlEncode(formatCoordinate(latitude)) + "&longitude=" + urlEncode(formatCoordinate(longitude)) + "&localityLanguage=zh";
            JSONObject data = requestOpenJson(url);
            String country = data.optString("countryName", "");
            String region = data.optString("principalSubdivision", "");
            String city = normalizeCityPart(firstText(data.optString("city", ""), data.optString("locality", "")));
            String district = data.optString("locality", "");
            String detail = "";
            JSONObject localityInfo = data.optJSONObject("localityInfo");
            if (localityInfo != null) {
                JSONArray informative = localityInfo.optJSONArray("informative");
                if (informative != null && informative.length() > 0) {
                    JSONObject first = informative.optJSONObject(0);
                    detail = first == null ? "" : first.optString("name", "");
                }
            }
            String address = composeAddress(country, region, city, district, detail);
            return addressSource("gps", "定位地址", "BigDataCloud", "bigdatacloud", address, city, region, country, latitude, longitude)
                .put("district", district)
                .put("detail", detail);
        } catch (Exception exception) {
            return null;
        }
    }


    private JSONObject probeIpAddressSource(long attemptToken) {
        try {
            if (!reportProbeActive(attemptToken)) {
                return null;
            }
            JSONObject probe = getJson(ApiPaths.IP_PROBE);
            String ip = firstText(probe.optString("ip", ""));
            if (ip.isEmpty()) {
                JSONObject cloudflare = getJson(ApiPaths.CLOUDFLARE_LOCATION);
                ip = firstText(cloudflare.optString("ip", ""), cloudflare.optString("ipv6", ""));
            }
            if (ip.isEmpty()) {
                return null;
            }

            JSONObject geo = attemptToken > 0L ? null : geocodeIpAddress(attemptToken, ip);
            String address = geo == null ? ip : firstText(geo.optString("address", ""), ip);
            String city = geo == null ? "" : normalizeCityPart(geo.optString("city", ""));
            String region = geo == null ? "" : geo.optString("region", "");
            String country = geo == null ? "" : geo.optString("country", "");
            String provider = geo == null ? "服务端 IP" : firstText(geo.optString("provider", ""), "IP 探测");
            double latitude = geo == null ? 0 : geo.optDouble("latitude", 0);
            double longitude = geo == null ? 0 : geo.optDouble("longitude", 0);
            JSONObject source = addressSource("ip", "IP 探测", provider, "server", address, city, region, country, latitude, longitude)
                .put("ip", ip)
                .put("server_ip", ip);
            copyAddressDetailFields(geo, source);
            copyIpNetworkFields(geo, source);
            copyProviderAttempts(geo, source);
            JSONArray variants = geo == null ? null : geo.optJSONArray("variants");
            if (variants == null || variants.length() == 0) {
                variants = new JSONArray();
                JSONObject variant = new JSONObject()
                    .put("label", "服务端")
                    .put("ip", ip)
                    .put("address", address)
                    .put("city", city)
                    .put("region", region)
                    .put("country", country)
                    .put("provider", provider)
                    .put("source", "server");
                copyAddressDetailFields(geo, variant);
                copyIpNetworkFields(geo, variant);
                if (latitude != 0 || longitude != 0) {
                    variant.put("latitude", latitude).put("longitude", longitude);
                }
                variants.put(variant);
            } else {
                variants = new JSONArray(variants.toString());
            }
            source.put("variants", variants);
            return source;
        } catch (Exception exception) {
            Log.w(TAG, "IP probe failed: " + exception.getMessage());
            return null;
        }
    }

    private JSONObject probeWebRtcAddressSource(long attemptToken) {
        String base = serverUrl();
        if (base.isEmpty()) {
            return webRtcFailureSource("server_unavailable", null);
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<WebView> webViewRef = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        String url = base + ApiPaths.WEBRTC_PROBE;

        runUi(() -> {
            if (!reportProbeActive(attemptToken) || !canLoadForegroundWebView()) {
                done.set(true);
                latch.countDown();
                return;
            }
            WebView webView = managedWebView();
            webViewRef.set(webView);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(settings.getUserAgentString() + " loc-app/" + APP_VERSION_NAME);
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onWebRtcProbe(String json) {
                    if (done.compareAndSet(false, true)) {
                        try {
                            result.set(new JSONObject(json));
                        } catch (Exception ignored) {
                            result.set(null);
                        }
                        latch.countDown();
                    }
                }
            }, "locApp");
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.M || request == null || request.isForMainFrame()) && done.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    if (done.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                    return handleWebViewRendererGone(view, "");
                }
            });
            webView.loadUrl(url);
        });

        try {
            latch.await(3500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            done.set(true);
            runUi(() -> {
                WebView webView = webViewRef.get();
                if (webView != null) {
                    destroyManagedWebView(webView);
                }
            });
        }

        JSONObject payload = result.get();
        if (payload == null) {
            return webRtcFailureSource(canLoadForegroundWebView() ? "probe_timeout" : "foreground_unavailable", null);
        }
        if (!payload.optBoolean("ok", false)) {
            return webRtcFailureSource("webrtc_unavailable", payload.optJSONArray("candidates"));
        }
        JSONObject selected = payload.optJSONObject("selected");
        String ip = selected == null ? "" : selected.optString("ip", "");
        if (ip.isEmpty()) {
            return webRtcFailureSource("no_public_candidate", payload.optJSONArray("candidates"));
        }
        try {
            if (!reportProbeActive(attemptToken)) {
                return webRtcFailureSource("cancelled", payload.optJSONArray("candidates"));
            }
            // The IP probe runs in parallel and caches its geocoding result. WebRTC must
            // never start the same slow provider chain again: retaining the STUN/IP
            // evidence is more important than missing the enrichment deadline entirely.
            JSONObject geo = cachedIpGeocode(ip);
            String address = geo == null ? ip : firstText(geo.optString("address", ""), ip);
            String city = geo == null ? "" : normalizeCityPart(geo.optString("city", ""));
            String region = geo == null ? "" : geo.optString("region", "");
            String country = geo == null ? "" : geo.optString("country", "");
            double latitude = geo == null ? 0 : geo.optDouble("latitude", 0);
            double longitude = geo == null ? 0 : geo.optDouble("longitude", 0);
            String provider = geo == null ? selected.optString("stun_label", "WebRTC") : firstText(geo.optString("provider", ""), selected.optString("stun_label", "WebRTC"));
            JSONObject source = addressSource("webrtc", "WebRTC 探测", provider, selected.optString("stun_server", ""), address, city, region, country, latitude, longitude)
                .put("probe_status", "success")
                .put("ip", ip)
                .put("stun_server", selected.optString("stun_server", ""))
                .put("stun_label", selected.optString("stun_label", ""))
                .put("stun_scope", selected.optString("stun_scope", ""))
                .put("candidate_type", selected.optString("candidate_type", ""));
            copyAddressDetailFields(geo, source);
            copyIpNetworkFields(geo, source);
            copyProviderAttempts(geo, source);
            JSONArray candidates = mergeWebRtcAddressCandidates(
                selected,
                payload.optJSONArray("candidates"),
                geo == null ? null : geo.optJSONArray("variants")
            );
            if (candidates.length() > 0) {
                source.put("candidates", candidates);
            }
            return source;
        } catch (Exception exception) {
            Log.w(TAG, "WebRTC probe normalize failed: " + exception.getMessage());
            return webRtcFailureSource("invalid_response", payload.optJSONArray("candidates"));
        }
    }

    private JSONObject webRtcFailureSource(String reason, JSONArray candidates) {
        try {
            JSONObject source = addressSource("webrtc", "WebRTC 探测", "WebRTC", "stun", "", "", "", "", 0, 0)
                .put("probe_status", "failed")
                .put("failure_reason", firstText(reason, "webrtc_unavailable"));
            if (candidates != null && candidates.length() > 0) {
                source.put("candidates", new JSONArray(candidates.toString()));
            }
            return source;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject geocodeIpAddress(long attemptToken, String ip) {
        List<JSONObject> candidates = new ArrayList<>();
        JSONArray providerAttempts = new JSONArray();
        if (!reportProbeActive(attemptToken)) {
            return null;
        }
        for (IpGeoProbeResult result : collectIpGeoProbeResults(ip, attemptToken)) {
            recordIpProviderAttempt(providerAttempts, result.label, result.candidate, result.failureReason);
            if (result.candidate != null) {
                candidates.add(result.candidate);
            }
        }

        JSONObject coordinateCandidate = null;
        for (JSONObject candidate : candidates) {
            if (hasGeoCoordinates(candidate) && geocodeIpScore(candidate) > geocodeIpScore(coordinateCandidate)) {
                coordinateCandidate = candidate;
            }
        }
        if (coordinateCandidate != null && reportProbeActive(attemptToken)) {
            JSONObject amap = reverseAddressByAmapWebView(
                coordinateCandidate.optDouble("latitude", 0.0d),
                coordinateCandidate.optDouble("longitude", 0.0d),
                attemptToken,
                1_500L
            );
            if (amap != null) {
                try {
                    copyIpNetworkFields(coordinateCandidate, amap);
                    amap.put("coordinate_system", "wgs84");
                } catch (Exception ignored) {
                }
                candidates.add(amap);
            }
            recordIpProviderAttempt(providerAttempts, "高德逆地理(安卓)", amap);
        }

        JSONObject best = null;
        for (JSONObject candidate : candidates) {
            int candidateScore = geocodeIpScore(candidate);
            int bestScore = geocodeIpScore(best);
            if (candidateScore > bestScore
                || (candidateScore == bestScore
                    && addressProviderPriority(candidate.optString("provider", ""))
                    < addressProviderPriority(best == null ? "" : best.optString("provider", "")))) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        try {
            JSONObject selected = new JSONObject(best.toString());
            selected.put("ip", firstText(best.optString("ip", ""), ip));
            selected.put("variants", ipGeocodeVariants(candidates, ip));
            selected.put("provider_attempts", providerAttempts);
            cacheIpGeocode(ip, selected);
            return selected;
        } catch (Exception exception) {
            return best;
        }
    }

    private JSONArray ipGeocodeVariants(List<JSONObject> candidates, String ip) {
        Map<String, JSONObject> variants = new LinkedHashMap<>();
        for (JSONObject candidate : candidates) {
            JSONObject variant = diagnosticSourceSnapshot(candidate);
            if (variant == null) {
                continue;
            }
            try {
                if (!hasMeaningfulText(variant.optString("ip", ""))) {
                    variant.put("ip", ip);
                }
                String provider = variant.optString("provider", "");
                if (!hasMeaningfulText(variant.optString("label", ""))) {
                    variant.put("label", firstText(provider, "IP 地址"));
                }
                if (!hasMeaningfulText(variant.optString("source", ""))) {
                    variant.put("source", provider);
                }
            } catch (Exception ignored) {
            }
            String key = diagnosticNestedEvidenceKey(null, variant);
            if (!variants.containsKey(key)) {
                variants.put(key, variant);
            }
        }
        JSONArray result = new JSONArray();
        for (JSONObject variant : variants.values()) {
            result.put(variant);
        }
        return result;
    }

    private JSONArray mergeWebRtcAddressCandidates(JSONObject selected, JSONArray rawCandidates, JSONArray geocodeVariants) {
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        if (geocodeVariants != null) {
            for (int index = 0; index < geocodeVariants.length(); index += 1) {
                JSONObject candidate = cloneDiagnosticObject(geocodeVariants.optJSONObject(index));
                if (candidate == null) {
                    continue;
                }
                try {
                    for (String field : new String[] {"ip", "stun_server", "stun_label", "stun_scope", "candidate_type"}) {
                        if (!hasMeaningfulText(candidate.optString(field, "")) && selected != null && hasMeaningfulText(selected.optString(field, ""))) {
                            candidate.put(field, selected.opt(field));
                        }
                    }
                } catch (Exception ignored) {
                }
                String key = diagnosticNestedEvidenceKey(null, candidate);
                if (!merged.containsKey(key)) {
                    merged.put(key, candidate);
                }
            }
        }
        if (rawCandidates != null) {
            for (int index = 0; index < rawCandidates.length(); index += 1) {
                JSONObject candidate = cloneDiagnosticObject(rawCandidates.optJSONObject(index));
                if (candidate == null) {
                    continue;
                }
                String key = diagnosticNestedEvidenceKey(null, candidate);
                if (!merged.containsKey(key)) {
                    merged.put(key, candidate);
                }
            }
        }
        JSONArray result = new JSONArray();
        for (JSONObject candidate : merged.values()) {
            result.put(candidate);
        }
        return result;
    }

    private boolean hasGeoCoordinates(JSONObject geo) {
        if (geo == null) {
            return false;
        }
        double latitude = geo.optDouble("latitude", Double.NaN);
        double longitude = geo.optDouble("longitude", Double.NaN);
        return Double.isFinite(latitude)
            && Double.isFinite(longitude)
            && latitude >= -90.0d
            && latitude <= 90.0d
            && longitude >= -180.0d
            && longitude <= 180.0d
            && !(latitude == 0.0d && longitude == 0.0d);
    }

    private List<IpGeoProbeResult> collectIpGeoProbeResults(String ip, long attemptToken) {
        CompletionService<IpGeoProbeResult> completion = new ExecutorCompletionService<>(ipProviderExecutor);
        List<Future<IpGeoProbeResult>> futures = new ArrayList<>();
        Map<Integer, String> expected = new LinkedHashMap<>();
        int order = 0;
        for (String provider : new String[] {"ip-api", "uapis", "baidu", "iping", "xxapi", "ip2location", "ipdata", "ipregistry"}) {
            final int probeOrder = order++;
            final String probeProvider = provider;
            final String label = provider + "(服务端)";
            expected.put(probeOrder, label);
            try {
                futures.add(completion.submit(() -> probeServerIpGeo(probeOrder, label, probeProvider, ip)));
            } catch (RejectedExecutionException ignored) {
            }
        }
        for (String provider : new String[] {"ip-api", "uapis", "baidu", "iping", "xxapi"}) {
            final int probeOrder = order++;
            final String probeProvider = provider;
            final String label = provider + "(安卓)";
            expected.put(probeOrder, label);
            try {
                futures.add(completion.submit(() -> probeAndroidIpGeo(probeOrder, label, probeProvider, ip)));
            } catch (RejectedExecutionException ignored) {
            }
        }
        final int meituanOrder = order++;
        expected.put(meituanOrder, "meituan(安卓)");
        try {
            futures.add(completion.submit(() -> {
                AtomicReference<String> failure = new AtomicReference<>("no_result");
                JSONObject candidate = geocodeIpByMeituan(ip, failure);
                prepareIpGeoCandidate(candidate, ip, "android-direct", "meituan");
                return new IpGeoProbeResult(meituanOrder, "meituan(安卓)", candidate, failure.get());
            }));
        } catch (RejectedExecutionException ignored) {
        }
        final int ipInfoOrder = order;
        expected.put(ipInfoOrder, "ipinfo-lite(服务端)");
        try {
            futures.add(completion.submit(() -> {
                AtomicReference<String> failure = new AtomicReference<>("no_result");
                JSONObject candidate = geocodeIpByIpInfo(ip, failure);
                prepareIpGeoCandidate(candidate, ip, "server", "ipinfo-lite");
                return new IpGeoProbeResult(ipInfoOrder, "ipinfo-lite(服务端)", candidate, failure.get());
            }));
        } catch (RejectedExecutionException ignored) {
        }

        Map<Integer, IpGeoProbeResult> completedByOrder = new LinkedHashMap<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_800L);
        try {
            for (int completed = 0; completed < futures.size() && reportProbeActive(attemptToken); completed += 1) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                Future<IpGeoProbeResult> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) {
                    break;
                }
                IpGeoProbeResult result = future.get();
                if (result != null) {
                    completedByOrder.put(result.order, result);
                }
            }
        } catch (Throwable exception) {
            Log.w(TAG, "IP provider collection failed: " + safeThrowableMessage(exception));
        } finally {
            for (Future<IpGeoProbeResult> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
        List<IpGeoProbeResult> results = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            IpGeoProbeResult result = completedByOrder.get(entry.getKey());
            results.add(result == null
                ? new IpGeoProbeResult(entry.getKey(), entry.getValue(), null, "timeout")
                : result);
        }
        Collections.sort(results, Comparator.comparingInt(result -> result.order));
        return results;
    }

    private IpGeoProbeResult probeServerIpGeo(int order, String label, String provider, String ip) {
        try {
            JSONObject response = postDiagnosticJson(ApiPaths.IP_GEO, new JSONObject()
                .put("ip", ip)
                .put("provider", provider));
            JSONObject candidate = normalizeServerIpGeo(response, ip, provider);
            return new IpGeoProbeResult(order, label, candidate, candidate == null ? "no_result" : "");
        } catch (Throwable exception) {
            Log.w(TAG, "Server IP geo provider failed (" + provider + "): " + safeThrowableMessage(exception));
            return new IpGeoProbeResult(order, label, null, diagnosticFailureReason(exception));
        }
    }

    private JSONObject normalizeServerIpGeo(JSONObject response, String ip, String provider) throws Exception {
        if (response == null || !response.optBoolean("ok", false)) {
            return null;
        }
        String country = response.optString("country", "");
        String region = response.optString("region", "");
        String city = normalizeCityPart(response.optString("city", ""));
        String district = response.optString("district", "");
        String street = firstText(response.optString("street", ""), response.optString("township", ""));
        String detail = firstText(response.optString("detail", ""), response.optString("poi", ""), response.optString("address_detail", ""));
        String address = firstText(response.optString("address", ""), composeAddress(country, region, city, district, street, detail));
        return new JSONObject()
            .put("provider", firstText(response.optString("provider", ""), provider) + "（服务端）")
            .put("source", provider + "-server")
            .put("source_region", "server")
            .put("ip", firstText(response.optString("ip", ""), ip))
            .put("address", address.isEmpty() ? ip : address)
            .put("country", country)
            .put("region", region)
            .put("city", city)
            .put("district", district)
            .put("street", street)
            .put("detail", detail)
            .put("postal_code", response.optString("postal_code", ""))
            .put("latitude", response.optDouble("latitude", 0))
            .put("longitude", response.optDouble("longitude", 0))
            .put("asn", response.optString("asn", ""))
            .put("isp", response.optString("isp", ""))
            .put("org", response.optString("org", ""))
            .put("carrier", response.optString("carrier", ""))
            .put("mobile_network", response.optBoolean("mobile_network", false));
    }

    private IpGeoProbeResult probeAndroidIpGeo(int order, String label, String provider, String fallbackIp) {
        try {
            JSONObject candidate;
            switch (provider) {
                case "ip-api":
                    candidate = normalizeAndroidIpApi(requestDiagnosticOpenJson("http://ip-api.com/json/?lang=zh-CN"), fallbackIp);
                    break;
                case "uapis":
                    candidate = normalizeAndroidUApis(requestDiagnosticOpenJson("https://uapis.cn/api/v1/network/myip"), fallbackIp);
                    break;
                case "baidu":
                    candidate = normalizeAndroidBaidu(requestDiagnosticOpenJson(
                        "https://opendata.baidu.com/api.php?query=" + urlEncode(fallbackIp) + "&co=&resource_id=6086&oe=utf8"
                    ), fallbackIp);
                    break;
                case "iping":
                    candidate = normalizeAndroidIPing(requestDiagnosticOpenJson(
                        "https://api.iping.cc/v1/query?ip=" + urlEncode(fallbackIp) + "&language=zh"
                    ), fallbackIp);
                    break;
                case "xxapi":
                    candidate = normalizeAndroidXXAPI(requestDiagnosticOpenJson("https://v2.xxapi.cn/api/ip"), fallbackIp);
                    break;
                default:
                    candidate = null;
                    break;
            }
            return new IpGeoProbeResult(order, label, candidate, candidate == null ? "no_result" : "");
        } catch (Throwable exception) {
            Log.w(TAG, "Android IP geo provider failed (" + provider + "): " + safeThrowableMessage(exception));
            return new IpGeoProbeResult(order, label, null, diagnosticFailureReason(exception));
        }
    }

    private JSONObject normalizeAndroidIpApi(JSONObject data, String fallbackIp) throws Exception {
        if (data == null || !"success".equalsIgnoreCase(data.optString("status", ""))) {
            return null;
        }
        return androidIpGeoCandidate(
            "ip-api", firstText(data.optString("query", ""), fallbackIp),
            data.optString("country", ""), data.optString("regionName", ""), data.optString("city", ""),
            composeAddress(data.optString("country", ""), data.optString("regionName", ""), data.optString("city", "")),
            data.optDouble("lat", 0), data.optDouble("lon", 0),
            data.optString("as", ""), data.optString("isp", ""), data.optString("org", "")
        ).put("postal_code", data.optString("zip", ""));
    }

    private JSONObject normalizeAndroidUApis(JSONObject data, String fallbackIp) throws Exception {
        if (data == null || !hasMeaningfulText(data.optString("ip", ""))) {
            return null;
        }
        String address = data.optString("region", "");
        String[] place = splitProviderRegionText(address);
        return androidIpGeoCandidate(
            "UApi", firstText(data.optString("ip", ""), fallbackIp),
            place[0], place[1], place[2], address,
            data.optDouble("latitude", 0), data.optDouble("longitude", 0),
            data.optString("asn", ""), data.optString("isp", ""), data.optString("llc", "")
        );
    }

    private JSONObject normalizeAndroidBaidu(JSONObject response, String fallbackIp) throws Exception {
        JSONArray items = response == null ? null : response.optJSONArray("data");
        JSONObject data = items == null ? null : items.optJSONObject(0);
        String address = data == null ? "" : firstText(data.optString("location", ""), data.optString("address", ""));
        if (!hasMeaningfulText(address)) {
            return null;
        }
        String[] place = splitProviderRegionText(address);
        return androidIpGeoCandidate(
            "百度开放数据", fallbackIp, place[0], place[1], place[2], address,
            0, 0, "", data.optString("isp", ""), ""
        );
    }

    private JSONObject normalizeAndroidIPing(JSONObject response, String fallbackIp) throws Exception {
        if (response == null || response.optInt("code", 0) != 200) {
            return null;
        }
        JSONObject data = response.optJSONObject("data");
        if (data == null) {
            return null;
        }
        String country = data.optString("country", "");
        String region = data.optString("region", "");
        String city = data.optString("city", "");
        return androidIpGeoCandidate(
            "IPing", firstText(data.optString("ip", ""), fallbackIp), country, region, city,
            composeAddress(country, region, city), data.optDouble("latitude", 0), data.optDouble("longitude", 0),
            data.optString("asn", ""), data.optString("isp", ""),
            firstText(data.optString("company", ""), data.optString("as_owner", ""))
        );
    }

    private JSONObject normalizeAndroidXXAPI(JSONObject response, String fallbackIp) throws Exception {
        if (response == null || response.optInt("code", 0) != 200) {
            return null;
        }
        JSONObject data = response.optJSONObject("data");
        String address = data == null ? "" : data.optString("address", "");
        if (!hasMeaningfulText(address)) {
            return null;
        }
        String[] place = splitProviderRegionText(address);
        return androidIpGeoCandidate(
            "XXAPI", firstText(data.optString("ip", ""), fallbackIp), place[0], place[1], place[2], address,
            data.optDouble("lat", 0), data.optDouble("lng", 0), "", data.optString("isp", ""), ""
        );
    }

    private JSONObject androidIpGeoCandidate(
        String provider,
        String ip,
        String country,
        String region,
        String city,
        String address,
        double latitude,
        double longitude,
        String asn,
        String isp,
        String org
    ) throws Exception {
        return new JSONObject()
            .put("provider", provider + "（安卓直查）")
            .put("source", provider.toLowerCase(Locale.ROOT) + "-android")
            .put("source_region", "android-direct")
            .put("ip", ip)
            .put("address", firstText(address, ip))
            .put("country", country)
            .put("region", region)
            .put("city", normalizeCityPart(city))
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("asn", asn)
            .put("isp", isp)
            .put("org", org);
    }

    private void prepareIpGeoCandidate(JSONObject candidate, String ip, String sourceRegion, String source) {
        if (candidate == null) {
            return;
        }
        try {
            if (!hasMeaningfulText(candidate.optString("ip", ""))) {
                candidate.put("ip", ip);
            }
            candidate.put("source_region", sourceRegion).put("source", source);
            String provider = candidate.optString("provider", "");
            if (hasMeaningfulText(provider) && !provider.contains("（")) {
                candidate.put("provider", provider + ("server".equals(sourceRegion) ? "（服务端）" : "（安卓直查）"));
            }
        } catch (Exception ignored) {
        }
    }

    private String[] splitProviderRegionText(String value) {
        String text = value == null ? "" : value.trim().replace(',', ' ').replace('，', ' ').replace('|', ' ');
        String[] fields = text.split("\\s+");
        if (fields.length >= 3 && isProviderCountryToken(fields[0])) {
            StringBuilder city = new StringBuilder(fields[2]);
            for (int index = 3; index < fields.length; index += 1) {
                city.append(' ').append(fields[index]);
            }
            return new String[] {fields[0], fields[1], city.toString()};
        }
        String country = "";
        String rest = text;
        for (String item : new String[] {"中国", "美国", "英国", "日本", "韩国", "加拿大", "法国", "德国"}) {
            if (rest.startsWith(item)) {
                country = item;
                rest = rest.substring(item.length()).trim();
                break;
            }
        }
        int regionEnd = firstPlaceSuffixEnd(rest, new String[] {"特别行政区", "自治区", "省"});
        if (regionEnd < 0) {
            for (String municipality : new String[] {"北京市", "上海市", "天津市", "重庆市"}) {
                if (rest.startsWith(municipality)) {
                    regionEnd = municipality.length();
                    break;
                }
            }
        }
        String region = regionEnd < 0 ? rest : rest.substring(0, regionEnd).trim();
        String remainder = regionEnd < 0 ? "" : rest.substring(regionEnd).trim();
        int cityEnd = firstPlaceSuffixEnd(remainder, new String[] {"自治州", "地区", "市", "盟"});
        String city = cityEnd < 0 ? "" : remainder.substring(0, cityEnd).trim();
        return new String[] {country, region, city};
    }

    private boolean isProviderCountryToken(String value) {
        for (String country : new String[] {"中国", "美国", "英国", "日本", "韩国", "加拿大", "法国", "德国"}) {
            if (country.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private int firstPlaceSuffixEnd(String value, String[] suffixes) {
        int best = -1;
        for (String suffix : suffixes) {
            int index = value.indexOf(suffix);
            int end = index < 0 ? -1 : index + suffix.length();
            if (end >= 0 && (best < 0 || end < best)) {
                best = end;
            }
        }
        return best;
    }

    private void recordIpProviderAttempt(JSONArray attempts, String provider, JSONObject candidate) {
        recordIpProviderAttempt(attempts, provider, candidate, candidate == null ? "no_result" : "");
    }

    private void recordIpProviderAttempt(JSONArray attempts, String provider, JSONObject candidate, String failureReason) {
        if (attempts == null) {
            return;
        }
        try {
            JSONObject attempt = new JSONObject()
                .put("provider", provider == null ? "unknown" : provider)
                .put("status", candidate == null ? "failed" : "success")
                .put("precision", diagnosticPrecisionLabel(candidate));
            if (candidate == null && hasMeaningfulText(failureReason)) {
                attempt.put("reason", failureReason);
            }
            if (candidate != null && hasMeaningfulText(candidate.optString("ip", ""))) {
                attempt.put("ip", candidate.optString("ip", ""));
            }
            attempts.put(attempt);
        } catch (Exception ignored) {
        }
    }

    private String diagnosticFailureReason(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 4; depth += 1, current = current.getCause()) {
            if (current instanceof JsonApiClient.HttpStatusException) {
                String code = ((JsonApiClient.HttpStatusException) current).code;
                return hasMeaningfulText(code) ? code : "upstream_error";
            }
            if (current instanceof SocketTimeoutException) {
                return "timeout";
            }
            if (current instanceof JsonApiClient.RequestCancelledException || current instanceof InterruptedException) {
                return "cancelled";
            }
            if (current instanceof IOException) {
                return "network_error";
            }
        }
        return "invalid_response";
    }

    private void cacheIpGeocode(String ip, JSONObject geo) {
        if (!hasMeaningfulText(ip) || geo == null) {
            return;
        }
        synchronized (ipGeocodeCacheLock) {
            try {
                ipGeocodeCache.put(ip.trim().toLowerCase(Locale.ROOT), new JSONObject(geo.toString()));
                while (ipGeocodeCache.size() > 4) {
                    String oldest = ipGeocodeCache.keySet().iterator().next();
                    ipGeocodeCache.remove(oldest);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private JSONObject cachedIpGeocode(String ip) {
        synchronized (ipGeocodeCacheLock) {
            JSONObject cached = ipGeocodeCache.get(ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT));
            try {
                return cached == null ? null : new JSONObject(cached.toString());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String diagnosticPrecisionLabel(JSONObject candidate) {
        if (candidate == null) {
            return "none";
        }
        if (hasMeaningfulText(candidate.optString("street", ""))
            || hasMeaningfulText(candidate.optString("detail", ""))
            || hasMeaningfulText(candidate.optString("poi", ""))) {
            return "street";
        }
        if (hasMeaningfulText(candidate.optString("district", ""))) {
            return "district";
        }
        if (hasMeaningfulText(candidate.optString("city", ""))) {
            return "city";
        }
        if (hasMeaningfulText(candidate.optString("region", ""))) {
            return "region";
        }
        if (hasMeaningfulText(candidate.optString("country", ""))) {
            return "country";
        }
        return hasGeoCoordinates(candidate) ? "coordinate" : "none";
    }

    private int geocodeIpScore(JSONObject geo) {
        if (geo == null) {
            return -1;
        }
        int score = Math.max(0, addressPrecisionScore(geo)) * 10;
        if (!geo.optString("country", "").isEmpty()) score += 1;
        if (!geo.optString("region", "").isEmpty()) score += 2;
        if (!geo.optString("city", "").isEmpty()) score += 8;
        if (!geo.optString("district", "").isEmpty()) score += 12;
        if (!geo.optString("street", "").isEmpty()) score += 16;
        if (!geo.optString("detail", "").isEmpty() || !geo.optString("poi", "").isEmpty()) score += 20;
        double latitude = geo.optDouble("latitude", 0);
        double longitude = geo.optDouble("longitude", 0);
        if (latitude != 0 || longitude != 0) score += 3;
        return score;
    }

    private void copyAddressDetailFields(JSONObject from, JSONObject to) throws Exception {
        if (from == null || to == null) {
            return;
        }
        for (String field : new String[] {"district", "street", "detail", "poi"}) {
            String value = from.optString(field, "");
            if (!value.isEmpty()) {
                to.put(field, value);
            }
        }
    }

    private void copyIpNetworkFields(JSONObject from, JSONObject to) throws Exception {
        if (from == null || to == null) {
            return;
        }
        for (String field : new String[] {"asn", "isp", "org", "carrier"}) {
            String value = from.optString(field, "");
            if (!value.isEmpty()) {
                to.put(field, value);
            }
        }
        if (from.optBoolean("mobile_network", false)) {
            to.put("mobile_network", true);
        }
    }

    private JSONObject geocodeIpByMeituan(String ip, AtomicReference<String> failureReason) {
        try {
            String url = "https://apimobile.meituan.com/locate/v2/ip/loc?rgeo=true&ip=" + urlEncode(ip);
            JSONObject response = requestDiagnosticOpenJson(url);
            JSONObject data = response.optJSONObject("data");
            if (data == null) {
                data = response;
            }
            String country = firstText(data.optString("country", ""), "中国");
            String region = firstText(data.optString("province", ""), data.optString("region", ""));
            String city = normalizeCityPart(firstText(data.optString("city", ""), data.optString("openCityName", "")));
            String district = data.optString("district", "");
            String detail = firstText(data.optString("detail", ""), data.optString("address", ""), data.optString("name", ""));
            String address = composeAddress(country, region, city, district, detail);
            if (address.isEmpty() || "中国".equals(address)) {
                failureReason.set("no_result");
                return null;
            }
            JSONObject geo = new JSONObject()
                .put("provider", "美团")
                .put("address", address)
                .put("country", country)
                .put("region", region)
                .put("city", city)
                .put("district", district)
                .put("detail", detail);
            if (data.has("lat") && data.has("lng")) {
                geo.put("latitude", data.optDouble("lat", 0)).put("longitude", data.optDouble("lng", 0));
            }
            return geo;
        } catch (Exception exception) {
            failureReason.set(diagnosticFailureReason(exception));
            return null;
        }
    }

    private JSONObject geocodeIpByIpInfo(String ip, AtomicReference<String> failureReason) {
        try {
            JSONObject response = postDiagnosticJson(ApiPaths.IPINFO_LITE, new JSONObject().put("ip", ip));
            String country = response.optString("country", "");
            String region = response.optString("region", "");
            String city = normalizeCityPart(response.optString("city", ""));
            String address = composeAddress(country, region, city);
            return new JSONObject()
                .put("provider", response.optString("provider", "IPinfo Lite"))
                .put("address", address.isEmpty() ? ip : address)
                .put("country", country)
                .put("region", region)
                .put("city", city)
                .put("latitude", response.optDouble("latitude", 0))
                .put("longitude", response.optDouble("longitude", 0));
        } catch (Exception exception) {
            failureReason.set(diagnosticFailureReason(exception));
            return null;
        }
    }

    private JSONObject requestOpenJson(String target) throws Exception {
        return API_CLIENT.getOpen(target);
    }

    private JSONObject requestDiagnosticOpenJson(String target) throws Exception {
        return DIAGNOSTIC_API_CLIENT.getOpen(target);
    }

    private JSONObject postDiagnosticJson(String endpoint, JSONObject payload) throws Exception {
        String target = endpoint.startsWith("http") ? endpoint : serverUrl() + endpoint;
        return DIAGNOSTIC_API_CLIENT.post(target, payload, cookieHeader(), this::mergeCookieHeader);
    }

    private JSONObject addressSource(String type, String name, String provider, String source, String address, String city, String region, String country, double latitude, double longitude) throws Exception {
        return new JSONObject()
            .put("type", type)
            .put("name", name)
            .put("provider", provider)
            .put("source", source)
            .put("address", address)
            .put("city", city)
            .put("region", region)
            .put("country", country)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("domestic_source", true);
    }

    private boolean isUsefulAddressSource(JSONObject source, JSONObject fallback) {
        if (source == null) {
            return false;
        }
        String address = source.optString("address", "");
        return !address.isEmpty() && !address.equals(fallback.optString("address", ""));
    }

    private int addressProviderPriority(String provider) {
        String value = provider == null ? "" : provider.toLowerCase(java.util.Locale.US);
        if (value.contains("高德") || value.contains("amap") || value.contains("gaode")) {
            return 0;
        }
        if (value.contains("美团") || value.contains("meituan")) {
            return 1;
        }
        return 2;
    }

    private int addressPrecisionScore(JSONObject source) {
        return AddressPrecisionPolicy.score(
            source.optString("country", ""),
            source.optString("region", ""),
            source.optString("city", ""),
            source.optString("district", ""),
            source.optString("street", ""),
            cleanupComposedAddress(source.optString("address", "")),
            source.optString("detail", ""),
            source.optString("poi", "")
        );
    }

    private String composeAddress(String... parts) {
        List<String> selected = new ArrayList<>();
        for (String part : parts) {
            String text = normalizeAddressPart(part);
            if (text.isEmpty() || "0".equals(text)) {
                continue;
            }
            String key = text.replaceAll("\\s+", "");
            boolean skip = false;
            for (String existing : selected) {
                String existingKey = existing.replaceAll("\\s+", "");
                if (existingKey.equals(key) || existingKey.contains(key)) {
                    skip = true;
                    break;
                }
            }
            if (skip) {
                continue;
            }
            for (int index = selected.size() - 1; index >= 0; index -= 1) {
                String existingKey = selected.get(index).replaceAll("\\s+", "");
                if (key.contains(existingKey)) {
                    selected.remove(index);
                }
            }
            selected.add(text);
        }
        StringBuilder builder = new StringBuilder();
        for (String item : selected) {
            builder.append(item);
        }
        return cleanupComposedAddress(builder.toString());
    }

    private String normalizeAddressPart(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "";
        }
        text = text.replaceAll("\\s+", "");
        text = text.replace("城市：", "").replace("来源：", "");
        text = text.replace("街道县", "街道").replace("街道区", "街道");
        if (text.length() <= 8 && text.matches(".*[\u4e00-\u9fa5]区街道$")) {
            text = text.replace("区街道", "街道");
        }
        return text;
    }

    private String normalizeCityPart(String value) {
        String text = normalizeAddressPart(value);
        if (text.isEmpty() || text.matches(".*(市|盟|自治州|地区|区|县|旗|省|特别行政区)$")) {
            return text;
        }
        if (text.matches("^(香港|澳门|台湾)$")) {
            return text;
        }
        if (text.matches("^[\u4e00-\u9fa5]{2,8}$")) {
            return text + "市";
        }
        return text;
    }

    private String cleanupComposedAddress(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "";
        }
        text = text.replaceAll("\\s+", "");
        text = text.replace("街道县", "街道").replace("街道区", "街道");
        String previous;
        do {
            previous = text;
            text = text.replaceAll("([\u4e00-\u9fa5A-Za-z0-9]{2,16})\\1", "$1");
        } while (!previous.equals(text));
        return text;
    }

    private String firstText(String... values) {
        for (String value : values) {
            String text = value == null ? "" : value.trim();
            if (!text.isEmpty() && !"0".equals(text)) {
                return text;
            }
        }
        return "";
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
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(delimiter);
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private String formatMeituanCoordinate(double value) {
        return String.format(java.util.Locale.US, "%.4f", value);
    }


    private JSONObject buildDeviceEnvironmentReport(boolean includeInstalledApps) {
        JSONObject report = new JSONObject();
        try {
            report.put("manufacturer", Build.MANUFACTURER);
            report.put("brand", Build.BRAND);
            report.put("model", Build.MODEL);
            report.put("device", Build.DEVICE);
            report.put("product", Build.PRODUCT);
            report.put("board", Build.BOARD);
            report.put("hardware", Build.HARDWARE);
            report.put("fingerprint", Build.FINGERPRINT);
            report.put("supported_abis", supportedAbis());
            report.put("android_release", Build.VERSION.RELEASE);
            report.put("android_sdk", Build.VERSION.SDK_INT);
            report.put("locale", Locale.getDefault().toLanguageTag());
            report.put("timezone", TimeZone.getDefault().getID());
            report.put("app_version_name", APP_VERSION_NAME);
            report.put("app_version_code", APP_VERSION_CODE);
            report.put("package_name", getPackageName());
            report.put("installed_package_count", installedPackageCount());
            addScreenInfo(report);
            report.put("adb_enabled", isAdbEnabled());
            report.put("root_detected", isRootLikely());
            report.put("mock_location_risk", hasMockLocationRisk());
            report.put("fake_location_detected", hasSuspiciousPackage("fakegps", "mocklocation", "mock.location"));
            report.put("reqable_detected", hasSuspiciousPackage("reqable"));
            report.put("accessibility_risk", hasAccessibilityRisk());
            report.put("battery_optimization_ignored", isIgnoringBatteryOptimizations());
            addMemoryAndStorage(report);
            JSONArray suspiciousPackages = suspiciousPackages();
            report.put("suspicious_packages", suspiciousPackages);
            if (includeInstalledApps) {
                report.put("installed_apps", installedAppsSummary());
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
        return report;
    }

    private void uploadLoginDeviceReport() {
        if (currentUser == null) {
            return;
        }
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("force", true)
                    .put("report", buildDeviceEnvironmentReport(false));
                postJson(ApiPaths.DEVICE_REPORT, payload);
            } catch (Exception exception) {
                Log.w(TAG, "Login device report failed: " + exception.getMessage());
            }
        });
    }

    private void uploadEnvironmentReport(boolean includeInstalledApps, boolean force) {
        uploadEnvironmentReport(includeInstalledApps, force, false);
    }

    private void uploadEnvironmentReport(boolean includeInstalledApps, boolean force, boolean showResult) {
        uploadEnvironmentReport(includeInstalledApps, force, showResult, false);
    }

    private void uploadEnvironmentReport(boolean includeInstalledApps, boolean force, boolean showResult, boolean markDailyUpload) {
        if (currentUser == null || !currentUser.optBoolean("environment_data_consent", false)) {
            if (showResult) {
                setStatus("环境上报未开启，请先保存环境数据设置。");
            }
            return;
        }
        if (environmentReportUploading) {
            if (showResult) {
                setStatus("环境信息正在上报，请稍候。");
            }
            return;
        }
        environmentReportUploading = true;
        if (showResult) {
            setStatus("正在上报环境信息");
        }
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("force", force)
                    .put("report", buildDeviceEnvironmentReport(includeInstalledApps));
                postJson(ApiPaths.ENVIRONMENT_REPORT, payload);
                if (markDailyUpload) {
                    markEnvironmentReportUploaded();
                }
                if (showResult) {
                    runUi(() -> setStatus("环境信息已上报。"));
                }
            } catch (Exception exception) {
                Log.w(TAG, "Environment report failed: " + exception.getMessage());
                if (showResult) {
                    runUi(() -> setStatus("环境信息上报失败：" + exception.getMessage()));
                }
            } finally {
                environmentReportUploading = false;
            }
        });
    }

    private void uploadDailyEnvironmentReportIfDue() {
        if (currentUser == null || !currentUser.optBoolean("environment_data_consent", false)) {
            return;
        }
        long lastUploadAt = prefs().getLong(environmentReportLastUploadKey(), 0L);
        long now = System.currentTimeMillis();
        if (lastUploadAt > 0L && now - lastUploadAt < ENVIRONMENT_REPORT_INTERVAL_MS) {
            return;
        }
        uploadEnvironmentReport(true, false, false, true);
    }

    private void markEnvironmentReportUploaded() {
        prefs().edit()
            .putLong(environmentReportLastUploadKey(), System.currentTimeMillis())
            .apply();
    }

    private String environmentReportLastUploadKey() {
        int userId = currentUser == null ? 0 : currentUser.optInt("id", 0);
        return KEY_ENVIRONMENT_REPORT_LAST_UPLOAD_PREFIX + userId;
    }


    private boolean isAdbEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasAccessibilityRisk() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled == 1
                && AccessibilityKeepAlivePolicy.hasOtherEnabledService(services, getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasMockLocationRisk() {
        try {
            return "1".equals(Settings.Secure.getString(getContentResolver(), "mock_location"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isRootLikely() {
        String[] paths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return hasSuspiciousPackage("magisk", "supersu", "kingroot");
    }

    private boolean hasSuspiciousPackage(String... needles) {
        try {
            List<PackageInfo> packages = getPackageManager().getInstalledPackages(0);
            for (PackageInfo packageInfo : packages) {
                String packageName = packageInfo.packageName == null ? "" : packageInfo.packageName.toLowerCase(java.util.Locale.US);
                for (String needle : needles) {
                    if (!needle.isEmpty() && packageName.contains(needle.toLowerCase(java.util.Locale.US))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep best-effort result.
        }
        return false;
    }

    private JSONArray suspiciousPackages() {
        JSONArray matches = new JSONArray();
        String[] needles = {
            "reqable",
            "httpcanary",
            "charles",
            "fiddler",
            "magisk",
            "supersu",
            "kingroot",
            "fakegps",
            "mocklocation",
            "xposed"
        };
        try {
            List<PackageInfo> packages = getPackageManager().getInstalledPackages(0);
            for (PackageInfo packageInfo : packages) {
                String packageName = packageInfo.packageName == null ? "" : packageInfo.packageName.toLowerCase(java.util.Locale.US);
                for (String needle : needles) {
                    if (packageName.contains(needle)) {
                        matches.put(packageInfo.packageName);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep best-effort list.
        }
        return matches;
    }

    private JSONArray installedAppsSummary() {
        JSONArray apps = new JSONArray();
        try {
            List<PackageInfo> packages = getPackageManager().getInstalledPackages(0);
            int limit = Math.min(packages.size(), 300);
            for (int index = 0; index < limit; index += 1) {
                PackageInfo packageInfo = packages.get(index);
                JSONObject app = new JSONObject();
                ApplicationInfo applicationInfo = packageInfo.applicationInfo;
                app.put("package_name", packageInfo.packageName);
                app.put("version_name", packageInfo.versionName == null ? "" : packageInfo.versionName);
                app.put("system", applicationInfo != null && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                if (applicationInfo != null) {
                    CharSequence label = getPackageManager().getApplicationLabel(applicationInfo);
                    app.put("label", label == null ? "" : label.toString());
                }
                apps.put(app);
            }
        } catch (Exception ignored) {
            // Keep best-effort list.
        }
        return apps;
    }

    private JSONArray supportedAbis() {
        JSONArray values = new JSONArray();
        try {
            for (String abi : Build.SUPPORTED_ABIS) {
                values.put(abi);
            }
        } catch (Exception ignored) {
        }
        return values;
    }

    private int installedPackageCount() {
        try {
            return getPackageManager().getInstalledPackages(0).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void addScreenInfo(JSONObject report) {
        try {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            report.put("screen_width_px", metrics.widthPixels);
            report.put("screen_height_px", metrics.heightPixels);
            report.put("screen_density_dpi", metrics.densityDpi);
            report.put("screen_density", metrics.density);
        } catch (Exception ignored) {
        }
    }

    private void addMemoryAndStorage(JSONObject report) {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            if (manager != null) {
                manager.getMemoryInfo(memoryInfo);
                report.put("memory_total_bytes", memoryInfo.totalMem);
                report.put("memory_available_bytes", memoryInfo.availMem);
                report.put("memory_low", memoryInfo.lowMemory);
            }
        } catch (Exception ignored) {
        }

        try {
            StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            report.put("storage_total_bytes", statFs.getTotalBytes());
            report.put("storage_available_bytes", statFs.getAvailableBytes());
        } catch (Exception ignored) {
        }
    }

    private JSONObject postLocationReport(String groupName, JSONObject payload) throws Exception {
        JSONObject encryptedPayload = null;
        if (groupUsesP2P(groupName)) {
            encryptedPayload = P2PCryptoSupport.encryptedReportOrNull(this::postReportJson, this, groupName, payload);
        }
        return postReportJson(ApiPaths.REPORT_LOCATION, encryptedPayload == null ? payload : encryptedPayload);
    }

    private void copyProviderAttempts(JSONObject from, JSONObject to) throws Exception {
        if (from == null || to == null) {
            return;
        }
        JSONArray attempts = from.optJSONArray("provider_attempts");
        if (attempts != null) {
            to.put("provider_attempts", new JSONArray(attempts.toString()));
        }
    }

    private boolean groupUsesP2P(String groupName) {
        JSONArray groups = currentUser == null ? null : currentUser.optJSONArray("groups");
        if (groups == null || groupName == null) {
            return false;
        }
        for (int index = 0; index < groups.length(); index += 1) {
            JSONObject group = groups.optJSONObject(index);
            if (group != null && groupName.equals(group.optString("group_name", ""))) {
                return group.optBoolean("p2p_enabled", false);
            }
        }
        return false;
    }

    private JSONObject postReportJson(String endpoint, JSONObject payload) throws Exception {
        String target = endpoint.startsWith("http") ? endpoint : serverUrl() + endpoint;
        return REPORT_API_CLIENT.post(target, payload, cookieHeader(), this::mergeCookieHeader);
    }

    private boolean finishReport(long attemptToken, String message) {
        if (!reportAttemptGate.finish(attemptToken)) {
            return false;
        }
        long elapsedMs = Math.max(0L, android.os.SystemClock.elapsedRealtime() - reportStartedAtElapsedMs);
        reportStartedAtElapsedMs = 0L;
        Log.i(TAG, "PERF_LOCATION_REPORT_MS=" + elapsedMs);
        if (reportWatchdog != null) {
            mainHandler.removeCallbacks(reportWatchdog);
            reportWatchdog = null;
        }
        clearReportLocationListener(attemptToken);
        reporting = false;
        syncReportButtonState();
        if (message != null && !message.trim().isEmpty()) {
            setStatus(message.trim());
        }
        return true;
    }

    private void cancelActiveReportForBackground() {
        long attemptToken = reportAttemptGate.activeToken();
        if (attemptToken > 0L) {
            finishReport(attemptToken, "已退出前台，本次位置上报已取消。");
        }
    }

    private String safeThrowableMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message.trim();
    }

    private void logout() {
        String sessionCookie = cookieHeader();
        clearStoredSessionState();
        runBackground(() -> {
            try {
                API_CLIENT.post(serverUrl() + ApiPaths.LOGOUT, new JSONObject(), sessionCookie, null);
            } catch (Exception ignored) {
                // Local logout is enough if the network is unavailable.
            }
        });
        resetLoginScreenProbe();
        showLoginWithMessage("已退出登录。");
    }

    private void clearStoredSessionState() {
        stopEventStream();
        prefs().edit()
            .remove(KEY_SESSION_COOKIE)
            .remove(KEY_USER_ROLE)
            .remove(KEY_GROUP_NAME)
            .remove(KEY_GROUP_SESSIONS)
            .apply();
        currentUser = null;
        selectedGroupName = "";
        stopKeepAliveService();
    }

    private JSONObject getJson(String endpoint) throws Exception {
        return requestJson(endpoint, "GET", null);
    }

    private JSONObject getJson(String endpoint, JsonApiClient.RequestHandle handle) throws Exception {
        return requestJson(endpoint, "GET", null, handle);
    }

    private JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        return requestJson(endpoint, "POST", payload);
    }

    private JSONObject postJson(String endpoint, JSONObject payload, JsonApiClient.RequestHandle handle) throws Exception {
        return requestJson(endpoint, "POST", payload, handle);
    }

    private JSONObject requestJson(String endpoint, String method, JSONObject payload) throws Exception {
        return requestJson(endpoint, method, payload, null);
    }

    private JSONObject requestJson(String endpoint, String method, JSONObject payload, JsonApiClient.RequestHandle handle) throws Exception {
        String target = endpoint.startsWith("http") ? endpoint : serverUrl() + endpoint;
        if ("POST".equals(method)) {
            return API_CLIENT.post(target, payload, cookieHeader(), this::mergeCookieHeader, handle);
        }
        return API_CLIENT.get(target, cookieHeader(), this::mergeCookieHeader, handle);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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

    private void persistUserSession(JSONObject user, int intervalSeconds) {
        JSONObject selectedGroup = selectedGroupForSession(user);
        selectedGroupName = selectedGroup.optString("group_name", user.optString("group_name", selectedGroupName));
        SharedPreferences.Editor editor = prefs().edit()
            .putString(KEY_USER_ROLE, normalizeRole(selectedGroup.optString("role", user.optString("role", ""))))
            .putString(KEY_GROUP_NAME, selectedGroupName)
            .putInt(KEY_REPORT_INTERVAL_SECONDS, Math.max(60, intervalSeconds));

        JSONArray groups = user.optJSONArray("groups");
        if (groups != null) {
            JSONArray sessions = new JSONArray();
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group == null) {
                    continue;
                }
                JSONObject session = new JSONObject();
                putJson(session, "group_name", group.optString("group_name", ""));
                putJson(session, "role", normalizeRole(group.optString("role", "")));
                putJson(session, "continuous", guardianContinuousEnabled(group.optString("group_name", "")));
                sessions.put(session);
            }
            editor.putString(KEY_GROUP_SESSIONS, sessions.toString());
        }
        editor.apply();
    }

    private JSONObject selectedGroupForSession(JSONObject user) {
        JSONArray groups = user == null ? null : user.optJSONArray("groups");
        String preferredGroupName = selectedGroupName == null ? "" : selectedGroupName.trim();
        if (preferredGroupName.isEmpty()) {
            preferredGroupName = prefs().getString(KEY_GROUP_NAME, "").trim();
        }
        if (preferredGroupName.isEmpty() && user != null) {
            preferredGroupName = user.optString("group_name", "").trim();
        }

        JSONObject first = null;
        if (groups != null) {
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group == null) {
                    continue;
                }
                if (first == null) {
                    first = group;
                }
                if (!preferredGroupName.isEmpty() && preferredGroupName.equals(group.optString("group_name", ""))) {
                    return group;
                }
            }
        }
        if (first != null) {
            return first;
        }
        JSONObject group = new JSONObject();
        putJson(group, "group_name", user == null ? "" : user.optString("group_name", ""));
        putJson(group, "role", user == null ? "" : user.optString("role", ""));
        return group;
    }

    private void putJson(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception exception) {
            Log.w(TAG, "JSON put failed: " + exception.getMessage());
        }
    }

    private void persistSelectedGroup(JSONObject group) {
        prefs().edit()
            .putString(KEY_GROUP_NAME, group.optString("group_name", ""))
            .putString(KEY_USER_ROLE, normalizeRole(group.optString("role", "")))
            .apply();
        syncKeepAliveService();
    }

    private String safeTokenPrefix(String token) {
        String value = token == null ? "" : token.trim();
        if (value.isEmpty()) {
            return "empty";
        }
        int end = Math.min(16, value.length());
        return value.substring(0, end) + "(len=" + value.length() + ")";
    }

    private boolean guardianContinuousEnabled(String groupName) {
        if (groupName == null || groupName.isEmpty()) {
            return false;
        }
        return prefs().getBoolean("guardian_continuous_reporting_" + groupName, false);
    }

    private void requestStartupPermissions() {
        if (requestNotificationPermissionIfNeeded()) {
            return;
        }
        if (requestForegroundLocationPermissionIfNeeded()) {
            return;
        }
        if (requestBackgroundLocationPermissionIfNeeded()) {
            return;
        }
        requestBatteryOptimizationPermission();
        requestExactAlarmPermission();
    }

    private boolean requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (notificationPermissionRequestInFlight) {
            return true;
        }
        notificationPermissionRequestInFlight = true;
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIFICATION);
        return true;
    }

    private boolean requestForegroundLocationPermissionIfNeeded() {
        if (hasFineLocationPermission()) {
            return false;
        }
        if (hasCoarseLocationPermission()) {
            return showPreciseLocationPromptIfNeeded();
        }
        if (locationPermissionRequestInFlight) {
            return true;
        }
        locationPermissionRequestInFlight = true;
        requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_LOCATION);
        return true;
    }

    private boolean showPreciseLocationPromptIfNeeded() {
        if (prefs().getBoolean(KEY_PRECISE_LOCATION_PROMPT_SHOWN, false)) {
            return false;
        }
        prefs().edit().putBoolean(KEY_PRECISE_LOCATION_PROMPT_SHOWN, true).apply();
        showPopupDialog(
            "\u9700\u8981\u7cbe\u786e\u5b9a\u4f4d",
            new String[][] {
                new String[] {"\u6743\u9650\u8bf4\u660e", "\u5f53\u524d\u53ef\u80fd\u53ea\u6388\u6743\u4e86\u6a21\u7cca\u5b9a\u4f4d\uff0c\u4f1a\u5f71\u54cd\u4e0a\u62a5\u4f4d\u7f6e\u548c\u5730\u56fe\u7cbe\u5ea6\u3002\u8bf7\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u5f00\u542f\u201c\u7cbe\u786e\u4f4d\u7f6e\u201d\uff0c\u5e76\u5141\u8bb8\u5b9a\u4f4d\u6743\u9650\u3002"}
            },
            "\u53bb\u8bbe\u7f6e",
            () -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            },
            "\u7a0d\u540e"
        );
        return true;
    }

    private boolean requestBackgroundLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!hasFineLocationPermission()) {
            return false;
        }
        if (backgroundLocationPermissionRequestInFlight) {
            return true;
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            backgroundLocationPermissionRequestInFlight = true;
            requestPermissions(new String[] { Manifest.permission.ACCESS_BACKGROUND_LOCATION }, REQUEST_BACKGROUND_LOCATION);
            return true;
        }
        if (prefs().getBoolean(KEY_BACKGROUND_LOCATION_PROMPT_SHOWN, false)) {
            return false;
        }
        prefs().edit().putBoolean(KEY_BACKGROUND_LOCATION_PROMPT_SHOWN, true).apply();
        showPopupDialog(
            "\u5141\u8bb8\u540e\u53f0\u5b9a\u4f4d",
            new String[][] {
                new String[] {"\u6743\u9650\u8bf4\u660e", "\u6301\u7eed\u4e0a\u62a5\u9700\u8981\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u628a\u5b9a\u4f4d\u6743\u9650\u6539\u4e3a\u201c\u59cb\u7ec8\u5141\u8bb8\u201d\u3002"}
            },
            "\u53bb\u8bbe\u7f6e",
            () -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            },
            "\u7a0d\u540e"
        );
        return true;
    }

    private void requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || batteryOptimizationPromptShown) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
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

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || exactAlarmPromptShown) {
            return;
        }
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null || alarmManager.canScheduleExactAlarms()) {
                return;
            }
            exactAlarmPromptShown = true;
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception exception) {
            Log.w(TAG, "Exact alarm permission request failed: " + exception.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION) {
            notificationPermissionRequestInFlight = false;
            requestStartupPermissions();
        } else if (requestCode == REQUEST_LOCATION) {
            locationPermissionRequestInFlight = false;
            requestStartupPermissions();
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            backgroundLocationPermissionRequestInFlight = false;
            requestBatteryOptimizationPermission();
            requestExactAlarmPermission();
        }
        syncKeepAliveService();
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCoarseLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void syncKeepAliveService() {
        backgroundLocationController.sync(this, new BackgroundLocationController.State() {
            @Override
            public String role() {
                return prefs().getString(KEY_USER_ROLE, "");
            }

            @Override
            public String groupName() {
                return prefs().getString(KEY_GROUP_NAME, "");
            }

            @Override
            public boolean guardianContinuousEnabled(String groupName) {
                return MainActivity.this.guardianContinuousEnabled(groupName);
            }
        });
    }

    private void stopKeepAliveService() {
        backgroundLocationController.stop(this);
    }

    private void showUpdateRequired(String versionName, String apkUrl) {
        LinearLayout card = screen("需要更新");
        LinearLayout message = simpleSummaryPanel("更新说明", "检测到新版位置 " + versionName + "，App 会自动下载，下载完成后请确认安装。");
        Button retry = primaryButton("重新下载更新");
        Button open = secondaryButton("浏览器下载");
        retry.setOnClickListener(view -> downloadAppUpdate(apkUrl));
        open.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))));
        card.addView(message, blockParams(16));
        card.addView(retry, blockParams(10));
        card.addView(open, blockParams(0));
        setScreen(card, true);
        downloadAppUpdate(apkUrl);
    }

    private void downloadAppUpdate(String apkUrl) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("位置更新");
            request.setDescription("正在下载 location-release.apk");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            prepareUpdateApkFile();
            request.setDestinationUri(Uri.fromFile(updateApkFile()));
            request.addRequestHeader("User-Agent", "loc-app/" + APP_VERSION_NAME);
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
            setStatus("新版 APK 已开始下载，完成后会自动打开安装确认。");
        } catch (Exception exception) {
            setStatus("自动下载更新失败：" + exception.getMessage());
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
            if (status.startsWith("failed:")) {
                prefs().edit().remove(KEY_ACTIVE_UPDATE_DOWNLOAD_ID).apply();
                setStatus("APK 下载失败，错误码：" + status.substring("failed:".length()));
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
            setStatus("自动拉起安装失败：" + exception.getMessage());
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
                    throw new IllegalStateException("APK 下载失败，错误码：" + status.substring("failed:".length()));
                }
                throw new IllegalStateException("APK 仍在下载中，请稍后再试。");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                pendingInstallDownloadId = downloadId;
                prefs().edit()
                    .putLong(KEY_PENDING_UPDATE_INSTALL_ID, downloadId)
                    .remove(KEY_ACTIVE_UPDATE_DOWNLOAD_ID)
                    .apply();
                installingDownloadId = -1L;
                setStatus("APK 已下载，请允许本应用安装未知应用后返回安装。");
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
            install.setClipData(ClipData.newRawUri(UPDATE_APK_NAME, apkUri));
            try {
                startActivity(install);
            } catch (Exception firstException) {
                Intent fallback = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                fallback.setClipData(ClipData.newRawUri(UPDATE_APK_NAME, apkUri));
                startActivity(fallback);
            }
            pendingInstallDownloadId = -1L;
            prefs().edit()
                .remove(KEY_PENDING_UPDATE_INSTALL_ID)
                .remove(KEY_ACTIVE_UPDATE_DOWNLOAD_ID)
                .apply();
            setStatus("下载完成，请确认安装新版本。");
        } catch (Exception exception) {
            installingDownloadId = -1L;
            setStatus("打开安装失败：" + exception.getMessage());
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
        return new File(directory, UPDATE_APK_NAME);
    }

    private Uri updateApkUri() {
        return Uri.parse("content://" + getPackageName() + ".apkprovider/" + UPDATE_APK_NAME);
    }

    private String downloadStatus(DownloadManager manager, long downloadId) {
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId));
            if (cursor == null || !cursor.moveToFirst()) {
                return "missing";
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                return "success";
            }
            if (status == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                return "failed:" + reason;
            }
            return "pending";
        } catch (Exception exception) {
            return "failed:" + exception.getMessage();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        frontendRuntime.onResume();
        startEventStreamIfNeeded();
        syncReportButtonState();
        if (accessibilitySettingsLaunched) {
            accessibilitySettingsLaunched = false;
            syncKeepAliveService();
            if (currentUser != null && currentTab == TAB_MINE) {
                showSettings();
            }
        }
        if (restoreHomeMapOnResume && currentUser != null && currentTab == TAB_POSITION && content != null) {
            restoreHomeMapOnResume = false;
            refreshLocations();
        }
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
        cancelActiveReportForBackground();
        restoreHomeMapOnResume = restoreHomeMapOnResume
            || (currentUser != null && currentTab == TAB_POSITION && homeMapWebView != null);
        frontendRuntime.onStop();
        homeMapWebView = null;
        eventStreamWebView = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelActiveReportForBackground();
        invalidateScreenRequests();
        backgroundExecutor.shutdownNow();
        addressProbeExecutor.shutdownNow();
        ipProviderExecutor.shutdownNow();
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
            Log.w(TAG, "Cache trim failed: " + exception.getMessage());
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
        if (!canLoadForegroundWebView() && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            destroyManagedWebViews();
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runStartupMaintenance();
        }
    }

    @Override
    public void onLowMemory() {
        if (!canLoadForegroundWebView()) {
            destroyManagedWebViews();
        }
        trimAppCaches();
        super.onLowMemory();
    }

    private boolean canLoadForegroundWebView() {
        return frontendRuntime.canLoadForegroundWebView();
    }

    private WebView managedWebView() {
        return frontendRuntime.createManagedWebView();
    }

    private void destroyManagedWebView(WebView webView) {
        if (webView != null && webView == homeMapWebView) {
            homeMapWebView = null;
        }
        frontendRuntime.destroyManagedWebView(webView);
    }

    private void destroyManagedWebViews() {
        frontendRuntime.destroyManagedWebViews();
        homeMapWebView = null;
        eventStreamWebView = null;
    }

    private void stopEventStream() {
        WebView stream = eventStreamWebView;
        eventStreamWebView = null;
        if (stream != null) {
            destroyManagedWebView(stream);
        }
    }

    private void attachEventStreamToContent() {
        WebView stream = eventStreamWebView;
        if (stream == null || content == null) {
            return;
        }
        if (stream.getParent() instanceof ViewGroup) {
            ((ViewGroup) stream.getParent()).removeView(stream);
        }
        stream.setAlpha(0f);
        stream.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        content.addView(stream, new LinearLayout.LayoutParams(dp(1), dp(1)));
    }

    private boolean handleWebViewRendererGone(WebView webView, String message) {
        destroyManagedWebView(webView);
        if (message != null && !message.trim().isEmpty()) {
            setStatus(message);
        }
        return true;
    }


    private String homeTitle() {
        String role = currentUser == null ? "" : currentUser.optString("role_label", "");
        return role.isEmpty() ? "位置" : role;
    }

    private String compactUserDisplayName(JSONObject user) {
        if (user == null) {
            return "未登录";
        }
        String displayName = user.optString("display_name", "").trim();
        String username = user.optString("username", "").trim();
        String group = selectedGroupName.isEmpty() ? user.optString("group_name", "").trim() : selectedGroupName;
        return joinTexts(" / ",
            displayName.isEmpty() ? username : displayName,
            !displayName.isEmpty() && !username.isEmpty() && !username.equals(displayName) ? username : "",
            group
        );
    }

    private String homeHeaderLine(JSONObject user) {
        if (user == null) {
            return "未登录";
        }
        String displayName = user.optString("display_name", "").trim();
        String username = user.optString("username", "").trim();
        String group = selectedGroupName.isEmpty() ? user.optString("group_name", "").trim() : selectedGroupName;

        List<String> parts = new ArrayList<>();
        if (!displayName.isEmpty()) {
            parts.add(displayName);
        } else if (!username.isEmpty()) {
            parts.add(username);
        }
        if (!displayName.isEmpty() && !username.isEmpty() && !username.equals(displayName)) {
            parts.add(username);
        }
        if (!group.isEmpty()) {
            parts.add(group);
        }
        return joinTexts(" / ", parts.toArray(new String[0]));
    }

    private String homeScreenTitle(JSONObject user) {
        if (user == null) {
            return "位置";
        }
        return firstText(user.optString("role_label", "").trim(), "位置");
    }

    private String userDisplayName(JSONObject user) {
        if (user == null) {
            return "未登录";
        }
        String displayName = user.optString("display_name", "").trim();
        String username = user.optString("username", "").trim();
        String role = user.optString("role_label", "").trim();
        String primary = displayName.isEmpty() ? username : displayName;
        String secondary = !displayName.isEmpty() && !username.isEmpty() && !username.equals(displayName)
            ? username
            : "";
        return joinTexts(" / ", primary, secondary, role);
    }

    private LinearLayout screen(String titleText) {
        return screenWithAction(titleText, "", null);
    }

    private boolean denseUi() {
        return currentUser != null;
    }

    private LinearLayout screenWithAction(String titleText, View actionView) {
        return screenWithAction(titleText, "", actionView);
    }

    private LinearLayout screenWithAction(String titleText, String subtitleText, View actionView) {
        boolean dense = denseUi();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dense ? 0 : uiStyle.screenPadding(dense);
        card.setPadding(padding, padding, padding, padding);
        if (dense) {
            card.setBackgroundColor(Color.TRANSPARENT);
        } else {
            card.setBackground(cardBackground());
        }
        if (!dense && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(2));
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleBlockParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(uiStyle.screenTitleSize(dense));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(actionView == null ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        title.setTextColor(colorText());
        titleBlock.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String subtitle = subtitleText == null ? "" : subtitleText.trim();
        if (!subtitle.isEmpty()) {
            TextView subtitleView = body(subtitle);
            subtitleView.setTextColor(colorMuted());
            subtitleView.setTextSize(uiStyle.compactBodyTextSize(dense));
            subtitleView.setLineSpacing(dp(1), 1f);
            subtitleView.setPadding(0, dp(2), 0, 0);
            titleBlock.addView(subtitleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        header.addView(titleBlock, titleBlockParams);
        if (actionView != null) {
            header.addView(actionView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(header, blockParams(dense ? 6 : 8));

        statusView = body("");
        statusView.setTextSize(uiStyle.compactBodyTextSize(dense));
        statusView.setPadding(dp(dense ? 9 : 12), dp(dense ? 5 : 10), dp(dense ? 9 : 12), dp(dense ? 5 : 10));
        statusView.setBackground(dense ? pillBackground() : roundedDrawable(colorAccentMuted(), dp(8)));
        statusView.setVisibility(View.GONE);
        card.addView(statusView, blockParams(dense ? 10 : 16));
        return card;
    }

    private Button announcementIconButton() {
        Button button = secondaryButton("🔔");
        button.setContentDescription("公告");
        button.setText("公告");
        button.setContentDescription("公告");
        button.setTextSize(12);
        button.setMinWidth(0);
        button.setMinimumWidth(dp(50));
        button.setMinHeight(0);
        button.setMinimumHeight(dp(32));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setOnClickListener(view -> showLatestAnnouncementPopup());
        return button;
    }

    private void setScreen(LinearLayout card) {
        setScreen(card, false);
    }

    private void setScreen(LinearLayout card, boolean center) {
        invalidateScreenRequests();
        screenGeneration += 1L;
        content = card;
        attachEventStreamToContent();
        if (center) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
            root.setPadding(dp(20), topSafePadding(), dp(20), dp(20));
            root.setBackgroundColor(colorSurface());
            int minHeight = getResources().getDisplayMetrics().heightPixels - topSafePadding() - dp(40);
            root.setMinimumHeight(Math.max(dp(320), minHeight));
            root.addView(card, centeredScreenCardLayoutParams());
            scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            setContentView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activeScrollView = scroll;
            animateScreen(scroll, true);
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        boolean dense = denseUi();
        root.setPadding(dp(dense ? 14 : 20), topSafePadding(), dp(dense ? 14 : 20), dp(dense ? 12 : 20));
        root.setBackgroundColor(colorSurface());
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activeScrollView = scroll;

        if (currentUser == null) {
            setContentView(scroll);
            animateScreen(scroll, false);
            return;
        }

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(colorSurface());
        frame.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(bottomNavigation(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(frame);
        animateScreen(scroll, false);
    }

    private LinearLayout.LayoutParams centeredScreenCardLayoutParams() {
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(40);
        int preferredWidth = Math.min(dp(392), availableWidth);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(preferredWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
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

    private LinearLayout bottomNavigation() {
        boolean dense = denseUi();
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(
            uiStyle.bottomNavOuterHorizontalPadding(dense),
            dp(3),
            uiStyle.bottomNavOuterHorizontalPadding(dense),
            uiStyle.bottomNavOuterBottomPadding(dense)
        );
        outer.setBackgroundColor(colorSurface());

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(dense ? 4 : 6), dp(dense ? 4 : 6), dp(dense ? 4 : 6), dp(dense ? 4 : 6));
        nav.setBackground(bottomNavBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nav.setElevation(dp(2));
        }

        nav.addView(navButton("⌂", "位置", TAB_POSITION, () -> {
            showHome();
            refreshLocations();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navButton("▦", "家庭组", TAB_GROUPS, this::showGroups), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navButton("☏", "帮助", TAB_HELP, this::showTickets), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navButton("⚙", "我的", TAB_MINE, this::showSettings), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        outer.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private View navButton(String icon, String label, int tab, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        boolean dense = denseUi();
        item.setMinimumHeight(uiStyle.navItemMinHeight(dense));
        item.setPadding(dp(3), uiStyle.navItemVerticalPadding(dense), dp(3), uiStyle.navItemVerticalPadding(dense));
        boolean active = currentTab == tab;
        item.setBackground(active ? navActiveBackground() : transparentButtonBackground());
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> {
            if (action != null) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastNavigationActionAtElapsedMs < 250L) {
                    return;
                }
                lastNavigationActionAtElapsedMs = now;
                animateTap(view);
                view.postDelayed(action, 45);
            }
        });

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(uiStyle.navIconSize(dense, active));
        iconView.setGravity(Gravity.CENTER);
        iconView.setTypeface(Typeface.DEFAULT_BOLD);
        iconView.setTextColor(active ? colorPrimary() : colorMuted());

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(uiStyle.navLabelSize(dense));
        labelView.setGravity(Gravity.CENTER);
        labelView.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        labelView.setTextColor(active ? colorPrimary() : colorMuted());

        item.addView(iconView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.addView(labelView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return item;
    }

    private void showPopupDialog(String title, String[][] sections, String primaryText, Runnable primaryAction, String secondaryText) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(8));
        }

        TextView heading = new TextView(this);
        heading.setText(title == null || title.isEmpty() ? "\u63d0\u793a" : title);
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(colorText());
        heading.setPadding(dp(16), dp(16), dp(16), dp(14));
        card.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(divider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (sections != null) {
            for (String[] section : sections) {
                if (section == null || section.length == 0) {
                    continue;
                }
                LinearLayout sectionCard = dialogSectionCard(section);
                body.addView(sectionCard, blockParams(10));
            }
        }

        final int maxBodyHeight = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.58f), dp(460));
        ScrollView scroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxBodyHeight, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(divider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (secondaryText != null && !secondaryText.isEmpty()) {
            Button secondary = secondaryButton(secondaryText);
            secondary.setOnClickListener(view -> dialog.dismiss());
            actions.addView(secondary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f));
            View spacer = new View(this);
            actions.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
        }
        Button primary = primaryButton(primaryText == null || primaryText.isEmpty() ? "\u5173\u95ed" : primaryText);
        primary.setOnClickListener(view -> {
            dialog.dismiss();
            if (primaryAction != null) {
                primaryAction.run();
            }
        });
        actions.addView(primary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.58f;
            window.setAttributes(params);
        }
        dialog.show();
        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(44), dp(560));
            shownWindow.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            animateDialog(shownWindow.getDecorView());
        }
    }

    private LinearLayout dialogSectionCard(String[] section) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        String titleText = section == null || section.length == 0 ? "" : firstText(section[0]);
        TextView title = sectionHeading(titleText);
        title.setTextColor(colorText());
        title.setPadding(0, 0, 0, 0);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (section != null) {
            for (int index = 1; index < section.length; index += 1) {
                String paragraphText = section[index] == null ? "" : section[index].trim();
                if (paragraphText.isEmpty()) {
                    continue;
                }
                panel.addView(detailDivider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                TextView paragraph = body(paragraphText);
                paragraph.setTextColor(colorMuted());
                paragraph.setLineSpacing(0, 1.65f);
                paragraph.setPadding(0, dp(8), 0, dp(6));
                panel.addView(paragraph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        return panel;
    }

    private TextView sectionHeading(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(colorText());
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private LinearLayout homeOverviewRow(String labelText, String valueText, String actionText, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView label = new TextView(this);
        label.setText(firstText(labelText, "信息"));
        label.setTextColor(colorMuted());
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(12f);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(label, labelParams);

        TextView value = new TextView(this);
        value.setText(firstText(valueText, "未设置"));
        value.setTextColor(colorPrimary());
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setTextSize(12.5f);
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.CENTER);
        value.setPadding(dp(12), dp(8), dp(12), dp(8));
        value.setBackground(pillBackground());
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueParams.setMargins(0, 0, dp(8), 0);
        row.addView(value, valueParams);

        Button actionButton = secondaryButton(firstText(actionText, "打开"));
        actionButton.setTag(VIEW_TAG_DYNAMIC);
        uiStyle.styleHistorySecondaryButton(actionButton, denseUi());
        actionButton.setOnClickListener(view1 -> {
            if (action != null) {
                action.run();
            }
        });
        row.addView(actionButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void showHomeGroupPicker() {
        JSONArray groups = userGroups();
        if (groups.length() <= 1) {
            showGroups();
            return;
        }
        Dialog dialog = choiceDialog("切换家庭组");
        LinearLayout dialogBody = choiceDialogBody(dialog);
        String currentGroup = currentGroupName();
        for (int index = 0; index < groups.length(); index += 1) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) {
                continue;
            }
            String groupName = group.optString("group_name", "");
            String displayName = group.optString("display_name", groupName);
            String roleLabel = group.optString("role_label", "");
            Button button = secondaryButton((groupName.equals(currentGroup) ? "✓ " : "") + firstText(displayName, groupName));
            button.setOnClickListener(view -> {
                dialog.dismiss();
                if (groupName.equals(currentGroup)) {
                    setStatus("当前家庭组：" + firstText(displayName, groupName, "家庭组"));
                    return;
                }
                selectedGroupName = groupName;
                persistSelectedGroup(group);
                setStatus("已切换当前家庭组：" + firstText(displayName, groupName, "家庭组"));
                refreshLocations();
            });
            dialogBody.addView(button, blockParams(roleLabel.isEmpty() ? 8 : 4));
            if (!roleLabel.isEmpty()) {
                TextView role = body(roleLabel);
                role.setTextColor(colorMuted());
                role.setPadding(dp(12), 0, 0, dp(8));
                dialogBody.addView(role, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        showChoiceDialog(dialog, dialogBody);
    }

    private LinearLayout authScreen(String titleText) {
        return screenWithAction(titleText, "", null);
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(colorOutline());
        return view;
    }

    private final class LoadingOrbitView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private float angle;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                angle = (angle + 12f) % 360f;
                invalidate();
                postDelayed(this, 16L);
            }
        };

        LoadingOrbitView(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
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
            setMeasuredDimension(resolveSize(dp(88), widthMeasureSpec), dp(34));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerY = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(colorAccentMuted());
            canvas.drawLine(dp(38), centerY, getWidth() - dp(12), centerY, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colorAccent());
            float dotX = dp(18) + (float) Math.cos(Math.toRadians(angle)) * dp(8);
            float dotY = centerY + (float) Math.sin(Math.toRadians(angle)) * dp(8);
            canvas.drawCircle(dotX, dotY, dp(4), paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(colorAccent());
            arc.set(dp(8), centerY - dp(10), dp(28), centerY + dp(10));
            canvas.drawArc(arc, angle, 82f, false, paint);
        }
    }

    private void showLoading(String text) {
        LinearLayout card = screen("位置");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(new LoadingOrbitView(this), new LinearLayout.LayoutParams(dp(96), dp(34)));
        TextView label = body(text);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setTextColor(colorMuted());
        row.addView(label, blockParams(8));
        card.addView(row, blockParams(0));
        setScreen(card, true);
    }
    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(uiStyle.sectionTitleSize(denseUi()));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(colorText());
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView dynamicSectionTitle(String text) {
        TextView view = sectionTitle(text);
        view.setTag("dynamic");
        return view;
    }
    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(uiStyle.bodyTextSize(denseUi()));
        view.setLineSpacing(dp(2), 1.0f);
        view.setTextColor(colorMuted());
        return view;
    }

    private TextView infoPanel(String text, boolean dynamic) {
        TextView view = body(text);
        view.setTextColor(colorText());
        boolean dense = denseUi();
        view.setPadding(
            uiStyle.infoPanelHorizontalPadding(dense),
            uiStyle.infoPanelVerticalPadding(dense),
            uiStyle.infoPanelHorizontalPadding(dense),
            uiStyle.infoPanelVerticalPadding(dense)
        );
        view.setBackground(panelBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setElevation(dp(0));
        }
        if (dynamic) {
            view.setTag("dynamic");
        }
        return view;
    }

    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        uiStyle.styleInput(view, denseUi());
        return view;
    }

    private EditText multiLineInput(String hint) {
        EditText view = input(hint);
        view.setSingleLine(false);
        view.setMinLines(4);
        view.setGravity(Gravity.TOP | Gravity.START);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return view;
    }

    private TextView compactInfoPanel(String text, boolean dynamic) {
        TextView view = infoPanel(text, dynamic);
        boolean dense = denseUi();
        view.setTextSize(uiStyle.compactBodyTextSize(dense));
        view.setPadding(
            uiStyle.compactPanelHorizontalPadding(dense),
            uiStyle.compactPanelVerticalPadding(dense),
            uiStyle.compactPanelHorizontalPadding(dense),
            uiStyle.compactPanelVerticalPadding(dense)
        );
        return view;
    }

    private LinearLayout detailListPanel(boolean dynamic) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        boolean dense = denseUi();
        panel.setPadding(dp(dense ? 11 : 13), dp(dense ? 6 : 7), dp(dense ? 11 : 13), dp(dense ? 6 : 7));
        panel.setBackground(panelBackground());
        if (dynamic) {
            panel.setTag(VIEW_TAG_DYNAMIC);
        }
        return panel;
    }

    private void addDetailRow(LinearLayout panel, String label, String value) {
        String text = value == null ? "" : value.trim();
        String titleText = label == null ? "" : label.trim();
        if (text.isEmpty()) {
            if (titleText.isEmpty()) {
                return;
            }
        }
        if (panel.getChildCount() > 0) {
            panel.addView(detailDivider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }

        boolean dense = denseUi();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(dense ? 7 : 8), 0, dp(dense ? 7 : 8));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(colorText());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(dense ? 13f : 14f);
        title.setIncludeFontPadding(false);
        row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!text.isEmpty()) {
            TextView body = new TextView(this);
            body.setText(text);
            body.setTextColor(colorMuted());
            body.setTextSize(dense ? 12f : 13f);
            body.setLineSpacing(dp(2), 1f);
            body.setPadding(0, dp(5), 0, 0);
            row.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View detailDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(colorOutline());
        return divider;
    }

    private LinearLayout buttonRow(Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        Object leftTag = left.getTag();
        Object rightTag = right.getTag();
        if (leftTag != null && leftTag.equals(rightTag)) {
            row.setTag(leftTag);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        uiStyle.stylePrimaryButton(button, denseUi());
        decorateButton(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        uiStyle.styleSecondaryButton(button, denseUi());
        decorateButton(button);
        return button;
    }

    private Button historyControlButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        uiStyle.styleHistorySecondaryButton(button, denseUi());
        decorateButton(button);
        return button;
    }

    private int historyRoleColor(String role) {
        if ("monitor".equals(role)) {
            return colorAccent();
        }
        return colorPrimary();
    }

    private GradientDrawable historyCardBackground(boolean selected) {
        return strokedDrawable(
            colorSurfaceContainer(),
            dp(8),
            selected ? colorPrimary() : colorOutline(),
            dp(selected ? 2 : 1)
        );
    }

    private GradientDrawable navActiveBackground() {
        return uiStyle.navActiveBackground();
    }

    private GradientDrawable bottomNavBackground() {
        return uiStyle.bottomNavBackground();
    }

    private GradientDrawable transparentButtonBackground() {
        return uiStyle.transparentButtonBackground();
    }

    private GradientDrawable cardBackground() {
        return uiStyle.cardBackground();
    }

    private GradientDrawable panelBackground() {
        return uiStyle.panelBackground();
    }

    private GradientDrawable pillBackground() {
        return uiStyle.pillBackground();
    }

    private GradientDrawable roundedDrawable(int color, int radius) {
        return uiStyle.roundedDrawable(color, radius);
    }

    private GradientDrawable strokedDrawable(int color, int radius, int strokeColor, int strokeWidth) {
        return uiStyle.strokedDrawable(color, radius, strokeColor, strokeWidth);
    }

    private RippleDrawable rippleDrawable(int color, int radius) {
        return uiStyle.rippleDrawable(color, radius);
    }

    private LinearLayout.LayoutParams blockParams(int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(bottomMarginDp));
        return params;
    }

    private int dp(int value) {
        return uiStyle.dp(value);
    }

    private int colorSurface() {
        return uiStyle.colorSurface();
    }

    private int colorSurfaceContainer() {
        return uiStyle.colorSurfaceContainer();
    }

    private int colorText() {
        return uiStyle.colorText();
    }

    private int colorMuted() {
        return uiStyle.colorMuted();
    }

    private int colorAccent() {
        return uiStyle.colorAccent();
    }

    private int colorAccentMuted() {
        return uiStyle.colorAccentMuted();
    }

    private int colorPrimary() {
        return uiStyle.colorPrimary();
    }

    private int colorOnPrimary() {
        return uiStyle.colorOnPrimary();
    }

    private int colorOutline() {
        return uiStyle.colorOutline();
    }

    private int colorRipple() {
        return uiStyle.colorRipple();
    }

    private boolean isDarkMode() {
        return uiStyle.isDarkMode();
    }

    private boolean isDarkModeFromPreference() {
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

    private void showTransientFeedback(String message) {
        String text = firstText(message, "操作已完成");
        setStatus(text);
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private boolean runBackground(Runnable runnable) {
        final long targetScreenGeneration = screenGeneration;
        final TextView targetStatusView = statusView;
        try {
            backgroundExecutor.execute(() -> {
                try {
                    runnable.run();
                } catch (Throwable throwable) {
                    Log.e(TAG, "Background task failed", throwable);
                    runUi(() -> {
                        if (screenGeneration == targetScreenGeneration && statusView == targetStatusView) {
                            setStatus("后台任务失败：" + exceptionMessage(throwable));
                        }
                    });
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            setStatus("请求过多，请稍候再试。");
            return false;
        }
    }

    private void replaceActiveRequest(
        AtomicReference<JsonApiClient.RequestHandle> target,
        JsonApiClient.RequestHandle next
    ) {
        JsonApiClient.RequestHandle previous = target.getAndSet(next);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void cancelActiveRequest(AtomicReference<JsonApiClient.RequestHandle> target) {
        JsonApiClient.RequestHandle active = target.getAndSet(null);
        if (active != null) {
            active.cancel();
        }
    }

    private void invalidateScreenRequests() {
        locationRefreshGeneration += 1L;
        historyRequestGate.invalidate();
        ticketListRequestGate.invalidate();
        ticketThreadRequestGate.invalidate();
        p2pRequestGate.invalidate();
        cancelActiveRequest(activeLocationRequest);
        cancelActiveRequest(activeHistoryRequest);
        cancelActiveRequest(activeTicketListRequest);
        cancelActiveRequest(activeTicketThreadRequest);
    }

    private boolean isCancelledRequest(Exception exception) {
        return exception instanceof JsonApiClient.RequestCancelledException;
    }

    private boolean beginGroupWrite() {
        if (groupWriteInFlight.compareAndSet(false, true)) {
            return true;
        }
        setStatus("家庭组操作正在提交，请勿重复点击。");
        return false;
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

    private void runUiIfScreenCurrent(long targetScreenGeneration, Runnable runnable) {
        runUi(() -> {
            if (screenGeneration == targetScreenGeneration) {
                runnable.run();
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

    private String getStoredServerUrl() {
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

    private String normalizeRole(String role) {
        String value = role == null ? "" : role.trim();
        return "parent".equals(value) ? "monitor" : value;
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
