package com.example.minimawallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

public class LogFragment extends Fragment {

    private WalletViewModel walletViewModel;
    private RecyclerView logRecycler;
    private TextView logEmptyText;
    private TextView logCountText;
    private LogAdapter adapter;
    private List<String> currentEntries = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);

        logRecycler = view.findViewById(R.id.log_recycler);
        logEmptyText = view.findViewById(R.id.log_empty_text);
        logCountText = view.findViewById(R.id.log_count_text);
        MaterialButton clearBtn = view.findViewById(R.id.clear_log_btn);
        MaterialButton copyBtn = view.findViewById(R.id.copy_log_btn);

        adapter = new LogAdapter();
        logRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        logRecycler.setAdapter(adapter);

        clearBtn.setOnClickListener(v -> walletViewModel.clearServerLog());
        copyBtn.setOnClickListener(v -> copyAllLogs());

        walletViewModel.getServerLog().observe(getViewLifecycleOwner(), entries -> {
            currentEntries = entries != null ? entries : new ArrayList<>();
            if (entries == null || entries.isEmpty()) {
                logRecycler.setVisibility(View.GONE);
                logEmptyText.setVisibility(View.VISIBLE);
                logCountText.setText("0 " + getString(R.string.log_entries_suffix));
            } else {
                logRecycler.setVisibility(View.VISIBLE);
                logEmptyText.setVisibility(View.GONE);
                logCountText.setText(entries.size() + " " + getString(R.string.log_entries_suffix));
                adapter.setEntries(entries);
            }
        });
    }

    private void copyAllLogs() {
        if (currentEntries.isEmpty()) return;
        String all = TextUtils.join("\n", currentEntries);
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Minima logs", all));
            Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT).show();
        }
    }

    // --- Adapter ---
    static class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

        private List<String> entries = new ArrayList<>();

        void setEntries(List<String> newEntries) {
            entries = newEntries;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.text.setText(entries.get(position));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView text;
            VH(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.log_entry_text);
            }
        }
    }
}
