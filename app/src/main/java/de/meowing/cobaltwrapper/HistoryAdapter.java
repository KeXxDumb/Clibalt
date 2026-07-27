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
        holder.time.setText(entry.time);

        if (entry.thumbPath != null && new File(entry.thumbPath).exists()) {
            holder.thumb.setPadding(0, 0, 0, 0);
            holder.thumb.setImageBitmap(BitmapFactory.decodeFile(entry.thumbPath));
        } else {
            int pad = (int) (14 * holder.itemView.getResources().getDisplayMetrics().density);
            holder.thumb.setPadding(pad, pad, pad, pad);
            holder.thumb.setImageResource(R.drawable.ic_link);
        }

        holder.itemView.setOnClickListener(v -> listener.onItemClick(entry));
        holder.deleteButton.setOnClickListener(v -> listener.onDeleteClick(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView thumb;
        TextView url;
        TextView time;
        ImageButton deleteButton;

        ViewHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            url = itemView.findViewById(R.id.url);
            time = itemView.findViewById(R.id.time);
            deleteButton = itemView.findViewById(R.id.delete_button);
        }
    }
}
