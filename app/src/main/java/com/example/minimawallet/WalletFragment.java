package com.example.minimawallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.minimawallet.KeyGenerator.KeyData;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

public class WalletFragment extends Fragment implements KeyGenerator.KeyGeneratorCallback {

    private KeyGenerator keyGenerator;
    private SharedPreferences sharedPreferences;
    private WalletViewModel walletViewModel;

    // UI elements
    private TextInputEditText addressNumberEdit;
    private MaterialTextView progressText, addressText, balanceText, seedStatusText;
    private MaterialButton generateKeysBtn;
    private ProgressBar loadingProgress;

    private boolean isFragmentInitialized = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_wallet, container, false);
        initializeViews(view);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);

        if (!isFragmentInitialized) {
            try {
                setupClickListeners();
                showLoading(true);

                new Thread(() -> {
                    try {
                        Context ctx = getContext();
                        if (ctx == null) return;

                        keyGenerator = new KeyGenerator(this);
                        sharedPreferences = ctx.getSharedPreferences("app_settings", 0);

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (!isAdded()) return;
                                isFragmentInitialized = true;
                                showLoading(false);
                                updateSeedStatus();
                            });
                        }
                    } catch (Exception e) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (!isAdded()) return;
                                showToast(getString(R.string.error_initialization));
                                showLoading(false);
                            });
                        }
                    }
                }).start();

            } catch (Exception e) {
                showToast(getString(R.string.error_initialization));
                showLoading(false);
            }
        }

        walletViewModel.getKeyData().observe(getViewLifecycleOwner(), keyData -> {
            updateWalletDisplay();
        });

        walletViewModel.getSeedPhrase().observe(getViewLifecycleOwner(), phrase -> {
            updateSeedStatus();
        });
    }

    private void initializeViews(View view) {
        try {
            addressNumberEdit = view.findViewById(R.id.address_number_edit);
            progressText = view.findViewById(R.id.progress_text);
            addressText = view.findViewById(R.id.address_text);
            balanceText = view.findViewById(R.id.balance_text);
            generateKeysBtn = view.findViewById(R.id.generate_keys_btn);
            loadingProgress = view.findViewById(R.id.loading_progress);
            seedStatusText = view.findViewById(R.id.seed_status_text);
        } catch (Exception e) {
            showToast(getString(R.string.error_ui_initialization));
        }
    }

    private void showLoading(boolean show) {
        if (getActivity() != null && loadingProgress != null) {
            getActivity().runOnUiThread(() -> {
                loadingProgress.setVisibility(show ? View.VISIBLE : View.GONE);
                if (generateKeysBtn != null) generateKeysBtn.setEnabled(!show);
                if (addressNumberEdit != null) addressNumberEdit.setEnabled(!show);
            });
        }
    }

    private void setupClickListeners() {
        if (generateKeysBtn != null) {
            generateKeysBtn.setOnClickListener(v -> generateKeys());
        }
    }

    private void updateSeedStatus() {
        if (seedStatusText == null) return;
        String phrase = walletViewModel.getCurrentSeedPhrase();
        if (phrase != null && !phrase.isEmpty()) {
            seedStatusText.setVisibility(View.GONE);
        } else {
            seedStatusText.setVisibility(View.VISIBLE);
        }
    }

    private void generateKeys() {
        if (addressNumberEdit == null) return;

        String phrase = walletViewModel.getCurrentSeedPhrase();
        if (phrase == null || phrase.isEmpty()) {
            showToast(getString(R.string.seed_phrase_not_loaded));
            return;
        }

        String addressNumberStr = addressNumberEdit.getText().toString();

        if (addressNumberStr.isEmpty()) {
            showToast(getString(R.string.enter_address_number));
            return;
        }

        try {
            int addressNumber = Integer.parseInt(addressNumberStr);
            if (keyGenerator != null) {
                String apiUrl = sharedPreferences.getString("api_url",
                        "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD");
                keyGenerator.initialize(apiUrl, phrase, addressNumber);
                if (generateKeysBtn != null) {
                    generateKeysBtn.setEnabled(false);
                    generateKeysBtn.setText(R.string.generating);
                }
            }
        } catch (NumberFormatException e) {
            showToast(getString(R.string.address_number_number));
        } catch (Exception e) {
            showToast(getString(R.string.error_generating_keys));
        }
    }

    @Override
    public void onProgressUpdate(String message) {
        if (getActivity() != null && progressText != null) {
            getActivity().runOnUiThread(() -> {
                if (isAdded()) progressText.setText(message);
            });
        }
    }

    @Override
    public void onKeyGenerated(KeyData keyData) {
        walletViewModel.setKeyData(keyData);
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (generateKeysBtn != null) {
                    generateKeysBtn.setEnabled(true);
                    generateKeysBtn.setText(R.string.generate_keys);
                }

                updateWalletDisplay();

                if (keyData.generatedPhrase != null && !keyData.generatedPhrase.isEmpty()) {
                    walletViewModel.setSeedPhrase(keyData.generatedPhrase);
                }

                showToast(getString(R.string.keys_generated_success));
            });
        }
    }

    @Override
    public void onTransactionCreated(String txId) {
        // Handled in SendFragment
    }

    @Override
    public void onError(String error) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (generateKeysBtn != null) {
                    generateKeysBtn.setEnabled(true);
                    generateKeysBtn.setText(R.string.generate_keys);
                }
                showToast(getString(R.string.error) + ": " + error);
            });
        }
    }

    private void updateWalletDisplay() {
        KeyData currentKeyData = walletViewModel.getCurrentKeyData();
        if (currentKeyData != null) {
            if (addressText != null) {
                addressText.setText(currentKeyData.miniAddress);
            }
            if (balanceText != null) {
                balanceText.setText(currentKeyData.balance + " MINIMA");
            }
        }
    }

    private void showToast(String message) {
        if (getActivity() != null && isAdded()) {
            getActivity().runOnUiThread(() -> {
                Context ctx = getContext();
                if (ctx != null) {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateWalletDisplay();
        updateSeedStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (keyGenerator != null) {
            keyGenerator.cleanup();
            keyGenerator = null;
        }
        isFragmentInitialized = false;
    }
}
