package com.mouscripts.moumsgs;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import io.socket.client.IO;
import io.socket.client.Socket;

public class PhoneSocketService {

    private static final String TAG = "PhoneSocketService";
    private static final String PREFS_NAME = "MOUMSGS_PREFS";
    private static final String KEY_GATEWAY_URL = "gateway_url";
    private static final String KEY_PHONE_NUMBER = "phone_number";

    public interface SocketListener {
        void onConnectionStateChanged(boolean connected);
    }

    private final Context context;
    private Socket socket;
    private volatile boolean connected = false;
    private volatile boolean destroyed = false;
    private String phoneNumber = "";
    private String deviceId = "";
    private String deviceName = "";
    private String androidVersion = "";
    private final List<Integer> simSlots = new ArrayList<>();
    private final List<Integer> signalValues = new ArrayList<>();
    private final List<String> simPhoneNumbers = new ArrayList<>();
    private final CopyOnWriteArrayList<SocketListener> listeners = new CopyOnWriteArrayList<>();
    private String gatewayUrl = "";

    private final ConcurrentLinkedQueue<JSONObject> pendingSmsQueue = new ConcurrentLinkedQueue<>();
    private SubscriptionManager.OnSubscriptionsChangedListener subscriptionsListener;
    private boolean simListenerRegistered = false;

    private static final ConcurrentHashMap<String, Long> rateLimitMap = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_MS = 1000;
    private static final int MAX_INPUT_LENGTH = 5000;

    private static final String[] AUTO_DETECT_CODES = {"*947#", "*878#", "*688#"};
    private boolean isAutoDetectingNumber = false;
    private int autoDetectSimSlot = -1;
    private int autoDetectRetryCount = 0;

    public PhoneSocketService(Context context) {
        this.context = context.getApplicationContext();
        detectDeviceInfo();
    }

    private void detectDeviceInfo() {
        try {
            deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            deviceId = "unknown";
        }
        deviceName = Build.MODEL;
        androidVersion = Build.VERSION.RELEASE;
    }

    public void addListener(SocketListener listener) {
        if (listener == null) return;
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        listener.onConnectionStateChanged(isConnected());
    }

    public void removeListener(SocketListener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return socket != null && socket.connected() && connected;
    }

    public synchronized void connect() {
        if (destroyed) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gatewayUrl = prefs.getString(KEY_GATEWAY_URL, "");
        if (gatewayUrl.isEmpty()) return;

        phoneNumber = detectPhoneNumber();
        if (phoneNumber.isEmpty()) {
            phoneNumber = "Unknown_" + (deviceId.length() > 6 ? deviceId.substring(deviceId.length() - 6) : deviceId);
            detectSimSlots();
            startAutoNumberDetection();
        } else {
            detectSimSlots();
        }
        registerSimStateListener();

        if (socket != null) {
            if (socket.connected()) {
                registerPhone();
                flushPendingSms();
                return;
            }
            socket.off();
            socket.disconnect();
            socket.close();
            socket = null;
        }

        try {
            String baseUrl = extractBaseUrl(gatewayUrl);
            IO.Options options = new IO.Options();
            String authToken = generateAuthToken();
            options.query = "isPhone=true&phoneNumber=" + phoneNumber + "&token=" + authToken;
            options.reconnection = true;
            options.reconnectionDelay = 1000;
            options.reconnectionDelayMax = 10000;
            options.timeout = 15000;
            options.transports = new String[]{"websocket"};
            options.forceNew = false;

            socket = IO.socket(baseUrl, options);

            socket.on(Socket.EVENT_CONNECT, args -> {
                Log.i(TAG, "Connected (or reconnected)");
                connected = true;
                notifyState(true);
                registerPhone();
                flushPendingSms();
                sendAllMessagesToServer();
            });

            socket.on(Socket.EVENT_DISCONNECT, args -> {
                Log.w(TAG, "Disconnected");
                boolean wasConnected = connected;
                connected = false;
                if (wasConnected) notifyState(false);
            });

            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                Object detail = (args != null && args.length > 0) ? args[0] : "unknown";
                Log.e(TAG, "Connection error: " + detail);
                connected = false;
                notifyState(false);
            });

