package com.familylocation.admin;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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
    private static final String PREFS = "family_location_admin";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String DEFAULT_SERVER_URL = "";
    private static final int APP_VERSION_CODE = 33;
    private static final String APP_VERSION_NAME = "2.0.0";
    private static final String ADMIN_APK_NAME = "location-admin-release.apk";
    private static final String USER_AGENT = "loc-admin-app/" + APP_VERSION_NAME + " loc-app/" + APP_VERSION_NAME;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        String serverUrl = storedServerUrl();
        if (serverUrl.isEmpty()) {
            serverUrl = readAssetServerUrl();
        }
        if (serverUrl.isEmpty()) {
            serverUrl = DEFAULT_SERVER_URL;
        }
        if (serverUrl.isEmpty()) {
            showServerSetup();
            return;
        }
        prefs().edit().putString(KEY_SERVER_URL, normalizeUrl(serverUrl)).apply();
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

    private void showServerSetup() {
        LinearLayout card = screen("后台服务器");
        EditText input = input("https://example.com/");
        Button save = primaryButton("保存");
        save.setOnClickListener(view -> {
            String url = normalizeUrl(input.getText().toString());
            if (url.isEmpty() || !url.startsWith("https://")) {
                setStatus("请输入 HTTPS 服务器地址。");
                return;
            }
            prefs().edit().putString(KEY_SERVER_URL, url).apply();
            showLogin("");
        });
        card.addView(body("后台端独立于用户端，请填写同一个服务端地址。"), blockParams(12));
        card.addView(input, blockParams(12));
        card.addView(save, blockParams(0));
        setScreen(card, true);
    }

    private void showLogin(String message) {
        LinearLayout card = screen("后台登录");
        EditText username = input("管理员账号");
        EditText password = input("管理员密码");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button login = primaryButton("登录后台");
        login.setOnClickListener(view -> login(username.getText().toString(), password.getText().toString()));
        Button server = secondaryButton("修改服务器地址");
        server.setOnClickListener(view -> showServerSetup());
        card.addView(username, blockParams(12));
        card.addView(password, blockParams(12));
        card.addView(login, blockParams(10));
        card.addView(server, blockParams(0));
        setScreen(card, true);
        setStatus(message);
    }

    private void login(String username, String password) {
        if (username.trim().isEmpty() || password.isEmpty()) {
            setStatus("请输入后台账号和密码。");
            return;
        }

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

        runUi(() -> {
            setStatus("请在打开的浏览器完成 Cloudflare 质询，完成后回到 App。");
            openExternalUrl(challengeUrl);
        });

        long deadline = System.currentTimeMillis() + 300000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2500L);
            JSONObject poll = getJson("api/app_challenge.php?id=" + urlEncode(challengeId) + "&secret=" + urlEncode(challengeSecret));
            if (poll.optBoolean("verified", false)) {
                String token = poll.optString("app_challenge_token", "");
                if (!token.isEmpty()) {
                    return token;
                }
            }
            runUi(() -> setStatus("等待 Cloudflare 质询完成……"));
        }

        throw new IllegalStateException("Cloudflare 质询超时，请重新登录。");
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

    private void showAdminUpdate(JSONObject update, String redirectPath) {
        String versionName = update.optString("latest_version_name", "");
        int versionCode = update.optInt("latest_version_code", 0);
        boolean force = update.optBoolean("force_update", true);
        String apkUrl = update.optString("apk_url", "");
        LinearLayout card = screen("后台更新");
        String message = "检测到后台 App 新版本：" + versionName + " (" + versionCode + ")" +
            "\n更新包放在 private 目录，只能在后台登录后下载。";
        if (force) {
            message += "\n当前版本需要先更新后再进入后台。";
        }
        card.addView(infoPanel(message), blockParams(14));
        Button download = primaryButton("下载后台更新");
        download.setOnClickListener(view -> downloadAdminUpdate(apkUrl));
        card.addView(download, blockParams(10));
        if (!force) {
            Button later = secondaryButton("稍后进入后台");
            later.setOnClickListener(view -> showAdminHome(redirectPath));
            card.addView(later, blockParams(0));
        }
        setScreen(card, true);
        setStatus("已登录，正在等待下载后台更新。");
    }

    private void downloadAdminUpdate(String apkUrl) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
            request.setTitle("位置后台更新");
            request.setDescription("正在下载 " + ADMIN_APK_NAME);
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, ADMIN_APK_NAME);
            request.addRequestHeader("User-Agent", USER_AGENT);
            String cookies = cookieHeader();
            if (!cookies.trim().isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                throw new IllegalStateException("系统下载服务不可用。");
            }
            long downloadId = manager.enqueue(request);
            setStatus("后台 APK 已加入下载队列，完成后请从下载通知安装。ID=" + downloadId);
        } catch (Exception exception) {
            setStatus("下载后台更新失败：" + exception.getMessage());
        }
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception exception) {
            setStatus("无法打开浏览器完成 Cloudflare 质询：" + exception.getMessage());
        }
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

        Button refresh = primaryButton("刷新后台数据");
        refresh.setOnClickListener(view -> showAdminHome(redirectPath));
        Button relogin = secondaryButton("重新登录");
        relogin.setOnClickListener(view -> showLogin(""));
        card.addView(refresh, blockParams(10));
        card.addView(relogin, blockParams(0));
        setScreen(card, false);
        setStatus("后台数据已加载：" + response.optString("server_time", ""));
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
        card.addView(statusView, blockParams(16));
        return card;
    }

    private void setScreen(LinearLayout card) {
        setScreen(card, false);
    }

    private void setScreen(LinearLayout card, boolean center) {
        content = card;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setGravity((center ? Gravity.CENTER : Gravity.TOP) | Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(colorSurface());
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(root);
        setContentView(scroll);
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
            statusView.setText(message == null ? "" : message);
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
