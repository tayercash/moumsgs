package com.mouscripts.moumsgs;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "KeepAlive";
    private static final String PREFS_NAME = "MOUMSGS_PREFS";
    private static final String KEY_GATEWAY_URL = "gateway_url";
    private static final String ACTION_KEEP_ALIVE = "com.mouscripts.moumsgs.KEEP_ALIVE";
    private static final long INTERVAL = 300000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!ACTION_KEEP_ALIVE.equals(intent.getAction())) return;

        Log.i(TAG, "Keep-alive check");

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(KEY_GATEWAY_URL, "");
        if (url.isEmpty()) return;

        MyForegroundService fs = MyForegroundService.getInstance();
        if (fs == null) {
            Log.w(TAG, "Service dead, restarting...");
            Intent svc = new Intent(context, MyForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        }
    }

    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, KeepAliveReceiver.class);
        intent.setAction(ACTION_KEEP_ALIVE);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getBroadcast(context, 2003, intent, flags);

        long triggerAt = SystemClock.elapsedRealtime() + INTERVAL;
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, INTERVAL, pi);
        } catch (Exception e) {
            try {
                am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, INTERVAL, pi);
            } catch (Exception ignored) {}
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, KeepAliveReceiver.class);
        intent.setAction(ACTION_KEEP_ALIVE);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getBroadcast(context, 2003, intent, flags);
        am.cancel(pi);
    }
}
