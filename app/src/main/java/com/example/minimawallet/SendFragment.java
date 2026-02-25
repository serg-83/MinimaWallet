package com.example.minimawallet;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
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
import java.util.ArrayList;
import java.util.List;

public class SendFragment extends Fragment implements KeyGenerator.KeyGeneratorCallback {

    private KeyGenerator keyGenerator;
    private SharedPreferences sharedPreferences;
    private WalletViewModel walletViewModel;

    private TextInputEditText recipientAddressEdit, amountEdit;
    private TextInputLayout recipientAddressLayout, amountLayout;
    private AutoCompleteTextView tokenSelector;
    private MaterialTextView currentAddressText, currentBalanceText;
    private MaterialButton sendTransactionBtn;

    private boolean isTransactionInProgress = false;

    // Список токенов включая Minima
    private List<TokenBalance> availableTokens = new ArrayList<>();
    private int selectedTokenIndex = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_send, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            initializeViews(view);
            setupClickListeners();

            keyGenerator = new KeyGenerator(this);
            sharedPreferences = requireContext().getSharedPreferences("app_settings", 0);

            walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);
            walletViewModel.getKeyData().observe(getViewLifecycleOwner(), keyData -> {
                updateWalletInfo();
                updateTokenDropdown();
            });

            updateWalletInfo();
            updateTokenDropdown();

        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_initialization, Toast.LENGTH_LONG).show();
        }
    }

    private void initializeViews(View view) {
        recipientAddressEdit = view.findViewById(R.id.recipient_address_edit);
        recipientAddressLayout = view.findViewById(R.id.recipient_address_layout);
        amountEdit = view.findViewById(R.id.amount_edit);
        amountLayout = view.findViewById(R.id.amount_layout);
        tokenSelector = view.findViewById(R.id.token_selector);
        currentAddressText = view.findViewById(R.id.current_address_text);
        currentBalanceText = view.findViewById(R.id.current_balance_text);
        sendTransactionBtn = view.findViewById(R.id.send_transaction_btn);
    }

    private void setupClickListeners() {
        if (sendTransactionBtn != null) {
            sendTransactionBtn.setOnClickListener(v -> {
                if (!isTransactionInProgress) {
                    sendTransactionWithConfirmation();
                }
            });
        }
        if (recipientAddressLayout != null) {
            recipientAddressLayout.setEndIconOnClickListener(v -> pasteAddressFromClipboard());
        }
        if (tokenSelector != null) {
            tokenSelector.setOnItemClickListener((parent, v, position, id) -> {
                selectedTokenIndex = position;
                updateAmountSuffix();
                updateBalanceForSelectedToken();
            });
        }
    }

    private void pasteAddressFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
                    if (text != null && recipientAddressEdit != null) {
                        recipientAddressEdit.setText(text.toString().trim());
                    }
                }
            }
        } catch (Exception e) {
            // Ignore clipboard errors
        }
    }

    /**
     * Заполняет выпадающий список всеми доступными токенами из баланса кошелька.
     * Формат отображения: "Название  [баланс]"
     * По умолчанию выбирается первый токен (обычно Minima).
     *
     * Populates the dropdown with all available tokens from the wallet balance.
     * Display format: "Name  [balance]"
     * The first token (usually Minima) is selected by default.
     */
    private void updateTokenDropdown() {
        KeyData currentKeyData = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;
        availableTokens.clear();
        selectedTokenIndex = 0;

        if (currentKeyData != null && currentKeyData.tokens != null) {
            availableTokens.addAll(currentKeyData.tokens);
        }

        // Если токены не загружены — показываем Minima с нулевым балансом
        // If tokens are not loaded — show Minima with zero balance
        if (availableTokens.isEmpty()) {
            availableTokens.add(new TokenBalance("Minima", "0x00", "0", "0", "0", null, null));
        }

        List<String> displayNames = new ArrayList<>();
        for (TokenBalance t : availableTokens) {
            String name = t.tokenName;
            if (name == null || name.isEmpty()) name = t.tokenId;
            displayNames.add(name + "  [" + t.confirmed + "]");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                displayNames
        );

        if (tokenSelector != null) {
            tokenSelector.setAdapter(adapter);
            // Выбираем первый токен по умолчанию / Select first token by default
            if (!displayNames.isEmpty()) {
                tokenSelector.setText(displayNames.get(0), false);
            }
        }

        updateAmountSuffix();
        updateBalanceForSelectedToken();
    }

    /**
     * Обновляет суффикс поля суммы именем выбранного токена.
     * Updates the amount field suffix with the selected token name.
     */
    private void updateAmountSuffix() {
        if (amountLayout == null) return;
        if (selectedTokenIndex >= 0 && selectedTokenIndex < availableTokens.size()) {
            TokenBalance t = availableTokens.get(selectedTokenIndex);
            String name = (t.tokenName != null && !t.tokenName.isEmpty()) ? t.tokenName : t.tokenId;
            amountLayout.setSuffixText(name);
        }
    }

    /**
     * Обновляет отображение баланса для выбранного токена в карточке кошелька.
     * Updates the balance display for the selected token in the wallet card.
     */
    private void updateBalanceForSelectedToken() {
        if (currentBalanceText == null) return;
        if (selectedTokenIndex >= 0 && selectedTokenIndex < availableTokens.size()) {
            TokenBalance t = availableTokens.get(selectedTokenIndex);
            String name = (t.tokenName != null && !t.tokenName.isEmpty()) ? t.tokenName : t.tokenId;
            currentBalanceText.setText(t.confirmed + " " + name);
        }
    }

    private void updateWalletInfo() {
        KeyData currentKeyData = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;

        if (currentKeyData != null) {
            if (currentAddressText != null) {
                currentAddressText.setText(currentKeyData.miniAddress);
            }
        } else {
            if (currentAddressText != null) {
                currentAddressText.setText(getString(R.string.not_generated));
            }
            if (currentBalanceText != null) {
                currentBalanceText.setText("0 MINIMA");
            }
        }
    }

    private TokenBalance getSelectedToken() {
        if (selectedTokenIndex >= 0 && selectedTokenIndex < availableTokens.size()) {
            return availableTokens.get(selectedTokenIndex);
        }
        return null;
    }

    private void sendTransactionWithConfirmation() {
        KeyData currentKeyData = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;

        if (currentKeyData == null) {
            Toast.makeText(requireContext(), R.string.generate_keys_first, Toast.LENGTH_SHORT).show();
            return;
        }

        String toAddress = recipientAddressEdit.getText().toString().trim();
        String amount = amountEdit.getText().toString().trim();

        if (toAddress.isEmpty() || amount.isEmpty()) {
            Toast.makeText(requireContext(), R.string.fill_recipient_and_amount, Toast.LENGTH_SHORT).show();
            return;
        }

        TokenBalance selectedToken = getSelectedToken();
        if (selectedToken == null) {
            Toast.makeText(requireContext(), R.string.select_token, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            BigDecimal amountValue = new BigDecimal(amount);
            BigDecimal tokenBalance = new BigDecimal(selectedToken.confirmed);

            if (amountValue.compareTo(BigDecimal.ZERO) <= 0) {
                Toast.makeText(requireContext(), R.string.amount_greater_zero, Toast.LENGTH_SHORT).show();
                return;
            }

            if (amountValue.compareTo(tokenBalance) > 0) {
                Toast.makeText(requireContext(), R.string.amount_exceeds_balance, Toast.LENGTH_SHORT).show();
                return;
            }

            String tokenName = (selectedToken.tokenName != null && !selectedToken.tokenName.isEmpty())
                    ? selectedToken.tokenName : selectedToken.tokenId;

            String confirmMessage = String.format(
                    getString(R.string.confirm_send_token_message),
                    amount, tokenName, toAddress
            );

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm_transaction)
                    .setMessage(confirmMessage)
                    .setPositiveButton(R.string.yes_send, (dialog, which) -> {
                        sendTransaction(toAddress, amount, selectedToken);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();

        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), R.string.invalid_amount, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_sending_transaction, Toast.LENGTH_SHORT).show();
        }
    }

    private void sendTransaction(String toAddress, String amount, TokenBalance token) {
        KeyData currentKeyData = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;

        if (currentKeyData != null && sendTransactionBtn != null) {
            isTransactionInProgress = true;
            String apiUrl = sharedPreferences.getString("api_url",
                    "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");
            keyGenerator.sendTransaction(
                    apiUrl,
                    currentKeyData.miniAddress,
                    toAddress,
                    amount,
                    token.tokenId,
                    currentKeyData.script,
                    currentKeyData.treeKey
            );
            sendTransactionBtn.setEnabled(false);
            sendTransactionBtn.setText(R.string.sending);
        }
    }

    @Override
    public void onProgressUpdate(String message) {
        // Progress handled silently
    }

    @Override
    public void onKeyGenerated(KeyData keyData) {
        // Handled in WalletFragment
    }

    @Override
    public void onTransactionCreated(String txId) {
        isTransactionInProgress = false;
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (sendTransactionBtn != null) {
                    sendTransactionBtn.setEnabled(true);
                    sendTransactionBtn.setText(R.string.send_transaction);
                }

                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.transaction_sent)
                        .setMessage(getString(R.string.transaction_sent_success))
                        .setPositiveButton(R.string.ok, null)
                        .show();

                if (recipientAddressEdit != null) recipientAddressEdit.setText("");
                if (amountEdit != null) amountEdit.setText("");

                updateWalletInfo();
            });
        }
    }

    @Override
    public void onError(String error) {
        isTransactionInProgress = false;
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (sendTransactionBtn != null) {
                    sendTransactionBtn.setEnabled(true);
                    sendTransactionBtn.setText(R.string.send_transaction);
                }

                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.error)
                        .setMessage(getString(R.string.error_creating_transaction))
                        .setPositiveButton(R.string.ok, null)
                        .show();
            });
        }
    }

    @Override
    public void onServerLog(String command, String response) {
        if (walletViewModel != null) {
            walletViewModel.addServerLog("CMD: " + command + "\nRES: " + response);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWalletInfo();
        updateTokenDropdown();
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
