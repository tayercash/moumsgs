package com.mouscripts.moumsgs;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements PhoneSocketService.SocketListener {

    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private MaterialToolbar toolbar;
    private FragmentManager fragmentManager;
    private SharedPreferences prefs;
    private TextView connectionStatusText;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "MOUMSGS_PREFS";
    private static final String KEY_FIRST_LAUNCH_DONE = "first_launch_done";
    private static final String KEY_THEME_MODE = "theme_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        forceEnglishLocale();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int themeMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(themeMode);
        super.onCreate(savedInstanceState);
        
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        
        setContentView(R.layout.activity_main);

        NotificationHelper.init(this);
        startForegroundService();
        requestAppPermissions();
        requestBatteryOptimization();

        this.prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        fragmentManager = getSupportFragmentManager();
        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navView);
        toolbar = findViewById(R.id.toolbar);

        addConnectionStatusIndicator();
        attachSocketListener();

        setupBackStackListener();
        setupNavigation();

        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("sender")) {
            String sender = intent.getStringExtra("sender");
            openConversation(sender);
        } else {
            boolean firstLaunch = !this.prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false);
            if (firstLaunch) {
                openFragment(new SettingsFragment(), "Settings");
                navView.setCheckedItem(R.id.nav_settings);
            } else {
                openFragment(new MessagesFragment(), "Messages");
                navView.setCheckedItem(R.id.nav_messages);
            }
            showHamburger();
        }
    }

    private void openConversation(String sender) {
        DatabaseHelper dbHelper = new DatabaseHelper(this);
        List<SmsItem> messages = dbHelper.getMessagesBySender(sender);
        dbHelper.markAsRead(sender);
        NotificationHelper.dismissConversationNotification(this, sender);
        
        openFragment(ConversationDetailFragment.newInstance(sender, messages), "Messages");
        navView.setCheckedItem(R.id.nav_messages);
    }

    private void forceEnglishLocale() {
        Locale locale = new Locale("en");
        Locale.setDefault(locale);
        Resources resources = getResources();
        Configuration config = resources.getConfiguration();
        DisplayMetrics dm = resources.getDisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale);
        } else {
            config.locale = locale;
        }
        resources.updateConfiguration(config, dm);
    }

    private void attachSocketListener() {
        MyForegroundService fs = MyForegroundService.getInstance();
        if (fs != null && fs.getPhoneSocket() != null) {
            Log.d("MainActivity", "Attaching socket listener");
            fs.getPhoneSocket().addListener(this);
        } else {
            retryHandler.postDelayed(this::attachSocketListener, 500);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        retryHandler.removeCallbacksAndMessages(null);
        MyForegroundService fs = MyForegroundService.getInstance();
        if (fs != null && fs.getPhoneSocket() != null) {
            fs.getPhoneSocket().removeListener(this);
        }
    }

    public void navigateToMessages() {
        popToRoot();
        Fragment current = fragmentManager.findFragmentById(R.id.fragmentContainer);
        if (!(current instanceof MessagesFragment)) {
            openFragment(new MessagesFragment(), "Messages");
            navView.setCheckedItem(R.id.nav_messages);
        }
    }

    private void startForegroundService() {
        Intent service = new Intent(MainActivity.this, MyForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void requestAppPermissions() {
        ArrayList<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.RECEIVE_SMS);
        perms.add(Manifest.permission.SEND_SMS);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);

        ArrayList<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) toRequest.add(p);
        }
        if (!toRequest.isEmpty()) ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private void setupNavigation() {
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_messages) { popToRoot(); openFragment(new MessagesFragment(), "Messages"); }
            else if (id == R.id.nav_settings) { popToRoot(); openFragment(new SettingsFragment(), "Settings"); }
            else if (id == R.id.nav_ussd) { popToRoot(); openFragment(new UssdFragment(), "USSD"); }
            drawerLayout.closeDrawer(navView);
            return true;
        });
    }

    private void setupBackStackListener() {
        fragmentManager.addOnBackStackChangedListener(() -> {
            Fragment current = fragmentManager.findFragmentById(R.id.fragmentContainer);
            if (current instanceof ConversationDetailFragment) { toolbar.setTitle("Messages"); showBackArrow(); }
            else if (current instanceof MessagesFragment) { toolbar.setTitle("Messages"); showHamburger(); }
            else if (current instanceof SettingsFragment) { toolbar.setTitle("Settings"); showHamburger(); }
            else if (current instanceof UssdFragment) { toolbar.setTitle("USSD"); showHamburger(); }
        });
    }

    private void showHamburger() {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        toolbar.setNavigationIcon(R.drawable.ic_hamburger);
        toolbar.setNavigationOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(navView)) drawerLayout.closeDrawer(navView);
            else drawerLayout.openDrawer(navView);
        });
    }

    private void showBackArrow() {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        toolbar.setNavigationIcon(R.drawable.ic_back_arrow);
        toolbar.setNavigationOnClickListener(v -> fragmentManager.popBackStack());
    }

    private void popToRoot() { fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE); }

    public void openFragment(Fragment fragment, String title) {
        toolbar.setTitle(title);
        fragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment).commit();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (MyForegroundService.getInstance() != null) MyForegroundService.getInstance().startSocket();
        }
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        runOnUiThread(() -> {
            if (connectionStatusText != null) {
                connectionStatusText.setText(connected ? "● Connected" : "○ Disconnected");
                connectionStatusText.setTextColor(connected ? 0xFF00E676 : 0xFFFF5252); 
                GradientDrawable bg = (GradientDrawable) connectionStatusText.getBackground();
                if (bg != null) bg.setColor(0xFF000000); 
            }
        });
    }

    private void addConnectionStatusIndicator() {
        connectionStatusText = new TextView(this);
        connectionStatusText.setText("○ Disconnected");
        connectionStatusText.setTextColor(0xFFFF5252);
        connectionStatusText.setTextSize(11);
        connectionStatusText.setTypeface(null, Typeface.BOLD);
        connectionStatusText.setPadding(35, 15, 35, 15); 
        connectionStatusText.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF000000); 
        background.setCornerRadius(8);
        connectionStatusText.setBackground(background);

        MaterialToolbar.LayoutParams params = new MaterialToolbar.LayoutParams(
                MaterialToolbar.LayoutParams.WRAP_CONTENT,
                MaterialToolbar.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.setMarginEnd(30);
        connectionStatusText.setLayoutParams(params);

        toolbar.addView(connectionStatusText);
    }
}
