package com.example.minimawallet;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.minimawallet.KeyGenerator.KeyData;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.math.BigDecimal;

public class SendFragment extends Fragment implements KeyGenerator.KeyGeneratorCallback {

    private KeyGenerator keyGenerator;
    private SharedPreferences sharedPreferences;
    private WalletViewModel walletViewModel;

    private TextInputEditText recipientAddressEdit, amountEdit;
    private TextInputLayout recipientAddressLayout;
    private MaterialTextView currentAddressText, currentBalanceText;
    private MaterialButton sendTransactionBtn;

    private boolean isTransactionInProgress = false;

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
            walletViewModel.getKeyData().observe(getViewLifecycleOwner(), keyData -> updateWalletInfo());

            updateWalletInfo();

        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_initialization, Toast.LENGTH_LONG).show();
        }
    }

    private void initializeViews(View view) {
        recipientAddressEdit = view.findViewById(R.id.recipient_address_edit);
        recipientAddressLayout = view.findViewById(R.id.recipient_address_layout);
        amountEdit = view.findViewById(R.id.amount_edit);
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

    private void updateWalletInfo() {
        KeyData currentKeyData = walletViewModel != null ? walletViewModel.getCurrentKeyData() : null;

        if (currentKeyData != null) {
            if (currentAddressText != null) {
                currentAddressText.setText(currentKeyData.miniAddress);
            }
            if (currentBalanceText != null) {
                currentBalanceText.setText(currentKeyData.balance + " MINIMA");
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

        try {
            BigDecimal amountValue = new BigDecimal(amount);
            BigDecimal currentBalance = new BigDecimal(currentKeyData.balance);

            if (amountValue.compareTo(BigDecimal.ZERO) <= 0) {
                Toast.makeText(requireContext(), R.string.amount_greater_zero, Toast.LENGTH_SHORT).show();
                return;
            }

            if (amountValue.compareTo(currentBalance) > 0) {
                Toast.makeText(requireContext(), R.string.amount_exceeds_balance, Toast.LENGTH_SHORT).show();
                return;
            }

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm_transaction)
                    .setMessage(String.format(getString(R.string.confirm_send_message), amount, toAddress))
                    .setPositiveButton(R.string.yes_send, (dialog, which) -> {
                        sendTransaction(toAddress, amount);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();

        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), R.string.invalid_amount, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_sending_transaction, Toast.LENGTH_SHORT).show();
        }
    }

    private void sendTransaction(String toAddress, String amount) {
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
    public void onResume() {
        super.onResume();
        updateWalletInfo();
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
