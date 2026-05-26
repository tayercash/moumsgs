package com.mouscripts.moumsgs;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.ViewHolder> {

    private final List<Conversation> conversations;
    private final OnConversationClickListener listener;

    public interface OnConversationClickListener {
        void onConversationClick(Conversation conversation);
    }

    public ConversationAdapter(List<Conversation> conversations,
                               OnConversationClickListener listener) {
        this.conversations = conversations;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Conversation conv = conversations.get(position);
        boolean isRead = conv.isRead() && conv.getUnreadCount() == 0;
        String sender = conv.getSender();
        String initial = sender.length() > 0 ? String.valueOf(sender.charAt(0)) : "?";

        holder.tvAvatar.setText(initial);
        holder.tvSender.setText(sender);
        holder.tvLastMessage.setText(conv.getLastMessage());

        int unread = conv.getUnreadCount();
        if (unread > 0) {
            holder.tvCount.setVisibility(View.VISIBLE);
            holder.tvCount.setText(String.valueOf(unread));
        } else {
            holder.tvCount.setVisibility(View.GONE);
        }

        holder.tvDate.setText(formatRelativeDate(conv.getLastDate()));

        if (!isRead) {
            holder.tvSender.setTypeface(Typeface.DEFAULT_BOLD);
            holder.tvLastMessage.setTypeface(Typeface.DEFAULT_BOLD);
            holder.tvLastMessage.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.on_surface));
        } else {
            holder.tvSender.setTypeface(Typeface.DEFAULT);
            holder.tvLastMessage.setTypeface(Typeface.DEFAULT);
            holder.tvLastMessage.setTextColor(
                    ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary));
        }

        holder.itemView.setOnClickListener(v -> listener.onConversationClick(conv));
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    private String formatRelativeDate(long date) {
        long now = System.currentTimeMillis();
        long diff = now - date;
        if (diff < 86400000) return "Today";
        if (diff < 172800000) return "Yesterday";
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM", Locale.US);
        return sdf.format(new Date(date));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvAvatar, tvSender, tvLastMessage, tvDate, tvCount;

        ViewHolder(View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tvAvatar);
            tvSender = itemView.findViewById(R.id.tvSender);
            tvLastMessage = itemView.findViewById(R.id.tvLastMessage);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvCount = itemView.findViewById(R.id.tvCount);
        }
    }
}
