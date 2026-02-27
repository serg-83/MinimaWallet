package com.example.minimawallet;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.minimawallet.KeyGenerator.KeyData;
import com.example.minimawallet.KeyGenerator.TokenBalance;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tab 1 of FutureCash: lock funds until a chosen date.
 *
 * Flow:
 * 1. User picks token, amount and unlock date/time.
 * 2. App calculates target block: currentBlock + (deltaMs / 50_000).
 * 3. Sends createfrom with the time-lock script stored as state.
 *
 * Time-lock script:
 *   RETURN (@BLOCK GTE PREVSTATE(1) OR @COINAGE GTE PREVSTATE(4)) AND VERIFYOUT(@INPUT PREVSTATE(2) @AMOUNT @TOKENID FALSE)
 *
 * State slots:
 *   1 = target block
 *   2 = recipient address (for VERIFYOUT)
 *   4 = coinage threshold
 */
public class FutureSendFragment extends Fragment implements KeyGenerator.KeyGeneratorCallback {

    // ~50 seconds per Minima block
    private static final long BLOCK_TIME_MS = 50_000L;

    private static final String FUTURE_SCRIPT =
            "RETURN (@BLOCK GTE PREVSTATE(1) OR @COINAGE GTE PREVSTATE(4)) AND VERIFYOUT(@INPUT PREVSTATE(2) @AMOUNT @TOKENID FALSE)";

    private WalletViewModel walletViewModel;
    private SharedPreferences sharedPreferences;
    private KeyGenerator keyGenerator;

    private MaterialTextView balanceText, addressText, blockInfoText;
    private AutoCompleteTextView tokenSelector;
    private TextInputLayout amountLayout;
    private TextInputEditText amountEdit, dateEdit;
    private MaterialButton sendBtn;

    private List<TokenBalance> availableTokens = new ArrayList<>();
    private int selectedTokenIndex = 0;

    private Calendar selectedDateTime = null;
    private long currentBlock = -1;
    private boolean isSending = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_future_send, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        balanceText = view.findViewById(R.id.future_balance_text);
        addressText = view.findViewById(R.id.future_address_text);
        blockInfoText = view.findViewById(R.id.future_block_info);
        tokenSelector = view.findViewById(R.id.future_token_selector);
        amountLayout = view.findViewById(R.id.future_amount_layout);
        amountEdit = view.findViewById(R.id.future_amount_edit);
        dateEdit = view.findViewById(R.id.future_date_edit);
        sendBtn = view.findViewById(R.id.future_send_btn);

