package com.mouscripts.moumsgs;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class App extends Application {

    private static final String TAG = "MOUMSGS_App";
    private static App instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "Application created");
        startForegroundServiceWithDelay();
    }

    private void startForegroundServiceWithDelay() {
        mainHandler.postDelayed(() -> {
            if (!isServiceRunning(MyForegroundService.class)) {
                Log.i(TAG, "Starting foreground service from Application class");
                startForegroundServiceInternal();
            }
        }, 1000);
    }

    public void startForegroundServiceInternal() {
        Intent intent = new Intent(this, MyForegroundService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting service: " + e.getMessage());
            mainHandler.postDelayed(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                } catch (Exception ignored) {}
            }, 5000);
        }
    }

    public void restartService() {
        try {
            Intent intent = new Intent(this, MyForegroundService.class);
            stopService(intent);
        } catch (Exception ignored) {}
        mainHandler.postDelayed(this::startForegroundServiceInternal, 2000);
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking service: " + e.getMessage());
        }
        return false;
    }
}
