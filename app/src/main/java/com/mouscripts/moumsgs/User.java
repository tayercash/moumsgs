package com.mouscripts.moumsgs;

public class User {

    String Msg;
    String PhoneNo;
    String Msg_Date;
    String Msg_time;
    int SimSlot;

    public User(String msg, String phoneNo, String msg_Date, String msg_time) {
        this(msg, phoneNo, msg_Date, msg_time, 0);
    }

    public User(String msg, String phoneNo, String msg_Date, String msg_time, int simSlot) {
        Msg = msg;
        PhoneNo = phoneNo;
        Msg_Date = msg_Date;
        Msg_time = msg_time;
        SimSlot = simSlot;
    }

    public String getMsg() {
        return Msg;
    }

    public String getPhoneNo() {
        return PhoneNo;
    }

    public String getMsg_Date() {
        return Msg_Date;
    }

    public String getMsg_time() {
        return Msg_time;
    }

    public int getSimSlot() {
        return SimSlot;
    }

}