            socket.on("ussd-command", args -> {
                try {
                    if (!validateSocketEvent(args)) return;
                    JSONObject data = (JSONObject) args[0];
                    String code = data.optString("code", "");
                    int simSlot = data.optInt("simSlot", -1);
                    if (code.isEmpty() || !simSlots.contains(simSlot)) return;
                    if (!checkRateLimit("ussd")) return;
                    executeUssd(code, simSlot);
                } catch (Exception e) { Log.e(TAG, "USSD error", e); }
            });

            socket.on("ussd-cancel-command", args -> {
                try {
                    if (!validateSocketEvent(args)) return;
                    JSONObject data = (JSONObject) args[0];
                    int simSlot = data.optInt("simSlot", -1);
                    if (!simSlots.contains(simSlot)) return;
                    cancelUssd(simSlot);
                } catch (Exception e) { Log.e(TAG, "USSD cancel error", e); }
            });

            socket.on("send-sms", args -> {
                try {
                    if (!validateSocketEvent(args)) return;
                    JSONObject data = (JSONObject) args[0];
                    String to = sanitizeInput(data.optString("to", ""));
                    String message = sanitizeInput(data.optString("message", ""));
                    int simSlot = data.optInt("simSlot", -1);
                    if (to.isEmpty() || message.isEmpty() || !simSlots.contains(simSlot)) return;
                    if (!checkRateLimit("send-sms")) return;
                    sendSms(to, message, simSlot);
                } catch (Exception e) { Log.e(TAG, "SMS error", e); }
            });

            socket.on("vibrate-phone", args -> {
                if (!checkRateLimit("vibrate")) return;
                vibrateDevice();
            });

            socket.on("server-notification", args -> {
                try {
                    if (!validateSocketEvent(args)) return;
                    JSONObject data = (JSONObject) args[0];
                    String sender = sanitizeInput(data.optString("sender", "Server"));
                    String body = sanitizeInput(data.optString("body", ""));
                    String timestamp = data.optString("timestamp", String.valueOf(System.currentTimeMillis()));
                    int simSlot = data.optInt("simSlot", 0);
                    if (sender.isEmpty()) sender = "Server";
                    if (body.isEmpty()) return;
                    long date;
                    try { date = Long.parseLong(timestamp); } catch (NumberFormatException e) { date = System.currentTimeMillis(); }
                    DatabaseHelper.getInstance(context).insertMessage(sender, body, date, simSlot);
                    NotificationHelper.showMessageNotification(context, sender, body, date);
                    MyReceiver.OnSmsReceivedListener listener = MyReceiver.getLiveListener();
                    if (listener != null) listener.onSmsReceived(null);
                    Log.i(TAG, "Server notification from " + sender);
                } catch (Exception e) { Log.e(TAG, "server-notification error", e); }
            });

