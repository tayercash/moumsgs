package com.mouscripts.moumsgs;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

public class MyForegroundService extends Service {

    private static final String TAG = "ForegroundService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS_NAME = "MOUMSGS_PREFS";
    private static final String KEY_GATEWAY_URL = "gateway_url";
    private static final String CHANNEL_ID = "MOUMSGS_Channel_v4";

    private static final long HEARTBEAT_INTERVAL = 10000;
    private static final long ALARM_FALLBACK_INTERVAL = 60000;

    private static MyForegroundService instance;
    private PhoneSocketService phoneSocket;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ScreenStateReceiver screenStateReceiver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };

    public static MyForegroundService getInstance() {
        return instance;
    }

    public PhoneSocketService getPhoneSocket() {
        return phoneSocket;
    }

    private PendingIntent getAlarmFallbackIntent() {
        Intent intent = new Intent(this, MyForegroundService.class);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, 1002, intent, flags);
        } else {
            return PendingIntent.getService(this, 1002, intent, flags);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "Service created");

        startForegroundNotification();
        acquireLocks();
        registerNetworkMonitor();
        registerScreenStateReceiver();
        KeepAliveReceiver.schedule(this);

        phoneSocket = new PhoneSocketService(this);
        phoneSocket.connect();

        heartbeatHandler.post(heartbeatRunnable);
        scheduleAlarmFallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand received");

        if (instance == null) instance = this;
        if (phoneSocket == null) {
            phoneSocket = new PhoneSocketService(this);
        }

        startForegroundNotification();
        reacquireLocks();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_GATEWAY_URL, "");

        if (!savedUrl.isEmpty()) {
            if (!phoneSocket.isConnected()) {
                Log.w(TAG, "Socket not connected, connecting...");
                phoneSocket.connect();
            } else {
                phoneSocket.sendHeartbeat();
            }
        }

        scheduleAlarmFallback();
        return START_STICKY;
    }

    private void tick() {
        if (phoneSocket == null) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_GATEWAY_URL, "");
        if (savedUrl.isEmpty()) return;

        if (!phoneSocket.isConnected()) {
            phoneSocket.connect();
        } else {
            phoneSocket.sendHeartbeat();
        }
    }

    public void startSocket() {
        if (phoneSocket != null) {
            phoneSocket.connect();
        }
    }

    private void scheduleAlarmFallback() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = getAlarmFallbackIntent();
            long triggerAt = SystemClock.elapsedRealtime() + ALARM_FALLBACK_INTERVAL;

            try {
                alarmManager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, pi), pi);
            } catch (Exception e) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    } else {
                        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    }
                } catch (Exception e2) {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling alarm fallback", e);
        }
    }

    private void registerNetworkMonitor() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "Network restored, connecting socket...");
                    if (phoneSocket != null) {
                        phoneSocket.connect();
                    }
                }

                @Override
                public void onLost(Network network) {
                    Log.w(TAG, "Network lost");
                }
            };

            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            connectivityManager.registerNetworkCallback(builder.build(), networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "Network monitor registration failed", e);
        }
    }

    private void registerScreenStateReceiver() {
        screenStateReceiver = new ScreenStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            registerReceiver(screenStateReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "Screen state receiver registration failed", e);
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MOUMSGS:WakeLock");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire();
                }
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (wifiLock == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MOUMSGS:WifiLock");
                    } else {
                        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MOUMSGS:WifiLock");
                    }
                    wifiLock.setReferenceCounted(false);
                }
                if (!wifiLock.isHeld()) {
                    wifiLock.acquire();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lock acquisition failed", e);
        }
    }

    private void reacquireLocks() {
        acquireLocks();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "Service being destroyed, attempting restart...");
        instance = null;

        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        KeepAliveReceiver.cancel(this);

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (phoneSocket != null) phoneSocket.destroy();
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
        }
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception ignored) {}
        }

        Intent restartIntent = new Intent(this, MyForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w(TAG, "Task removed by user, scheduling restart...");
        Intent restartIntent = new Intent(this, MyForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w(TAG, "Low memory, service may be killed");
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(NotificationHelper.CHANNEL_SERVICE) == null) {
                NotificationChannel channel = new NotificationChannel(
                        NotificationHelper.CHANNEL_SERVICE,
                        "Service Status",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setShowBadge(false);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(channel);
            }

            Notification notification = new Notification.Builder(this, NotificationHelper.CHANNEL_SERVICE)
                    .setContentTitle("MOU MSGS Active")
                    .setContentText("Connected to SMS Gateway server")
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .build();

            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground notification, retrying...", e);
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                } catch (Exception e2) {
                    Log.e(TAG, "Retry also failed", e2);
                }
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                Log.i(TAG, "Screen on, checking connection...");
                if (phoneSocket != null && !phoneSocket.isConnected()) {
                    phoneSocket.connect();
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.i(TAG, "Screen off, holding locks...");
                reacquireLocks();
            }
        }
    }
}
