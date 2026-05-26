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
        }

        detectSimSlots();
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
            options.query = "isPhone=true&phoneNumber=" + phoneNumber;
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
                    JSONObject data = (JSONObject) args[0];
                    executeUssd(data.optString("code"), data.optInt("simSlot"));
                } catch (Exception e) { Log.e(TAG, "USSD error", e); }
            });

            socket.on("ussd-cancel-command", args -> {
                try {
                    JSONObject data = (JSONObject) args[0];
                    cancelUssd(data.optInt("simSlot"));
                } catch (Exception e) { Log.e(TAG, "USSD cancel error", e); }
            });

            socket.on("send-sms", args -> {
                try {
                    JSONObject data = (JSONObject) args[0];
                    sendSms(data.optString("to"), data.optString("message"), data.optInt("simSlot"));
                } catch (Exception e) { Log.e(TAG, "SMS error", e); }
            });

            socket.on("vibrate-phone", args -> vibrateDevice());

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
            if (line1 != null && !line1.isEmpty()) return line1;
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (subManager != null) {
                    List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (SubscriptionInfo sub : subs) {
                            String num = sub.getNumber();
                            if (num != null && !num.isEmpty()) return num;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
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
        if (simSlots.isEmpty()) {
            simSlots.add(0);
            simPhoneNumbers.add(detectPhoneNumber());
        }
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

    private void sendUssdRequestInternal(String code, int simSlot, int retryCount) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    TelephonyManager tm = getTelephonyManagerForSlot(simSlot);
                    if (tm == null) {
                        Log.e(TAG, "TelephonyManager is null for slot " + simSlot);
                        sendUssdResponse(simSlot, "Error: TelephonyManager not available");
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
                            if (retryCount < 1 && failureCode == TelephonyManager.USSD_RETURN_FAILURE) {
                                Log.i(TAG, "Retrying USSD on slot " + simSlot + " in 2s...");
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    sendUssdRequestInternal(code, simSlot, retryCount + 1);
                                }, 2000);
                                return;
                            }
                            String msg;
                            if (failureCode == TelephonyManager.USSD_RETURN_FAILURE) {
                                msg = "Network rejected request or network busy";
                            } else if (failureCode == -1) {
                                msg = "Network error: SIM not registered or network timeout";
                            } else {
                                msg = "USSD failed. Code: " + failureCode;
                            }
                            sendUssdResponse(simSlot, "Error: " + msg);
                        }
                    }, new Handler(Looper.getMainLooper()));
                } else {
                    Intent intent = new Intent(Intent.ACTION_CALL);
                    intent.setData(Uri.parse("tel:" + Uri.encode(code)));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "USSD SecurityException", e);
                sendUssdResponse(simSlot, "Error: " + e.getMessage());
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
}
