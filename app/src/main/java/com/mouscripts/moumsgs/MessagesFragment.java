package com.mouscripts.moumsgs;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Telephony;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MessagesFragment extends Fragment {

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private ConversationAdapter adapter;
    private List<Conversation> conversations;
    private DatabaseHelper dbHelper;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_messages, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        dbHelper = new DatabaseHelper(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        conversations = new ArrayList<>();
        adapter = new ConversationAdapter(conversations, conversation -> {
            dbHelper.markAsRead(conversation.getSender());
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer,
                            ConversationDetailFragment.newInstance(
                                    conversation.getSender(),
                                    new ArrayList<>(conversation.getMessages())))
                    .addToBackStack(null)
                    .commit();
        });
        recyclerView.setAdapter(adapter);
        reloadMessages();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (dbHelper != null && dbHelper.hasMessages()) {
            loadFromDatabase();
        }
    }

    public void reloadMessages() {
        if (!isAdded()) return;

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.no_permission);
            recyclerView.setVisibility(View.GONE);
            return;
        }

        recyclerView.setVisibility(View.VISIBLE);

        if (!dbHelper.hasMessages()) {
            loadFromSystemProvider();
        } else {
            loadFromDatabase();
        }
    }

    private void loadFromSystemProvider() {
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
            Log.e("MessagesFragment", "Error: " + e.getMessage(), e);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("Error: " + e.getMessage());
            return;
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

        loadFromDatabase();
    }

    private void loadFromDatabase() {
        List<SmsItem> allMessages = dbHelper.getAllMessages();

        if (allMessages.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.no_messages);
            return;
        }

        Map<String, List<SmsItem>> grouped = new LinkedHashMap<>();
        for (SmsItem item : allMessages) {
            String key = item.getAddress();
            if (!grouped.containsKey(key)) {
                grouped.put(key, new ArrayList<>());
            }
            grouped.get(key).add(item);
        }

        conversations.clear();
        SimpleDateFormat dateOnly = new SimpleDateFormat("dd/MM", Locale.ENGLISH);
        for (Map.Entry<String, List<SmsItem>> entry : grouped.entrySet()) {
            List<SmsItem> msgs = entry.getValue();
            Collections.sort(msgs, (a, b) -> Long.compare(b.getDate(), a.getDate()));
            SmsItem last = msgs.get(0);
            int unread = dbHelper.getUnreadCount(entry.getKey());
            conversations.add(new Conversation(
                    entry.getKey(),
                    last.getBody(),
                    last.getDate(),
                    last.isRead(),
                    unread,
                    dateOnly.format(new Date(last.getDate())),
                    msgs
            ));
        }
        Collections.sort(conversations, (a, b) -> Long.compare(b.getLastDate(), a.getLastDate()));
        adapter.notifyDataSetChanged();

        if (conversations.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText(R.string.no_messages);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }
}
