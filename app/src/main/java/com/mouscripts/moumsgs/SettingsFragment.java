package com.mouscripts.moumsgs;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.app.role.RoleManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.List;

public class SettingsFragment extends Fragment {

    private TextInputEditText serverUrlEditText;
    private MaterialSwitch gatewaySwitch, firebaseSwitch;
    private MaterialButton saveButton, testButton, sendAllButton, goToMessagesButton, batteryOptimizationButton, autoStartButton, defaultSmsButton, defaultDialerButton;
    private TextView statusTextView, batteryStatusText, defaultSmsStatusText, defaultDialerStatusText;
    private MaterialCardView firstTimeCard, bulkSendCard;
    private RadioGroup themeRadioGroup;

    private SharedPreferences prefs;
    private DatabaseHelper dbHelper;
    private boolean isFirstLaunch;
    private boolean settingsSaved = false;

    private static final String PREFS_NAME = "MOUMSGS_PREFS";
    private static final String KEY_GATEWAY_URL = "gateway_url";
    private static final String KEY_GATEWAY_ENABLED = "gateway_enabled";
    private static final String KEY_FIREBASE_ENABLED = "firebase_enabled";
    private static final String KEY_FIRST_LAUNCH_DONE = "first_launch_done";
    private static final String KEY_BULK_SENT = "bulk_sent";

    private static final String KEY_THEME_MODE = "theme_mode";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        dbHelper = DatabaseHelper.getInstance(requireContext());

