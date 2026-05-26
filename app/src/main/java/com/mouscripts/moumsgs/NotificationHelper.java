package com.mouscripts.moumsgs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NotificationHelper {

    private static final String TAG = "NotificationHelper";
    private static final String GROUP_KEY_CONVERSATIONS = "com.mouscripts.moumsgs.CONVERSATIONS";

    public static final String CHANNEL_MESSAGES = "sms_messages_v2";
    public static final String CHANNEL_SERVICE = "MOUMSGS_Channel_v4";

    private static final int BASE_NOTIFICATION_ID = 2000;
    private static final int SUMMARY_NOTIFICATION_ID = 1000;

    private static final ConcurrentHashMap<String, SenderNotification> senderNotifs = new ConcurrentHashMap<>();
    private static int nextId = BASE_NOTIFICATION_ID;

    private static class SenderNotification {
        final int id;
        final SmsStorage storage;

        SenderNotification(int id, SmsStorage storage) {
            this.id = id;
            this.storage = storage;
        }
    }

    private static class SmsStorage {
        final String sender;
        final LinkedHashMap<Long, String> messages = new LinkedHashMap<>();

        SmsStorage(String sender) {
            this.sender = sender;
        }

        void addMessage(String message, long timestamp) {
            while (messages.size() >= 10) {
                Long oldest = null;
                for (Long key : messages.keySet()) {
                    oldest = key;
                    break;
                }
                if (oldest != null) messages.remove(oldest);
            }
            messages.put(timestamp, message);
        }

        String getSummary() {
            if (messages.isEmpty()) return "";
            Long last = null;
            for (Long key : messages.keySet()) last = key;
            return last != null ? messages.get(last) : "";
        }

        int getCount() {
            return messages.size();
        }
    }

    public static void init(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannelGroup group = new NotificationChannelGroup("sms_group", "SMS Conversations");
                nm.createNotificationChannelGroup(group);

                NotificationChannel msgChannel = new NotificationChannel(
                        CHANNEL_MESSAGES,
                        "Messages",
                        NotificationManager.IMPORTANCE_HIGH
                );
                msgChannel.setDescription("New SMS messages");
                msgChannel.setGroup("sms_group");
                msgChannel.setShowBadge(true);
                msgChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                msgChannel.enableVibration(true);
                msgChannel.setVibrationPattern(new long[]{0, 200, 100, 200});
                nm.createNotificationChannel(msgChannel);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create channels", e);
            }
        }
    }

    public static void showMessageNotification(Context context, String sender, String message, long timestamp) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_MESSAGES) == null) {
                init(context);
            }
        }

        SenderNotification sn = senderNotifs.get(sender);
        SmsStorage storage;
        int notificationId;

        if (sn == null) {
            int id = nextId++;
            storage = new SmsStorage(sender);
            senderNotifs.put(sender, new SenderNotification(id, storage));
            notificationId = id;
        } else {
            storage = sn.storage;
            notificationId = sn.id;
        }

        storage.addMessage(message, timestamp);

        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timestamp));

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("sender", sender);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(context, notificationId, intent, flags);

        NotificationCompat.MessagingStyle messagingStyle = buildMessagingStyle(sender, storage, message, timestamp);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setStyle(messagingStyle)
                .setContentTitle(sender)
                .setContentText(storage.getSummary())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup(GROUP_KEY_CONVERSATIONS)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setShortcutId(sender)
                .setColor(0xFF1A73E8)
                .setDefaults(Notification.DEFAULT_ALL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_MESSAGES);
        }

        nm.notify(notificationId, builder.build());
        updateSummaryNotification(context, nm);
    }

    private static NotificationCompat.MessagingStyle buildMessagingStyle(
            String sender, SmsStorage storage, String newMessage, long newTimestamp) {

        Person senderPerson = new Person.Builder()
                .setName(sender)
                .setKey(sender)
                .build();

        Person appPerson = new Person.Builder()
                .setName("Me")
                .build();

        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(appPerson);
        style.setConversationTitle(sender);

        for (Map.Entry<Long, String> entry : storage.messages.entrySet()) {
            style.addMessage(entry.getValue(), entry.getKey(), senderPerson);
        }

        return style;
    }

    private static void updateSummaryNotification(Context context, NotificationManager nm) {
        int count = senderNotifs.size();
        if (count <= 0) return;

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(context, SUMMARY_NOTIFICATION_ID, intent, flags);

        NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
        inboxStyle.setSummaryText(count + " conversations");

        for (SenderNotification sn : senderNotifs.values()) {
            inboxStyle.addLine(sn.storage.sender + ": " + sn.storage.getSummary());
        }

        Notification summaryNotification = new NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("MOU MSGS")
                .setContentText(count + (count == 1 ? " conversation" : " conversations"))
                .setStyle(inboxStyle)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setGroup(GROUP_KEY_CONVERSATIONS)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_MESSAGES);
            if (channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_HIGH) {
            }
        }

        nm.notify(SUMMARY_NOTIFICATION_ID, summaryNotification);
    }

    public static void dismissConversationNotification(Context context, String sender) {
        SenderNotification sn = senderNotifs.remove(sender);
        if (sn != null) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(sn.id);
                Log.d(TAG, "Dismissed notification for " + sender);
            }
        }
        updateSummaryAfterDismiss(context);
    }

    public static void dismissAllNotifications(Context context) {
        senderNotifs.clear();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancelAll();
        }
    }

    private static void updateSummaryAfterDismiss(Context context) {
        if (senderNotifs.isEmpty()) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(SUMMARY_NOTIFICATION_ID);
            }
        } else {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                updateSummaryNotification(context, nm);
            }
        }
    }
}
