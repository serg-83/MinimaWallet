package com.example.minimawallet;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MaximizeStakeFragment extends Fragment implements KeyGenerator.KeyGeneratorCallback {

    private static final String BOND_ADDRESS =
            "MxG0861MPQ3ZQTM4GFTZ0UJA74Y48A4GDPYM1NTVKDTU0B34BFDV86G5A0PD21N";

    private static final int MIN_BOND = 1;
    private static final int MAX_BOND = 25000;
    private static final int DAY_OF_BLOCKS = 1728;

    // Timeframe months -> rate multiplier
    private static final int[] TIMEFRAME_MONTHS = {1, 3, 6, 9, 12};
    private static final double[] RATES = {1.005, 1.0175, 1.04, 1.065, 1.09};
    private static final String[] RATE_LABELS_EN = {
            "1 month (+0.5%)", "3 months (+1.75%)", "6 months (+4%)",
            "9 months (+6.5%)", "12 months (+9%)"
    };
    private static final String[] RATE_LABELS_RU = {
            "1 месяц (+0.5%)", "3 месяца (+1.75%)", "6 месяцев (+4%)",
            "9 месяцев (+6.5%)", "12 месяцев (+9%)"
    };

    private WalletViewModel walletViewModel;
    private SharedPreferences sharedPreferences;
    private KeyGenerator keyGenerator;

    private MaterialTextView balanceText, addressText;
    private TextInputEditText amountEdit;
    private AutoCompleteTextView timeframeSelector;
    private MaterialCardView incomeCard;
    private MaterialTextView incomeRate, incomeAmount;
    private MaterialButton stakeBtn;

    private int selectedTimeframeIndex = -1;
    private long currentBlock = -1;
    private boolean isSending = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_maximize_stake, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        balanceText = view.findViewById(R.id.maximize_balance_text);
        addressText = view.findViewById(R.id.maximize_address_text);
        amountEdit = view.findViewById(R.id.maximize_amount_edit);
        timeframeSelector = view.findViewById(R.id.maximize_timeframe_selector);
        incomeCard = view.findViewById(R.id.maximize_income_card);
        incomeRate = view.findViewById(R.id.maximize_income_rate);
        incomeAmount = view.findViewById(R.id.maximize_income_amount);
        stakeBtn = view.findViewById(R.id.maximize_stake_btn);

        sharedPreferences = requireContext().getSharedPreferences("app_settings", 0);
        keyGenerator = new KeyGenerator(requireContext(), this);

        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);
        walletViewModel.getKeyData().observe(getViewLifecycleOwner(), kd -> updateWalletCard());
        updateWalletCard();

        // Timeframe dropdown
        String lang = getResources().getConfiguration().locale.getLanguage();
        String[] labels = "ru".equals(lang) ? RATE_LABELS_RU : RATE_LABELS_EN;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, labels);
        timeframeSelector.setAdapter(adapter);
        timeframeSelector.setOnItemClickListener((parent, v, pos, id) -> {
            selectedTimeframeIndex = pos;
            updateIncomeInfo();
        });

        // Amount change listener
        amountEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateIncomeInfo(); }
        });

        stakeBtn.setOnClickListener(v -> {
            if (!isSending) confirmAndStake();
        });

        loadCurrentBlock();
    }

    private void loadCurrentBlock() {
        keyGenerator.getBlockNumber(null);
    }

    private void updateWalletCard() {
        KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        if (kd != null) {
            if (addressText != null) addressText.setText(kd.miniAddress);
            // Show Minima balance
            String bal = "0";
            if (kd.tokens != null && !kd.tokens.isEmpty()) {
                bal = kd.tokens.get(0).confirmed;
            }
            if (balanceText != null) balanceText.setText(bal + " MINIMA");
        } else {
            if (addressText != null) addressText.setText(getString(R.string.not_generated));
            if (balanceText != null) balanceText.setText("0 MINIMA");
        }
    }

    private void updateIncomeInfo() {
        if (selectedTimeframeIndex < 0) {
            incomeCard.setVisibility(View.GONE);
            return;
        }

        String amountStr = amountEdit.getText() != null ? amountEdit.getText().toString().trim() : "";
        if (amountStr.isEmpty()) {
            incomeCard.setVisibility(View.GONE);
            return;
        }

        try {
            BigDecimal amount = new BigDecimal(amountStr);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                incomeCard.setVisibility(View.GONE);
                return;
            }

            double rate = RATES[selectedTimeframeIndex];
            BigDecimal income = amount.multiply(BigDecimal.valueOf(rate))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal profit = income.subtract(amount).setScale(4, RoundingMode.HALF_UP);
            double pct = (rate - 1.0) * 100.0;

            incomeRate.setText(getString(R.string.maximize_rate_info,
                    TIMEFRAME_MONTHS[selectedTimeframeIndex], pct));
            incomeAmount.setText(getString(R.string.maximize_income_info,
                    income.toPlainString(), profit.toPlainString()));
            incomeCard.setVisibility(View.VISIBLE);
        } catch (NumberFormatException e) {
            incomeCard.setVisibility(View.GONE);
        }
    }

    private void confirmAndStake() {
        KeyData kd = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        if (kd == null) {
            Toast.makeText(requireContext(), R.string.generate_keys_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedTimeframeIndex < 0) {
            Toast.makeText(requireContext(), R.string.maximize_select_timeframe, Toast.LENGTH_SHORT).show();
            return;
        }
        String amountStr = amountEdit.getText() != null ? amountEdit.getText().toString().trim() : "";
        if (amountStr.isEmpty()) {
            Toast.makeText(requireContext(), R.string.fill_recipient_and_amount, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            BigDecimal amount = new BigDecimal(amountStr);
            if (amount.compareTo(new BigDecimal(MIN_BOND)) < 0) {
                Toast.makeText(requireContext(),
                        getString(R.string.maximize_min_amount, MIN_BOND), Toast.LENGTH_SHORT).show();
                return;
            }
            if (amount.compareTo(new BigDecimal(MAX_BOND)) > 0) {
                Toast.makeText(requireContext(),
                        getString(R.string.maximize_max_amount, MAX_BOND), Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), R.string.invalid_amount, Toast.LENGTH_SHORT).show();
            return;
        }

        int months = TIMEFRAME_MONTHS[selectedTimeframeIndex];
        double rate = RATES[selectedTimeframeIndex];
        BigDecimal amt = new BigDecimal(amountStr);
        BigDecimal income = amt.multiply(BigDecimal.valueOf(rate)).setScale(4, RoundingMode.HALF_UP);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.maximize_confirm_title)
                .setMessage(getString(R.string.maximize_confirm_msg,
                        amountStr, months, income.toPlainString()))
                .setPositiveButton(R.string.maximize_stake_btn, (d, w) -> doStake(kd, amountStr))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doStake(KeyData kd, String amount) {
        if (currentBlock < 0) {
            Toast.makeText(requireContext(), R.string.future_block_loading, Toast.LENGTH_SHORT).show();
            return;
        }

        int months = TIMEFRAME_MONTHS[selectedTimeframeIndex];
        double rate = RATES[selectedTimeframeIndex];

        isSending = true;
        stakeBtn.setEnabled(false);
        stakeBtn.setText(R.string.sending);

        keyGenerator.sendMaximizeStake(null, kd, amount, months, rate, currentBlock);
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
            stakeBtn.setEnabled(true);
            stakeBtn.setText(R.string.maximize_stake_btn);
            amountEdit.setText("");
            incomeCard.setVisibility(View.GONE);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.maximize_staked_title)
                    .setMessage(R.string.maximize_staked_msg)
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
            stakeBtn.setEnabled(true);
            stakeBtn.setText(R.string.maximize_stake_btn);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.error)
                    .setMessage(getString(R.string.error_creating_transaction))
                    .setPositiveButton(R.string.ok, null)
                    .show();
        });
    }

    public void onBlockLoaded(long block) {
        currentBlock = block;
    }

    @Override
    public void onServerLog(String command, String response) {
        if (walletViewModel != null) walletViewModel.addServerLog("CMD: " + command + "\nRES: " + response);
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