        isFirstLaunch = !prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false);
        boolean bulkSent = prefs.getBoolean(KEY_BULK_SENT, false);

        serverUrlEditText = view.findViewById(R.id.serverUrlEditText);
        gatewaySwitch = view.findViewById(R.id.gatewaySwitch);
        firebaseSwitch = view.findViewById(R.id.firebaseSwitch);
        saveButton = view.findViewById(R.id.saveButton);
        testButton = view.findViewById(R.id.testButton);
        statusTextView = view.findViewById(R.id.statusTextView);
        batteryOptimizationButton = view.findViewById(R.id.batteryOptimizationButton);
        autoStartButton = view.findViewById(R.id.autoStartButton);
        batteryStatusText = view.findViewById(R.id.batteryStatusText);
        firstTimeCard = view.findViewById(R.id.firstTimeCard);
        bulkSendCard = view.findViewById(R.id.bulkSendCard);
        sendAllButton = view.findViewById(R.id.sendAllButton);
        goToMessagesButton = view.findViewById(R.id.goToMessagesButton);
        themeRadioGroup = view.findViewById(R.id.themeRadioGroup);
        defaultSmsButton = view.findViewById(R.id.defaultSmsButton);
        defaultSmsStatusText = view.findViewById(R.id.defaultSmsStatusText);
        defaultDialerButton = view.findViewById(R.id.defaultDialerButton);
        defaultDialerStatusText = view.findViewById(R.id.defaultDialerStatusText);

        if (isFirstLaunch) {
            firstTimeCard.setVisibility(View.VISIBLE);
        }

        if (isFirstLaunch && !bulkSent) {
            loadSettings();
            checkSavedState();
        } else {
            firstTimeCard.setVisibility(View.GONE);
            bulkSendCard.setVisibility(View.GONE);
            loadSettings();
        }

        setupClickListeners();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateBatteryStatus();
        updateDefaultSmsStatus();
        updateDefaultDialerStatus();
    }

    private void checkSavedState() {
        String url = prefs.getString(KEY_GATEWAY_URL, "");
        settingsSaved = !url.isEmpty();
        if (settingsSaved) {
            bulkSendCard.setVisibility(View.VISIBLE);
        }
    }

    private void loadSettings() {
        String url = prefs.getString(KEY_GATEWAY_URL, "");
        boolean gatewayEnabled = prefs.getBoolean(KEY_GATEWAY_ENABLED, true);
        boolean firebaseEnabled = prefs.getBoolean(KEY_FIREBASE_ENABLED, true);

        serverUrlEditText.setText(url);
        gatewaySwitch.setChecked(gatewayEnabled);
        firebaseSwitch.setChecked(firebaseEnabled);

        updateBatteryStatus();
    }

    private void updateBatteryStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) requireContext().getSystemService(requireContext().POWER_SERVICE);
            boolean isIgnoring = pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
            if (isIgnoring) {
                batteryStatusText.setText("✅ Battery optimization is disabled");
                batteryStatusText.setTextColor(0xFF4CAF50);
                batteryOptimizationButton.setText("✅ DISABLED");
                batteryOptimizationButton.setEnabled(false);
            } else {
                batteryStatusText.setText("⚠️ Battery optimization active - connection might drop");
                batteryStatusText.setTextColor(0xFFEF5350);
                batteryOptimizationButton.setText("🔓 DISABLE OPTIMIZATION");
                batteryOptimizationButton.setEnabled(true);
            }
        }
    }

    private void updateDefaultSmsStatus() {
        boolean isDefault = isDefaultSmsApp();
        if (isDefault) {
            defaultSmsStatusText.setText("Default SMS App");
            defaultSmsStatusText.setTextColor(0xFF4CAF50);
            defaultSmsButton.setText("Default SMS App");
            defaultSmsButton.setEnabled(false);
        } else {
            defaultSmsStatusText.setText("Not the default SMS app");
            defaultSmsStatusText.setTextColor(0xFFEF5350);
            defaultSmsButton.setText("Set as Default SMS App");
            defaultSmsButton.setEnabled(true);
        }
    }

    private boolean isDefaultSmsApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = requireContext().getSystemService(RoleManager.class);
                if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        try {
            String defaultPackage = Telephony.Sms.getDefaultSmsPackage(requireContext());
            return defaultPackage != null && defaultPackage.equals(requireContext().getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void requestDefaultSms() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = requireContext().getSystemService(RoleManager.class);
                if (roleManager != null) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
                    startActivity(intent);
                    return;
                }
            }
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.e("SettingsFragment", "Error requesting default SMS", e);
            Toast.makeText(requireContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = requireContext().getSystemService(RoleManager.class);
                if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        try {
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager)
                    requireContext().getSystemService(requireContext().TELECOM_SERVICE);
            if (tm != null) {
                String defaultDialer = tm.getDefaultDialerPackage();
                return defaultDialer != null && defaultDialer.equals(requireContext().getPackageName());
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void updateDefaultDialerStatus() {
        boolean isDefault = isDefaultDialer();
        if (isDefault) {
            defaultDialerStatusText.setText("Default Dialer App");
            defaultDialerStatusText.setTextColor(0xFF4CAF50);
            defaultDialerButton.setText("Default Dialer App");
            defaultDialerButton.setEnabled(false);
        } else {
            defaultDialerStatusText.setText("Not the default dialer app");
            defaultDialerStatusText.setTextColor(0xFFEF5350);
            defaultDialerButton.setText("Set as Default Dialer App");
            defaultDialerButton.setEnabled(true);
        }
    }

    private void requestDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = requireContext().getSystemService(RoleManager.class);
                if (roleManager != null) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    startActivity(intent);
                    return;
                }
            }
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.e("SettingsFragment", "Error requesting default dialer", e);
            Toast.makeText(requireContext(), "خطأ: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String detectPhoneNumberBulk() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        try {
            TelephonyManager tm = (TelephonyManager)
                    requireContext().getSystemService(requireContext().TELEPHONY_SERVICE);
            String num = tm.getLine1Number();
            if (num != null && !num.isEmpty()) return num;
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager subManager = (SubscriptionManager)
                        requireContext().getSystemService(requireContext().TELEPHONY_SUBSCRIPTION_SERVICE);
                if (subManager != null) {
                    List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (SubscriptionInfo sub : subs) {
                            String n = sub.getNumber();
                            if (n != null && !n.isEmpty()) return n;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private void setupClickListeners() {
        saveButton.setOnClickListener(v -> saveSettings());
        testButton.setOnClickListener(v -> testConnection());
        sendAllButton.setOnClickListener(v -> sendAllMessages());
        goToMessagesButton.setOnClickListener(v -> navigateToMessages());

        themeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode;
            if (checkedId == R.id.themeLight) {
                mode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.themeDark) {
                mode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            prefs.edit().putInt(KEY_THEME_MODE, mode).apply();
            AppCompatDelegate.setDefaultNightMode(mode);
        });

        batteryOptimizationButton.setOnClickListener(v -> {
            try {
                Intent miui = new Intent("miui.intent.action.APP_BATTERY_SAVER");
                miui.putExtra("package_name", requireContext().getPackageName());
                miui.setClassName("com.miui.securitycenter", "com.miui.securitycenter.power.BatteryAppActivity");
                startActivity(miui);
                return;
            } catch (Exception ignored) {}
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        });

        autoStartButton.setOnClickListener(v -> {
            try {
                Intent miui = new Intent();
                miui.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
                miui.setData(Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(miui);
                Toast.makeText(requireContext(), "Enable Auto-Start in Settings > Autostart", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });

        defaultSmsButton.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "جاري فتح إعدادات SMS...", Toast.LENGTH_SHORT).show();
            requestDefaultSms();
        });
        defaultDialerButton.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "جاري فتح إعدادات الاتصال...", Toast.LENGTH_SHORT).show();
            requestDefaultDialer();
        });
    }

    private String buildApiUrl(String baseUrl) {
        baseUrl = baseUrl.trim();
        if (baseUrl.isEmpty()) return baseUrl;
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://" + baseUrl;
        }
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!baseUrl.endsWith("/api/phone-message")) {
            baseUrl += "/api/phone-message";
        }
        return baseUrl;
    }

    private void saveSettings() {
        String url = serverUrlEditText.getText().toString().trim();
        if (url.isEmpty()) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText(R.string.enter_server_url);
            return;
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        url = url.replaceAll("/+$", "");

        prefs.edit()
                .putString(KEY_GATEWAY_URL, url)
                .putBoolean(KEY_GATEWAY_ENABLED, gatewaySwitch.isChecked())
                .putBoolean(KEY_FIREBASE_ENABLED, firebaseSwitch.isChecked())
                .apply();

        settingsSaved = true;
        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
        statusTextView.setText(R.string.settings_saved);

        Intent serviceIntent = new Intent(requireContext(), MyForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent);
        } else {
            requireContext().startService(serviceIntent);
        }
        MyForegroundService fs = MyForegroundService.getInstance();
        if (fs != null) fs.startSocket();

        if (isFirstLaunch) {
            bulkSendCard.setVisibility(View.VISIBLE);
        }
    }

    private void sendAllMessages() {
        String url = prefs.getString(KEY_GATEWAY_URL, "");
        if (url.isEmpty()) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText(R.string.save_first);
            return;
        }

        if (!dbHelper.hasMessages()) {
            loadMessagesFromSystem();
        }

        List<SmsItem> allMessages = dbHelper.getAllMessages();
        if (allMessages.isEmpty()) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            statusTextView.setText(R.string.no_messages_to_send);
            completeFirstLaunch();
            return;
        }

        sendAllButton.setEnabled(false);
        final int total = allMessages.size();
        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        statusTextView.setText(getString(R.string.sending_progress, 0, total));

        final String apiUrl = buildApiUrl(url);
        final String deviceNumber = detectPhoneNumberBulk();

        new Thread(() -> {
            int sent = 0;
            boolean allSuccess = true;
            for (SmsItem item : allMessages) {
                try {
                    URL sendUrl = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) sendUrl.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    JSONObject json = new JSONObject();
                    json.put("sender", item.getAddress());
                    json.put("content", item.getBody());
                    json.put("timestamp", new Date(item.getDate()).toString());
                    if (!deviceNumber.isEmpty()) {
                        json.put("receiver", deviceNumber);
                        json.put("deviceNumber", deviceNumber);
                    }
                    json.put("simSlot", item.getSimSlot());

                    OutputStream os = conn.getOutputStream();
                    os.write(json.toString().getBytes("UTF-8"));
                    os.close();

                    int code = conn.getResponseCode();
                    conn.disconnect();

                    if (code >= 200 && code < 300) {
                        sent++;
                    } else {
                        allSuccess = false;
                    }
                } catch (Exception e) {
                    allSuccess = false;
                }

                int finalSent = sent;
                int finalTotal = total;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    statusTextView.setText(
                            getString(R.string.sending_progress, finalSent, finalTotal));
                });
            }

            final int finalSent = sent;
            final boolean finalAllSuccess = allSuccess;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                sendAllButton.setEnabled(true);
                if (finalAllSuccess && finalSent == total) {
                    statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                    statusTextView.setText(getString(R.string.send_success, finalSent));
                    goToMessagesButton.setVisibility(View.VISIBLE);
                    completeFirstLaunch();
                } else {
                    statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
                    statusTextView.setText(getString(R.string.send_failed) + " (" + finalSent + "/" + total + ")");
                }
            });
        }).start();
    }

    private void completeFirstLaunch() {
        prefs.edit()
                .putBoolean(KEY_FIRST_LAUNCH_DONE, true)
                .putBoolean(KEY_BULK_SENT, true)
                .apply();
        isFirstLaunch = false;
    }

    private void navigateToMessages() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateToMessages();
        }
    }

    private void loadMessagesFromSystem() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ContentResolver cr = requireContext().getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(
                    Telephony.Sms.CONTENT_URI,
                    new String[]{
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.BODY,
                            Telephony.Sms.DATE,
                            Telephony.Sms.TYPE
                    },
                    Telephony.Sms.TYPE + " = ?",
                    new String[]{"1"},
                    Telephony.Sms.DATE + " DESC"
            );
        } catch (Exception e) {
            Log.e("SettingsFragment", "Error loading SMS: " + e.getMessage());
        }
        if (c != null) {
            while (c.moveToNext()) {
                String address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                String body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY));
                long date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE));
                dbHelper.insertMessage(address, body, date);
            }
            c.close();
        }
    }

    private void testConnection() {
        String url = serverUrlEditText.getText().toString().trim();
        if (url.isEmpty()) {
            statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
            statusTextView.setText(R.string.enter_server_url);
            return;
        }

        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        statusTextView.setText(R.string.testing);
        testButton.setEnabled(false);

        final String apiUrl = buildApiUrl(url);

        new Thread(() -> {
            try {
                URL testUrl = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) testUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                JSONObject json = new JSONObject();
                json.put("sender", "TEST");
                json.put("content", "Test connection from MOU MSGS");
                json.put("timestamp", new Date().toString());

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes("UTF-8"));
                os.close();

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (responseCode >= 200 && responseCode < 300) {
                        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.success));
                        statusTextView.setText(R.string.connection_success);
                    } else {
                        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
                        statusTextView.setText(
                                getString(R.string.connection_failed) + " (HTTP " + responseCode + ")");
                    }
                    testButton.setEnabled(true);
                });
            } catch (Exception e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    statusTextView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
                    statusTextView.setText(
                            getString(R.string.connection_failed) + ": " + e.getMessage());
                    testButton.setEnabled(true);
                });
            }
        }).start();
    }
}
