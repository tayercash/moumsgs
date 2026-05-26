package com.mouscripts.moumsgs;

import java.util.List;

public class Conversation {
    private String sender;
    private String lastMessage;
    private long lastDate;
    private boolean isRead;
    private int unreadCount;
    private String lastDateFormatted;
    private List<SmsItem> messages;

    public Conversation(String sender, String lastMessage, long lastDate,
                        boolean isRead, int unreadCount,
                        String lastDateFormatted, List<SmsItem> messages) {
        this.sender = sender;
        this.lastMessage = lastMessage;
        this.lastDate = lastDate;
        this.isRead = isRead;
        this.unreadCount = unreadCount;
        this.lastDateFormatted = lastDateFormatted;
        this.messages = messages;
    }

    public String getSender() { return sender; }
    public String getLastMessage() { return lastMessage; }
    public long getLastDate() { return lastDate; }
    public boolean isRead() { return isRead; }
    public int getUnreadCount() { return unreadCount; }
    public String getLastDateFormatted() { return lastDateFormatted; }
    public List<SmsItem> getMessages() { return messages; }
    public int getMessageCount() { return messages.size(); }
}
