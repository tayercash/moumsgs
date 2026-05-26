package com.mouscripts.moumsgs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.ViewHolder> {

    private final List<SmsItem> messages;

    public MessagesAdapter(List<SmsItem> messages) {
        this.messages = messages;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SmsItem item = messages.get(position);
        holder.tvSender.setText("\uD83D\uDCF1 " + item.getAddress());
        holder.tvBody.setText(item.getBody());
        holder.tvDate.setText(item.getDateFormatted());
        String simLabel = item.getSimLabel();
        if (!simLabel.isEmpty()) {
            holder.tvSimSlot.setVisibility(View.VISIBLE);
            holder.tvSimSlot.setText(simLabel);
        } else {
            holder.tvSimSlot.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvSender, tvBody, tvDate, tvSimSlot;

        ViewHolder(View itemView) {
            super(itemView);
            tvSender = itemView.findViewById(R.id.tvSender);
            tvBody = itemView.findViewById(R.id.tvBody);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvSimSlot = itemView.findViewById(R.id.tvSimSlot);
        }
    }
}
