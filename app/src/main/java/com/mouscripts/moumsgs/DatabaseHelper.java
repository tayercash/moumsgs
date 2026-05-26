package com.mouscripts.moumsgs;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "moumsgs.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE_MESSAGES = "messages";

    private static final String COL_ID = "id";
    private static final String COL_SENDER = "sender";
    private static final String COL_BODY = "body";
    private static final String COL_DATE = "date";
    private static final String COL_DATE_FORMATTED = "date_formatted";
    private static final String COL_IS_READ = "is_read";
    private static final String COL_SIM_SLOT = "sim_slot";

    private static final String CREATE_TABLE =
            "CREATE TABLE " + TABLE_MESSAGES + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_SENDER + " TEXT NOT NULL, " +
                    COL_BODY + " TEXT NOT NULL, " +
                    COL_DATE + " LONG NOT NULL, " +
                    COL_DATE_FORMATTED + " TEXT, " +
                    COL_IS_READ + " INTEGER DEFAULT 0, " +
                    COL_SIM_SLOT + " INTEGER DEFAULT 0" +
                    ")";

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        onCreate(db);
    }

    public void insertMessage(String sender, String body, long date) {
        insertMessage(sender, body, date, 0);
    }

    public void insertMessage(String sender, String body, long date, int simSlot) {
        SQLiteDatabase db = getWritableDatabase();
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
        String dateFormatted = sdf.format(new Date(date));
        db.execSQL("INSERT INTO " + TABLE_MESSAGES +
                        " (" + COL_SENDER + ", " + COL_BODY + ", " + COL_DATE +
                        ", " + COL_DATE_FORMATTED + ", " + COL_IS_READ + ", " + COL_SIM_SLOT +
                        ") VALUES (?, ?, ?, ?, 0, ?)",
                new Object[]{sender, body, date, dateFormatted, simSlot});
    }

    public List<SmsItem> getAllMessages() {
        List<SmsItem> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT * FROM " + TABLE_MESSAGES + " ORDER BY " + COL_DATE + " DESC",
                null
        );
        while (c.moveToNext()) {
            messages.add(cursorToItem(c));
        }
        c.close();
        return messages;
    }

    public List<SmsItem> getMessagesBySender(String sender) {
        List<SmsItem> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT * FROM " + TABLE_MESSAGES +
                        " WHERE " + COL_SENDER + " = ? ORDER BY " + COL_DATE + " ASC",
                new String[]{sender}
        );
        while (c.moveToNext()) {
            messages.add(cursorToItem(c));
        }
        c.close();
        return messages;
    }

    public void markAsRead(String sender) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE " + TABLE_MESSAGES +
                " SET " + COL_IS_READ + " = 1 WHERE " + COL_SENDER + " = ?",
                new Object[]{sender});
    }

    public int getUnreadCount(String sender) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_MESSAGES +
                        " WHERE " + COL_SENDER + " = ? AND " + COL_IS_READ + " = 0",
                new String[]{sender}
        );
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public int getTotalUnreadCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_MESSAGES +
                        " WHERE " + COL_IS_READ + " = 0",
                null
        );
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public boolean hasMessages() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_MESSAGES, null);
        boolean has = false;
        if (c.moveToFirst()) has = c.getInt(0) > 0;
        c.close();
        return has;
    }

    private SmsItem cursorToItem(Cursor c) {
        String sender = c.getString(c.getColumnIndexOrThrow(COL_SENDER));
        String body = c.getString(c.getColumnIndexOrThrow(COL_BODY));
        long date = c.getLong(c.getColumnIndexOrThrow(COL_DATE));
        String dateFormatted = c.getString(c.getColumnIndexOrThrow(COL_DATE_FORMATTED));
        boolean isRead = c.getInt(c.getColumnIndexOrThrow(COL_IS_READ)) == 1;
        int simSlot = c.getInt(c.getColumnIndexOrThrow(COL_SIM_SLOT));
        return new SmsItem(sender, body, date, dateFormatted, isRead, simSlot);
    }
}
