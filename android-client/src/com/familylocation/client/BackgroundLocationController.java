package com.familylocation.client;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

final class BackgroundLocationController {
    interface State {
        String role();

        String groupName();

        boolean guardianContinuousEnabled(String groupName);
    }

    private static final String TAG = "BackgroundLocation";

    void sync(Context context, State state) {
        Intent intent = new Intent(context, KeepAliveService.class);
        try {
            if (shouldRun(state)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } else {
                context.stopService(intent);
            }
        } catch (Exception exception) {
            Log.w(TAG, "KeepAlive sync failed: " + exception.getMessage());
        }
    }

    void stop(Context context) {
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (Exception ignored) {
            // Service may not be running.
        }
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