        sharedPreferences = requireContext().getSharedPreferences("app_settings", 0);
        keyGenerator = new KeyGenerator(this);

        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);
        walletViewModel.getKeyData().observe(getViewLifecycleOwner(), kd -> {
            updateWalletCard();
            updateTokenDropdown();
        });

        updateWalletCard();
        updateTokenDropdown();

        // Date/time picker
        TextInputLayout dateLayout = view.findViewById(R.id.future_date_layout);
        dateLayout.setEndIconOnClickListener(v -> showDateTimePicker());
        dateEdit.setOnClickListener(v -> showDateTimePicker());

        // Token selection
        tokenSelector.setOnItemClickListener((parent, v, pos, id) -> {
            selectedTokenIndex = pos;
            updateAmountSuffix();
            updateBalanceForToken();
        });

        sendBtn.setOnClickListener(v -> {
            if (!isSending) confirmAndSend();
        });

        // Load current block number from API
        loadCurrentBlock();
    }

    private void loadCurrentBlock() {
        String apiUrl = sharedPreferences.getString("api_url",
                "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");
        keyGenerator.getBlockNumber(apiUrl);
    }

    private void showDateTimePicker() {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(requireContext(), (dp, year, month, day) -> {
            new TimePickerDialog(requireContext(), (tp, hour, minute) -> {
                selectedDateTime = Calendar.getInstance();
                selectedDateTime.set(year, month, day, hour, minute, 0);
                selectedDateTime.set(Calendar.MILLISECOND, 0);

                if (selectedDateTime.getTimeInMillis() <= System.currentTimeMillis()) {
                    Toast.makeText(requireContext(),
                            getString(R.string.future_date_must_be_future), Toast.LENGTH_SHORT).show();
                    selectedDateTime = null;
                    dateEdit.setText("");
                    blockInfoText.setVisibility(View.GONE);
                    return;
                }

                SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
                dateEdit.setText(sdf.format(selectedDateTime.getTime()));
                updateBlockInfo();
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateBlockInfo() {
        if (selectedDateTime == null || currentBlock < 0) {
            blockInfoText.setVisibility(View.GONE);
            return;
        }
        long deltaMs = selectedDateTime.getTimeInMillis() - System.currentTimeMillis();
        long targetBlock = currentBlock + (deltaMs / BLOCK_TIME_MS);
        String msg = getString(R.string.future_block_info, currentBlock, targetBlock);
        blockInfoText.setText(msg);
        blockInfoText.setVisibility(View.VISIBLE);
    }

    private void updateTokenDropdown() {
        KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        availableTokens.clear();
        selectedTokenIndex = 0;

        if (kd != null && kd.tokens != null) availableTokens.addAll(kd.tokens);
        if (availableTokens.isEmpty())
            availableTokens.add(new TokenBalance("Minima", "0x00", "0", "0", "0", null, null));

        List<String> names = new ArrayList<>();
        for (TokenBalance t : availableTokens) {
            String n = (t.tokenName != null && !t.tokenName.isEmpty()) ? t.tokenName : t.tokenId;
            names.add(n + "  [" + t.confirmed + "]");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, names);
        tokenSelector.setAdapter(adapter);
        if (!names.isEmpty()) tokenSelector.setText(names.get(0), false);

        updateAmountSuffix();
        updateBalanceForToken();
    }

    private void updateAmountSuffix() {
        if (selectedTokenIndex < availableTokens.size()) {
            TokenBalance t = availableTokens.get(selectedTokenIndex);
            String n = (t.tokenName != null && !t.tokenName.isEmpty()) ? t.tokenName : t.tokenId;
            amountLayout.setSuffixText(n);
        }
    }

    private void updateBalanceForToken() {
        if (selectedTokenIndex < availableTokens.size()) {
            TokenBalance t = availableTokens.get(selectedTokenIndex);
            String n = (t.tokenName != null && !t.tokenName.isEmpty()) ? t.tokenName : t.tokenId;
            if (balanceText != null) balanceText.setText(t.confirmed + " " + n);
        }
    }

    private void updateWalletCard() {
        KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        if (kd != null) {
            if (addressText != null) addressText.setText(kd.miniAddress);
        } else {
            if (addressText != null) addressText.setText(getString(R.string.not_generated));
            if (balanceText != null) balanceText.setText("0 MINIMA");
        }
    }

    private void confirmAndSend() {
        KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        if (kd == null) {
            Toast.makeText(requireContext(), R.string.generate_keys_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedDateTime == null) {
            Toast.makeText(requireContext(), R.string.future_pick_date, Toast.LENGTH_SHORT).show();
            return;
        }
        String amountStr = amountEdit.getText() != null ? amountEdit.getText().toString().trim() : "";
        if (amountStr.isEmpty()) {
            Toast.makeText(requireContext(), R.string.fill_recipient_and_amount, Toast.LENGTH_SHORT).show();
            return;
        }

        TokenBalance token = availableTokens.get(selectedTokenIndex);
        try {
            BigDecimal amount = new BigDecimal(amountStr);
            BigDecimal bal = new BigDecimal(token.confirmed);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                Toast.makeText(requireContext(), R.string.amount_greater_zero, Toast.LENGTH_SHORT).show();
                return;
            }
            if (amount.compareTo(bal) > 0) {
                Toast.makeText(requireContext(), R.string.amount_exceeds_balance, Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), R.string.invalid_amount, Toast.LENGTH_SHORT).show();
            return;
        }

        String tokenName = (token.tokenName != null && !token.tokenName.isEmpty())
                ? token.tokenName : token.tokenId;
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());
        String dateStr = sdf.format(new Date(selectedDateTime.getTimeInMillis()));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.future_confirm_title)
                .setMessage(getString(R.string.future_confirm_msg, amountStr, tokenName, dateStr))
                .setPositiveButton(R.string.yes_send, (d, w) -> doSend(kd, amountStr, token))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doSend(KeyData kd, String amount, TokenBalance token) {
        if (currentBlock < 0) {
            Toast.makeText(requireContext(), R.string.future_block_loading, Toast.LENGTH_SHORT).show();
            return;
        }

        long deltaMs = selectedDateTime.getTimeInMillis() - System.currentTimeMillis();
        long deltaBlocks = deltaMs / BLOCK_TIME_MS;
        long targetBlock = currentBlock + deltaBlocks;
        long targetMs = selectedDateTime.getTimeInMillis();
        // coinage = targetBlock - currentBlock (exact delta, same as original fcash)
        long coinage = deltaBlocks;

        // State slots:
        // 1 = target block (for @BLOCK GTE check)
        // 2 = recipient address (for VERIFYOUT)
        // 3 = timestamp ms (display only)
        // 4 = coinage threshold in blocks (for @COINAGE GTE check)
        String script = FUTURE_SCRIPT;
        String stateJson = "{\"1\":\"" + targetBlock + "\", "
                + "\"2\":\"" + kd.address + "\", "
                + "\"3\":\"" + targetMs + "\", "
                + "\"4\":\"" + coinage + "\"}";

        String apiUrl = sharedPreferences.getString("api_url",
                "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");

        isSending = true;
        sendBtn.setEnabled(false);
        sendBtn.setText(R.string.sending);

        keyGenerator.sendFutureTransaction(apiUrl, kd, amount, token.tokenId, script, stateJson);
    }

    // --- KeyGeneratorCallback ---

    @Override
    public void onProgressUpdate(String message) { }

    @Override
    public void onKeyGenerated(KeyData keyData) { }

    @Override
    public void onTransactionCreated(String txId) {
        isSending = false;
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            sendBtn.setEnabled(true);
            sendBtn.setText(R.string.future_lock_btn);
            amountEdit.setText("");
            dateEdit.setText("");
            selectedDateTime = null;
            blockInfoText.setVisibility(View.GONE);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.future_locked_title)
                    .setMessage(R.string.future_locked_msg)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        });
    }

    @Override
    public void onError(String error) {
        isSending = false;
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (!isAdded()) return;
            sendBtn.setEnabled(true);
            sendBtn.setText(R.string.future_lock_btn);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.error)
                    .setMessage(getString(R.string.error_creating_transaction))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        });
    }

    /** Called by KeyGenerator when block number is loaded. */
    public void onBlockLoaded(long block) {
        currentBlock = block;
        if (getActivity() != null) {
            getActivity().runOnUiThread(this::updateBlockInfo);
        }
    }

    @Override
    public void onServerLog(String command, String response) {
        WalletViewModel vm = walletViewModel;
        if (vm != null) vm.addServerLog("CMD: " + command + "\nRES: " + response);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (keyGenerator != null) {
            keyGenerator.cleanup();
            keyGenerator = null;
        }
    }
}
