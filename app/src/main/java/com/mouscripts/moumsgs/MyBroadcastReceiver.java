package com.mouscripts.moumsgs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class MyBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String PREFS_NAME = "MOUMSGS_PREFS";
    private static final String KEY_GATEWAY_ENABLED = "gateway_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String gatewayUrl = prefs.getString("gateway_url", "");
            boolean gatewayEnabled = prefs.getBoolean(KEY_GATEWAY_ENABLED, true);

            if (gatewayUrl.isEmpty() || !gatewayEnabled) {
                Log.i(TAG, "Gateway not configured, skipping auto-start");
                return;
            }

            Log.i(TAG, "Boot completed, starting foreground service");
            Intent serviceIntent = new Intent(context, MyForegroundService.class);

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting service on boot: " + e.getMessage());
            }
        }
    }
}
