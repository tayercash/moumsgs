package com.mouscripts.moumsgs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsMessage;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MyReceiver extends BroadcastReceiver {

    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    private static final String SMS_DELIVER = "android.provider.Telephony.SMS_DELIVER";
    private static final String TAG = "SmsReceiver";

    private static final String PREFS_NAME = "MOUMSGS_PREFS";
    private static final String KEY_GATEWAY_ENABLED = "gateway_enabled";
    private static final String KEY_FIREBASE_ENABLED = "firebase_enabled";

    public interface OnSmsReceivedListener {
        void onSmsReceived(SmsItem item);
    }

    private static OnSmsReceivedListener liveListener;

    public static void setLiveListener(OnSmsReceivedListener listener) {
        liveListener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Intent Received: " + intent.getAction());
        if (!SMS_RECEIVED.equals(intent.getAction()) && !SMS_DELIVER.equals(intent.getAction())) return;

        Bundle dataBundle = intent.getExtras();
        if (dataBundle == null) return;

        Object[] pdus = (Object[]) dataBundle.get("pdus");
        if (pdus == null || pdus.length == 0) return;

        int simSlot = 0;
        if (dataBundle.containsKey("subscription")) {
            simSlot = dataBundle.getInt("subscription");
        } else if (dataBundle.containsKey("slot")) {
            simSlot = dataBundle.getInt("slot");
        } else if (dataBundle.containsKey("simId")) {
            simSlot = dataBundle.getInt("simId");
        }

        SmsMessage[] messages = new SmsMessage[pdus.length];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pdus.length; i++) {
            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            sb.append(messages[i].getMessageBody());
        }

        final String phoneNo = messages[0].getOriginatingAddress();
        final String msg = sb.toString();
        long smsDate = messages[0].getTimestampMillis();
        final String currentTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(smsDate));
        final int finalSimSlot = simSlot;

        SmsItem newItem = new SmsItem(phoneNo, msg, smsDate, currentTimestamp, false, simSlot);

        DatabaseHelper dbHelper = new DatabaseHelper(context);
        dbHelper.insertMessage(phoneNo, msg, smsDate, simSlot);

        NotificationHelper.showMessageNotification(context, phoneNo, msg, smsDate);

        if (liveListener != null) {
            liveListener.onSmsReceived(newItem);
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean gatewayEnabled = prefs.getBoolean(KEY_GATEWAY_ENABLED, true);
        boolean firebaseEnabled = prefs.getBoolean(KEY_FIREBASE_ENABLED, true);

        if (gatewayEnabled) {
            MyForegroundService fs = MyForegroundService.getInstance();
            if (fs != null) {
                PhoneSocketService socketService = fs.getPhoneSocket();
                if (socketService != null) {
                    socketService.sendSmsEvent(phoneNo, msg, currentTimestamp, simSlot);
                } else {
                    startServiceAndSend(context, phoneNo, msg, currentTimestamp, simSlot);
                }
            } else {
                startServiceAndSend(context, phoneNo, msg, currentTimestamp, simSlot);
            }
        }

        if (firebaseEnabled) {
            uploadToFirebase(msg, phoneNo, currentTimestamp.substring(0, 10), currentTimestamp.substring(11), simSlot);
        }
    }

    private void startServiceAndSend(Context context, String phoneNo, String msg, String timestamp, int simSlot) {
        Intent serviceIntent = new Intent(context, MyForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            MyForegroundService delayedFs = MyForegroundService.getInstance();
            if (delayedFs != null && delayedFs.getPhoneSocket() != null) {
                delayedFs.getPhoneSocket().sendSmsEvent(phoneNo, msg, timestamp, simSlot);
            }
        }, 1000);
    }

    private void uploadToFirebase(String msg, String phoneNo, String msgDate, String msgTime, int simSlot) {
        try {
            DatabaseReference MsgDbRef = FirebaseDatabase.getInstance().getReference().child("Message");
            User user = new User(msg, phoneNo, msgDate, msgTime, simSlot);
            MsgDbRef.push().setValue(user);
        } catch (Exception e) {
            Log.e(TAG, "Error uploading to Firebase: " + e.getMessage());
        }
    }
}
