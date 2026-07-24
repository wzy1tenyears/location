package com.familylocation.client;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public final class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "FamilyLocationNative";
    private static final long MODE_TRANSITION_DELAY_MS = 1_000L;

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
        Log.i(TAG, "ACCESSIBILITY_KEEP_ALIVE_BOUND=true");
        syncLocationService();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ACCESSIBILITY_KEEP_ALIVE_BOUND=false");
        new BackgroundLocationController().requestRestart(this, MODE_TRANSITION_DELAY_MS);
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        new BackgroundLocationController().requestRestart(this, MODE_TRANSITION_DELAY_MS);
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately empty: this service never reads or stores accessibility events.
    }

    @Override
    public void onInterrupt() {
        // No accessibility feedback is produced, so there is nothing to interrupt.
    }

    private void syncLocationService() {
        new BackgroundLocationController().syncFromPreferences(this);
    }
}
