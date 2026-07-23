package com.familylocation.client;

import com.familylocation.net.JsonApiClient;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "family_location_keep_alive";
    private static final String PREFS = "family_location";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_GROUP_NAME = "group_name";
    private static final String KEY_GUARDIAN_CONTINUOUS_REPORTING = "guardian_continuous_reporting";
    private static final String KEY_GROUP_SESSIONS = "group_sessions_json";
    private static final String KEY_REPORT_INTERVAL_SECONDS = "report_interval_seconds";
    private static final String KEY_DEVICE_COOKIE = "device_cookie";
    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String KEY_LAST_BACKGROUND_REPORT_AT = "last_background_report_at";
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final int DEFAULT_REPORT_INTERVAL_SECONDS = 300;
    private static final int NOTIFICATION_ID = 10001;
    private static final String TAG = "位置服务";
    private static final String USER_AGENT = "loc-app/2.3.4";
    private static final JsonApiClient API_CLIENT = new JsonApiClient(USER_AGENT, 12_000, 15_000);

    private HandlerThread locationThread;
    private Handler handler;
    private ExecutorService networkExecutor;
    private BackgroundLocationSampler locationSampler;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Runnable tickRunnable;

    private final AtomicBoolean reportInFlight = new AtomicBoolean(false);
    private final AtomicBoolean settingsInFlight = new AtomicBoolean(false);
    private long nextTickAtElapsed;
    private long lastSuccessfulReportAt;
    private long lastSettingsAttemptAt;
    private int consecutiveFailures;
    private boolean networkCallbackRegistered;

    private static final class ReportTarget {
        final String groupName;
        final String role;

        ReportTarget(String groupName, String role) {
            this.groupName = groupName;
            this.role = role;
        }
    }

    private static final class ReportResult {
        final boolean success;
        final boolean authenticationExpired;
        final String message;

        private ReportResult(boolean success, boolean authenticationExpired, String message) {
            this.success = success;
            this.authenticationExpired = authenticationExpired;
            this.message = message;
        }

        static ReportResult success() {
            return new ReportResult(true, false, "");
        }

        static ReportResult failure(String message) {
            return new ReportResult(false, false, message);
        }

        static ReportResult authenticationExpired(String message) {
            return new ReportResult(false, true, message);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationThread = new HandlerThread("位置后台采样");
        locationThread.start();
        handler = new Handler(locationThread.getLooper());
        networkExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "位置后台网络");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationSampler = new BackgroundLocationSampler(locationManager, handler);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        tickRunnable = this::runTick;
        lastSuccessfulReportAt = prefs().getLong(KEY_LAST_BACKGROUND_REPORT_AT, 0L);
        createNotificationChannel();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundCompat();
        } catch (Exception exception) {
            Log.w(TAG, "进入前台服务失败：" + exception.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!shouldReport()) {
            stopServiceCleanly();
            return START_NOT_STICKY;
        }
        scheduleTick(0L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null && tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
        }
        if (locationSampler != null) {
            locationSampler.cancel();
        }
        unregisterNetworkCallback();
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
            networkExecutor = null;
        }
        if (locationThread != null) {
            locationThread.quitSafely();
            locationThread = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (shouldReport()) {
            scheduleTick(0L);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runTick() {
        nextTickAtElapsed = 0L;
        if (!shouldReport()) {
            stopServiceCleanly();
            return;
        }

        refreshSettingsIfDue();
        long now = System.currentTimeMillis();
        long intervalMs = reportIntervalMs();
        long regularDelay = BackgroundReportPolicy.nextRegularDelayMs(now, lastSuccessfulReportAt, intervalMs);
        if (regularDelay > 0) {
            scheduleTick(regularDelay);
            return;
        }
        if (reportInFlight.get() || (locationSampler != null && locationSampler.isActive())) {
            scheduleTick(5000L);
            return;
        }
        if (!networkAvailable()) {
            scheduleTick(BackgroundReportPolicy.LOCATION_RETRY_DELAY_MS);
            return;
        }

        locationSampler.sample(
            hasFineLocationPermission(),
            BackgroundReportPolicy.LOCATION_SAMPLE_TIMEOUT_MS,
            this::onLocationSampled
        );
    }

    private void onLocationSampled(Location location) {
        long now = System.currentTimeMillis();
        if (location == null || !BackgroundReportPolicy.locationIsFresh(location.getTime(), now, reportIntervalMs())) {
            Log.w(TAG, "本轮没有可用的新位置，稍后重试。");
            scheduleTick(BackgroundReportPolicy.LOCATION_RETRY_DELAY_MS);
            return;
        }
        if (!reportInFlight.compareAndSet(false, true)) {
            return;
        }

        Location snapshot = new Location(location);
        networkExecutor.execute(() -> {
            ReportResult result = reportLocationNow(snapshot);
            postToHandler(() -> finishReport(result));
        });
    }

    private void finishReport(ReportResult result) {
        reportInFlight.set(false);
        if (result.authenticationExpired) {
            Log.w(TAG, "后台会话已失效，停止位置服务：" + result.message);
            stopServiceCleanly();
            return;
        }
        if (result.success) {
            consecutiveFailures = 0;
            lastSuccessfulReportAt = System.currentTimeMillis();
            prefs().edit().putLong(KEY_LAST_BACKGROUND_REPORT_AT, lastSuccessfulReportAt).apply();
            updateNotification("位置已上报，后台服务运行中。");
            scheduleTick(reportIntervalMs());
            return;
        }

        consecutiveFailures++;
        long retryDelay = BackgroundReportPolicy.retryDelayMs(consecutiveFailures);
        Log.w(TAG, "后台位置上报失败，" + (retryDelay / 1000L) + " 秒后重试：" + result.message);
        updateNotification("上报暂时失败，正在自动重试。");
        scheduleTick(retryDelay);
    }

    private ReportResult reportLocationNow(Location location) {
        String serverUrl = serverUrl();
        String cookie = sessionCookie(serverUrl);
        List<ReportTarget> targets = reportTargets();
        if (serverUrl.isEmpty() || cookie.isEmpty() || targets.isEmpty()) {
            return ReportResult.authenticationExpired("缺少登录会话或家庭组。" );
        }

        String firstFailure = "";
        for (ReportTarget target : targets) {
            try {
                JSONObject body = locationPayload(location, target.groupName);
                JSONObject encryptedBody = P2PCryptoSupport.encryptedReportOrNull(
                    (endpoint, payload) -> postJson(serverUrl, cookie, endpoint, payload),
                    this,
                    target.groupName,
                    body
                );
                postJson(serverUrl, cookie, ApiPaths.REPORT_LOCATION, encryptedBody == null ? body : encryptedBody);
                Log.i(TAG, "后台位置上报完成：" + target.groupName);
            } catch (JsonApiClient.HttpStatusException exception) {
                if (exception.status == 401 || exception.status == 403) {
                    return ReportResult.authenticationExpired(exception.getMessage());
                }
                if (firstFailure.isEmpty()) {
                    firstFailure = exception.getMessage();
                }
            } catch (Exception exception) {
                if (firstFailure.isEmpty()) {
                    firstFailure = exception.getMessage();
                }
            }
        }
        return firstFailure.isEmpty() ? ReportResult.success() : ReportResult.failure(firstFailure);
    }

    private JSONObject locationPayload(Location location, String groupName) throws Exception {
        JSONObject body = new JSONObject();
        body.put("group_name", groupName);
        body.put("latitude", location.getLatitude());
        body.put("longitude", location.getLongitude());
        if (location.hasAltitude()) {
            body.put("altitude", location.getAltitude());
        }
        if (location.hasAccuracy()) {
            body.put("accuracy", location.getAccuracy());
        }
        if (location.hasBearing()) {
            body.put("heading", location.getBearing());
        }
        if (location.hasSpeed()) {
            body.put("speed", location.getSpeed());
        }
        body.put("location_provider", location.getProvider());
        body.put("location_time", String.valueOf(location.getTime()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (location.hasVerticalAccuracy()) {
                body.put("vertical_accuracy", location.getVerticalAccuracyMeters());
            }
            if (location.hasBearingAccuracy()) {
                body.put("bearing_accuracy", location.getBearingAccuracyDegrees());
            }
            if (location.hasSpeedAccuracy()) {
                body.put("speed_accuracy", location.getSpeedAccuracyMetersPerSecond());
            }
        }
        return body;
    }

    private void refreshSettingsIfDue() {
        long now = System.currentTimeMillis();
        if (lastSettingsAttemptAt > 0
            && now - lastSettingsAttemptAt < BackgroundReportPolicy.SETTINGS_REFRESH_INTERVAL_MS) {
            return;
        }
        if (!settingsInFlight.compareAndSet(false, true)) {
            return;
        }
        lastSettingsAttemptAt = now;
        String serverUrl = serverUrl();
        String cookie = sessionCookie(serverUrl);
        if (serverUrl.isEmpty() || cookie.isEmpty()) {
            settingsInFlight.set(false);
            return;
        }

        networkExecutor.execute(() -> {
            try {
                JSONObject payload = API_CLIENT.get(serverUrl + ApiPaths.SETTINGS + settingsQuery(), cookie, null);
                int seconds = Math.max(60, payload.optInt("report_interval_seconds", DEFAULT_REPORT_INTERVAL_SECONDS));
                SharedPreferences.Editor editor = prefs().edit().putInt(KEY_REPORT_INTERVAL_SECONDS, seconds);
                JSONObject user = payload.optJSONObject("user");
                if (user != null) {
                    editor.putString(KEY_USER_ROLE, normalizeRole(user.optString("role", prefs().getString(KEY_USER_ROLE, ""))));
                    editor.putString(KEY_GROUP_NAME, user.optString("group_name", prefs().getString(KEY_GROUP_NAME, "")));
                    JSONArray groups = user.optJSONArray("groups");
                    if (groups != null) {
                        editor.putString(KEY_GROUP_SESSIONS, mergeServerGroupsWithContinuity(groups).toString());
                    }
                }
                editor.apply();
            } catch (Exception exception) {
                Log.w(TAG, "读取后台上报设置失败：" + exception.getMessage());
            } finally {
                settingsInFlight.set(false);
            }
        });
    }

    private JSONObject postJson(String serverUrl, String cookie, String endpoint, JSONObject payload) throws Exception {
        return API_CLIENT.post(serverUrl + endpoint, payload, cookie, null);
    }

    private void scheduleTick(long delayMs) {
        if (handler == null || tickRunnable == null) {
            return;
        }
        long target = SystemClock.elapsedRealtime() + Math.max(0L, delayMs);
        if (nextTickAtElapsed > 0 && nextTickAtElapsed <= target) {
            return;
        }
        handler.removeCallbacks(tickRunnable);
        nextTickAtElapsed = target;
        handler.postDelayed(tickRunnable, Math.max(0L, delayMs));
    }

    private void postToHandler(Runnable runnable) {
        if (handler != null) {
            handler.post(runnable);
        }
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null || networkCallbackRegistered) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleTick(0L);
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback, handler);
            networkCallbackRegistered = true;
        } catch (Exception exception) {
            Log.w(TAG, "注册网络监听失败：" + exception.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered || connectivityManager == null || networkCallback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // Callback may already be unregistered by the system.
        }
        networkCallbackRegistered = false;
    }

    private boolean networkAvailable() {
        if (connectivityManager == null) {
            return true;
        }
        try {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return true;
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification("后台定位上报运行中。");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(message));
        }
    }

    private void stopServiceCleanly() {
        if (locationSampler != null) {
            locationSampler.cancel();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private JSONArray mergeServerGroupsWithContinuity(JSONArray groups) {
        JSONArray currentSessions = parseGroupSessions(prefs().getString(KEY_GROUP_SESSIONS, ""));
        JSONArray merged = new JSONArray();
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) {
                continue;
            }
            String groupName = group.optString("group_name", "").trim();
            if (groupName.isEmpty()) {
                continue;
            }
            JSONObject session = new JSONObject();
            try {
                session.put("group_name", groupName);
                session.put("role", normalizeRole(group.optString("role", "")));
                session.put("continuous", continuousForGroup(currentSessions, groupName));
                merged.put(session);
            } catch (Exception ignored) {
                // Keep the usable group entries.
            }
        }
        return merged;
    }

    private boolean continuousForGroup(JSONArray sessions, String groupName) {
        for (int index = 0; index < sessions.length(); index++) {
            JSONObject session = sessions.optJSONObject(index);
            if (session != null && groupName.equals(session.optString("group_name", ""))) {
                return session.optBoolean("continuous", false);
            }
        }
        String currentGroupName = prefs().getString(KEY_GROUP_NAME, "");
        return groupName.equals(currentGroupName)
            && prefs().getBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false);
    }

    private List<ReportTarget> reportTargets() {
        List<ReportTarget> targets = new ArrayList<>();
        JSONArray sessions = parseGroupSessions(prefs().getString(KEY_GROUP_SESSIONS, ""));
        for (int index = 0; index < sessions.length(); index++) {
            JSONObject session = sessions.optJSONObject(index);
            if (!sessionShouldReport(session)) {
                continue;
            }
            String groupName = session.optString("group_name", "").trim();
            if (!containsTarget(targets, groupName)) {
                targets.add(new ReportTarget(groupName, normalizeRole(session.optString("role", ""))));
            }
        }
        if (!targets.isEmpty()) {
            return targets;
        }

        String groupName = value(prefs().getString(KEY_GROUP_NAME, ""));
        String role = normalizeRole(prefs().getString(KEY_USER_ROLE, ""));
        boolean enabled = "monitor".equals(role)
            || ("guardian".equals(role) && prefs().getBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false));
        if (enabled && !groupName.isEmpty()) {
            targets.add(new ReportTarget(groupName, role));
        }
        return targets;
    }

    private boolean containsTarget(List<ReportTarget> targets, String groupName) {
        for (ReportTarget target : targets) {
            if (target.groupName.equals(groupName)) {
                return true;
            }
        }
        return false;
    }

    private JSONArray parseGroupSessions(String value) {
        try {
            return new JSONArray(value == null ? "" : value);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private boolean sessionShouldReport(JSONObject session) {
        if (session == null || session.optString("group_name", "").trim().isEmpty()) {
            return false;
        }
        String role = normalizeRole(session.optString("role", ""));
        return "monitor".equals(role)
            || ("guardian".equals(role) && session.optBoolean("continuous", false));
    }

    private boolean shouldReport() {
        return !reportTargets().isEmpty()
            && hasLocationPermission()
            && hasBackgroundLocationPermission()
            && hasNotificationPermission()
            && !value(prefs().getString(KEY_SESSION_COOKIE, "")).isEmpty();
    }

    private long reportIntervalMs() {
        return BackgroundReportPolicy.reportIntervalMs(
            prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, DEFAULT_REPORT_INTERVAL_SECONDS)
        );
    }

    private String settingsQuery() throws Exception {
        List<ReportTarget> targets = reportTargets();
        if (targets.isEmpty()) {
            return "";
        }
        return "?group_name=" + URLEncoder.encode(targets.get(0).groupName, "UTF-8");
    }

    private String serverUrl() {
        String value = value(prefs().getString(KEY_SERVER_URL, ""));
        if (value.isEmpty()) {
            return "";
        }
        return value.endsWith("/") ? value : value + "/";
    }

    private String sessionCookie(String serverUrl) {
        if (serverUrl == null || serverUrl.isEmpty()) {
            return "";
        }
        String sessionCookie = value(prefs().getString(KEY_SESSION_COOKIE, ""));
        if (sessionCookie.isEmpty()) {
            return "";
        }
        return sessionCookie + "; " + DEVICE_COOKIE_NAME + "=" + deviceCookieValue();
    }

    private String deviceCookieValue() {
        String value = value(prefs().getString(KEY_DEVICE_COOKIE, ""));
        if (value.matches("^[a-f0-9]{64}$")) {
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

    private String normalizeRole(String role) {
        String value = value(role).toLowerCase(java.util.Locale.US);
        return "parent".equals(value) ? "monitor" : value;
    }

    private String value(String text) {
        return text == null ? "" : text.trim();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean hasLocationPermission() {
        return hasFineLocationPermission()
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "位置",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("保持后台位置上报运行。");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("位置")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
    }
}
