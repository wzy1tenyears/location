package com.familylocation.client;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Window;
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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final class ChallengeCancelledException extends Exception {
    }

    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_NOTIFICATION = 1002;
    private static final int REQUEST_BACKGROUND_LOCATION = 1003;
    private static final int APP_VERSION_CODE = 57;
    private static final String APP_VERSION_NAME = "2.0.24";
    private static final String PREFS = "family_location";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_GROUP_NAME = "group_name";
    private static final String KEY_GUARDIAN_CONTINUOUS_REPORTING = "guardian_continuous_reporting";
    private static final String KEY_GROUP_SESSIONS = "group_sessions_json";
    private static final String KEY_CROSS_GROUP_SYNC = "cross_group_sync_json";
    private static final String KEY_REPORT_INTERVAL_SECONDS = "report_interval_seconds";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_PENDING_UPDATE_INSTALL_ID = "pending_update_install_id";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String KEY_SEEN_ANNOUNCEMENT_PREFIX = "announcement_seen_";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String TAG = "FamilyLocationNative";
    private static final String UPDATE_APK_NAME = "location-release.apk";
    private static final long MAX_CACHE_BYTES = 50L * 1024L * 1024L;
    private static final int TAB_POSITION = 0;
    private static final int TAB_GROUPS = 1;
    private static final int TAB_HELP = 2;
    private static final int TAB_MINE = 3;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView statusView;
    private Button reportButton;
    private Button refreshButton;
    private JSONObject currentUser;
    private JSONObject legalDocuments;
    private String selectedGroupName = "";
    private int historyPage = 1;
    private int historyPageSize = 20;
    private int historyMapPageSize = 20;
    private int historyUserId = 0;
    private int currentTab = TAB_POSITION;
    private boolean reporting;
    private long updateDownloadId = -1L;
    private long pendingInstallDownloadId = -1L;
    private long installingDownloadId = -1L;
    private BroadcastReceiver updateReceiver;
    private final List<WebView> managedWebViews = new ArrayList<>();
    private volatile boolean activityForeground;
    private boolean batteryOptimizationPromptShown;
    private boolean exactAlarmPromptShown;
    private volatile int challengeGeneration;
    private volatile boolean challengeCancelled;
    private String loginDraftUsername = "";
    private String loginDraftPassword = "";
    private boolean loginDraftTerms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        runStartupMaintenance();

        String serverUrl = getStoredServerUrl();
        if (serverUrl.isEmpty()) {
            serverUrl = readAssetServerUrl();
        }

        if (serverUrl.isEmpty()) {
            showServerSetup();
            return;
        }

        prefs().edit().putString(KEY_SERVER_URL, normalizeUrl(serverUrl)).apply();
        showLoading("正在检查登录状态");
        checkUpdateThenLoadSession();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(colorSurface());
        window.setNavigationBarColor(colorSurface());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isDarkMode()) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void checkUpdateThenLoadSession() {
        runBackground(() -> {
            try {
                JSONObject update = getJson("api/app_update.php?version_code=" + APP_VERSION_CODE);
                boolean required = update.optBoolean("update_required", false);
                boolean force = update.optBoolean("force_update", true);
                String apkUrl = update.optString("apk_url", "");
                String versionName = update.optString("latest_version_name", "");
                if (required && force && !apkUrl.isEmpty()) {
                    runUi(() -> showUpdateRequired(versionName, apkUrl));
                    return;
                }
            } catch (Exception exception) {
                Log.w(TAG, "Update check failed: " + exception.getMessage());
            }

            loadSession();
        });
    }

    private void loadSession() {
        runBackground(() -> {
            try {
                JSONObject response = getJson("api/me.php");
                JSONObject user = response.optJSONObject("user");
                if (user == null) {
                    runUi(this::showLogin);
                    return;
                }

                currentUser = user;
                persistUserSession(user, response.optInt("report_interval_seconds", 300));
                runUi(this::showHome);
                uploadEnvironmentReport(false, false);
                refreshLocations();
            } catch (Exception exception) {
                runUi(this::showLogin);
            }
        });
    }

    private void showServerSetup() {
        LinearLayout card = screen("服务器地址");
        TextView description = body("填写 HTTPS 服务器地址后继续使用。开发者也可以在 android-client/assets/server-url.txt 预置。");
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
            showLoading("正在检查登录状态");
            checkUpdateThenLoadSession();
        });
        card.addView(description, blockParams(16));
        card.addView(input, blockParams(12));
        card.addView(save, blockParams(0));
        setScreen(card, true);
    }

    private void showLogin() {
        showLoginWithMessage("");
    }

    private void showLoginWithMessage(String messageText) {
        LinearLayout card = screen("登录位置");
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
        card.addView(terms, blockParams(12));
        card.addView(login, blockParams(10));
        card.addView(register, blockParams(0));
        setScreen(card, true);
        setStatus(messageText);
    }

    private void showRegister() {
        LinearLayout card = screen("注册账号");
        EditText username = input("账号：至少 6 位，包含英文和数字");
        EditText displayName = input("显示名称");
        EditText password = input("密码：至少 6 位");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText passwordConfirm = input("再次输入密码");
        passwordConfirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText inviteCode = input("邀请码");
        EditText groupName = input("家庭组名称：创建型邀请码需要填写");
        EditText groupCode = input("家庭组号：加入型邀请码需要填写 6 位小写组号");
        groupCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        CheckBox terms = termsCheckBox();


        Button checkInvite = secondaryButton("检查邀请码");
        checkInvite.setOnClickListener(view -> checkInviteCode(inviteCode.getText().toString(), groupName, groupCode));
        Button submit = primaryButton("完成注册");
        submit.setOnClickListener(view -> register(
            username.getText().toString(),
            displayName.getText().toString(),
            password.getText().toString(),
            passwordConfirm.getText().toString(),
            inviteCode.getText().toString(),
            groupName.getText().toString(),
            groupCode.getText().toString(),
            terms.isChecked()
        ));
        Button back = secondaryButton("返回登录");
        back.setOnClickListener(view -> showLogin());

        card.addView(username, blockParams(12));
        card.addView(displayName, blockParams(12));
        card.addView(password, blockParams(12));
        card.addView(passwordConfirm, blockParams(12));
        card.addView(inviteCode, blockParams(10));
        card.addView(checkInvite, blockParams(12));
        card.addView(groupName, blockParams(12));
        card.addView(groupCode, blockParams(12));
        card.addView(terms, blockParams(12));
        card.addView(submit, blockParams(10));
        card.addView(back, blockParams(0));
        setScreen(card, true);
        maybeFillClipboardInvite(inviteCode, groupName, groupCode);
        if (inviteCode.getText().toString().trim().isEmpty()) {
            setStatus("请先填写邀请码；不确定类型时点“检查邀请码”。");
        }
    }


    private void maybeFillClipboardInvite(EditText inviteCode, EditText groupName, EditText groupCode) {
        String code = inviteCodeFromClipboardText(readClipboardText());
        if (code.isEmpty()) {
            return;
        }
        runBackground(() -> {
            try {
                getJson("api/invite_check.php?code=" + urlEncode(code));
                runUi(() -> {
                    if (!inviteCode.getText().toString().trim().isEmpty()) {
                        return;
                    }
                    inviteCode.setText(code);
                    checkInviteCode(code, groupName, groupCode);
                    showPopupDialog(
                        "检测到邀请码",
                        new String[][] {new String[] {"剪贴板", "剪贴板中存在可用邀请码，已自动填入注册表单。"}},
                        "我知道了",
                        null,
                        null
                    );
                });
            } catch (Exception ignored) {
            }
        });
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
        String text = "\u6211\u5df2\u540c\u610f\u7528\u6237\u534f\u8bae\u3001\u9690\u79c1\u6761\u7ea6\u548c\u7528\u6237\u6570\u636e\u8de8\u5883\u52a0\u5bc6\u4f20\u8f93\u534f\u8bae";
        CheckBox terms = new CheckBox(this);
        SpannableString spannable = new SpannableString(text);
        addLegalLink(spannable, text, "\u7528\u6237\u534f\u8bae", "user_agreement", "\u7528\u6237\u534f\u8bae");
        addLegalLink(spannable, text, "\u9690\u79c1\u6761\u7ea6", "privacy_policy", "\u9690\u79c1\u6761\u7ea6");
        addLegalLink(spannable, text, "\u7528\u6237\u6570\u636e\u8de8\u5883\u52a0\u5bc6\u4f20\u8f93\u534f\u8bae", "cross_border_transfer", "\u7528\u6237\u6570\u636e\u8de8\u5883\u52a0\u5bc6\u4f20\u8f93\u534f\u8bae");
        terms.setText(spannable);
        terms.setMovementMethod(LinkMovementMethod.getInstance());
        terms.setHighlightColor(Color.TRANSPARENT);
        terms.setLinkTextColor(Color.rgb(13, 95, 84));
        terms.setTextColor(colorText());
        return terms;
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
                    JSONObject response = getJson("api/legal_documents.php");
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
                runUi(() -> setStatus("\u52a0\u8f7d\u534f\u8bae\u5931\u8d25\uff1a" + exception.getMessage()));
            }
        });
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

    private void checkInviteCode(String inviteCode, EditText groupName, EditText groupCode) {
        String code = inviteCode.trim();
        if (code.isEmpty()) {
            setStatus("请先输入邀请码。");
            return;
        }

        setStatus("正在检查邀请码");
        runBackground(() -> {
            try {
                JSONObject response = getJson("api/invite_check.php?code=" + urlEncode(code));
                boolean requiresGroupName = response.optBoolean("requires_group_name", false);
                boolean requiresGroupCode = response.optBoolean("requires_group_code", false);
                runUi(() -> {
                    groupName.setEnabled(requiresGroupName);
                    groupCode.setEnabled(requiresGroupCode);
                    if (!requiresGroupName) {
                        groupName.setText("");
                    }
                    if (!requiresGroupCode) {
                        groupCode.setText("");
                    }
                    if (requiresGroupName) {
                        setStatus("邀请码可用：请填写要创建的家庭组名称。");
                    } else if (requiresGroupCode) {
                        setStatus("邀请码可用：请填写 6 位家庭组号。");
                    } else {
                        setStatus("邀请码可用：无需额外填写家庭组信息。");
                    }
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void register(String username, String displayName, String password, String passwordConfirm, String inviteCode, String groupName, String groupCode, boolean termsAccepted) {
        String usernameValue = username.trim();
        String inviteCodeValue = inviteCode.trim();
        if (usernameValue.isEmpty() || password.isEmpty() || passwordConfirm.isEmpty() || inviteCodeValue.isEmpty()) {
            setStatus("请填写账号、密码、确认密码和邀请码。");
            return;
        }
        if (!termsAccepted) {
            setStatus("请先同意协议。");
            return;
        }

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
                    .put("group_name", groupName.trim())
                    .put("group_code", groupCode.trim().toLowerCase(java.util.Locale.US))
                    .put("terms_accepted", true)
                    .put("cross_border_transfer_accepted", true)
                    .put("browser_fingerprint", deviceCookieValue())
                    .put("turnstile_token", challengeToken);
                JSONObject response = postJson("api/register.php", payload);
                JSONObject user = response.optJSONObject("user");
                if (user == null) {
                    throw new IllegalStateException("注册成功但服务器未返回用户信息。");
                }

                currentUser = user;
                persistUserSession(user, response.optInt("report_interval_seconds", 300));
                runUi(this::showHome);
                uploadEnvironmentReport(false, false);
                refreshLocations();
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
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
        setStatus("正在登录");
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
                JSONObject user = response.optJSONObject("user");
                if (user == null) {
                    throw new IllegalStateException("登录成功但服务器未返回用户信息。");
                }

                currentUser = user;
                persistUserSession(user, response.optInt("report_interval_seconds", 300));
                runUi(this::showHome);
                uploadEnvironmentReport(false, false);
                refreshLocations();
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
        showLoginWithMessage("");
    }

    private boolean isChallengeCancelled(int generation) {
        return challengeCancelled || challengeGeneration != generation;
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
        card.addView(infoPanel("请在下方完成 Cloudflare 质询，完成后 App 会自动继续。", false), blockParams(10));
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
        settings.setUserAgentString(settings.getUserAgentString() + " loc-app/" + APP_VERSION_NAME);
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


    private void showHome() {
        currentTab = TAB_POSITION;
        LinearLayout card = screen("位置");
        TextView userLine = compactInfoPanel(compactUserDisplayName(currentUser), false);
        reportButton = primaryButton("上报位置");
        refreshButton = secondaryButton("刷新");
        Button historyButton = secondaryButton("历史位置");
        Button announcementButton = secondaryButton("公告");
        Button crossGroupSyncButton = secondaryButton("跨组同步");
        Button continuousReportButton = secondaryButton(continuousReportButtonText());

        reportButton.setOnClickListener(view -> reportCurrentLocation());
        refreshButton.setOnClickListener(view -> refreshLocations());
        historyButton.setOnClickListener(view -> showHistory());
        announcementButton.setOnClickListener(view -> showAnnouncement());
        crossGroupSyncButton.setOnClickListener(view -> showCrossGroupSync());
        continuousReportButton.setOnClickListener(view -> toggleGuardianContinuousReport());

        boolean guardian = "guardian".equals(currentUserRole());
        card.addView(userLine, blockParams(10));
        card.addView(reportButton, blockParams(8));
        card.addView(buttonRow(refreshButton, historyButton), blockParams(8));
        if (guardian && userGroupCount() > 1) {
            card.addView(buttonRow(continuousReportButton, crossGroupSyncButton), blockParams(8));
            card.addView(announcementButton, blockParams(12));
        } else if (guardian) {
            card.addView(buttonRow(continuousReportButton, announcementButton), blockParams(12));
        } else if (userGroupCount() > 1) {
            card.addView(buttonRow(crossGroupSyncButton, announcementButton), blockParams(12));
        } else {
            card.addView(announcementButton, blockParams(12));
        }
        setScreen(card, false);
        requestStartupPermissions();
        syncKeepAliveService();
        maybeAutoShowAnnouncement();
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

        LinearLayout card = screen("跨组同步");
        TextView description = infoPanel("手动上报时，可把同一定位同时同步到勾选的其他家庭组；自动/持续上报不会跨组同步。", false);
        card.addView(description, blockParams(14));

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
            check.setTextColor(colorText());
            check.setChecked(selected.contains(groupName));
            checks.add(check);
            card.addView(check, blockParams(8));
        }

        if (checks.isEmpty()) {
            TextView empty = infoPanel("当前账号没有其他可同步家庭组。", true);
            card.addView(empty, blockParams(12));
        }

        Button save = primaryButton("保存跨组同步设置");
        Button back = secondaryButton("返回位置看板");
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
            setStatus("跨组同步设置已保存：" + next.size() + " 个家庭组");
        });
        back.setOnClickListener(view -> {
            showHome();
            refreshLocations();
        });
        card.addView(save, blockParams(10));
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus("请选择需要同步的家庭组。");
    }

    private void showHistory() {
        currentTab = TAB_POSITION;
        historyPage = 1;
        historyUserId = 0;
        LinearLayout card = screen("历史定位");
        Button refresh = primaryButton("刷新历史记录");
        Button back = secondaryButton("返回位置看板");
        refresh.setOnClickListener(view -> loadHistory());
        back.setOnClickListener(view -> {
            showHome();
            refreshLocations();
        });
        card.addView(refresh, blockParams(10));
        card.addView(back, blockParams(16));
        setScreen(card, false);
        loadHistory();
    }

    private void loadHistory() {
        if (content == null) {
            return;
        }

        final int page = Math.max(1, historyPage);
        final int perPage = normalizedHistoryPageSize(historyPageSize);
        final int mapPerUser = normalizedHistoryPageSize(historyMapPageSize);
        final int userId = Math.max(0, historyUserId);
        setStatus("正在加载历史记录");
        runBackground(() -> {
            try {
                String endpoint = "api/history.php?page=" + page + "&per_page=" + perPage + "&map_per_user=" + mapPerUser;
                if (userId > 0) {
                    endpoint += "&user_id=" + userId;
                }
                if (!selectedGroupName.isEmpty()) {
                    endpoint += "&group_name=" + urlEncode(selectedGroupName);
                }
                JSONObject response = getJson(endpoint);
                decryptHistoryResponse(response);
                runUi(() -> renderHistory(response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private int normalizedHistoryPageSize(int value) {
        return value == 50 || value == 100 ? value : 20;
    }

    private void renderHistory(JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONObject pagination = response.optJSONObject("pagination");
        if (pagination != null) {
            historyPage = Math.max(1, pagination.optInt("page", historyPage));
            historyPageSize = normalizedHistoryPageSize(pagination.optInt("per_page", historyPageSize));
            historyMapPageSize = normalizedHistoryPageSize(pagination.optInt("map_per_user", historyMapPageSize));
            historyUserId = Math.max(0, pagination.optInt("user_id", historyUserId));
        }

        JSONObject selectedGroup = response.optJSONObject("selected_group");
        if (selectedGroup != null) {
            TextView group = infoPanel("家庭组：" + selectedGroup.optString("display_name", selectedGroup.optString("group_name", "")), true);
            group.setTag("dynamic");
            content.addView(group, blockParams(10));
        }

        renderHistoryControls(response);
        appendHistoryMap(response.optJSONArray("map_history"));

        JSONArray history = response.optJSONArray("history");
        int total = pagination == null ? (history == null ? 0 : history.length()) : pagination.optInt("total", history == null ? 0 : history.length());
        int totalPages = pagination == null ? 1 : Math.max(1, pagination.optInt("total_pages", 1));
        content.addView(dynamicSectionTitle("历史记录（第 " + historyPage + " / " + totalPages + " 页）"), blockParams(8));
        if (history == null || history.length() == 0) {
            TextView empty = infoPanel("暂无历史定位记录。", true);
            empty.setTag("dynamic");
            content.addView(empty, blockParams(10));
            setStatus("历史记录为空");
            return;
        }

        for (int index = 0; index < history.length(); index += 1) {
            appendHistoryRow(history.optJSONObject(index));
        }
        setStatus("已加载历史记录：" + history.length() + " / " + total);
    }

    private void renderHistoryControls(JSONObject response) {
        JSONArray members = response.optJSONArray("members");
        if (members != null && members.length() > 1) {
            content.addView(dynamicSectionTitle("筛选成员"), blockParams(8));
            Button all = secondaryButton(historyUserId == 0 ? "✓ 全部成员" : "全部成员");
            all.setTag("dynamic");
            all.setOnClickListener(view -> {
                historyUserId = 0;
                historyPage = 1;
                loadHistory();
            });
            content.addView(all, blockParams(6));
            for (int index = 0; index < members.length(); index += 1) {
                JSONObject member = members.optJSONObject(index);
                if (member == null) {
                    continue;
                }
                int memberId = member.optInt("user_id", 0);
                String label = memberLabel(member);
                Button memberButton = secondaryButton((memberId == historyUserId ? "✓ " : "") + label);
                memberButton.setTag("dynamic");
                memberButton.setOnClickListener(view -> {
                    historyUserId = memberId;
                    historyPage = 1;
                    loadHistory();
                });
                content.addView(memberButton, blockParams(6));
            }
        }

        content.addView(dynamicSectionTitle("每页条数"), blockParams(8));
        int[] sizes = new int[] {20, 50, 100};
        for (int size : sizes) {
            Button sizeButton = secondaryButton((historyPageSize == size ? "✓ " : "") + size + " 条");
            sizeButton.setTag("dynamic");
            sizeButton.setOnClickListener(view -> {
                historyPageSize = size;
                historyPage = 1;
                loadHistory();
            });
            content.addView(sizeButton, blockParams(6));
        }

        content.addView(dynamicSectionTitle("地图每人条数"), blockParams(8));
        for (int size : sizes) {
            Button mapSizeButton = secondaryButton((historyMapPageSize == size ? "✓ " : "") + size + " 条/人");
            mapSizeButton.setTag("dynamic");
            mapSizeButton.setOnClickListener(view -> {
                historyMapPageSize = size;
                loadHistory();
            });
            content.addView(mapSizeButton, blockParams(6));
        }

        JSONObject pagination = response.optJSONObject("pagination");
        int total = pagination == null ? 0 : pagination.optInt("total", 0);
        int totalPages = pagination == null ? 1 : Math.max(1, pagination.optInt("total_pages", 1));
        TextView pageInfo = infoPanel("共 " + total + " 条，当前第 " + historyPage + " / " + totalPages + " 页，地图每人 " + historyMapPageSize + " 条", true);
        pageInfo.setTag("dynamic");
        content.addView(pageInfo, blockParams(8));

        LinearLayout pager = new LinearLayout(this);
        pager.setTag("dynamic");
        pager.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = secondaryButton("上一页");
        Button next = secondaryButton("下一页");
        previous.setEnabled(historyPage > 1);
        next.setEnabled(historyPage < totalPages);
        previous.setOnClickListener(view -> {
            if (historyPage > 1) {
                historyPage -= 1;
                loadHistory();
            }
        });
        next.setOnClickListener(view -> {
            if (historyPage < totalPages) {
                historyPage += 1;
                loadHistory();
            }
        });
        pager.addView(previous, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this);
        pager.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
        pager.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(pager, blockParams(10));
    }

    private String memberLabel(JSONObject member) {
        String name = member.optString("display_name", member.optString("username", "成员"));
        String role = member.optString("role_label", "");
        return role.isEmpty() ? name : name + " / " + role;
    }


    private void appendHistoryMap(JSONArray mapHistory) {
        JSONArray records = displayableLocations(mapHistory);
        if (records.length() == 0) {
            return;
        }
        content.addView(dynamicSectionTitle("历史轨迹"), blockParams(8));
        WebView map = locationMapWebView(records);
        LinearLayout.LayoutParams params = blockParams(12);
        params.height = dp(300);
        content.addView(map, params);
    }

    private WebView locationMapWebView(JSONArray records) {
        WebView map = managedWebView();
        map.setTag("dynamic");
        WebSettings settings = map.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(settings.getUserAgentString() + " loc-app/" + APP_VERSION_NAME);
        map.setBackgroundColor(isDarkMode() ? Color.rgb(17, 29, 26) : Color.WHITE);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        String baseUrl = serverUrl();
        cookieManager.setCookie(baseUrl, cookieHeader());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.flush();
        }
        String recordsJson = records.toString();
        map.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    view.evaluateJavascript("window.renderLocHistoryMap(" + recordsJson + ")", null);
                } else {
                    view.loadUrl("javascript:window.renderLocHistoryMap(" + Uri.encode(recordsJson) + ")");
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                return handleWebViewRendererGone(view, "地图 WebView 已释放，请刷新后重试。");
            }
        });
        map.loadUrl(baseUrl + "api/history_map_webview.php");
        return map;
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

    private void appendHistoryRow(JSONObject location) {
        if (location == null) {
            return;
        }

        String name = location.optString("display_name", location.optString("username", "成员"));
        String role = location.optString("role_label", "");
        String encryptionMode = location.optString("encryption_mode", "");
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        String address = diagnostics == null ? "" : diagnostics.optString("preferred_address", "");
        boolean unreadable = location.optBoolean("encrypted_unreadable", false);

        StringBuilder builder = new StringBuilder()
            .append(name);
        if (!role.isEmpty()) {
            builder.append(" / ").append(role);
        }
        builder.append("\n时间：").append(location.optString("created_at", ""));
        String groupName = location.optString("group_name", "");
        if (!groupName.isEmpty()) {
            builder.append("\n家庭组：").append(groupName);
        }
        if (!encryptionMode.isEmpty()) {
            builder.append("\n端到端加密记录：").append(encryptionMode);
            if (location.optBoolean("p2p_decrypted", false)) {
                builder.append("（已解密）");
            }
            if (unreadable) {
                builder.append("（当前设备不可读）");
            }
        }

        if (!unreadable && hasUsableCoordinates(location)) {
            builder.append("\n坐标：")
                .append(formatCoordinate(location.optDouble("latitude", 0)))
                .append(", ")
                .append(formatCoordinate(location.optDouble("longitude", 0)));
        }
        appendHistoryNumeric(builder, location, "altitude", "高度", "m", 0);
        appendHistoryNumeric(builder, location, "accuracy", "精度", "m", 0);
        appendHistoryNumeric(builder, location, "heading", "方向", "°", 0);
        appendHistoryNumeric(builder, location, "speed", "速度", " m/s", 2);

        if (!address.isEmpty()) {
            builder.append("\n地址：").append(address);
        }
        builder.append("\n地址状态：").append(historyAddressStatus(location, diagnostics));
        if (diagnostics != null && !diagnostics.optString("checked_at", "").isEmpty()) {
            builder.append("\n对比时间：").append(diagnostics.optString("checked_at", ""));
        }
        appendHistoryAddressSources(builder, diagnostics);

        TextView row = infoPanel(builder.toString(), true);
        attachMapOpenAction(row, location, name);
        row.setTag("dynamic");
        content.addView(row, blockParams(8));
    }

    private void appendHistoryNumeric(StringBuilder builder, JSONObject location, String key, String label, String suffix, int decimals) {
        if (!location.has(key) || location.isNull(key)) {
            return;
        }
        double value = location.optDouble(key, Double.NaN);
        if (!Double.isFinite(value)) {
            return;
        }
        String formatted = decimals <= 0
            ? String.valueOf(Math.round(value))
            : String.format(java.util.Locale.US, "%." + decimals + "f", value);
        builder.append("\n").append(label).append("：").append(formatted).append(suffix);
    }

    private String historyAddressStatus(JSONObject location, JSONObject diagnostics) {
        if (diagnostics == null || diagnostics.optJSONArray("sources") == null) {
            return location != null && location.optBoolean("address_mismatch", false)
                ? "位置信息不一致"
                : "位置信息一致或无法完整判断";
        }
        if (diagnostics.optBoolean("mismatch", false)) {
            return "位置信息不一致";
        }
        if (diagnostics.optBoolean("mobile_ip_uncertain", false)) {
            return "移动网络出口省份不一致";
        }
        return "位置信息一致或无法完整判断";
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
        for (int index = 0; index < sources.length(); index += 1) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null) {
                continue;
            }
            String label = source.optString("name", source.optString("type", "地址"));
            String sourceAddress = source.optString("address", source.optString("ip", "未知"));
            String city = source.optString("city", "");
            builder.append("\n- ").append(label).append("：").append(sourceAddress);
            if (!city.isEmpty()) {
                builder.append(" / 城市：").append(city);
            }
            if (source.optBoolean("mobile_network_uncertain", false)) {
                builder.append(" / 移动网络出口省份不一致");
            }
        }
    }



    private void maybeAutoShowAnnouncement() {
        runBackground(() -> {
            try {
                JSONObject response = getJson("api/announcement.php");
                JSONObject announcement = response.optJSONObject("announcement");
                if (announcement == null || !shouldAutoShowAnnouncement(announcement)) {
                    return;
                }
                runUi(() -> showAnnouncementPopup(announcement, true));
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
        showPopupDialog(
            announcement.optString("title", "公告"),
            new String[][] {new String[] {"公告内容", bodyText + "\n\n更新时间：" + announcement.optString("updated_at", "")}},
            "知道了",
            null,
            null
        );
    }

    private void showAnnouncement() {
        currentTab = TAB_POSITION;
        LinearLayout card = screen("公告");
        Button refresh = primaryButton("刷新公告");
        Button back = secondaryButton("返回位置看板");
        refresh.setOnClickListener(view -> loadAnnouncement());
        back.setOnClickListener(view -> {
            showHome();
            refreshLocations();
        });
        card.addView(refresh, blockParams(10));
        card.addView(back, blockParams(16));
        setScreen(card, false);
        loadAnnouncement();
    }

    private void loadAnnouncement() {
        setStatus("正在加载公告");
        runBackground(() -> {
            try {
                JSONObject response = getJson("api/announcement.php");
                runUi(() -> renderAnnouncement(response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void renderAnnouncement(JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONObject announcement = response.optJSONObject("announcement");
        if (announcement == null) {
            TextView empty = infoPanel("暂无公告。", true);
            empty.setTag("dynamic");
            content.addView(empty, blockParams(10));
            setStatus("暂无公告");
            return;
        }

        StringBuilder builder = new StringBuilder()
            .append(announcement.optString("title", "公告"))
            .append("\n更新时间：")
            .append(announcement.optString("updated_at", ""))
            .append("\n版本：")
            .append(announcement.optInt("version", 1))
            .append("\n\n")
            .append(announcement.optString("body", ""));
        TextView row = infoPanel(builder.toString(), true);
        row.setTag("dynamic");
        content.addView(row, blockParams(10));
        Button popup = secondaryButton("弹窗查看公告");
        popup.setTag("dynamic");
        popup.setOnClickListener(view -> showAnnouncementPopup(announcement, false));
        content.addView(popup, blockParams(10));
        setStatus("公告已加载");
    }

    private void showTickets() {
        currentTab = TAB_HELP;
        LinearLayout card = screen("帮助");
        TextView intro = compactInfoPanel("遇到账号、家庭组、位置异常或后台操作问题，可以在这里提交工单。", false);
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
        setStatus("正在加载工单");
        runBackground(() -> {
            try {
                JSONObject response = getJson("api/tickets.php");
                runUi(() -> renderTickets(response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void renderTickets(JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONArray tickets = response.optJSONArray("tickets");
        if (tickets == null || tickets.length() == 0) {
            TextView empty = infoPanel("暂无工单。", true);
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
            Button item = secondaryButton(
                "#" + ticket.optInt("id", 0)
                    + " " + ticket.optString("subject", "工单")
                    + " / " + ticket.optString("status_label", "")
            );
            item.setTag("dynamic");
            int ticketId = ticket.optInt("id", 0);
            item.setOnClickListener(view -> showTicketThread(ticketId));
            content.addView(item, blockParams(8));
            TextView summary = infoPanel(
                "更新时间：" + ticket.optString("updated_at", "")
                    + "\n最后消息：" + ticket.optString("last_message", "暂无回复"),
                true
            );
            summary.setTag("dynamic");
            content.addView(summary, blockParams(10));
        }
        setStatus("工单已加载：" + tickets.length());
    }

    private void showCreateTicket() {
        currentTab = TAB_HELP;
        LinearLayout card = screen("新建工单");
        EditText subject = input("标题");
        EditText message = multiLineInput("内容");
        Button submit = primaryButton("提交工单");
        Button back = secondaryButton("返回工单列表");
        submit.setOnClickListener(view -> createTicket(subject.getText().toString(), message.getText().toString()));
        back.setOnClickListener(view -> showTickets());
        card.addView(subject, blockParams(12));
        card.addView(message, blockParams(12));
        card.addView(submit, blockParams(10));
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus("请填写工单标题和内容。");
    }

    private void createTicket(String subject, String message) {
        if (subject.trim().isEmpty() || message.trim().isEmpty()) {
            setStatus("请填写标题和内容。");
            return;
        }

        setStatus("正在提交工单");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "create")
                    .put("group_name", selectedGroupName)
                    .put("subject", subject.trim())
                    .put("message", message.trim());
                JSONObject response = postJson("api/tickets.php", payload);
                int ticketId = response.optInt("ticket_id", 0);
                runUi(() -> showTicketThread(ticketId));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showTicketThread(int ticketId) {
        if (ticketId <= 0) {
            setStatus("工单编号无效。");
            return;
        }

        LinearLayout card = screen("工单详情");
        Button refresh = primaryButton("刷新详情");
        Button back = secondaryButton("返回工单列表");
        refresh.setOnClickListener(view -> loadTicketThread(ticketId));
        back.setOnClickListener(view -> showTickets());
        card.addView(refresh, blockParams(10));
        card.addView(back, blockParams(16));
        setScreen(card, false);
        loadTicketThread(ticketId);
    }

    private void loadTicketThread(int ticketId) {
        setStatus("正在加载工单详情");
        runBackground(() -> {
            try {
                JSONObject response = getJson("api/tickets.php?ticket_id=" + ticketId);
                runUi(() -> renderTicketThread(ticketId, response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void renderTicketThread(int ticketId, JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONObject ticket = response.optJSONObject("ticket");
        if (ticket == null) {
            setStatus("工单不存在。");
            return;
        }

        TextView header = infoPanel(
            "#" + ticket.optInt("id", ticketId)
                + " " + ticket.optString("subject", "工单")
                + "\n状态：" + ticket.optString("status_label", "")
                + "\n创建时间：" + ticket.optString("created_at", ""),
            true
        );
        header.setTag("dynamic");
        content.addView(header, blockParams(10));

        JSONArray messages = response.optJSONArray("messages");
        if (messages != null) {
            content.addView(dynamicSectionTitle("消息"), blockParams(8));
            for (int index = 0; index < messages.length(); index += 1) {
                JSONObject message = messages.optJSONObject(index);
                if (message == null) {
                    continue;
                }
                TextView row = infoPanel(
                    message.optString("sender_label", "")
                        + " / " + message.optString("created_at", "")
                        + "\n" + message.optString("message", ""),
                    true
                );
                row.setTag("dynamic");
                content.addView(row, blockParams(8));
            }
        }

        if (!"closed".equals(ticket.optString("status", ""))) {
            EditText reply = multiLineInput("输入回复");
            reply.setTag("dynamic");
            Button submit = primaryButton("发送回复");
            submit.setTag("dynamic");
            submit.setOnClickListener(view -> replyTicket(ticketId, reply.getText().toString()));
            Button close = secondaryButton("关闭工单");
            close.setTag("dynamic");
            close.setOnClickListener(view -> confirmCloseTicket(ticketId));
            content.addView(reply, blockParams(10));
            content.addView(buttonRow(submit, close), blockParams(8));
        }
        setStatus("工单详情已加载");
    }

    private void confirmCloseTicket(int ticketId) {
        showPopupDialog(
            "关闭工单",
            new String[][] {
                new String[] {"确认操作", "确定关闭这个工单？关闭后不能继续回复，如需处理请新建工单。"}
            },
            "确认关闭",
            () -> closeTicket(ticketId),
            "取消"
        );
    }

    private void closeTicket(int ticketId) {
        if (ticketId <= 0) {
            setStatus("工单信息不完整。");
            return;
        }
        setStatus("正在关闭工单");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "close")
                    .put("ticket_id", ticketId);
                postJson("api/tickets.php", payload);
                runUi(() -> showTicketThread(ticketId));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void replyTicket(int ticketId, String message) {
        if (message.trim().isEmpty()) {
            setStatus("回复内容不能为空。");
            return;
        }

        setStatus("正在发送回复");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "reply")
                    .put("ticket_id", ticketId)
                    .put("message", message.trim());
                postJson("api/tickets.php", payload);
                runUi(() -> showTicketThread(ticketId));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showGroups() {
        currentTab = TAB_GROUPS;
        LinearLayout card = screen("家庭组管理");
        EditText joinCode = input("输入 6 位家庭组号");
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
            TextView empty = infoPanel("暂无家庭组，请通过组号加入。", true);
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
            String groupCode = group.optString("group_code", "");
            boolean selected = groupName.equals(selectedGroupName) || (selectedGroupName.isEmpty() && groupName.equals(currentUser.optString("group_name", "")));
            StringBuilder builder = new StringBuilder()
                .append(selected ? "当前：" : "")
                .append(displayName)
                .append("\n组名：").append(groupName)
                .append("\n组号：").append(groupCode.isEmpty() ? "未生成" : groupCode)
                .append("\n身份：").append(group.optString("role_label", ""));
            if (group.optBoolean("p2p_enabled", false)) {
                builder.append("\n端到端加密：已开启");
            }
            TextView row = infoPanel(builder.toString(), true);
            row.setTag("dynamic");
            content.addView(row, blockParams(8));

            Button select = secondaryButton("切换到 " + displayName);
            select.setTag("dynamic");
            select.setOnClickListener(view -> {
                selectedGroupName = groupName;
                persistSelectedGroup(group);
                showHome();
                refreshLocations();
            });
            content.addView(select, blockParams(8));

            Button p2p = secondaryButton("端到端加密状态");
            p2p.setTag("dynamic");
            p2p.setOnClickListener(view -> showP2PStatus(groupName));
            content.addView(p2p, blockParams(8));

            if (group.optInt("owner_user_id", 0) == currentUserId) {
                EditText rename = input("新的家庭组显示名");
                rename.setText(displayName);
                rename.setTag("dynamic");
                Button save = secondaryButton("保存“" + displayName + "”名称");
                save.setTag("dynamic");
                int groupId = group.optInt("id", 0);
                save.setOnClickListener(view -> renameGroup(groupId, rename.getText().toString()));
                content.addView(rename, blockParams(8));
                content.addView(save, blockParams(10));
                appendOwnedGroupMembers(group);
            }
        }
        setStatus("家庭组已加载：" + groups.length());
    }

    private void joinGroupByCode(String groupCode) {
        String code = groupCode.trim().toLowerCase(java.util.Locale.US);
        if (!code.matches("^[0-9a-z]{6}$")) {
            setStatus("请输入 6 位小写家庭组号。");
            return;
        }

        setStatus("正在加入家庭组");
        runBackground(() -> {
            try {
                JSONObject response = postJson("api/groups.php", new JSONObject()
                    .put("action", "join_by_code")
                    .put("group_code", code));
                applyUserResponse(response);
                runUi(() -> {
                    renderGroups();
                    setStatus("已加入家庭组");
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
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

        setStatus("正在退出家庭组");
        runBackground(() -> {
            try {
                JSONObject response = postJson("api/groups.php", new JSONObject()
                    .put("action", "leave_group")
                    .put("group_name", groupName.trim()));
                applyUserResponse(response);
                runUi(() -> {
                    renderGroups();
                    setStatus("已退出家庭组");
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void renameGroup(int groupId, String displayName) {
        String value = displayName.trim();
        if (groupId <= 0 || value.isEmpty()) {
            setStatus("请填写家庭组显示名。");
            return;
        }

        setStatus("正在保存家庭组名称");
        runBackground(() -> {
            try {
                JSONObject response = postJson("api/groups.php", new JSONObject()
                    .put("action", "rename_group")
                    .put("group_id", groupId)
                    .put("group_name", value));
                applyUserResponse(response);
                runUi(() -> {
                    renderGroups();
                    setStatus("家庭组名称已保存");
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void appendOwnedGroupMembers(JSONObject group) {
        JSONArray members = group.optJSONArray("members");
        if (members == null || members.length() == 0) {
            return;
        }

        String groupName = group.optString("group_name", "");
        content.addView(dynamicSectionTitle("\u6210\u5458\u7ba1\u7406"), blockParams(6));
        int currentUserId = currentUser == null ? 0 : currentUser.optInt("id", 0);
        for (int index = 0; index < members.length(); index += 1) {
            JSONObject member = members.optJSONObject(index);
            if (member == null) {
                continue;
            }
            int memberId = member.optInt("user_id", 0);
            String memberLabel = userDisplayName(member);
            TextView memberRow = infoPanel(
                memberLabel
                    + "\n\u8d26\u53f7\uff1a" + member.optString("username", "")
                    + "\n\u8eab\u4efd\uff1a" + member.optString("role_label", ""),
                true
            );
            memberRow.setTag("dynamic");
            content.addView(memberRow, blockParams(6));

            if (memberId > 0 && memberId != currentUserId) {
                Button reset = secondaryButton("\u91cd\u7f6e\u5bc6\u7801\uff1a" + memberLabel);
                reset.setTag("dynamic");
                reset.setOnClickListener(view -> showMemberPasswordReset(groupName, memberId, memberLabel));
                Button remove = secondaryButton("\u79fb\u51fa\u6210\u5458\uff1a" + memberLabel);
                remove.setTag("dynamic");
                remove.setOnClickListener(view -> confirmRemoveMember(groupName, memberId, memberLabel));
                content.addView(reset, blockParams(6));
                content.addView(remove, blockParams(10));
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

        setStatus("\u6b63\u5728\u79fb\u51fa\u6210\u5458");
        runBackground(() -> {
            try {
                JSONObject response = postJson("api/groups.php", new JSONObject()
                    .put("action", "remove_member")
                    .put("group_name", groupName)
                    .put("target_user_id", memberId));
                applyUserResponse(response);
                runUi(() -> {
                    renderGroups();
                    setStatus("\u6210\u5458\u5df2\u79fb\u51fa\u5bb6\u5ead\u7ec4");
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showMemberPasswordReset(String groupName, int memberId, String memberLabel) {
        LinearLayout card = screen("\u91cd\u7f6e\u6210\u5458\u5bc6\u7801");
        TextView warning = infoPanel("\u4ec5\u5f53\u8be5\u6210\u5458\u53ea\u5c5e\u4e8e\u5f53\u524d\u5bb6\u5ead\u7ec4\u65f6\u53ef\u76f4\u63a5\u91cd\u7f6e\u3002\u82e5\u6210\u5458\u5c5e\u4e8e\u591a\u4e2a\u5bb6\u5ead\u7ec4\uff0c\u8bf7\u8d70\u5de5\u5355\u3002\n\u6210\u5458\uff1a" + memberLabel, false);
        EditText newPassword = input("\u65b0\u5bc6\u7801\uff0c\u81f3\u5c11 6 \u4f4d");
        newPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPasswordConfirm = input("\u518d\u6b21\u8f93\u5165\u65b0\u5bc6\u7801");
        newPasswordConfirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox confirm = new CheckBox(this);
        confirm.setText("\u6211\u786e\u8ba4\u8981\u91cd\u7f6e\u8be5\u6210\u5458\u5bc6\u7801");
        confirm.setTextColor(colorText());
        Button submit = primaryButton("\u786e\u8ba4\u91cd\u7f6e\u5bc6\u7801");
        submit.setOnClickListener(view -> resetMemberPassword(
            groupName,
            memberId,
            newPassword.getText().toString(),
            newPasswordConfirm.getText().toString(),
            confirm.isChecked()
        ));
        Button back = secondaryButton("\u8fd4\u56de\u5bb6\u5ead\u7ec4");
        back.setOnClickListener(view -> showGroups());

        card.addView(warning, blockParams(14));
        card.addView(newPassword, blockParams(10));
        card.addView(newPasswordConfirm, blockParams(10));
        card.addView(confirm, blockParams(10));
        card.addView(submit, blockParams(10));
        card.addView(back, blockParams(0));
        setScreen(card, true);
    }

    private void resetMemberPassword(String groupName, int memberId, String newPassword, String newPasswordConfirm, boolean confirmed) {
        if (!confirmed) {
            setStatus("\u8bf7\u5148\u52fe\u9009\u786e\u8ba4\u91cd\u7f6e\u64cd\u4f5c\u3002");
            return;
        }
        if (newPassword.trim().length() < 6 || !newPassword.equals(newPasswordConfirm)) {
            setStatus("\u8bf7\u586b\u5199\u4e24\u904d\u4e00\u81f4\u4e14\u81f3\u5c11 6 \u4f4d\u7684\u65b0\u5bc6\u7801\u3002");
            return;
        }

        setStatus("\u6b63\u5728\u91cd\u7f6e\u6210\u5458\u5bc6\u7801");
        runBackground(() -> {
            try {
                JSONObject response = postJson("api/groups.php", new JSONObject()
                    .put("action", "reset_member_password")
                    .put("group_name", groupName)
                    .put("target_user_id", memberId)
                    .put("new_password", newPassword)
                    .put("new_password_confirm", newPasswordConfirm)
                    .put("confirm", true));
                applyUserResponse(response);
                runUi(() -> {
                    showGroups();
                    setStatus("\u6210\u5458\u5bc6\u7801\u5df2\u91cd\u7f6e");
                });
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void applyUserResponse(JSONObject response) {
        JSONObject user = response.optJSONObject("user");
        if (user == null) {
            return;
        }
        currentUser = user;
        JSONObject selected = firstGroup(user);
        selectedGroupName = selected.optString("group_name", "");
        persistUserSession(user, response.optInt("report_interval_seconds", prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300)));
    }

    private void showP2PStatus(String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            setStatus("家庭组无效。");
            return;
        }

        LinearLayout card = screen("端到端加密");
        Button refresh = primaryButton("刷新状态");
        Button consent = secondaryButton("同意并发布本机公钥");
        Button back = secondaryButton("返回家庭组");
        refresh.setOnClickListener(view -> loadP2PStatus(groupName));
        consent.setOnClickListener(view -> consentP2P(groupName));
        back.setOnClickListener(view -> showGroups());
        card.addView(refresh, blockParams(10));
        card.addView(consent, blockParams(10));
        card.addView(back, blockParams(16));
        setScreen(card, false);
        loadP2PStatus(groupName);
    }

    private void loadP2PStatus(String groupName) {
        setStatus("正在加载端到端加密状态");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.status(this::postJson, groupName);
                runUi(() -> renderP2PStatus(groupName, response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void renderP2PStatus(String groupName, JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        StringBuilder builder = new StringBuilder()
            .append("家庭组：").append(groupName)
            .append("\n状态：").append(response.optBoolean("enabled", false) ? "已开启" : "未开启")
            .append("\n密钥版本：").append(response.optInt("key_version", 0))
            .append("\n组主权限：").append(response.optBoolean("is_owner", false) ? "是" : "否");
        if (response.optBoolean("needs_key_distribution", false)) {
            builder.append("\n提示：有成员等待组主补发密钥。");
        }
        if (response.optBoolean("enabled", false) && response.optString("wrapped_group_key", "").isEmpty()) {
            builder.append("\n提示：本机没有组密钥，开启后无法解密或上报，请让组主补发。");
        }
        TextView summary = infoPanel(builder.toString(), true);
        summary.setTag("dynamic");
        content.addView(summary, blockParams(10));

        JSONArray members = response.optJSONArray("members");
        boolean allMembersReady = members != null && members.length() > 0;
        if (members != null && members.length() > 0) {
            content.addView(dynamicSectionTitle("成员准备状态"), blockParams(8));
            for (int index = 0; index < members.length(); index += 1) {
                JSONObject member = members.optJSONObject(index);
                if (member == null) {
                    continue;
                }
                boolean memberReady = member.optBoolean("consented", false) && member.optBoolean("has_public_key", false);
                allMembersReady = allMembersReady && memberReady;
                TextView row = infoPanel(
                    member.optString("display_name", member.optString("username", "成员"))
                        + " / " + member.optString("role_label", "")
                        + "\n已同意：" + (member.optBoolean("consented", false) ? "是" : "否")
                        + "\n已发布公钥：" + (member.optBoolean("has_public_key", false) ? "是" : "否")
                        + "\n已有组密钥：" + (member.optBoolean("has_wrapped_key", false) ? "是" : "否"),
                    true
                );
                row.setTag("dynamic");
                content.addView(row, blockParams(8));
            }
        }

        if (response.optBoolean("is_owner", false) && !response.optBoolean("enabled", false)) {
            if (allMembersReady) {
                Button enable = primaryButton("开启端到端加密");
                enable.setTag("dynamic");
                enable.setOnClickListener(view -> enableP2PGroup(groupName));
                content.addView(enable, blockParams(10));
            } else {
                TextView hint = infoPanel("等待所有成员同意并发布公钥后，组主即可开启端到端加密。", true);
                hint.setTag("dynamic");
                content.addView(hint, blockParams(10));
            }
        }
        if (response.optBoolean("is_owner", false) && response.optBoolean("needs_key_distribution", false)) {
            Button distribute = primaryButton("补发组密钥");
            distribute.setTag("dynamic");
            distribute.setOnClickListener(view -> distributeP2PGroupKey(groupName));
            content.addView(distribute, blockParams(10));
        }
        setStatus("端到端加密状态已加载");
    }



    private void consentP2P(String groupName) {
        setStatus("正在发布本机公钥");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.setConsent(this::postJson, this, groupName, true);
                runUi(() -> renderP2PStatus(groupName, response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void enableP2PGroup(String groupName) {
        setStatus("正在开启端到端加密");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.enableGroup(this::postJson, this, groupName);
                runUi(() -> renderP2PStatus(groupName, response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void distributeP2PGroupKey(String groupName) {
        setStatus("正在补发组密钥");
        runBackground(() -> {
            try {
                JSONObject response = P2PCryptoSupport.distributeGroupKey(this::postJson, this, groupName);
                runUi(() -> renderP2PStatus(groupName, response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void showSettings() {
        currentTab = TAB_MINE;
        LinearLayout card = screen("我的");
        TextView account = infoPanel(userDisplayName(currentUser), false);
        CheckBox environmentConsent = new CheckBox(this);
        environmentConsent.setText("同意上传环境诊断数据");
        environmentConsent.setTextColor(colorText());
        environmentConsent.setChecked(currentUser != null && currentUser.optBoolean("environment_data_consent", false));
        CheckBox guardianContinuous = new CheckBox(this);
        guardianContinuous.setText("监护端持续上报当前位置");
        guardianContinuous.setTextColor(colorText());
        guardianContinuous.setChecked(guardianContinuousEnabled(selectedGroupName));
        EditText currentPassword = input("当前密码");
        currentPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPassword = input("新密码");
        newPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPasswordConfirm = input("再次输入新密码");
        newPasswordConfirm.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Button saveEnvironment = primaryButton("保存环境数据设置");
        saveEnvironment.setOnClickListener(view -> saveEnvironmentConsent(environmentConsent.isChecked()));
        Button uploadEnvironment = secondaryButton("立即上报环境信息");
        uploadEnvironment.setOnClickListener(view -> {
            if (!environmentConsent.isChecked()) {
                setStatus("请先勾选并保存环境数据设置。");
                return;
            }
            uploadEnvironmentReport(true, true, true);
        });
        Button saveContinuous = secondaryButton("保存持续上报设置");
        saveContinuous.setOnClickListener(view -> saveGuardianContinuous(guardianContinuous.isChecked()));
        Button changePassword = secondaryButton("修改密码");
        changePassword.setOnClickListener(view -> changePassword(
            currentPassword.getText().toString(),
            newPassword.getText().toString(),
            newPasswordConfirm.getText().toString()
        ));
        Button logout = secondaryButton("退出登录");
        logout.setOnClickListener(view -> logout());

        card.addView(sectionTitle("账号信息"), blockParams(8));
        card.addView(account, blockParams(14));
        card.addView(sectionTitle("界面主题"), blockParams(8));
        card.addView(themeButtonRow("system", "跟随系统", "light", "明亮"), blockParams(8));
        card.addView(themeButton("dark", "暗色"), blockParams(14));
        card.addView(sectionTitle("隐私与上报"), blockParams(8));
        card.addView(environmentConsent, blockParams(8));
        card.addView(saveEnvironment, blockParams(8));
        card.addView(uploadEnvironment, blockParams(12));
        card.addView(guardianContinuous, blockParams(8));
        card.addView(saveContinuous, blockParams(14));
        card.addView(sectionTitle("账号安全"), blockParams(8));
        card.addView(currentPassword, blockParams(10));
        card.addView(newPassword, blockParams(10));
        card.addView(newPasswordConfirm, blockParams(10));
        card.addView(changePassword, blockParams(14));
        card.addView(logout, blockParams(0));
        setScreen(card, false);
        setStatus("当前上报间隔：" + prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300) + " 秒");
    }

    private Button themeButton(String mode, String label) {
        String current = themeMode();
        Button button = secondaryButton((mode.equals(current) ? "✓ " : "") + label);
        button.setOnClickListener(view -> applyThemeMode(mode));
        return button;
    }

    private LinearLayout themeButtonRow(String leftMode, String leftLabel, String rightMode, String rightLabel) {
        return buttonRow(themeButton(leftMode, leftLabel), themeButton(rightMode, rightLabel));
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
        setStatus("正在保存环境数据设置");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("group_name", selectedGroupName)
                    .put("environment_data_consent", enabled);
                JSONObject response = postJson("api/settings.php", payload);
                JSONObject user = response.optJSONObject("user");
                if (user != null) {
                    currentUser = user;
                    persistUserSession(user, response.optInt("report_interval_seconds", prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300)));
                }
                if (enabled) {
                    uploadEnvironmentReport(true, true);
                }
                runUi(() -> setStatus(enabled ? "环境数据设置已保存，正在上传诊断。" : "环境数据设置已保存"));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void saveGuardianContinuous(boolean enabled) {
        String groupName = selectedGroupName.isEmpty() && currentUser != null ? currentUser.optString("group_name", "") : selectedGroupName;
        SharedPreferences.Editor editor = prefs().edit()
            .putBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, enabled);
        if (!groupName.isEmpty()) {
            editor.putBoolean("guardian_continuous_reporting_" + groupName, enabled);
        }
        editor.apply();
        syncKeepAliveService();
        setStatus(enabled ? "监护端持续上报已开启" : "监护端持续上报已关闭");
    }

    private void changePassword(String currentPassword, String newPassword, String newPasswordConfirm) {
        if (currentPassword.isEmpty() || newPassword.isEmpty() || newPasswordConfirm.isEmpty()) {
            setStatus("请填写完整密码信息。");
            return;
        }

        setStatus("正在修改密码");
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("action", "change_password")
                    .put("group_name", selectedGroupName)
                    .put("current_password", currentPassword)
                    .put("new_password", newPassword)
                    .put("new_password_confirm", newPasswordConfirm);
                JSONObject response = postJson("api/settings.php", payload);
                JSONObject user = response.optJSONObject("user");
                if (user != null) {
                    currentUser = user;
                    persistUserSession(user, response.optInt("report_interval_seconds", prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300)));
                }
                runUi(() -> setStatus("密码已修改"));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            }
        });
    }

    private void refreshLocations() {
        if (refreshButton != null) {
            refreshButton.setEnabled(false);
        }
        setStatus("正在刷新位置");
        runBackground(() -> {
            try {
                String endpoint = "api/locations.php";
                if (!selectedGroupName.isEmpty()) {
                    endpoint += "?group_name=" + urlEncode(selectedGroupName);
                }
                JSONObject response = getJson(endpoint);
                JSONObject user = response.optJSONObject("user");
                if (user != null) {
                    currentUser = user;
                    persistUserSession(user, response.optInt("report_interval_seconds", 300));
                }
                decryptLocationsResponse(response);
                runUi(() -> renderLocations(response));
            } catch (Exception exception) {
                runUi(() -> setStatus(exception.getMessage()));
            } finally {
                runUi(() -> {
                    if (refreshButton != null) {
                        refreshButton.setEnabled(true);
                    }
                });
            }
        });
    }

    private void renderLocations(JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONArray groups = currentUser == null ? new JSONArray() : currentUser.optJSONArray("groups");
        if (groups != null && groups.length() > 1) {
            content.addView(dynamicSectionTitle("家庭组"), blockParams(6));
            for (int index = 0; index < groups.length(); index += 1) {
                JSONObject group = groups.optJSONObject(index);
                if (group == null) {
                    continue;
                }
                Button groupButton = secondaryButton(group.optString("display_name", group.optString("group_name", "家庭组")));
                groupButton.setTag("dynamic");
                groupButton.setOnClickListener(view -> {
                    selectedGroupName = group.optString("group_name", "");
                    persistSelectedGroup(group);
                    refreshLocations();
                });
                content.addView(groupButton, blockParams(6));
            }
        }

        appendMapPreview(response);
        content.addView(dynamicSectionTitle("最新位置"), blockParams(8));
        appendLocationSection("我的云端位置", response.optJSONObject("mine"));
        appendLocationArray("监测端云端位置", response.optJSONArray("monitors"));
        appendLocationArray("监护端云端位置", response.optJSONArray("guardians"));
        appendAddressDiagnostics(response.optJSONObject("mine"));
        setStatus("已刷新：" + response.optString("server_time", ""));
    }

    private void appendMapPreview(JSONObject response) {
        JSONArray records = latestMapLocations(response);
        if (records.length() == 0) {
            TextView empty = compactInfoPanel("暂无云端位置", true);
            empty.setTag("dynamic");
            content.addView(empty, blockParams(12));
            return;
        }

        content.addView(dynamicSectionTitle("位置地图"), blockParams(8));
        WebView map = locationMapWebView(records);
        LinearLayout.LayoutParams params = blockParams(12);
        params.height = dp(260);
        content.addView(map, params);
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

    private void appendAddressDiagnostics(JSONObject location) {
        if (location == null) {
            return;
        }
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        if (diagnostics == null) {
            return;
        }
        StringBuilder builder = new StringBuilder("地址对比");
        String preferred = diagnostics.optString("preferred_address", "");
        if (!preferred.isEmpty()) {
            builder.append("\n首选地址：").append(preferred);
        }
        JSONArray sources = diagnostics.optJSONArray("sources");
        if (sources != null) {
            for (int index = 0; index < sources.length(); index += 1) {
                JSONObject source = sources.optJSONObject(index);
                if (source == null) {
                    continue;
                }
                String sourceName = source.optString("name", source.optString("type", "地址"));
                String address = source.optString("address", source.optString("ip", ""));
                if (!address.isEmpty()) {
                    builder.append("\n").append(sourceName).append("：").append(address);
                }
            }
        }
        if (builder.length() > "地址对比".length()) {
            TextView panel = compactInfoPanel(builder.toString(), true);
            panel.setTag("dynamic");
            content.addView(dynamicSectionTitle("地址对比"), blockParams(6));
            content.addView(panel, blockParams(12));
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

    private void decryptHistoryResponse(JSONObject response) {
        JSONObject selectedGroup = response.optJSONObject("selected_group");
        String groupName = selectedGroup == null ? selectedGroupName : selectedGroup.optString("group_name", selectedGroupName);
        decryptLocationArray(response.optJSONArray("history"), groupName);
        decryptLocationArray(response.optJSONArray("map_history"), groupName);
    }

    private void decryptLocationArray(JSONArray locations, String groupName) {
        if (locations == null) {
            return;
        }
        for (int index = 0; index < locations.length(); index += 1) {
            JSONObject location = locations.optJSONObject(index);
            if (location == null) {
                continue;
            }
            try {
                locations.put(index, P2PCryptoSupport.decryptRecord(this::postJson, this, groupName, location));
            } catch (Exception exception) {
                Log.w(TAG, "P2P row decrypt failed: " + exception.getMessage());
            }
        }
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
                TextView empty = infoPanel(label + "：暂无定位", true);
                empty.setTag("dynamic");
                content.addView(empty, blockParams(8));
            }
            return;
        }

        String name = label.isEmpty()
            ? location.optString("display_name", location.optString("username", "成员"))
            : label;
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        String address = "";
        if (diagnostics != null) {
            address = diagnostics.optString("preferred_address", "");
        }

        StringBuilder builder = new StringBuilder()
            .append(name)
            .append(" · ")
            .append(location.optString("role_label", ""))
            .append("\n坐标：")
            .append(formatCoordinate(location.optDouble("latitude", 0)))
            .append(", ")
            .append(formatCoordinate(location.optDouble("longitude", 0)))
            .append("\n更新时间：")
            .append(location.optString("updated_at", ""));

        if (!address.isEmpty()) {
            builder.append("\n地址：").append(address);
        }

        TextView row = infoPanel(builder.toString(), true);
        attachMapOpenAction(row, location, name);
        row.setTag("dynamic");
        content.addView(row, blockParams(8));
    }

    private void attachMapOpenAction(TextView row, JSONObject location, String label) {
        if (!hasUsableCoordinates(location)) {
            return;
        }
        double latitude = location.optDouble("latitude", 0);
        double longitude = location.optDouble("longitude", 0);
        row.setClickable(true);
        row.setOnClickListener(view -> openMapLocation(latitude, longitude, label));
    }

    private boolean hasUsableCoordinates(JSONObject location) {
        if (location == null || !location.has("latitude") || !location.has("longitude") || location.isNull("latitude") || location.isNull("longitude")) {
            return false;
        }
        double latitude = location.optDouble("latitude", 0);
        double longitude = location.optDouble("longitude", 0);
        return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180 && !(latitude == 0 && longitude == 0);
    }

    private void openMapLocation(double latitude, double longitude, String label) {
        String safeLabel = label == null || label.trim().isEmpty() ? "位置" : label.trim();
        try {
            JSONObject record = new JSONObject()
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("display_name", safeLabel)
                .put("updated_at", "坐标：" + formatCoordinate(latitude) + ", " + formatCoordinate(longitude));
            JSONArray records = new JSONArray().put(record);
            LinearLayout card = screen(safeLabel);
            Button back = secondaryButton("返回位置看板");
            back.setOnClickListener(view -> {
                showHome();
                refreshLocations();
            });
            card.addView(back, blockParams(10));
            WebView map = locationMapWebView(records);
            LinearLayout.LayoutParams params = blockParams(0);
            params.height = dp(420);
            card.addView(map, params);
            setScreen(card, false);
            setStatus("已打开位置地图：" + safeLabel);
        } catch (Exception exception) {
            setStatus("打开地图失败：" + exception.getMessage());
        }
    }

    private void removeDynamicRows() {
        for (int index = content.getChildCount() - 1; index >= 0; index -= 1) {
            View child = content.getChildAt(index);
            if ("dynamic".equals(child.getTag())) {
                content.removeViewAt(index);
                if (child instanceof WebView) {
                    destroyManagedWebView((WebView) child);
                }
            }
        }
    }

    private void reportCurrentLocation() {
        if (reporting) {
            return;
        }

        if (!hasFineLocationPermission()) {
            requestForegroundLocationPermissionIfNeeded();
            return;
        }

        reporting = true;
        if (reportButton != null) {
            reportButton.setEnabled(false);
        }
        setStatus("正在读取定位");

        android.location.LocationManager manager = (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        android.location.Location location = bestLastKnownLocation(manager);
        if (location != null) {
            submitLocation(location);
            return;
        }

        try {
            List<String> providers = manager == null ? new ArrayList<>() : manager.getProviders(true);
            String provider = providers.contains(android.location.LocationManager.GPS_PROVIDER)
                ? android.location.LocationManager.GPS_PROVIDER
                : providers.isEmpty() ? android.location.LocationManager.NETWORK_PROVIDER : providers.get(0);
            manager.requestSingleUpdate(provider, new android.location.LocationListener() {
                @Override
                public void onLocationChanged(android.location.Location newLocation) {
                    submitLocation(newLocation);
                }

                @Override
                public void onProviderEnabled(String providerName) {
                }

                @Override
                public void onProviderDisabled(String providerName) {
                }

                @Override
                public void onStatusChanged(String providerName, int status, Bundle extras) {
                }
            }, Looper.getMainLooper());
            mainHandler.postDelayed(() -> {
                if (reporting) {
                    finishReport("定位超时，请确认定位已开启。");
                }
            }, 15000L);
        } catch (Exception exception) {
            finishReport("读取定位失败：" + exception.getMessage());
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

    private void submitLocation(android.location.Location location) {
        setStatus("正在上报位置");
        runBackground(() -> {
            try {
                String reportGroupName = currentGroupName();
                JSONObject addressDiagnostics = buildAddressDiagnostics(location);
                JSONObject payload = locationReportPayload(reportGroupName, location, addressDiagnostics);
                JSONObject response = postLocationReport(reportGroupName, payload);
                List<String> extraGroupNames = selectedCrossSyncGroups();
                extraGroupNames.remove(reportGroupName);
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
                    finishReport(message);
                    refreshLocations();
                });
            } catch (Exception exception) {
                runUi(() -> finishReport(exception.getMessage()));
            }
        });
    }

    private JSONObject locationReportPayload(String groupName, android.location.Location location, JSONObject addressDiagnostics) throws Exception {
        JSONObject payload = new JSONObject()
            .put("group_name", groupName)
            .put("latitude", location.getLatitude())
            .put("longitude", location.getLongitude())
            .put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL)
            .put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL)
            .put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL)
            .put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL)
            .put("location_provider", location.getProvider())
            .put("location_time", String.valueOf(location.getTime()));
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

    private JSONObject buildAddressDiagnostics(android.location.Location location) {
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        JSONObject fallback;
        try {
            fallback = addressSource("gps", "定位地址", "坐标", "", formatCoordinate(latitude) + ", " + formatCoordinate(longitude), "", "", "", latitude, longitude);
        } catch (Exception exception) {
            return null;
        }
        List<JSONObject> addressCandidates = new ArrayList<>();
        addressCandidates.add(reverseAddressByAmapWebView(latitude, longitude));
        addressCandidates.add(reverseAddressByMeituan(latitude, longitude));
        addressCandidates.add(reverseAddressByBigDataCloud(latitude, longitude));

        JSONArray sources = new JSONArray();
        JSONObject best = fallback;
        int bestScore = 0;
        int bestPriority = Integer.MAX_VALUE;
        for (JSONObject candidate : addressCandidates) {
            if (!isUsefulAddressSource(candidate, fallback)) {
                continue;
            }
            sources.put(candidate);
            int score = addressPrecisionScore(candidate);
            int priority = addressProviderPriority(candidate.optString("provider", ""));
            if (score > bestScore || (score == bestScore && priority < bestPriority)) {
                best = candidate;
                bestScore = score;
                bestPriority = priority;
            }
        }

        JSONObject ipSource = probeIpAddressSource();
        if (ipSource != null) {
            sources.put(ipSource);
        }
        JSONObject webRtcSource = probeWebRtcAddressSource();
        if (webRtcSource != null) {
            sources.put(webRtcSource);
        }
        if (sources.length() == 0) {
            sources.put(fallback);
        }

        JSONObject diagnostics = new JSONObject();
        try {
            diagnostics.put("complete", true)
                .put("mismatch", false)
                .put("preferred_source", "gps")
                .put("preferred_address", best.optString("address", fallback.optString("address", "")))
                .put("preferred_latitude", latitude)
                .put("preferred_longitude", longitude)
                .put("sources", sources);
        } catch (Exception ignored) {
            return null;
        }
        return diagnostics;
    }

    private JSONObject reverseAddressByAmapWebView(double latitude, double longitude) {
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
            url = base + "api/amap_reverse_webview.php?lat=" + urlEncode(formatCoordinate(latitude)) + "&lng=" + urlEncode(formatCoordinate(longitude));
        } catch (Exception exception) {
            return null;
        }

        runUi(() -> {
            if (!canLoadForegroundWebView()) {
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
            latch.await(12000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
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
            JSONObject response = requestOpenJson(url);
            JSONObject data = response.optJSONObject("data");
            if (data == null) {
                return null;
            }
            String country = firstText(data.optString("country", "中国"), "中国");
            String region = data.optString("province", "");
            String city = firstText(data.optString("city", ""), data.optString("openCityName", ""));
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
            String city = firstText(data.optString("city", ""), data.optString("locality", ""));
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


    private JSONObject probeIpAddressSource() {
        try {
            JSONObject probe = getJson("api/ip_probe.php");
            String ip = firstText(probe.optString("ip", ""));
            if (ip.isEmpty()) {
                JSONObject cloudflare = getJson("api/cloudflare_location.php");
                ip = firstText(cloudflare.optString("ip", ""), cloudflare.optString("ipv6", ""));
            }
            if (ip.isEmpty()) {
                return null;
            }

            JSONObject geo = geocodeIpAddress(ip);
            String address = geo == null ? ip : firstText(geo.optString("address", ""), ip);
            String city = geo == null ? "" : geo.optString("city", "");
            String region = geo == null ? "" : geo.optString("region", "");
            String country = geo == null ? "" : geo.optString("country", "");
            String provider = geo == null ? "服务端 IP" : firstText(geo.optString("provider", ""), "IP 探测");
            double latitude = geo == null ? 0 : geo.optDouble("latitude", 0);
            double longitude = geo == null ? 0 : geo.optDouble("longitude", 0);
            JSONObject source = addressSource("ip", "IP 探测", provider, "server", address, city, region, country, latitude, longitude)
                .put("ip", ip)
                .put("server_ip", ip);
            JSONArray variants = new JSONArray();
            JSONObject variant = new JSONObject()
                .put("label", "服务端")
                .put("ip", ip)
                .put("address", address)
                .put("city", city)
                .put("region", region)
                .put("country", country)
                .put("provider", provider)
                .put("source", "server");
            if (latitude != 0 || longitude != 0) {
                variant.put("latitude", latitude).put("longitude", longitude);
            }
            variants.put(variant);
            source.put("variants", variants);
            return source;
        } catch (Exception exception) {
            Log.w(TAG, "IP probe failed: " + exception.getMessage());
            return null;
        }
    }

    private JSONObject probeWebRtcAddressSource() {
        String base = serverUrl();
        if (base.isEmpty()) {
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<WebView> webViewRef = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);
        String url = base + "api/webrtc_probe_webview.php";

        runUi(() -> {
            if (!canLoadForegroundWebView()) {
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
            latch.await(9000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            runUi(() -> {
                WebView webView = webViewRef.get();
                if (webView != null) {
                    destroyManagedWebView(webView);
                }
            });
        }

        JSONObject payload = result.get();
        if (payload == null || !payload.optBoolean("ok", false)) {
            return null;
        }
        JSONObject selected = payload.optJSONObject("selected");
        String ip = selected == null ? "" : selected.optString("ip", "");
        if (ip.isEmpty()) {
            return null;
        }
        try {
            JSONObject geo = geocodeIpAddress(ip);
            String address = geo == null ? ip : firstText(geo.optString("address", ""), ip);
            String city = geo == null ? "" : geo.optString("city", "");
            String region = geo == null ? "" : geo.optString("region", "");
            String country = geo == null ? "" : geo.optString("country", "");
            double latitude = geo == null ? 0 : geo.optDouble("latitude", 0);
            double longitude = geo == null ? 0 : geo.optDouble("longitude", 0);
            String provider = geo == null ? selected.optString("stun_label", "WebRTC") : firstText(geo.optString("provider", ""), selected.optString("stun_label", "WebRTC"));
            JSONObject source = addressSource("webrtc", "WebRTC 探测", provider, selected.optString("stun_server", ""), address, city, region, country, latitude, longitude)
                .put("ip", ip)
                .put("stun_server", selected.optString("stun_server", ""))
                .put("stun_label", selected.optString("stun_label", ""))
                .put("stun_scope", selected.optString("stun_scope", ""))
                .put("candidate_type", selected.optString("candidate_type", ""));
            JSONArray candidates = payload.optJSONArray("candidates");
            if (candidates != null) {
                source.put("candidates", candidates);
            }
            return source;
        } catch (Exception exception) {
            Log.w(TAG, "WebRTC probe normalize failed: " + exception.getMessage());
            return null;
        }
    }

    private JSONObject geocodeIpAddress(String ip) {
        JSONObject meituan = geocodeIpByMeituan(ip);
        if (meituan != null) {
            return meituan;
        }
        return geocodeIpByIpInfo(ip);
    }

    private JSONObject geocodeIpByMeituan(String ip) {
        try {
            String url = "https://apimobile.meituan.com/locate/v2/ip/loc?rgeo=true&ip=" + urlEncode(ip);
            JSONObject response = requestOpenJson(url);
            JSONObject data = response.optJSONObject("data");
            if (data == null) {
                data = response;
            }
            String country = firstText(data.optString("country", ""), "中国");
            String region = firstText(data.optString("province", ""), data.optString("region", ""));
            String city = firstText(data.optString("city", ""), data.optString("openCityName", ""));
            String district = data.optString("district", "");
            String detail = firstText(data.optString("detail", ""), data.optString("address", ""), data.optString("name", ""));
            String address = composeAddress(country, region, city, district, detail);
            if (address.isEmpty() || "中国".equals(address)) {
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
            return null;
        }
    }

    private JSONObject geocodeIpByIpInfo(String ip) {
        try {
            JSONObject response = postJson("api/ipinfo_lite.php", new JSONObject().put("ip", ip));
            String country = response.optString("country", "");
            String region = response.optString("region", "");
            String city = response.optString("city", "");
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
            return null;
        }
    }

    private JSONObject requestOpenJson(String target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "loc-app/" + APP_VERSION_NAME);
        connection.setRequestProperty("Accept", "application/json");
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseText = readResponse(stream);
        connection.disconnect();
        return responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
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
        String combined = source.optString("address", "") + source.optString("detail", "") + source.optString("street", "");
        int score = 0;
        if (!source.optString("country", "").isEmpty()) score = Math.max(score, 1);
        if (!source.optString("region", "").isEmpty()) score = Math.max(score, 2);
        if (!source.optString("city", "").isEmpty()) score = Math.max(score, 3);
        if (!source.optString("district", "").isEmpty()) score = Math.max(score, 4);
        if (!source.optString("street", "").isEmpty() || combined.matches(".*(街道|镇|乡|路|街|大道|巷|弄).*")) score = Math.max(score, 5);
        if (combined.matches(".*(小区|花园|家园|公寓|大厦|广场|中心|园区|学校|医院|写字楼|商务|住宅区|酒店|商场|市场|超市|银行|地铁站|车站|停车场|便利店|餐厅|门店|馆|苑|府|轩|阁).*")) score = Math.max(score, 6);
        if (combined.matches(".*(\\d+\\s*号|[一二三四五六七八九十\\d]+\\s*(栋|幢|座|单元|楼|层|室)|[A-Z]\\s*\\d).*")) score = Math.max(score, 7);
        return score;
    }

    private String composeAddress(String... parts) {
        List<String> selected = new ArrayList<>();
        for (String part : parts) {
            String text = part == null ? "" : part.trim();
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
        return builder.toString();
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
            report.put("android_release", Build.VERSION.RELEASE);
            report.put("android_sdk", Build.VERSION.SDK_INT);
            report.put("app_version_name", APP_VERSION_NAME);
            report.put("app_version_code", APP_VERSION_CODE);
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

    private void uploadEnvironmentReport(boolean includeInstalledApps, boolean force) {
        uploadEnvironmentReport(includeInstalledApps, force, false);
    }

    private void uploadEnvironmentReport(boolean includeInstalledApps, boolean force, boolean showResult) {
        if (currentUser == null || !currentUser.optBoolean("environment_data_consent", false)) {
            if (showResult) {
                setStatus("环境上报未开启，请先保存环境数据设置。");
            }
            return;
        }
        if (showResult) {
            setStatus("正在上报环境信息");
        }
        runBackground(() -> {
            try {
                JSONObject payload = new JSONObject()
                    .put("force", force)
                    .put("report", buildDeviceEnvironmentReport(includeInstalledApps));
                postJson("api/environment_report.php", payload);
                if (showResult) {
                    runUi(() -> setStatus("环境信息已上报。"));
                }
            } catch (Exception exception) {
                Log.w(TAG, "Environment report failed: " + exception.getMessage());
                if (showResult) {
                    runUi(() -> setStatus("环境信息上报失败：" + exception.getMessage()));
                }
            }
        });
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
            return enabled == 1 && services != null && !services.trim().isEmpty();
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
        JSONObject encryptedPayload = P2PCryptoSupport.encryptedReportOrNull(this::postJson, this, groupName, payload);
        return postJson("api/report_location.php", encryptedPayload == null ? payload : encryptedPayload);
    }

    private void finishReport(String message) {
        reporting = false;
        if (reportButton != null) {
            reportButton.setEnabled(true);
        }
        setStatus(message);
    }

    private void logout() {
        prefs().edit()
            .remove(KEY_SESSION_COOKIE)
            .remove(KEY_USER_ROLE)
            .remove(KEY_GROUP_NAME)
            .remove(KEY_GROUP_SESSIONS)
            .apply();
        runBackground(() -> {
            try {
                postJson("api/logout.php", new JSONObject());
            } catch (Exception ignored) {
                // Local logout is enough if the network is unavailable.
            }
        });
        currentUser = null;
        selectedGroupName = "";
        stopKeepAliveService();
        showLoginWithMessage("已退出登录。");
    }

    private JSONObject getJson(String endpoint) throws Exception {
        return requestJson(endpoint, "GET", null);
    }

    private JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        return requestJson(endpoint, "POST", payload);
    }

    private JSONObject requestJson(String endpoint, String method, JSONObject payload) throws Exception {
        String target = endpoint.startsWith("http") ? endpoint : serverUrl() + endpoint;
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "loc-app/" + APP_VERSION_NAME);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cookie", cookieHeader());
        if (payload != null) {
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int status = connection.getResponseCode();
        captureCookies(connection);
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseText = readResponse(stream);
        connection.disconnect();

        assertJsonResponse(responseText);
        JSONObject response = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
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
            String[] parts = existing.split(";");
            for (String part : parts) {
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
        String sessionCookie = prefs().getString(KEY_SESSION_COOKIE, "");
        String deviceCookie = DEVICE_COOKIE_NAME + "=" + deviceCookieValue();
        if (sessionCookie == null || sessionCookie.trim().isEmpty()) {
            return deviceCookie;
        }
        return sessionCookie + "; " + deviceCookie;
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

    private void persistUserSession(JSONObject user, int intervalSeconds) {
        JSONObject selectedGroup = firstGroup(user);
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

    private JSONObject firstGroup(JSONObject user) {
        JSONArray groups = user == null ? null : user.optJSONArray("groups");
        if (groups != null && groups.length() > 0) {
            JSONObject group = groups.optJSONObject(0);
            if (group != null) {
                return group;
            }
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

    private boolean guardianContinuousEnabled(String groupName) {
        if (groupName == null || groupName.isEmpty()) {
            return prefs().getBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false);
        }
        return prefs().getBoolean("guardian_continuous_reporting_" + groupName, false);
    }

    private void requestStartupPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIFICATION);
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

    private boolean requestForegroundLocationPermissionIfNeeded() {
        if (hasFineLocationPermission()) {
            return false;
        }
        requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_LOCATION);
        return true;
    }

    private boolean requestBackgroundLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!hasFineLocationPermission()) {
            return false;
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_BACKGROUND_LOCATION }, REQUEST_BACKGROUND_LOCATION);
            return true;
        }
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
            requestStartupPermissions();
        } else if (requestCode == REQUEST_LOCATION) {
            requestStartupPermissions();
        } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
            requestBatteryOptimizationPermission();
            requestExactAlarmPermission();
        }
        syncKeepAliveService();
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void syncKeepAliveService() {
        Intent intent = new Intent(this, KeepAliveService.class);
        try {
            if (shouldRunKeepAlive()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } else {
                stopService(intent);
            }
        } catch (Exception exception) {
            Log.w(TAG, "KeepAlive sync failed: " + exception.getMessage());
        }
    }

    private boolean shouldRunKeepAlive() {
        String role = normalizeRole(prefs().getString(KEY_USER_ROLE, ""));
        String groupName = prefs().getString(KEY_GROUP_NAME, "");
        return !groupName.isEmpty()
            && ("monitor".equals(role) || ("guardian".equals(role) && guardianContinuousEnabled(groupName)));
    }

    private void stopKeepAliveService() {
        try {
            stopService(new Intent(this, KeepAliveService.class));
        } catch (Exception ignored) {
            // Service may not be running.
        }
    }

    private void showUpdateRequired(String versionName, String apkUrl) {
        LinearLayout card = screen("需要更新");
        TextView message = body("检测到新版位置 " + versionName + "，App 会自动下载，下载完成后请确认安装。");
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
            prefs().edit().remove(KEY_PENDING_UPDATE_INSTALL_ID).apply();
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
                prefs().edit().putLong(KEY_PENDING_UPDATE_INSTALL_ID, downloadId).apply();
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
            prefs().edit().remove(KEY_PENDING_UPDATE_INSTALL_ID).apply();
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
            // WebView may already be torn down by the renderer process or lifecycle.
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


    private String homeTitle() {
        String role = currentUser == null ? "" : currentUser.optString("role_label", "");
        return role.isEmpty() ? "位置" : role;
    }

    private String compactUserDisplayName(JSONObject user) {
        if (user == null) {
            return "未登录";
        }
        String displayName = user.optString("display_name", "");
        String username = user.optString("username", "");
        String group = selectedGroupName.isEmpty() ? user.optString("group_name", "") : selectedGroupName;
        return (displayName.isEmpty() ? username : displayName)
            + (username.isEmpty() || username.equals(displayName) ? "" : " / " + username)
            + "\n家庭组：" + (group.isEmpty() ? "未选择" : group);
    }

    private String userDisplayName(JSONObject user) {
        if (user == null) {
            return "未登录";
        }
        String displayName = user.optString("display_name", "");
        String username = user.optString("username", "");
        String role = user.optString("role_label", "");
        String group = selectedGroupName.isEmpty() ? user.optString("group_name", "") : selectedGroupName;
        return (displayName.isEmpty() ? username : displayName)
            + (username.isEmpty() || username.equals(displayName) ? "" : "（" + username + "）")
            + "\n家庭组：" + (group.isEmpty() ? "未选择" : group)
            + (role.isEmpty() ? "" : "\n身份：" + role)
            + "\n上报间隔：" + prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300) + " 秒";
    }

    private LinearLayout screen(String titleText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(cardBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            card.setElevation(dp(4));
        }

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(22);
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
        root.setPadding(dp(16), dp(16), dp(16), dp(14));
        root.setBackgroundColor(colorSurface());
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (currentUser == null) {
            setContentView(scroll);
            return;
        }

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(colorSurface());
        frame.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(bottomNavigation(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(frame);
    }

    private LinearLayout bottomNavigation() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(14), dp(6), dp(14), dp(10));
        outer.setBackgroundColor(colorSurface());

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        nav.setBackground(bottomNavBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nav.setElevation(dp(12));
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
        item.setMinimumHeight(dp(64));
        item.setPadding(dp(4), dp(6), dp(4), dp(6));
        boolean active = currentTab == tab;
        item.setBackground(active ? navActiveBackground() : transparentButtonBackground());
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> {
            if (action != null) {
                action.run();
            }
        });

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(active ? 26 : 24);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTypeface(Typeface.DEFAULT_BOLD);
        iconView.setTextColor(active ? Color.rgb(0, 218, 194) : colorMuted());

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(12);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        labelView.setTextColor(active ? Color.rgb(0, 218, 194) : colorMuted());

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
                TextView sectionTitle = sectionHeading(section[0]);
                body.addView(sectionTitle, blockParams(6));
                for (int index = 1; index < section.length; index += 1) {
                    TextView paragraph = body(section[index]);
                    paragraph.setLineSpacing(0, 1.65f);
                    body.addView(paragraph, blockParams(10));
                }
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxBodyHeight = Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.58f), dp(460));
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxBodyHeight));
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
        }
    }

    private TextView sectionHeading(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(colorText());
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(isDarkMode() ? Color.rgb(45, 72, 66) : Color.rgb(217, 231, 226));
        return view;
    }

    private void showLoading(String text) {
        LinearLayout card = screen("位置");
        TextView message = body(text);
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(message, blockParams(0));
        setScreen(card, true);
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

    private TextView dynamicSectionTitle(String text) {
        TextView view = sectionTitle(text);
        view.setTag("dynamic");
        return view;
    }
    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(15);
        view.setLineSpacing(0, 1.15f);
        view.setTextColor(colorMuted());
        return view;
    }

    private TextView infoPanel(String text, boolean dynamic) {
        TextView view = body(text);
        view.setTextColor(colorText());
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackground(panelBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setElevation(dp(1));
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
        view.setTextSize(16);
        view.setTextColor(colorText());
        view.setHintTextColor(colorMuted());
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
        view.setTextSize(14);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        return view;
    }

    private LinearLayout buttonRow(Button left, Button right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(buttonBackground(Color.rgb(13, 95, 84)));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorText());
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(buttonBackground(isDarkMode() ? Color.rgb(37, 50, 48) : Color.rgb(228, 237, 234)));
        return button;
    }

    private GradientDrawable navActiveBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(7, 61, 58) : Color.rgb(221, 244, 239));
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private GradientDrawable bottomNavBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(7, 18, 36) : Color.WHITE);
        drawable.setCornerRadius(dp(24));
        if (!isDarkMode()) {
            drawable.setStroke(dp(1), Color.rgb(217, 231, 226));
        }
        return drawable;
    }

    private GradientDrawable transparentButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(17, 29, 26) : Color.WHITE);
        drawable.setCornerRadius(dp(18));
        return drawable;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(24, 43, 39) : Color.rgb(248, 252, 250));
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), isDarkMode() ? Color.rgb(45, 72, 66) : Color.rgb(217, 231, 226));
        return drawable;
    }

    private GradientDrawable pillBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(isDarkMode() ? Color.rgb(22, 55, 48) : Color.rgb(222, 242, 236));
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
        return isDarkMode() ? Color.rgb(3, 31, 26) : Color.rgb(238, 243, 241);
    }

    private int colorText() {
        return isDarkMode() ? Color.rgb(225, 241, 237) : Color.rgb(23, 34, 32);
    }

    private int colorMuted() {
        return isDarkMode() ? Color.rgb(152, 174, 168) : Color.rgb(92, 111, 106);
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
        if (statusView != null) {
            String value = message == null ? "" : message.trim();
            statusView.setText(value);
            statusView.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void runBackground(Runnable runnable) {
        new Thread(runnable, "loc-native").start();
    }

    private void runUi(Runnable runnable) {
        mainHandler.post(runnable);
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
