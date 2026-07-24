package com.familylocation.client;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

final class BackgroundLocationController {
    static final String EXTRA_FOREGROUND_START =
        "com.familylocation.client.extra.FOREGROUND_START";

    interface State {
        String role();

        String groupName();

        boolean guardianContinuousEnabled(String groupName);
    }

    private static final String TAG = "BackgroundLocation";
    private static final String PREFS = "family_location";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_GROUP_NAME = "group_name";
    private static final String RESTART_ACTION =
        "com.familylocation.client.action.RESTART_KEEP_ALIVE";
    private static final int RESTART_REQUEST_CODE = 10002;
    private static final long MINIMUM_RESTART_DELAY_MS = 1_000L;

    void sync(Context context, State state) {
        Intent intent = new Intent(context, KeepAliveService.class);
        try {
            if (shouldRun(state)) {
                cancelRestart(context);
                start(context, intent);
            } else {
                stop(context);
            }
        } catch (Exception exception) {
            Log.w(TAG, "KeepAlive sync failed: " + exception.getMessage());
        }
    }

    void syncFromPreferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sync(context, new State() {
            @Override
            public String role() {
                return preferences.getString(KEY_USER_ROLE, "");
            }

            @Override
            public String groupName() {
                return preferences.getString(KEY_GROUP_NAME, "");
            }

            @Override
            public boolean guardianContinuousEnabled(String groupName) {
                return preferences.getBoolean("guardian_continuous_reporting_" + groupName, false);
            }
        });
    }

    void stop(Context context) {
        cancelRestart(context);
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (Exception ignored) {
            // Service may not be running.
        }
    }

    void requestRestart(Context context, long delayMs) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(MINIMUM_RESTART_DELAY_MS, delayMs);
        PendingIntent operation = restartOperation(context, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation);
        } else {
            manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation);
        }
    }

    void cancelRestart(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent operation = restartOperation(context, PendingIntent.FLAG_NO_CREATE);
        if (operation != null) {
            manager.cancel(operation);
            operation.cancel();
        }
    }

    private void start(Context context, Intent intent) {
        boolean accessibilityEnabled = KeepAliveAccessibilityService.isEnabled(context);
        boolean notificationMode = AccessibilityKeepAlivePolicy.usesForegroundNotification(accessibilityEnabled);
        if (notificationMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(EXTRA_FOREGROUND_START, true);
            context.startForegroundService(intent);
            return;
        }
        try {
            context.startService(intent);
        } catch (IllegalStateException backgroundStartBlocked) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                throw backgroundStartBlocked;
            }
            intent.putExtra(EXTRA_FOREGROUND_START, true);
            context.startForegroundService(intent);
        }
    }

    private PendingIntent restartOperation(Context context, int baseFlags) {
        Intent intent = new Intent(context, KeepAliveRestartReceiver.class).setAction(RESTART_ACTION);
        int flags = baseFlags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, RESTART_REQUEST_CODE, intent, flags);
    }

    private boolean shouldRun(State state) {
        String groupName = state.groupName();
        if (groupName == null || groupName.isEmpty()) {
            return false;
        }

        String role = normalizeRole(state.role());
        return "monitor".equals(role) || ("guardian".equals(role) && state.guardianContinuousEnabled(groupName));
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return "";
        }
        String value = role.trim().toLowerCase(java.util.Locale.US);
        if ("parent".equals(value)) {
            return "monitor";
        }
        return value;
    }
}