            socket.connect();
        } catch (Exception e) {
            Log.e(TAG, "Connection exception", e);
            connected = false;
            notifyState(false);
        }
    }

    public synchronized void destroy() {
        destroyed = true;
        connected = false;
        unregisterSimStateListener();
        if (socket != null) {
            socket.off();
            socket.disconnect();
            socket.close();
            socket = null;
        }
        notifyState(false);
    }

    public void forceReconnect() {
        destroy();
        destroyed = false;
        connect();
    }

    private void notifyState(boolean state) {
        for (SocketListener l : listeners) {
            try { l.onConnectionStateChanged(state); } catch (Exception ignored) {}
        }
    }

    private void registerPhone() {
        if (!isConnected()) return;
        try {
            if (simSlots.isEmpty()) {
                Log.w(TAG, "No SIMs detected, unregistering from server");
                JSONObject data = new JSONObject();
                data.put("phoneNumber", phoneNumber);
                data.put("simSlots", new JSONArray());
                data.put("signalValues", new JSONArray());
                data.put("simPhoneNumbers", new JSONArray());
                socket.emit("register-phone", data);
                return;
            }
            String validPhone = detectPhoneNumber();
            if (!validPhone.isEmpty()) phoneNumber = validPhone;
            JSONObject data = new JSONObject();
            data.put("phoneNumber", phoneNumber);
            JSONArray slots = new JSONArray();
            for (int slot : simSlots) slots.put(slot);
            data.put("simSlots", slots);
            data.put("deviceId", deviceId);
            data.put("deviceName", deviceName);
            data.put("androidVersion", androidVersion);
            JSONArray signals = new JSONArray();
            for (int sig : signalValues) signals.put(sig);
            data.put("signalValues", signals);
            JSONArray slotNumbers = new JSONArray();
            for (String num : simPhoneNumbers) slotNumbers.put(num != null ? num : "");
            data.put("simPhoneNumbers", slotNumbers);
            socket.emit("register-phone", data);
            Log.i(TAG, "Registered as phone: " + phoneNumber + " slots: " + simPhoneNumbers);
        } catch (Exception e) { Log.e(TAG, "Register error", e); }
    }

    public void sendSmsEvent(String sender, String content, String timestamp, int simSlot) {
        try {
            JSONObject data = new JSONObject();
            data.put("sender", sender);
            data.put("content", content);
            data.put("timestamp", timestamp);
            data.put("simSlot", simSlot);

            if (isConnected() && socket != null) {
                socket.emit("phone-sms", data);
                Log.i(TAG, "Forwarded SMS from " + sender);
            } else {
                pendingSmsQueue.add(data);
                Log.w(TAG, "SMS queued (pending: " + pendingSmsQueue.size() + ")");
                connect();
            }
        } catch (Exception e) {
            Log.e(TAG, "sendSmsEvent error", e);
        }
    }

    private void sendAllMessagesToServer() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean("messages_sent_to_server", false)) return;
            if (!isConnected() || socket == null) return;
            DatabaseHelper dbHelper = DatabaseHelper.getInstance(context);
            List<SmsItem> allMessages = dbHelper.getAllMessages();
            if (allMessages.isEmpty()) {
                prefs.edit().putBoolean("messages_sent_to_server", true).apply();
                return;
            }
            final List<SmsItem> messages = new ArrayList<>(allMessages);
            new Thread(() -> {
                try {
                    int sent = 0;
                    for (SmsItem item : messages) {
                        try {
                            if (!isConnected()) break;
                            JSONObject data = new JSONObject();
                            data.put("sender", item.getAddress());
                            data.put("content", item.getBody());
                            data.put("timestamp", String.valueOf(item.getDate()));
                            data.put("simSlot", item.getSimSlot());
                            socket.emit("phone-sms", data);
                            sent++;
                            if (sent % 50 == 0) {
                                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "sendAllMessages error on item", e);
                        }
                    }
                    SharedPreferences prefs2 = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs2.edit().putBoolean("messages_sent_to_server", true).apply();
                    Log.i(TAG, "Sent " + sent + " existing messages to server");
                } catch (Exception e) {
                    Log.e(TAG, "sendAllMessagesToServer error", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "sendAllMessagesToServer init error", e);
        }
    }

    private void flushPendingSms() {
        if (!isConnected() || socket == null) return;
        int count = 0;
        JSONObject data;
        while ((data = pendingSmsQueue.poll()) != null) {
            try {
                socket.emit("phone-sms", data);
                count++;
            } catch (Exception e) {
                Log.e(TAG, "Flush error", e);
            }
        }
        if (count > 0) Log.i(TAG, "Flushed " + count + " pending messages");
    }

    public void sendHeartbeat() {
        if (isConnected() && socket != null) {
            socket.emit("phone-ping");
            sendSignalUpdate();
        }
    }

    private void sendSignalUpdate() {
        if (!isConnected() || socket == null) return;
        try {
            JSONObject data = new JSONObject();
            JSONArray signals = new JSONArray();
            for (int i = 0; i < simSlots.size(); i++) {
                int sig = readSignalStrength(simSlots.get(i));
                if (i < signalValues.size()) {
                    signalValues.set(i, sig);
                }
                signals.put(sig);
            }
            data.put("signalValues", signals);
            socket.emit("phone-signal-update", data);
        } catch (Exception e) {
            Log.e(TAG, "sendSignalUpdate error", e);
        }
    }

    private int readSignalStrength(int simSlot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return -1;
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return -1;
            int subId = getSubIdForSlot(simSlot);
            if (subId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tm = tm.createForSubscriptionId(subId);
            }
            SignalStrength ss = tm.getSignalStrength();
            if (ss == null) return -1;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                List<CellSignalStrength> cssList = ss.getCellSignalStrengths();
                if (cssList != null && !cssList.isEmpty()) {
                    int bestLevel = 0;
                    for (CellSignalStrength css : cssList) {
                        int lvl = css.getLevel();
                        if (lvl > bestLevel) bestLevel = lvl;
                    }
                    return bestLevel * 25;
                }
            }

            int level = ss.getLevel();
            return level * 25;
        } catch (SecurityException e) {
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String detectPhoneNumber() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return "";
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            String line1 = tm.getLine1Number();
            if (isValidPhoneNumber(line1)) return line1;
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (subManager != null) {
                    List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (SubscriptionInfo sub : subs) {
                            String num = sub.getNumber();
                            if (isValidPhoneNumber(num)) return num;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private boolean isValidPhoneNumber(String num) {
        if (num == null || num.isEmpty()) return false;
        String digits = num.replaceAll("[^0-9]", "");
        return digits.length() >= 10;
    }

    private void startAutoNumberDetection() {
        if (simSlots.isEmpty() || isAutoDetectingNumber) return;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CALL_PHONE permission not granted for USSD auto-detection");
            return;
        }
        isAutoDetectingNumber = true;
        autoDetectSimSlot = simSlots.get(0);
        autoDetectRetryCount = 0;
        Log.i(TAG, "Starting USSD auto-number detection on slot " + autoDetectSimSlot);
        sendAutoDetectUssd();
    }

    private void sendAutoDetectUssd() {
        if (autoDetectRetryCount >= AUTO_DETECT_CODES.length || destroyed) {
            isAutoDetectingNumber = false;
            return;
        }
        String code = AUTO_DETECT_CODES[autoDetectRetryCount];
        Log.i(TAG, "Auto-detecting number via USSD: " + code + " on slot " + autoDetectSimSlot);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    Log.w(TAG, "USSD auto-detect requires API 26+, skipping");
                    isAutoDetectingNumber = false;
                    return;
                }
                TelephonyManager tm = getTelephonyManagerForSlot(autoDetectSimSlot);
                if (tm == null) {
                    autoDetectRetryCount++;
                    sendAutoDetectUssd();
                    return;
                }
                String ussdCode = code.startsWith("*") ? code : "*" + code;
                if (!ussdCode.endsWith("#")) ussdCode += "#";
                tm.sendUssdRequest(ussdCode, new TelephonyManager.UssdResponseCallback() {
                    @Override
                    public void onReceiveUssdResponse(TelephonyManager tm, String req, CharSequence res) {
                        String response = res.toString();
                        Log.i(TAG, "Auto-detect USSD response: " + response);
                        String number = extractPhoneNumberFromUssd(response);
                        if (number != null && !number.isEmpty()) {
                            phoneNumber = number;
                            Log.i(TAG, "Phone number detected via USSD: " + number);
                            if (isConnected()) {
                                registerPhone();
                            }
                            isAutoDetectingNumber = false;
                        } else {
                            autoDetectRetryCount++;
                            sendAutoDetectUssd();
                        }
                    }
                    @Override
                    public void onReceiveUssdResponseFailed(TelephonyManager tm, String req, int failureCode) {
                        Log.w(TAG, "Auto-detect USSD failed code: " + failureCode
                                + " for " + AUTO_DETECT_CODES[autoDetectRetryCount]);
                        autoDetectRetryCount++;
                        sendAutoDetectUssd();
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (SecurityException e) {
                Log.e(TAG, "Auto-detect USSD SecurityException", e);
                autoDetectRetryCount++;
                sendAutoDetectUssd();
            } catch (Exception e) {
                Log.e(TAG, "Auto-detect USSD error", e);
                autoDetectRetryCount++;
                sendAutoDetectUssd();
            }
        });
    }

    private String extractPhoneNumberFromUssd(String response) {
        if (response == null || response.isEmpty()) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(01[0125]\\d{8})");
        java.util.regex.Matcher m = p.matcher(response);
        if (m.find()) {
            String num = m.group(1);
            if (isValidPhoneNumber(num)) return num;
        }
        p = java.util.regex.Pattern.compile("(?:20)?(1[0125]\\d{8})");
        m = p.matcher(response);
        if (m.find()) {
            String num = m.group(1);
            if (num.startsWith("1")) num = "0" + num;
            if (isValidPhoneNumber(num)) return num;
        }
        return null;
    }

    private void detectSimSlots() {
        simSlots.clear();
        simPhoneNumbers.clear();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            simSlots.add(0);
            simPhoneNumbers.add(detectPhoneNumber());
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (subManager != null) {
                    List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (SubscriptionInfo sub : subs) {
                            simSlots.add(sub.getSimSlotIndex());
                            String num = sub.getNumber();
                            if (num == null || num.isEmpty()) {
                                TelephonyManager tm = getTelephonyManagerForSlot(sub.getSimSlotIndex());
                                if (tm != null) {
                                    try { num = tm.getLine1Number(); } catch (Exception ignored) {}
                                }
                            }
                            simPhoneNumbers.add((num != null && !num.isEmpty()) ? num : phoneNumber);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        initSignalValues();
    }

    private void initSignalValues() {
        signalValues.clear();
        for (int slot : simSlots) {
            signalValues.add(readSignalStrength(slot));
        }
    }

    public String getPhoneNumberForSlot(int simSlot) {
        int idx = simSlots.indexOf(simSlot);
        if (idx >= 0 && idx < simPhoneNumbers.size()) {
            String num = simPhoneNumbers.get(idx);
            if (num != null && !num.isEmpty()) return num;
        }
        return phoneNumber;
    }

    private void registerSimStateListener() {
        if (simListenerRegistered) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return;
        try {
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subManager == null) return;
            subscriptionsListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    checkSimChanges();
                }
            };
            subManager.addOnSubscriptionsChangedListener(subscriptionsListener);
            simListenerRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Error registering SIM listener", e);
        }
    }

    private void unregisterSimStateListener() {
        if (!simListenerRegistered || subscriptionsListener == null) return;
        try {
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subManager != null) {
                subManager.removeOnSubscriptionsChangedListener(subscriptionsListener);
            }
        } catch (Exception ignored) {}
        simListenerRegistered = false;
        subscriptionsListener = null;
    }

    void checkSimChanges() {
        List<Integer> oldSlots = new ArrayList<>(simSlots);
        detectSimSlots();
        if (listsEqual(oldSlots, simSlots)) return;

        List<Integer> added = new ArrayList<>(simSlots);
        added.removeAll(oldSlots);
        List<Integer> removed = new ArrayList<>(oldSlots);
        removed.removeAll(simSlots);

        Log.i(TAG, "SIM config changed! Added: " + added + ", Removed: " + removed);
        if (isConnected() && socket != null) {
            sendSimStatus(added, removed);
        }
    }

    private boolean listsEqual(List<Integer> a, List<Integer> b) {
        if (a.size() != b.size()) return false;
        Set<Integer> setA = new HashSet<>(a);
        Set<Integer> setB = new HashSet<>(b);
        return setA.equals(setB);
    }

    private void sendSimStatus(List<Integer> added, List<Integer> removed) {
        try {
            JSONObject data = new JSONObject();
            JSONArray currentSlots = new JSONArray();
            for (int s : simSlots) currentSlots.put(s);
            data.put("simSlots", currentSlots);

            JSONArray addedSlots = new JSONArray();
            for (int s : added) addedSlots.put(s);
            data.put("added", addedSlots);

            JSONArray removedSlots = new JSONArray();
            for (int s : removed) removedSlots.put(s);
            data.put("removed", removedSlots);

            JSONArray signals = new JSONArray();
            for (int s : signalValues) signals.put(s);
            data.put("signalValues", signals);

            JSONArray slotNumbers = new JSONArray();
            for (String num : simPhoneNumbers) slotNumbers.put(num != null ? num : "");
            data.put("simPhoneNumbers", slotNumbers);

            socket.emit("phone-sim-status", data);
            Log.i(TAG, "SIM status sent: added=" + added + " removed=" + removed);
        } catch (Exception e) {
            Log.e(TAG, "sendSimStatus error", e);
        }
    }

    private void cancelUssd(int simSlot) {
        if (!simSlots.contains(simSlot)) {
            Log.w(TAG, "Cancel USSD on invalid slot " + simSlot);
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    TelephonyManager tm = getTelephonyManagerForSlot(simSlot);
                    if (tm == null) return;
                    tm.sendUssdRequest("", new TelephonyManager.UssdResponseCallback() {
                        @Override public void onReceiveUssdResponse(TelephonyManager tm, String req, CharSequence res) {
                            Log.i(TAG, "USSD cancelled on slot " + simSlot + ": " + res);
                        }
                        @Override public void onReceiveUssdResponseFailed(TelephonyManager tm, String req, int failureCode) {
                            Log.w(TAG, "USSD cancel ignored on slot " + simSlot + " (code: " + failureCode + ")");
                        }
                    }, new Handler(Looper.getMainLooper()));
                }
            } catch (Exception e) {
                Log.w(TAG, "USSD cancel error on slot " + simSlot, e);
            }
        });
    }

    private boolean isSimRegistered(int simSlot) {
        try {
            TelephonyManager tm = getTelephonyManagerForSlot(simSlot);
            if (tm == null) return false;
            int simState = tm.getSimState();
            if (simState != TelephonyManager.SIM_STATE_READY) {
                Log.w(TAG, "SIM slot " + simSlot + " not ready (state=" + simState + ")");
                return false;
            }
            String operator = tm.getNetworkOperatorName();
            if (operator == null || operator.isEmpty()) {
                Log.w(TAG, "SIM slot " + simSlot + " not registered on network (no operator)");
                return false;
            }
            int voiceNetType = tm.getVoiceNetworkType();
            Log.i(TAG, "SIM slot " + simSlot + " registered on " + operator
                    + " (voiceNetType=" + voiceNetType + ", simState=" + simState + ")");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Can't check network registration for slot " + simSlot, e);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Error checking registration for slot " + simSlot, e);
            return true;
        }
    }

    private void executeUssd(String code, int simSlot) {
        if (!simSlots.contains(simSlot)) {
            Log.w(TAG, "USSD on invalid slot " + simSlot + ", available: " + simSlots);
            sendUssdResponse(simSlot, "Error: SIM slot " + simSlot + " not available");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CALL_PHONE permission not granted for USSD");
            sendUssdResponse(simSlot, "Error: CALL_PHONE permission denied");
            return;
        }

        if (!isSimRegistered(simSlot)) {
            Log.e(TAG, "SIM slot " + simSlot + " not registered, attempting anyway");
        }

        sendUssdRequestInternal(code, simSlot, 0);
    }

    private void callUssdViaDialer(String code, int simSlot) {
        try {
            String ussdCode = code.startsWith("*") ? code : "*" + code;
            if (!ussdCode.endsWith("#")) ussdCode += "#";
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + Uri.encode(ussdCode)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "USSD via dialer: " + ussdCode + " on slot " + simSlot);
        } catch (SecurityException e) {
            Log.e(TAG, "Dialer USSD SecurityException", e);
            sendUssdResponse(simSlot, "Error: CALL_PHONE permission needed for dialer USSD");
        } catch (Exception e) {
            Log.e(TAG, "Dialer USSD error", e);
            sendUssdResponse(simSlot, "Error: " + e.getMessage());
        }
    }

    private void sendUssdRequestInternal(String code, int simSlot, int retryCount) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    TelephonyManager tm = getTelephonyManagerForSlot(simSlot);
                    if (tm == null) {
                        Log.e(TAG, "TelephonyManager is null for slot " + simSlot);
                        callUssdViaDialer(code, simSlot);
                        return;
                    }
                    String ussdCode = code.startsWith("*") ? code : "*" + code;
                    if (!ussdCode.endsWith("#")) ussdCode += "#";
                    Log.i(TAG, "Sending USSD: " + ussdCode + " on slot " + simSlot
                            + " (attempt " + (retryCount + 1) + ")");
                    tm.sendUssdRequest(ussdCode, new TelephonyManager.UssdResponseCallback() {
                        @Override public void onReceiveUssdResponse(TelephonyManager tm, String req, CharSequence res) {
                            Log.i(TAG, "USSD response on slot " + simSlot + ": " + res);
                            sendUssdResponse(simSlot, res.toString());
                        }
                        @Override public void onReceiveUssdResponseFailed(TelephonyManager tm, String req, int failureCode) {
                            Log.e(TAG, "USSD failed on slot " + simSlot + " code: " + failureCode
                                    + " (attempt " + (retryCount + 1) + ")");
                            if (failureCode == TelephonyManager.USSD_RETURN_FAILURE) {
                                if (retryCount < 1) {
                                    Log.i(TAG, "Retrying USSD on slot " + simSlot + " in 2s...");
                                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                        sendUssdRequestInternal(code, simSlot, retryCount + 1);
                                    }, 2000);
                                    return;
                                }
                                Log.i(TAG, "Falling back to dialer USSD on slot " + simSlot);
                                callUssdViaDialer(code, simSlot);
                                return;
                            }
                            sendUssdResponse(simSlot, "Error: USSD failed. Code: " + failureCode);
                        }
                    }, new Handler(Looper.getMainLooper()));
                } else {
                    callUssdViaDialer(code, simSlot);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "USSD SecurityException", e);
                callUssdViaDialer(code, simSlot);
            } catch (Exception e) {
                Log.e(TAG, "USSD error", e);
                sendUssdResponse(simSlot, "Error: " + e.getMessage());
            }
        });
    }

    private TelephonyManager getTelephonyManagerForSlot(int simSlot) {
        TelephonyManager tm = null;
        try {
            tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        } catch (Exception e) {
            Log.e(TAG, "Error getting TelephonyManager", e);
            return null;
        }
        if (tm == null) return null;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    if (subManager != null) {
                        int subId = -1;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            SubscriptionInfo info = subManager.getActiveSubscriptionInfoForSimSlotIndex(simSlot);
                            if (info != null) subId = info.getSubscriptionId();
                        } else {
                            List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                            if (subs != null) {
                                for (SubscriptionInfo sub : subs) {
                                    if (sub.getSimSlotIndex() == simSlot) {
                                        subId = sub.getSubscriptionId();
                                        break;
                                    }
                                }
                            }
                        }
                        if (subId != -1) {
                            tm = tm.createForSubscriptionId(subId);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error creating sub-specific TM for slot " + simSlot, e);
            }
        }
        return tm;
    }

    public void sendUssdResponse(int simSlot, String response) {
        try {
            JSONObject data = new JSONObject();
            data.put("simSlot", simSlot);
            data.put("response", response);
            if (socket != null && socket.connected()) {
                socket.emit("phone-ussd-response", data);
            } else {
                Log.w(TAG, "Socket disconnected, USSD response dropped");
            }
        } catch (Exception e) {
            Log.e(TAG, "sendUssdResponse error", e);
        }
    }

    private void sendSms(String to, String message, int simSlot) {
        if (!simSlots.contains(simSlot)) {
            Log.w(TAG, "SMS on invalid slot " + simSlot + ", available: " + simSlots);
            return;
        }
        try {
            android.telephony.SmsManager smsManager;
            int subId = getSubIdForSlot(simSlot);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && subId >= 0) {
                smsManager = android.telephony.SmsManager.getSmsManagerForSubscriptionId(subId);
            } else {
                smsManager = android.telephony.SmsManager.getDefault();
            }
            smsManager.sendTextMessage(to, null, message, null, null);
            Log.i(TAG, "SMS sent to " + to + " via SIM slot " + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "SMS send error", e);
        }
    }

    private int getSubIdForSlot(int simSlot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                if (subs != null) {
                    for (SubscriptionInfo sub : subs) if (sub.getSimSlotIndex() == simSlot) return sub.getSubscriptionId();
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private void vibrateDevice() {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(1000);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibration failed", e);
        }
    }

    private String extractBaseUrl(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String port = (u.getPort() != -1) ? ":" + u.getPort() : "";
            return u.getProtocol() + "://" + u.getHost() + port;
        } catch (Exception e) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "http://" + url;
            }
            return url;
        }
    }

    private String generateAuthToken() {
        try {
            String raw = deviceId + "_MOUMSGS_AUTH_2024";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return deviceId;
        }
    }

    private String sanitizeInput(String input) {
        if (input == null) return "";
        if (input.length() > MAX_INPUT_LENGTH) input = input.substring(0, MAX_INPUT_LENGTH);
        return input.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
    }

    private boolean validateSocketEvent(Object[] args) {
        if (args == null || args.length == 0) return false;
        if (!(args[0] instanceof JSONObject)) return false;
        return true;
    }

    private boolean checkRateLimit(String action) {
        long now = System.currentTimeMillis();
        Long last = rateLimitMap.get(action);
        if (last != null && (now - last) < RATE_LIMIT_MS) {
            Log.w(TAG, "Rate limited: " + action);
            return false;
        }
        rateLimitMap.put(action, now);
        return true;
    }
}
