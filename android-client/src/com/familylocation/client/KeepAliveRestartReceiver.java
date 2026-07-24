package com.familylocation.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class KeepAliveRestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        new BackgroundLocationController().syncFromPreferences(context);
    }
}
