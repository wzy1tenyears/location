package com.familylocation.client;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
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

public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_NOTIFICATION = 1002;
    private static final int REQUEST_BACKGROUND_LOCATION = 1003;
    private static final int APP_VERSION_CODE = 33;
    private static final String APP_VERSION_NAME = "2.0.0";
    private static final String PREFS = "family_location";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_GROUP_NAME = "group_name";
    private static final String KEY_GUARDIAN_CONTINUOUS_REPORTING = "guardian_continuous_reporting";
    private static final String KEY_GROUP_SESSIONS = "group_sessions_json";
    private static final String KEY_REPORT_INTERVAL_SECONDS = "report_interval_seconds";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final String TAG = "FamilyLocationNative";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView statusView;
    private Button reportButton;
    private Button refreshButton;
    private JSONObject currentUser;
    private String selectedGroupName = "";
    private boolean reporting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();

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
                refreshLocations();
            } catch (Exception exception) {
                runUi(() -> showLoginWithMessage(exception.getMessage()));
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
        CheckBox terms = new CheckBox(this);
        terms.setText("我已同意用户协议、隐私条约和跨境加密传输协议");
        terms.setTextColor(colorText());
        terms.setChecked(true);
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
        CheckBox terms = new CheckBox(this);
        terms.setText("我已同意用户协议、隐私条款和跨境加密传输协议");
        terms.setTextColor(colorText());
        terms.setChecked(true);

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
        setStatus("请先填写邀请码；不确定类型时点“检查邀请码”。");
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
                refreshLocations();
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

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception exception) {
            setStatus("无法打开浏览器完成 Cloudflare 质询：" + exception.getMessage());
        }
    }

    private void showHome() {
        LinearLayout card = screen("位置看板");
        TextView userLine = infoPanel(userDisplayName(currentUser), false);
        reportButton = primaryButton("上报当前位置");
        refreshButton = secondaryButton("刷新位置");
        Button historyButton = secondaryButton("历史记录");
        Button settingsButton = secondaryButton("设置");
        Button groupsButton = secondaryButton("家庭组");
        Button announcementButton = secondaryButton("公告");
        Button ticketsButton = secondaryButton("工单");
        Button logoutButton = secondaryButton("退出登录");

        reportButton.setOnClickListener(view -> reportCurrentLocation());
        refreshButton.setOnClickListener(view -> refreshLocations());
        historyButton.setOnClickListener(view -> showHistory());
        settingsButton.setOnClickListener(view -> showSettings());
        groupsButton.setOnClickListener(view -> showGroups());
        announcementButton.setOnClickListener(view -> showAnnouncement());
        ticketsButton.setOnClickListener(view -> showTickets());
        logoutButton.setOnClickListener(view -> logout());

        card.addView(userLine, blockParams(16));
        card.addView(reportButton, blockParams(10));
        card.addView(refreshButton, blockParams(10));
        card.addView(historyButton, blockParams(10));
        card.addView(settingsButton, blockParams(10));
        card.addView(groupsButton, blockParams(10));
        card.addView(announcementButton, blockParams(10));
        card.addView(ticketsButton, blockParams(10));
        card.addView(logoutButton, blockParams(16));
        setScreen(card, false);
        requestStartupPermissions();
        syncKeepAliveService();
    }

    private void showHistory() {
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

        setStatus("正在加载历史记录");
        runBackground(() -> {
            try {
                String endpoint = "api/history.php?page=1&per_page=20";
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

    private void renderHistory(JSONObject response) {
        if (content == null) {
            return;
        }

        removeDynamicRows();
        JSONObject selectedGroup = response.optJSONObject("selected_group");
        if (selectedGroup != null) {
            TextView group = infoPanel("家庭组：" + selectedGroup.optString("display_name", selectedGroup.optString("group_name", "")), true);
            group.setTag("dynamic");
            content.addView(group, blockParams(10));
        }

        JSONArray history = response.optJSONArray("history");
        if (history == null || history.length() == 0) {
            TextView empty = infoPanel("暂无历史定位记录。", true);
            empty.setTag("dynamic");
            content.addView(empty, blockParams(10));
            setStatus("历史记录为空");
            return;
        }

        content.addView(sectionTitle("最近 20 条"), blockParams(8));
        for (int index = 0; index < history.length(); index += 1) {
            appendHistoryRow(history.optJSONObject(index));
        }
        JSONObject pagination = response.optJSONObject("pagination");
        String total = pagination == null ? "" : String.valueOf(pagination.optInt("total", history.length()));
        setStatus("已加载历史记录：" + history.length() + " / " + total);
    }

    private void appendHistoryRow(JSONObject location) {
        if (location == null) {
            return;
        }

        String name = location.optString("display_name", location.optString("username", "成员"));
        String encryptionMode = location.optString("encryption_mode", "");
        JSONObject diagnostics = location.optJSONObject("address_diagnostics");
        String address = diagnostics == null ? "" : diagnostics.optString("preferred_address", "");

        StringBuilder builder = new StringBuilder()
            .append(name)
            .append(" / ")
            .append(location.optString("role_label", ""))
            .append("\n时间：")
            .append(location.optString("created_at", ""));

        if (!encryptionMode.isEmpty()) {
            builder.append("\n端到端加密记录：").append(encryptionMode);
        } else {
            builder.append("\n坐标：")
                .append(formatCoordinate(location.optDouble("latitude", 0)))
                .append(", ")
                .append(formatCoordinate(location.optDouble("longitude", 0)));
            if (location.has("accuracy") && !location.isNull("accuracy")) {
                builder.append("\n精度：").append(formatCoordinate(location.optDouble("accuracy", 0))).append(" 米");
            }
        }

        if (!address.isEmpty()) {
            builder.append("\n地址：").append(address);
        }
        if (location.optBoolean("address_mismatch", false)) {
            builder.append("\n提示：定位/IP/WebRTC 地址不一致");
        }

        TextView row = infoPanel(builder.toString(), true);
        row.setTag("dynamic");
        content.addView(row, blockParams(10));
    }

    private void showAnnouncement() {
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
        setStatus("公告已加载");
    }

    private void showTickets() {
        LinearLayout card = screen("工单");
        Button refresh = primaryButton("刷新工单");
        Button create = secondaryButton("新建工单");
        Button back = secondaryButton("返回位置看板");
        refresh.setOnClickListener(view -> loadTickets());
        create.setOnClickListener(view -> showCreateTicket());
        back.setOnClickListener(view -> {
            showHome();
            refreshLocations();
        });
        card.addView(refresh, blockParams(10));
        card.addView(create, blockParams(10));
        card.addView(back, blockParams(16));
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

        content.addView(sectionTitle("最近工单"), blockParams(8));
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
            content.addView(sectionTitle("消息"), blockParams(8));
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
            content.addView(reply, blockParams(10));
            content.addView(submit, blockParams(8));
        }
        setStatus("工单详情已加载");
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
        LinearLayout card = screen("家庭组");
        EditText joinCode = input("输入 6 位家庭组号");
        Button join = primaryButton("加入家庭组");
        Button refresh = secondaryButton("刷新家庭组");
        Button leave = secondaryButton("退出当前家庭组");
        Button back = secondaryButton("返回位置看板");

        join.setOnClickListener(view -> joinGroupByCode(joinCode.getText().toString()));
        refresh.setOnClickListener(view -> renderGroups());
        leave.setOnClickListener(view -> leaveCurrentGroup());
        back.setOnClickListener(view -> {
            showHome();
            refreshLocations();
        });

        card.addView(joinCode, blockParams(10));
        card.addView(join, blockParams(10));
        card.addView(refresh, blockParams(10));
        card.addView(leave, blockParams(16));
        card.addView(back, blockParams(16));
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

        content.addView(sectionTitle("我的家庭组"), blockParams(8));
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

    private void leaveCurrentGroup() {
        String groupName = selectedGroupName.isEmpty() && currentUser != null ? currentUser.optString("group_name", "") : selectedGroupName;
        if (groupName.isEmpty()) {
            setStatus("当前没有可退出的家庭组。");
            return;
        }

        setStatus("正在退出家庭组");
        runBackground(() -> {
            try {
                JSONObject response = postJson("api/groups.php", new JSONObject()
                    .put("action", "leave_group")
                    .put("group_name", groupName));
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
        if (members != null && members.length() > 0) {
            content.addView(sectionTitle("成员准备状态"), blockParams(8));
            for (int index = 0; index < members.length(); index += 1) {
                JSONObject member = members.optJSONObject(index);
                if (member == null) {
                    continue;
                }
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

    private void showSettings() {
        LinearLayout card = screen("设置");
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
        Button saveContinuous = secondaryButton("保存持续上报设置");
        saveContinuous.setOnClickListener(view -> saveGuardianContinuous(guardianContinuous.isChecked()));
        Button changePassword = secondaryButton("修改密码");
        changePassword.setOnClickListener(view -> changePassword(
            currentPassword.getText().toString(),
            newPassword.getText().toString(),
            newPasswordConfirm.getText().toString()
        ));
        Button back = secondaryButton("返回位置看板");
        back.setOnClickListener(view -> {
            showHome();
            refreshLocations();
        });

        card.addView(account, blockParams(14));
        card.addView(environmentConsent, blockParams(8));
        card.addView(saveEnvironment, blockParams(14));
        card.addView(guardianContinuous, blockParams(8));
        card.addView(saveContinuous, blockParams(14));
        card.addView(sectionTitle("修改密码"), blockParams(8));
        card.addView(currentPassword, blockParams(10));
        card.addView(newPassword, blockParams(10));
        card.addView(newPasswordConfirm, blockParams(10));
        card.addView(changePassword, blockParams(14));
        card.addView(back, blockParams(0));
        setScreen(card, false);
        setStatus("当前上报间隔：" + prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, 300) + " 秒");
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
                runUi(() -> setStatus("环境数据设置已保存"));
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
            content.addView(sectionTitle("家庭组"), blockParams(6));
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

        content.addView(sectionTitle("最新位置"), blockParams(8));
        appendLocationSection("我", response.optJSONObject("mine"));
        appendLocationArray("监测端", response.optJSONArray("monitors"));
        appendLocationArray("监护端", response.optJSONArray("guardians"));
        setStatus("已刷新：" + response.optString("server_time", ""));
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

        content.addView(sectionTitle(title), blockParams(6));
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
        row.setTag("dynamic");
        content.addView(row, blockParams(10));
    }

    private void removeDynamicRows() {
        for (int index = content.getChildCount() - 1; index >= 0; index -= 1) {
            View child = content.getChildAt(index);
            if ("dynamic".equals(child.getTag())) {
                content.removeViewAt(index);
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
                String reportGroupName = selectedGroupName.isEmpty() && currentUser != null ? currentUser.optString("group_name", "") : selectedGroupName;
                JSONObject payload = new JSONObject()
                    .put("group_name", reportGroupName)
                    .put("latitude", location.getLatitude())
                    .put("longitude", location.getLongitude())
                    .put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL)
                    .put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL)
                    .put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL)
                    .put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL)
                    .put("location_provider", location.getProvider())
                    .put("location_time", String.valueOf(location.getTime()));
                JSONObject encryptedPayload = P2PCryptoSupport.encryptedReportOrNull(this::postJson, this, reportGroupName, payload);
                JSONObject response = postJson("api/report_location.php", encryptedPayload == null ? payload : encryptedPayload);
                runUi(() -> {
                    finishReport(response.optString("message", "位置已上报。"));
                    refreshLocations();
                });
            } catch (Exception exception) {
                runUi(() -> finishReport(exception.getMessage()));
            }
        });
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
        requestForegroundLocationPermissionIfNeeded();
    }

    private boolean requestForegroundLocationPermissionIfNeeded() {
        if (hasFineLocationPermission()) {
            requestBackgroundLocationPermissionIfNeeded();
            return false;
        }
        requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQUEST_LOCATION);
        return true;
    }

    private void requestBackgroundLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!hasFineLocationPermission()) {
            return;
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_BACKGROUND_LOCATION }, REQUEST_BACKGROUND_LOCATION);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("允许后台定位")
            .setMessage("持续上报需要在系统设置中把定位权限改为“始终允许”。")
            .setPositiveButton("去设置", (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("稍后", null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION) {
            requestForegroundLocationPermissionIfNeeded();
        } else if (requestCode == REQUEST_LOCATION) {
            requestBackgroundLocationPermissionIfNeeded();
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
        TextView message = body("请安装新版位置 " + versionName + " 后继续使用。");
        Button open = primaryButton("下载更新");
        open.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))));
        card.addView(message, blockParams(16));
        card.addView(open, blockParams(0));
        setScreen(card, true);
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
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(cardBackground());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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

    private void showLoading(String text) {
        LinearLayout card = screen("位置");
        TextView message = body(text);
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(message, blockParams(0));
        setScreen(card);
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setTag("dynamic");
        title.setText(text);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colorText());
        title.setPadding(0, dp(4), 0, 0);
        return title;
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

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(buttonBackground(Color.rgb(13, 95, 84)));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(colorText());
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(buttonBackground(isDarkMode() ? Color.rgb(37, 50, 48) : Color.rgb(228, 237, 234)));
        return button;
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
