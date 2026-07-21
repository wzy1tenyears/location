package com.familylocation.client;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public final class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "位置保活辅助服务";

    static boolean isEnabled(Context context) {
        try {
            int enabled = Settings.Secure.getInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            );
            String services = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return enabled == 1
                && AccessibilityKeepAlivePolicy.isOwnServiceEnabled(services, context.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            Intent service = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception exception) {
            Log.w(TAG, "同步位置服务失败：" + exception.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately empty: this service never reads or stores accessibility events.
    }

    @Override
    public void onInterrupt() {
        // No accessibility feedback is produced, so there is nothing to interrupt.
    }
}
