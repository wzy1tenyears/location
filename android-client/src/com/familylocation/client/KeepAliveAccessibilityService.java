package com.familylocation.client;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public final class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "FamilyLocationNative";
    private static final String PREFS = "family_location";
    private static final String KEY_USER_ROLE = "user_role";
    private static final String KEY_GROUP_NAME = "group_name";

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
        super.onDestroy();
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
        SharedPreferences preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        new BackgroundLocationController().sync(this, new BackgroundLocationController.State() {
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
}
