package com.mouscripts.moumsgs;

import java.io.Serializable;

public class SmsItem implements Serializable {
    private String address;
    private String body;
    private long date;
    private String dateFormatted;
    private boolean isRead;
    private int simSlot;

    public SmsItem(String address, String body, long date, String dateFormatted) {
        this(address, body, date, dateFormatted, false, 0);
    }

    public SmsItem(String address, String body, long date, String dateFormatted,
                   boolean isRead, int simSlot) {
        this.address = address;
        this.body = body;
        this.date = date;
        this.dateFormatted = dateFormatted;
        this.isRead = isRead;
        this.simSlot = simSlot;
    }

    public String getAddress() { return address; }
    public String getBody() { return body; }
    public long getDate() { return date; }
    public String getDateFormatted() { return dateFormatted; }
    public boolean isRead() { return isRead; }
    public int getSimSlot() { return simSlot; }
    public String getSimLabel() {
        if (simSlot == 1) return "SIM 1";
        if (simSlot == 2) return "SIM 2";
        return "";
    }
    public void setRead(boolean read) { isRead = read; }
}
