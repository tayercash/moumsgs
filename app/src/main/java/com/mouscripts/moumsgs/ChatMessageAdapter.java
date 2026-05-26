package com.mouscripts.moumsgs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.ViewHolder> {

    private final List<SmsItem> messages;
    private int lastAnimatedPosition = -1;

    public ChatMessageAdapter(List<SmsItem> messages) {
        this.messages = messages;
        // Initialize lastAnimatedPosition to the end of history to prevent it from animating
        this.lastAnimatedPosition = messages.size() - 1;
    }

    public void addMessage(SmsItem item) {
        messages.add(item);
        notifyItemInserted(messages.size() - 1);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmsItem item = messages.get(position);
        holder.tvBody.setText(item.getBody());
        holder.tvTime.setText(item.getDateFormatted());
        
        String simLabel = item.getSimLabel();
        if (!simLabel.isEmpty()) {
            holder.tvSimSlot.setVisibility(View.VISIBLE);
            holder.tvSimSlot.setText(simLabel);
        } else {
            holder.tvSimSlot.setVisibility(View.GONE);
        }
        
        int currentPos = holder.getBindingAdapterPosition();
        if (currentPos == RecyclerView.NO_POSITION) return;

        // ANIMATION LOGIC:
        // 1. Only animate if the message is NOT marked as read (it's brand new incoming)
        // 2. Only animate if it's at a position we haven't animated yet in this session
        if (!item.isRead() && currentPos > lastAnimatedPosition) {
            Animation animation = AnimationUtils.loadAnimation(holder.itemView.getContext(), R.anim.slide_up);
            holder.itemView.startAnimation(animation);
            lastAnimatedPosition = currentPos;
            item.setRead(true); // Mark as read locally so it won't animate again during scrolling
        } else {
            // Prevent recycled views from carrying over animations for old messages
            holder.itemView.clearAnimation();
            
            // Still update the tracking position for read items to maintain the "new message" threshold
            if (currentPos > lastAnimatedPosition) {
                lastAnimatedPosition = currentPos;
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvBody, tvTime, tvSimSlot;

        ViewHolder(View itemView) {
            super(itemView);
            tvBody = itemView.findViewById(R.id.tvChatBody);
            tvTime = itemView.findViewById(R.id.tvChatTime);
            tvSimSlot = itemView.findViewById(R.id.tvChatSimSlot);
        }
    }
}
