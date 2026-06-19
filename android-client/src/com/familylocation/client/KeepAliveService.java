package com.familylocation.client;

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
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

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
    private static final String DEVICE_COOKIE_NAME = "loc_device";
    private static final int DEFAULT_REPORT_INTERVAL_SECONDS = 300;
    private static final int NOTIFICATION_ID = 10001;
    private static final String TAG = "位置服务";
    private static final String USER_AGENT = "loc-app/2.0.23";

    private Handler handler;
    private HandlerThread workerThread;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Runnable tickRunnable;
    private boolean locationUpdatesActive;
    private long currentUpdateIntervalMs;
    private String currentProviderSignature = "";
    private long lastReportAt;

    private static class ReportTarget {
        final String groupName;
        final String role;

        ReportTarget(String groupName, String role) {
            this.groupName = groupName;
            this.role = role;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("位置后台定位");
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createNotificationChannel();
        createLocationListener();
        createTickRunnable();
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
            stopLocationUpdates();
            if (handler != null && tickRunnable != null) {
                handler.removeCallbacks(tickRunnable);
            }
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (handler != null) {
            handler.post(() -> {
                syncLocationUpdates();
                Location location = bestLastKnownLocation();
                if (location != null) {
                    reportIfDue(location);
                }
            });
        }
        scheduleNextTick(2000);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        if (handler != null && tickRunnable != null) {
            handler.removeCallbacks(tickRunnable);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (shouldReport()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(new Intent(getApplicationContext(), KeepAliveService.class));
                } else {
                    startService(new Intent(getApplicationContext(), KeepAliveService.class));
                }
            } catch (Exception exception) {
                Log.w(TAG, "重启后台服务失败：" + exception.getMessage());
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createLocationListener() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                reportIfDue(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                // Required for older Android API compatibility.
            }

            @Override
            public void onProviderEnabled(String provider) {
                syncLocationUpdates();
            }

            @Override
            public void onProviderDisabled(String provider) {
                syncLocationUpdates();
            }
        };
    }

    private void createTickRunnable() {
        tickRunnable = () -> {
            if (shouldReport()) {
                refreshSettingsFromServer();
                syncLocationUpdates();

                Location location = bestLastKnownLocation();
                if (location != null) {
                    reportIfDue(location);
                }
            } else {
                stopLocationUpdates();
            }

            scheduleNextTick(reportIntervalMs());
        };
    }

    private void scheduleNextTick(long delayMs) {
        if (handler == null || tickRunnable == null) {
            return;
        }

        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, Math.max(1000, delayMs));
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes());
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
            // Service may not have entered foreground state.
        }
    }

    private int foregroundServiceTypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0;
        }

        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (hasLocationPermission()) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return types;
    }

    private void syncLocationUpdates() {
        if (!shouldReport() || !hasLocationPermission() || locationManager == null) {
            stopLocationUpdates();
            return;
        }

        long intervalMs = reportIntervalMs();
        List<String> providers = enabledLocationProviders();
        String providerSignature = providers.toString();

        if (locationUpdatesActive && currentUpdateIntervalMs == intervalMs && currentProviderSignature.equals(providerSignature)) {
            return;
        }

        stopLocationUpdates();

        try {
            if (providers.isEmpty()) {
                return;
            }

            boolean requested = false;
            for (String provider : providers) {
                try {
                    Looper looper = handler == null ? Looper.getMainLooper() : handler.getLooper();
                    locationManager.requestLocationUpdates(provider, intervalMs, 0f, locationListener, looper);
                    requested = true;
                } catch (Exception exception) {
                    Log.w(TAG, "启动 " + provider + " 定位失败：" + exception.getMessage());
                }
            }

            locationUpdatesActive = requested;
            currentUpdateIntervalMs = intervalMs;
            currentProviderSignature = providerSignature;
        } catch (SecurityException exception) {
            Log.w(TAG, "没有定位权限。");
            stopLocationUpdates();
        } catch (Exception exception) {
            Log.w(TAG, "启动定位失败：" + exception.getMessage());
            stopLocationUpdates();
        }
    }

    private void stopLocationUpdates() {
        if (!locationUpdatesActive || locationManager == null || locationListener == null) {
            locationUpdatesActive = false;
            currentUpdateIntervalMs = 0;
            return;
        }

        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {
            // Best effort cleanup.
        }

        locationUpdatesActive = false;
        currentUpdateIntervalMs = 0;
        currentProviderSignature = "";
    }

    private List<String> enabledLocationProviders() {
        List<String> providers = new ArrayList<>();
        if (locationManager == null) {
            return providers;
        }

        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER);
        }

        if (hasFineLocationPermission() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providers.add(LocationManager.GPS_PROVIDER);
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                providers.add(LocationManager.PASSIVE_PROVIDER);
            }
        } catch (Exception ignored) {
            // Some devices do not expose passive provider state consistently.
        }

        return providers;
    }

    private Location bestLastKnownLocation() {
        if (!hasLocationPermission() || locationManager == null) {
            return null;
        }

        Location best = null;
        String[] providers = new String[] {
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        };

        for (String provider : providers) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location == null) {
                    continue;
                }

                if (best == null || location.getTime() > best.getTime()) {
                    best = location;
                }
            } catch (SecurityException ignored) {
                return null;
            } catch (Exception ignored) {
                // Ignore disabled providers.
            }
        }

        return best;
    }

    private void reportIfDue(Location location) {
        if (location == null || !shouldReport()) {
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMs = reportIntervalMs();
        if (lastReportAt > 0 && now - lastReportAt < intervalMs) {
            return;
        }

        lastReportAt = now;
        reportLocation(location);
    }

    private void refreshSettingsFromServer() {
        String serverUrl = serverUrl();
        String cookie = sessionCookie(serverUrl);
        if (serverUrl.isEmpty() || cookie.isEmpty()) {
            return;
        }

        new Thread(() -> {
            try {
                HttpURLConnection connection = openJsonConnection(serverUrl + "api/settings.php" + settingsQuery(), "GET", cookie);
                int status = connection.getResponseCode();
                String response = readResponse(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();

                if (status >= 200 && status < 300) {
                    JSONObject payload = new JSONObject(response);
                    if (payload.optBoolean("ok", false)) {
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
                    }
                }
            } catch (Exception exception) {
                Log.w(TAG, "读取频率失败：" + exception.getMessage());
            }
        }).start();
    }

    private void reportLocation(Location location) {
        String serverUrl = serverUrl();
        String cookie = sessionCookie(serverUrl);
        List<ReportTarget> targets = reportTargets();
        if (serverUrl.isEmpty() || cookie.isEmpty() || targets.isEmpty()) {
            return;
        }

        new Thread(() -> {
            for (ReportTarget target : targets) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("group_name", target.groupName);
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

                    JSONObject encryptedBody = P2PCryptoSupport.encryptedReportOrNull(
                        (endpoint, payload) -> postJson(serverUrl, cookie, endpoint, payload),
                        this,
                        target.groupName,
                        body
                    );
                    JSONObject reportBody = encryptedBody == null ? body : encryptedBody;
                    HttpURLConnection connection = openJsonConnection(serverUrl + "api/report_location.php", "POST", cookie);
                    byte[] bytes = reportBody.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream outputStream = connection.getOutputStream()) {
                        outputStream.write(bytes);
                    }

                    int status = connection.getResponseCode();
                    readResponse(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                    connection.disconnect();
                    Log.i(TAG, "后台位置上报完成：" + target.groupName + " / " + status);
                } catch (Exception exception) {
                    Log.w(TAG, "后台位置上报失败：" + target.groupName + " / " + exception.getMessage());
                }
            }
        }).start();
    }

    private JSONObject postJson(String serverUrl, String cookie, String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection connection = openJsonConnection(serverUrl + endpoint, "POST", cookie);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bytes);
        }

        int status = connection.getResponseCode();
        String response = readResponse(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        connection.disconnect();
        JSONObject json = response.isEmpty() ? new JSONObject() : new JSONObject(response);
        if (status < 200 || status >= 300 || !json.optBoolean("ok", false)) {
            throw new IllegalStateException(json.optString("message", "请求失败。"));
        }
        return json;
    }

    private HttpURLConnection openJsonConnection(String url, String method, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cookie", cookie);

        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }

        return connection;
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

    private JSONArray mergeServerGroupsWithContinuity(JSONArray groups) {
        JSONArray currentSessions = parseGroupSessions(prefs().getString(KEY_GROUP_SESSIONS, ""));
        JSONArray merged = new JSONArray();

        for (int index = 0; index < groups.length(); index += 1) {
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
                // Keep the best-effort session list.
            }
        }

        return merged;
    }

    private boolean continuousForGroup(JSONArray sessions, String groupName) {
        for (int index = 0; index < sessions.length(); index += 1) {
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

        for (int index = 0; index < sessions.length(); index += 1) {
            JSONObject session = sessions.optJSONObject(index);
            if (!sessionShouldReport(session)) {
                continue;
            }

            String groupName = session.optString("group_name", "").trim();
            if (containsTarget(targets, groupName)) {
                continue;
            }

            targets.add(new ReportTarget(groupName, session.optString("role", "")));
        }

        if (!targets.isEmpty()) {
            return targets;
        }

        String fallbackGroupName = prefs().getString(KEY_GROUP_NAME, "");
        fallbackGroupName = fallbackGroupName == null ? "" : fallbackGroupName.trim();
        if (fallbackGroupName.isEmpty()) {
            return targets;
        }

        String fallbackRole = normalizeRole(prefs().getString(KEY_USER_ROLE, ""));
        boolean shouldReport = "monitor".equals(fallbackRole)
            || ("guardian".equals(fallbackRole) && prefs().getBoolean(KEY_GUARDIAN_CONTINUOUS_REPORTING, false));
        if (shouldReport) {
            targets.add(new ReportTarget(fallbackGroupName, fallbackRole));
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
            && hasNotificationPermission();
    }

    private long reportIntervalMs() {
        int seconds = Math.max(60, prefs().getInt(KEY_REPORT_INTERVAL_SECONDS, DEFAULT_REPORT_INTERVAL_SECONDS));
        return seconds * 1000L;
    }

    private String groupName() {
        List<ReportTarget> targets = reportTargets();
        if (!targets.isEmpty()) {
            return targets.get(0).groupName;
        }

        String value = prefs().getString(KEY_GROUP_NAME, "");
        return value == null ? "" : value.trim();
    }

    private String settingsQuery() throws Exception {
        String groupName = groupName();
        if (groupName.isEmpty()) {
            return "";
        }

        return "?group_name=" + URLEncoder.encode(groupName, "UTF-8");
    }

    private String serverUrl() {
        String value = prefs().getString(KEY_SERVER_URL, "");
        if (value == null) {
            return "";
        }

        value = value.trim();
        if (value.isEmpty()) {
            return "";
        }

        return value.endsWith("/") ? value : value + "/";
    }

    private String sessionCookie(String serverUrl) {
        if (serverUrl == null || serverUrl.isEmpty()) {
            return "";
        }

        String sessionCookie = prefs().getString(KEY_SESSION_COOKIE, "");
        String deviceCookie = DEVICE_COOKIE_NAME + "=" + deviceCookieValue();
        if (sessionCookie == null || sessionCookie.trim().isEmpty()) {
            return deviceCookie;
        }
        return sessionCookie + "; " + deviceCookie;
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
        channel.setDescription("保持位置服务运行。");

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("位置")
            .setContentText("后台定位上报运行中。")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_LOW)
            .build();
    }
}
