package com.example.minimawallet;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MaximizeBondsFragment extends Fragment {

    private static final String BOND_ADDRESS =
            "MxG0861MPQ3ZQTM4GFTZ0UJA74Y48A4GDPYM1NTVKDTU0B34BFDV86G5A0PD21N";
    private static final long BLOCK_TIME_MS = 50_000L;
    private static final int DAY_OF_BLOCKS = 1728;

    // State slot indices for Maximize bonds
    private static final int STATE_PUBKEY = 100;
    private static final int STATE_MAXBLOCK = 101;
    private static final int STATE_USER_ADDRESS = 102;
    private static final int STATE_MAXCOINAGE = 104;
    private static final int STATE_RATE = 105;

    private WalletViewModel walletViewModel;
    private SharedPreferences sharedPreferences;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView recyclerView;
    private TextView emptyText;
    private TextView cancelInfoText;
    private ProgressBar loadingBar;

    private BondAdapter adapter;
    private List<MaximizeBond> bonds = new ArrayList<>();
    private long currentBlock = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_maximize_bonds, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sharedPreferences = requireContext().getSharedPreferences("app_settings", 0);
        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);

        recyclerView = view.findViewById(R.id.maximize_bonds_recycler);
        emptyText = view.findViewById(R.id.maximize_list_empty);
        cancelInfoText = view.findViewById(R.id.maximize_cancel_info);
        loadingBar = view.findViewById(R.id.maximize_list_loading);

        adapter = new BondAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        MaterialButton refreshBtn = view.findViewById(R.id.maximize_refresh_btn);
        if (refreshBtn != null) refreshBtn.setOnClickListener(v -> loadBonds());

        loadBonds();
    }

    private void loadBonds() {
        if (loadingBar != null) loadingBar.setVisibility(View.VISIBLE);
        if (emptyText != null) emptyText.setVisibility(View.GONE);

        String apiUrl = sharedPreferences.getString("api_url",
                "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");

        executor.execute(() -> {
            try {
                // Get current block
                String blockResp = ApiHelper.post(apiUrl, "block");
                currentBlock = parseBlock(blockResp);

                KeyGenerator.KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
                String userHexAddr = kd != null ? kd.address : null;
                if (userHexAddr == null || userHexAddr.isEmpty()) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (loadingBar != null) loadingBar.setVisibility(View.GONE);
                            if (emptyText != null) {
                                emptyText.setVisibility(View.VISIBLE);
                                emptyText.setText(R.string.generate_keys_first);
                            }
                        });
                    }
                    return;
                }

                // Query bonds at the Maximize bond address filtered by user's address in state
                String coinsCmd = "coins address:" + BOND_ADDRESS + " state:" + userHexAddr + " megammr:true";
                final String coinsCmdFinal = coinsCmd;
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (walletViewModel != null) walletViewModel.addServerLog("maximize >> " + coinsCmdFinal);
                });
                String coinsResp = ApiHelper.post(apiUrl, coinsCmd);
                final String coinsRespFinal = coinsResp;
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (walletViewModel != null) walletViewModel.addServerLog("maximize << " + coinsRespFinal);
                });

                List<MaximizeBond> parsed = parseBonds(coinsResp, currentBlock);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        bonds.clear();
                        bonds.addAll(parsed);
                        adapter.notifyDataSetChanged();
                        if (loadingBar != null) loadingBar.setVisibility(View.GONE);
                        if (emptyText != null) {
                            emptyText.setVisibility(bonds.isEmpty() ? View.VISIBLE : View.GONE);
                            emptyText.setText(R.string.maximize_no_bonds);
                        }
                        if (cancelInfoText != null) {
                            cancelInfoText.setVisibility(bonds.isEmpty() ? View.GONE : View.VISIBLE);
                        }
                    });
                }
            } catch (Exception e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        if (loadingBar != null) loadingBar.setVisibility(View.GONE);
                        if (emptyText != null) {
                            emptyText.setVisibility(View.VISIBLE);
                            emptyText.setText(R.string.future_load_error);
                        }
                    });
                }
            }
        });
    }

    private long parseBlock(String response) {
        try {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(response);
            if (parsed instanceof JSONObject) {
                Object resp = ((JSONObject) parsed).get("response");
                if (resp instanceof JSONObject) {
                    Object block = ((JSONObject) resp).get("block");
                    if (block != null) return Long.parseLong(block.toString());
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private List<MaximizeBond> parseBonds(String response, long block) {
        List<MaximizeBond> result = new ArrayList<>();
        try {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(response);
            if (!(parsed instanceof JSONObject)) return result;
            Object respObj = ((JSONObject) parsed).get("response");
            if (!(respObj instanceof JSONArray)) return result;

            JSONArray arr = (JSONArray) respObj;
            for (Object item : arr) {
                if (!(item instanceof JSONObject)) continue;
                JSONObject obj = (JSONObject) item;

                Object stateObj = obj.get("state");
                if (!(stateObj instanceof JSONArray)) continue;
                JSONArray state = (JSONArray) stateObj;

                String pubkey = null;
                long maxBlock = -1;
                String userAddress = null;
                long maxCoinage = -1;
                double rate = 0;

                for (Object s : state) {
                    if (!(s instanceof JSONObject)) continue;
                    JSONObject entry = (JSONObject) s;
                    Object portObj = entry.get("port");
                    Object dataObj = entry.get("data");
                    if (portObj == null || dataObj == null) continue;
                    int port = Integer.parseInt(portObj.toString());
                    String data = dataObj.toString();
                    if (port == STATE_PUBKEY) pubkey = data;
                    if (port == STATE_MAXBLOCK) maxBlock = Long.parseLong(data);
                    if (port == STATE_USER_ADDRESS) userAddress = data;
                    if (port == STATE_MAXCOINAGE) maxCoinage = Long.parseLong(data);
                    if (port == STATE_RATE) rate = Double.parseDouble(data);
                }

                if (maxBlock < 0) continue;

                String coinId = obj.get("coinid") != null ? obj.get("coinid").toString() : "";
                String amount = obj.get("amount") != null ? obj.get("amount").toString() : "0";

                long createdBlock = 0;
                Object createdObj = obj.get("created");
                if (createdObj != null) {
                    try { createdBlock = Long.parseLong(createdObj.toString()); } catch (Exception ignored) {}
                }

                MaximizeBond bond = new MaximizeBond();
                bond.coinId = coinId;
                bond.amount = amount;
                bond.pubkey = pubkey;
                bond.maxBlock = maxBlock;
                bond.userAddress = userAddress;
                bond.maxCoinage = maxCoinage;
                bond.rate = rate;
                bond.currentBlock = block;
                bond.createdBlock = createdBlock;
                bond.unlocked = (block >= 0) && (block >= maxBlock);

                // Cancel window: coinage (@BLOCK - created) must be between 3 and 10
                if (block > 0 && createdBlock > 0) {
                    long coinage = block - createdBlock;
                    bond.canCancel = (coinage >= 3 && coinage <= 10);
                }

                // Calculate income
                if (rate > 0) {
                    BigDecimal amt = new BigDecimal(amount);
                    bond.totalReturn = amt.multiply(BigDecimal.valueOf(rate))
                            .setScale(4, RoundingMode.HALF_UP).toPlainString();
                    bond.profit = amt.multiply(BigDecimal.valueOf(rate))
                            .subtract(amt).setScale(4, RoundingMode.HALF_UP).toPlainString();
                }

                // Estimate unlock date from blocks remaining
                if (maxBlock > 0 && block > 0) {
                    long remainBlocks = maxBlock - block;
                    bond.unlockMs = System.currentTimeMillis() + (remainBlocks * BLOCK_TIME_MS);
                }

                result.add(bond);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void cancelBond(MaximizeBond bond) {
        KeyGenerator.KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        if (kd == null) {
            Toast.makeText(requireContext(), R.string.generate_keys_first, Toast.LENGTH_SHORT).show();
            return;
        }

        String apiUrl = sharedPreferences.getString("api_url",
                "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.maximize_cancel_confirm_title)
                .setMessage(getString(R.string.maximize_cancel_confirm_msg, bond.amount))
                .setPositiveButton(R.string.maximize_cancel_stake, (d, w) -> {
                    KeyGenerator kg = new KeyGenerator(new KeyGenerator.KeyGeneratorCallback() {
                        @Override public void onProgressUpdate(String message) {}
                        @Override public void onKeyGenerated(KeyGenerator.KeyData keyData) {}
                        @Override public void onTransactionCreated(String txId) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), R.string.maximize_cancelled_ok, Toast.LENGTH_SHORT).show();
                            loadBonds();
                        }
                        @Override public void onError(String error) {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(), R.string.maximize_cancel_error, Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onServerLog(String command, String response) {
                            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                                if (walletViewModel != null)
                                    walletViewModel.addServerLog("cancel >> " + command + "\ncancel << " + response);
                            });
                        }
                    });
                    kg.cancelMaximizeBond(apiUrl, kd, bond.coinId, bond.amount);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- RecyclerView Adapter ---

    private class BondAdapter extends RecyclerView.Adapter<BondAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_maximize_bond, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            MaximizeBond bond = bonds.get(position);

            h.amount.setText(bond.amount + " Minima");

            // Status badge
            if (bond.unlocked) {
                h.badge.setText(R.string.future_badge_unlocked);
                h.badge.setBackgroundResource(R.drawable.bg_badge_unlocked);
            } else {
                h.badge.setText(R.string.future_badge_locked);
                h.badge.setBackgroundResource(R.drawable.bg_badge_locked);
            }

            // Income
            if (bond.rate > 0) {
                double pct = (bond.rate - 1.0) * 100.0;
                h.income.setText(getString(R.string.maximize_bond_income,
                        String.format(Locale.US, "%.2f%%", pct),
                        bond.totalReturn != null ? bond.totalReturn : "?"));
                h.income.setVisibility(View.VISIBLE);
            } else {
                h.income.setVisibility(View.GONE);
            }

            // Unlock date
            if (bond.unlockMs > 0 && !bond.unlocked) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
                h.unlockDate.setText(getString(R.string.future_unlocks_on,
                        sdf.format(new Date(bond.unlockMs))));
            } else if (bond.unlocked) {
                h.unlockDate.setText(R.string.future_badge_unlocked);
            } else {
                h.unlockDate.setText(getString(R.string.future_unlocks_block, bond.maxBlock));
            }

            // Progress bar
            if (bond.maxCoinage > 0 && bond.currentBlock > 0 && !bond.unlocked) {
                long totalBlocks = bond.maxCoinage;
                long elapsed = totalBlocks - (bond.maxBlock - bond.currentBlock);
                if (elapsed < 0) elapsed = 0;
                int pct = (int) (elapsed * 100 / totalBlocks);
                h.progress.setProgress(Math.max(0, Math.min(99, pct)));
                h.progress.setVisibility(View.VISIBLE);
            } else {
                h.progress.setVisibility(bond.unlocked ? View.GONE : View.VISIBLE);
                h.progress.setProgress(bond.unlocked ? 100 : 0);
            }

            // Cancel button — only available when coinage is 3-10 blocks
            if (bond.canCancel) {
                h.cancelBtn.setVisibility(View.VISIBLE);
                h.cancelBtn.setOnClickListener(v -> cancelBond(bond));
            } else {
                h.cancelBtn.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() { return bonds.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView amount, badge, income, unlockDate;
            ProgressBar progress;
            MaterialButton cancelBtn;

            VH(View v) {
                super(v);
                amount     = v.findViewById(R.id.bond_amount);
                badge      = v.findViewById(R.id.bond_status_badge);
                income     = v.findViewById(R.id.bond_income);
                unlockDate = v.findViewById(R.id.bond_unlock_date);
                progress   = v.findViewById(R.id.bond_progress);
                cancelBtn  = v.findViewById(R.id.bond_cancel_btn);
            }
        }
    }

    // --- Data model ---

    static class MaximizeBond {
        String coinId;
        String amount;
        String pubkey;
        long maxBlock;
        String userAddress;
        long maxCoinage;
        double rate;
        long currentBlock;
        long createdBlock;
        boolean unlocked;
        boolean canCancel;
        String totalReturn;
        String profit;
        long unlockMs;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }
}
