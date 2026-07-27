package de.meowing.cobaltwrapper;

import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    public interface Listener {
        void onItemClick(HistoryEntry entry);
        void onDeleteClick(int position);
    }

    private final List<HistoryEntry> items;
    private final Listener listener;

    public HistoryAdapter(List<HistoryEntry> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HistoryEntry entry = items.get(position);

        holder.url.setText(entry.url);
        holder.url.setTextColor(ThemeState.textPrimary);

        holder.card.setCardBackgroundColor(ThemeState.surface);
        holder.card.setStrokeColor(ThemeState.divider);

        switch (entry.status) {
            case HistoryEntry.STATUS_DOWNLOADING:
                holder.time.setText("Downloading…");
                holder.time.setTextColor(0xFF1E88E5);
                break;
            case HistoryEntry.STATUS_FAILED:
                holder.time.setText("Failed · tap to retry");
                holder.time.setTextColor(0xFFD32F2F);
                break;
            default:
                holder.time.setText(entry.time);
                holder.time.setTextColor(ThemeState.textSecondary);
                break;
        }

        if (entry.thumbPath != null && new File(entry.thumbPath).exists()) {
            holder.thumb.setPadding(0, 0, 0, 0);
            holder.thumb.setImageBitmap(BitmapFactory.decodeFile(entry.thumbPath));
            holder.thumb.setBackgroundColor(ThemeState.background);
        } else {
            int pad = (int) (14 * holder.itemView.getResources().getDisplayMetrics().density);
            holder.thumb.setPadding(pad, pad, pad, pad);
            holder.thumb.setImageResource(R.drawable.ic_link);
            holder.thumb.setBackgroundColor(ThemeState.background);
            holder.thumb.setColorFilter(ThemeState.textSecondary);
        }

        holder.deleteButton.setColorFilter(ThemeState.textSecondary);

        holder.itemView.setOnClickListener(v -> listener.onItemClick(entry));
        holder.deleteButton.setOnClickListener(v -> listener.onDeleteClick(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ImageView thumb;
        TextView url;
        TextView time;
        ImageButton deleteButton;

        ViewHolder(View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            thumb = itemView.findViewById(R.id.thumb);
            url = itemView.findViewById(R.id.url);
            time = itemView.findViewById(R.id.time);
            deleteButton = itemView.findViewById(R.id.delete_button);
        }
    }
}
