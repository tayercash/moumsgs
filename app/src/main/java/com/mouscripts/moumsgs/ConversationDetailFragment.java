package com.mouscripts.moumsgs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ConversationDetailFragment extends Fragment implements MyReceiver.OnSmsReceivedListener {

    private static final String ARG_SENDER = "sender";
    private static final String ARG_MESSAGES = "messages";

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private ChatMessageAdapter adapter;
    private String currentSender;

    public static ConversationDetailFragment newInstance(String sender, List<SmsItem> messages) {
        ConversationDetailFragment fragment = new ConversationDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SENDER, sender);
        args.putSerializable(ARG_MESSAGES, new ArrayList<>(messages));
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_conversation_detail, container, false);
        recyclerView = view.findViewById(R.id.chatRecyclerView);
        tvEmpty = view.findViewById(R.id.tvEmptyChat);

        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);

        Bundle args = getArguments();
        if (args != null) {
            currentSender = args.getString(ARG_SENDER);
            List<SmsItem> messages = (List<SmsItem>) args.getSerializable(ARG_MESSAGES);
            if (messages != null && !messages.isEmpty()) {
                // Sort history messages by date and mark them as read
                Collections.sort(messages, new Comparator<SmsItem>() {
                    @Override
                    public int compare(SmsItem o1, SmsItem b) {
                        return Long.compare(o1.getDate(), b.getDate());
                    }
                });

                for (SmsItem msg : messages) {
                    msg.setRead(true); // History messages are marked as read so they don't animate
                }
                adapter = new ChatMessageAdapter(new ArrayList<>(messages));
                recyclerView.setAdapter(adapter);
                recyclerView.scrollToPosition(adapter.getItemCount() - 1);
            } else {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("No messages found");
            }
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        MyReceiver.setLiveListener(this);
        if (currentSender != null && getActivity() != null) {
            NotificationHelper.dismissConversationNotification(getActivity(), currentSender);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        MyReceiver.setLiveListener(null);
    }

    @Override
    public void onSmsReceived(SmsItem item) {
        if (getActivity() == null || !isAdded()) return;

        getActivity().runOnUiThread(() -> {
            if (item.getAddress().equals(currentSender)) {
                NotificationHelper.dismissConversationNotification(getActivity(), currentSender);
                new DatabaseHelper(getActivity()).markAsRead(currentSender);
                if (adapter != null) {
                    adapter.addMessage(item);
                    recyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);
                } else {
                    List<SmsItem> list = new ArrayList<>();
                    list.add(item);
                    adapter = new ChatMessageAdapter(list);
                    recyclerView.setAdapter(adapter);
                    tvEmpty.setVisibility(View.GONE);
                }
            }
        });
    }
}
