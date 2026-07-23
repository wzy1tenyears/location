package com.familylocation.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "FamilyLocationBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Start service after boot failed: " + exception.getMessage());
        }
    }
}
