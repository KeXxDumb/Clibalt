package de.meowing.cobaltwrapper;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

public class HistoryBottomSheet extends BottomSheetDialogFragment {

    public interface OnHistoryActionListener {
        void onLinkSelected(String url);
    }

    private OnHistoryActionListener listener;
    private HistoryStore store;
    private HistoryAdapter adapter;
    private List<HistoryEntry> entries;
    private View emptyState;
    private RecyclerView recyclerView;

    public void setListener(OnHistoryActionListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        store = new HistoryStore(requireContext());
        entries = new ArrayList<>(store.loadAll());

        recyclerView = view.findViewById(R.id.history_list);
        emptyState = view.findViewById(R.id.empty_state);

        adapter = new HistoryAdapter(entries, new HistoryAdapter.Listener() {
            @Override
            public void onItemClick(HistoryEntry entry) {
                if (listener != null) listener.onLinkSelected(entry.url);
                dismiss();
            }

            @Override
            public void onDeleteClick(int position) {
                if (position < 0 || position >= entries.size()) return;
                entries.remove(position);
                store.removeAt(position);
                adapter.notifyItemRemoved(position);
                updateEmptyState();
                Snackbar.make(view, R.string.snackbar_entry_removed, Snackbar.LENGTH_SHORT).show();
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        view.findViewById(R.id.clear_all_button).setOnClickListener(v -> confirmClearAll(view));

        updateEmptyState();
    }

    private void confirmClearAll(View anchor) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_clear_confirm_title)
                .setMessage(R.string.history_clear_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_clear_confirm_action, (dialog, which) -> {
                    store.clear();
                    int size = entries.size();
                    entries.clear();
                    adapter.notifyItemRangeRemoved(0, size);
                    updateEmptyState();
                })
                .show();
    }

    private void updateEmptyState() {
        boolean empty = entries.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
