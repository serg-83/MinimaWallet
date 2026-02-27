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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tab 2 of FutureCash: shows locked coins and lets the user collect them
 * once the target block is reached.
 *
 * Uses the "coins relevant:true" API command to fetch coins locked at
 * the FutureCash time-lock script address.
 */
public class FutureCoinsFragment extends Fragment {

    // ~50 seconds per Minima block
    private static final long BLOCK_TIME_MS = 50_000L;

    // State slot indices matching the time-lock script
    private static final int STATE_TARGET_BLOCK = 1;
    private static final int STATE_TARGET_ADDRESS = 2;
    private static final int STATE_TARGET_MS = 3;
    private static final int STATE_COINAGE = 4;

    private WalletViewModel walletViewModel;
    private SharedPreferences sharedPreferences;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView recyclerView;
    private TextView emptyText;
    private ProgressBar loadingBar;

    private CoinAdapter adapter;
    private List<FutureCoin> coins = new ArrayList<>();
    private long currentBlock = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Reuse a simple layout: just RecyclerView + empty text
        View root = inflater.inflate(R.layout.fragment_future_list, container, false);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sharedPreferences = requireContext().getSharedPreferences("app_settings", 0);
        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);

        recyclerView = view.findViewById(R.id.future_coins_recycler);
        emptyText = view.findViewById(R.id.future_list_empty);
        loadingBar = view.findViewById(R.id.future_list_loading);

        // Replace log adapter with our coin adapter
        adapter = new CoinAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        // Refresh button
        MaterialButton refreshBtn = view.findViewById(R.id.future_refresh_btn);
        if (refreshBtn != null) refreshBtn.setOnClickListener(v -> loadCoins());

        loadCoins();
    }

    private void loadCoins() {
        if (loadingBar != null) loadingBar.setVisibility(View.VISIBLE);
        if (emptyText != null) emptyText.setVisibility(View.GONE);

        String apiUrl = sharedPreferences.getString("api_url",
                "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");

        executor.execute(() -> {
            try {
                // 1. Get current block + script address in one batch
                String batchResp = ApiHelper.post(apiUrl,
                        "block;" +
                        "newscript script:\"RETURN (@BLOCK GTE PREVSTATE(1) OR @COINAGE GTE PREVSTATE(4)) AND VERIFYOUT(@INPUT PREVSTATE(2) @AMOUNT @TOKENID FALSE)\" trackall:false");

                String scriptAddress = parseScriptAddress(batchResp);
                currentBlock = parseBlockFromBatch(batchResp);

                // 2. Get locked coins filtered by script address + state port2 (recipient) + megammr
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
                if (scriptAddress == null || scriptAddress.isEmpty()) {
                    android.util.Log.e("FutureCoins", "scriptAddress is null, aborting coins load");
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (loadingBar != null) loadingBar.setVisibility(View.GONE);
                            if (emptyText != null) emptyText.setVisibility(View.VISIBLE);
                        });
                    }
                    return;
                }
                String coinsCmd = "coins address:" + scriptAddress + " state:" + userHexAddr + " megammr:true";
                final String coinsCmdFinal = coinsCmd;
                if (getActivity() != null) getActivity().runOnUiThread(() -> { if (walletViewModel != null) walletViewModel.addServerLog("coins >> " + coinsCmdFinal); });
                String coinsResp = ApiHelper.post(apiUrl, coinsCmd);
                final String coinsRespFinal = coinsResp;
                if (getActivity() != null) getActivity().runOnUiThread(() -> { if (walletViewModel != null) walletViewModel.addServerLog("coins << " + coinsRespFinal); });
                List<FutureCoin> parsed = parseCoins(coinsResp, currentBlock);
                final int parsedCount = parsed.size();
                if (getActivity() != null) getActivity().runOnUiThread(() -> { if (walletViewModel != null) walletViewModel.addServerLog("coins parsed: " + parsedCount); });

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        coins.clear();
                        coins.addAll(parsed);
                        adapter.notifyDataSetChanged();
                        if (loadingBar != null) loadingBar.setVisibility(View.GONE);
                        if (emptyText != null) {
                            emptyText.setVisibility(coins.isEmpty() ? View.VISIBLE : View.GONE);
                            emptyText.setText(R.string.future_no_coins);
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

    /** Parses block number from a single "block" command response. */
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

    /** Parses block number from a batch response array (first item = block command). */
    private long parseBlockFromBatch(String response) {
        try {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(response);
            if (parsed instanceof JSONArray) {
                Object first = ((JSONArray) parsed).get(0);
                if (first instanceof JSONObject) {
                    return parseBlock(first.toString());
                }
            }
            // Fallback: single response
            return parseBlock(response);
        } catch (Exception ignored) { }
        return -1;
    }

    /** Parses script address from a batch response array (second item = newscript command). */
    private String parseScriptAddress(String response) {
        try {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(response);
            if (parsed instanceof JSONArray) {
                JSONArray arr = (JSONArray) parsed;
                if (arr.size() >= 2) {
                    Object second = arr.get(1);
                    if (second instanceof JSONObject) {
                        Object resp = ((JSONObject) second).get("response");
                        if (resp instanceof JSONObject) {
                            Object addr = ((JSONObject) resp).get("address");
                            if (addr != null) return addr.toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private List<FutureCoin> parseCoins(String response, long block) {
        List<FutureCoin> result = new ArrayList<>();
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

                // Only process coins that have state data (FutureCash coins)
                Object stateObj = obj.get("state");
                if (!(stateObj instanceof JSONArray)) continue;
                JSONArray state = (JSONArray) stateObj;

                long targetBlock = -1;
                long targetMs = -1;

                for (Object s : state) {
                    if (!(s instanceof JSONObject)) continue;
                    JSONObject entry = (JSONObject) s;
                    Object portObj = entry.get("port");
                    Object dataObj = entry.get("data");
                    if (portObj == null || dataObj == null) continue;
                    int port = Integer.parseInt(portObj.toString());
                    if (port == STATE_TARGET_BLOCK) targetBlock = Long.parseLong(dataObj.toString());
                    if (port == STATE_TARGET_MS)    targetMs    = Long.parseLong(dataObj.toString());
                }

                if (targetBlock < 0) continue;

                String coinId  = obj.get("coinid")  != null ? obj.get("coinid").toString() : "";
                String amount  = obj.get("amount")  != null ? obj.get("amount").toString() : "0";
                String tokenId = obj.get("tokenid") != null ? obj.get("tokenid").toString() : "0x00";
                boolean unlocked = (block >= 0) && (block >= targetBlock);

                FutureCoin coin = new FutureCoin();
                coin.coinId      = coinId;
                coin.amount      = amount;
                coin.tokenId     = tokenId;
                coin.targetBlock = targetBlock;
                coin.targetMs    = targetMs;
                coin.currentBlock= block;
                coin.unlocked    = unlocked;
                result.add(coin);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void collectCoin(FutureCoin coin) {
        KeyGenerator.KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        if (kd == null) {
            Toast.makeText(requireContext(), R.string.generate_keys_first, Toast.LENGTH_SHORT).show();
            return;
        }

        String apiUrl = sharedPreferences.getString("api_url",
                "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.future_collect_confirm_title)
                .setMessage(getString(R.string.future_collect_confirm_msg, coin.amount))
                .setPositiveButton(R.string.future_collect, (d, w) -> {
                    executor.execute(() -> {
                        try {
                            // Build and post the raw transaction to collect the coin
                            // txncreate → txninput → txnoutput → txnbasics → txnsign → txnpost → txndelete
                            String txId = "futurecollect_" + System.currentTimeMillis();

                            String cmds =
                                    "txncreate id:" + txId + ";" +
                                    "txninput id:" + txId + " coinid:" + coin.coinId + ";" +
                                    "txnoutput id:" + txId +
                                        " amount:" + coin.amount +
                                        " address:" + kd.miniAddress +
                                        " tokenid:" + coin.tokenId +
                                        " storestate:false;" +
                                    "txnbasics id:" + txId + ";" +
                                    "txnpost id:" + txId + ";" +
                                    "txndelete id:" + txId;

                            String resp = ApiHelper.post(apiUrl, cmds);
                            final String respFinal = resp;
                            boolean success = resp != null && resp.contains("\"status\":true");
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    if (walletViewModel != null) walletViewModel.addServerLog("collect >> " + cmds + "\ncollect << " + respFinal);
                                    if (!isAdded()) return;
                                    Toast.makeText(requireContext(),
                                            success ? R.string.future_collected_ok : R.string.future_collect_error,
                                            Toast.LENGTH_SHORT).show();
                                    loadCoins();
                                });
                            }
                        } catch (Exception e) {
                            final String err = e.getMessage();
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    if (walletViewModel != null) walletViewModel.addServerLog("collect error: " + err);
                                    if (isAdded())
                                        Toast.makeText(requireContext(),
                                                R.string.future_collect_error, Toast.LENGTH_SHORT).show();
                                });
                            }
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // --- RecyclerView Adapter ---

    private class CoinAdapter extends RecyclerView.Adapter<CoinAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_future_coin, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            FutureCoin coin = coins.get(position);

            h.amount.setText(coin.amount + " " + ("0x00".equals(coin.tokenId) ? "Minima" : coin.tokenId));

            // Status badge
            if (coin.unlocked) {
                h.badge.setText(R.string.future_badge_unlocked);
                h.badge.setBackgroundResource(R.drawable.bg_badge_unlocked);
            } else {
                h.badge.setText(R.string.future_badge_locked);
                h.badge.setBackgroundResource(R.drawable.bg_badge_locked);
            }

            // Unlock date
            if (coin.targetMs > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
                h.unlockDate.setText(getString(R.string.future_unlocks_on,
                        sdf.format(new Date(coin.targetMs))));
            } else {
                h.unlockDate.setText(getString(R.string.future_unlocks_block, coin.targetBlock));
            }

            // Progress bar
            if (coin.unlocked) {
                h.progress.setVisibility(View.GONE);
                h.collectBtn.setVisibility(View.VISIBLE);
                h.collectBtn.setOnClickListener(v -> collectCoin(coin));
            } else {
                h.progress.setVisibility(View.VISIBLE);
                h.collectBtn.setVisibility(View.GONE);

                // Calculate progress based on blocks elapsed vs total
                if (coin.currentBlock > 0 && coin.targetBlock > 0) {
                    // Estimate lock start block from targetMs
                    long nowMs = System.currentTimeMillis();
                    long remainMs = Math.max(0, coin.targetMs - nowMs);
                    long totalEstMs = coin.targetBlock * BLOCK_TIME_MS; // rough
                    if (coin.targetMs > 0) {
                        // More accurate: calculate from how far we've come
                        long elapsedBlocks = coin.currentBlock; // blocks since creation unknown
                        long remainBlocks = coin.targetBlock - coin.currentBlock;
                        if (remainBlocks < 0) remainBlocks = 0;
                        // Progress = elapsed/(elapsed+remaining) — but we don't know start.
                        // Use time-based estimate instead.
                        long totalMs = coin.targetMs - (nowMs - remainMs); // approximation
                        long pct = totalMs > 0
                                ? (int) (100 - (remainMs * 100L / (totalMs > 0 ? totalMs : 1)))
                                : 0;
                        h.progress.setProgress((int) Math.max(0, Math.min(99, pct)));
                    }
                } else {
                    h.progress.setProgress(0);
                }
            }
        }

        @Override
        public int getItemCount() { return coins.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView amount, badge, unlockDate;
            ProgressBar progress;
            MaterialButton collectBtn;

            VH(View v) {
                super(v);
                amount     = v.findViewById(R.id.coin_amount);
                badge      = v.findViewById(R.id.coin_status_badge);
                unlockDate = v.findViewById(R.id.coin_unlock_date);
                progress   = v.findViewById(R.id.coin_progress);
                collectBtn = v.findViewById(R.id.coin_collect_btn);
            }
        }
    }

    // --- Data model ---

    static class FutureCoin {
        String coinId;
        String amount;
        String tokenId;
        long targetBlock;
        long targetMs;
        long currentBlock;
        boolean unlocked;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        executor.shutdownNow();
    }
}
