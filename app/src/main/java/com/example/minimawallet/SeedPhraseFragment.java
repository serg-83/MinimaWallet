package com.example.minimawallet;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.util.concurrent.Executor;

public class SeedPhraseFragment extends Fragment {

    private SecureStorage secureStorage;
    private WalletViewModel walletViewModel;

    private TextInputEditText seedPhraseEdit;
    private MaterialTextView progressText;
    private MaterialButton saveSeedPhraseBtn, generatePhraseBtn, loginBiometricBtn;
    private ProgressBar loadingProgress;
    private View biometricStateLayout;
    private TextInputLayout seedPhraseInputLayout;

    private boolean isFragmentInitialized = false;
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_seed_phrase, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);

        initializeViews(view);

        if (!isFragmentInitialized) {
            try {
                setupClickListeners();
                setupBiometricPrompt();
                showLoading(true);

                new Thread(() -> {
                    try {
                        Context ctx = getContext();
                        if (ctx == null) return;

                        secureStorage = new SecureStorage(ctx);

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (!isAdded()) return;
                                checkSavedPhrase();
                                isFragmentInitialized = true;
                                showLoading(false);
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
    }

    private void initializeViews(View view) {
        try {
            seedPhraseEdit = view.findViewById(R.id.seed_phrase_edit);
            progressText = view.findViewById(R.id.progress_text);
            saveSeedPhraseBtn = view.findViewById(R.id.save_seed_phrase_btn);
            generatePhraseBtn = view.findViewById(R.id.generate_phrase_btn);
            loginBiometricBtn = view.findViewById(R.id.login_biometric_btn);
            loadingProgress = view.findViewById(R.id.loading_progress);
            biometricStateLayout = view.findViewById(R.id.biometric_state_layout);
            seedPhraseInputLayout = view.findViewById(R.id.seed_phrase_input_layout);

            if (seedPhraseEdit != null) {
                seedPhraseEdit.setMinLines(4);
                seedPhraseEdit.setMaxLines(8);
                seedPhraseEdit.setVerticalScrollBarEnabled(true);

                seedPhraseEdit.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        updateSaveButtonState();
                    }
                });
            }
        } catch (Exception e) {
            showToast(getString(R.string.error_ui_initialization));
        }
    }

    private void showLoading(boolean show) {
        if (getActivity() != null && loadingProgress != null) {
            getActivity().runOnUiThread(() -> {
                loadingProgress.setVisibility(show ? View.VISIBLE : View.GONE);
                setUIEnabled(!show);
            });
        }
    }

    private void setUIEnabled(boolean enabled) {
        if (loginBiometricBtn != null) loginBiometricBtn.setEnabled(enabled);
        if (saveSeedPhraseBtn != null) saveSeedPhraseBtn.setEnabled(enabled);
        if (generatePhraseBtn != null) generatePhraseBtn.setEnabled(enabled);
        if (seedPhraseEdit != null) seedPhraseEdit.setEnabled(enabled);
    }

    private void setupBiometricPrompt() {
        executor = ContextCompat.getMainExecutor(requireContext());

        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED) {
                    showToast(getString(R.string.biometric_auth_error));
                }
            }

            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                loadSeedPhrase();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                showToast(getString(R.string.biometric_auth_failed));
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_auth))
                .setSubtitle(getString(R.string.biometric_auth_subtitle))
                .setNegativeButtonText(getString(R.string.cancel))
                .setConfirmationRequired(true)
                .build();
    }

    private void setupClickListeners() {
        if (loginBiometricBtn != null) {
            loginBiometricBtn.setOnClickListener(v -> showLoginBiometricAuth());
        }
        if (saveSeedPhraseBtn != null) {
            saveSeedPhraseBtn.setOnClickListener(v -> saveSeedPhraseWithAuth());
        }
        if (generatePhraseBtn != null) {
            generatePhraseBtn.setOnClickListener(v -> generateNewPhrase());
        }
    }

    private void checkSavedPhrase() {
        try {
            boolean hasSavedPhrase = secureStorage.hasSavedPhrase();
            boolean biometricAvailable = isBiometricAvailable();

            if (progressText != null) {
                if (hasSavedPhrase) {
                    progressText.setText(getString(R.string.saved_phrase_available));

                    if (biometricStateLayout != null) {
                        biometricStateLayout.setVisibility(View.VISIBLE);
                    }
                    if (seedPhraseInputLayout != null) {
                        seedPhraseInputLayout.setVisibility(View.GONE);
                    }

                    if (loginBiometricBtn != null) loginBiometricBtn.setVisibility(biometricAvailable ? View.VISIBLE : View.GONE);
                    if (saveSeedPhraseBtn != null) saveSeedPhraseBtn.setVisibility(View.GONE);
                    if (generatePhraseBtn != null) generatePhraseBtn.setVisibility(View.GONE);

                } else {
                    progressText.setText(getString(R.string.no_saved_phrase));

                    if (biometricStateLayout != null) {
                        biometricStateLayout.setVisibility(View.GONE);
                    }
                    if (seedPhraseInputLayout != null) {
                        seedPhraseInputLayout.setVisibility(View.VISIBLE);
                    }

                    if (loginBiometricBtn != null) loginBiometricBtn.setVisibility(View.GONE);
                    if (saveSeedPhraseBtn != null) saveSeedPhraseBtn.setVisibility(View.VISIBLE);
                    if (generatePhraseBtn != null) generatePhraseBtn.setVisibility(View.VISIBLE);
                }
            }

            updateSaveButtonState();

        } catch (Exception e) {
            showToast(getString(R.string.error_saved_phrase_check));
        }
    }

    private boolean isBiometricAvailable() {
        try {
            Context ctx = getContext();
            if (ctx == null) return false;
            BiometricManager biometricManager = BiometricManager.from(ctx);
            int result = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG |
                    BiometricManager.Authenticators.BIOMETRIC_WEAK);
            return result == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateSaveButtonState() {
        try {
            if (saveSeedPhraseBtn != null && seedPhraseEdit != null && secureStorage != null) {
                String currentPhrase = seedPhraseEdit.getText().toString().trim();
                boolean hasPhrase = !currentPhrase.isEmpty();
                boolean canSave = hasPhrase && secureStorage.isKeyStoreAvailable();

                saveSeedPhraseBtn.setEnabled(canSave);
                saveSeedPhraseBtn.setAlpha(canSave ? 1.0f : 0.5f);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void showLoginBiometricAuth() {
        if (secureStorage == null || !secureStorage.hasSavedPhrase()) {
            showToast(getString(R.string.no_saved_phrase));
            return;
        }

        try {
            biometricPrompt.authenticate(promptInfo);
        } catch (Exception e) {
            showToast(getString(R.string.biometric_auth_error));
        }
    }

    private void saveSeedPhraseWithAuth() {
        if (seedPhraseEdit != null) {
            String currentPhrase = seedPhraseEdit.getText().toString().trim();
            if (currentPhrase.isEmpty()) {
                showToast(getString(R.string.enter_seed_phrase));
                return;
            }
            saveCurrentPhrase();
        }
    }

    private void loadSeedPhrase() {
        try {
            String savedPhrase = secureStorage.getPhrase();
            if (savedPhrase != null && seedPhraseEdit != null) {
                if (biometricStateLayout != null) {
                    biometricStateLayout.setVisibility(View.GONE);
                }
                if (seedPhraseInputLayout != null) {
                    seedPhraseInputLayout.setVisibility(View.VISIBLE);
                }

                seedPhraseEdit.setText(savedPhrase);
                seedPhraseEdit.setEnabled(true);

                // Update ViewModel with loaded phrase
                walletViewModel.setSeedPhrase(savedPhrase);

                updateSaveButtonState();
                showToast(getString(R.string.phrase_restored));
                if (progressText != null) {
                    progressText.setText(getString(R.string.phrase_restored));
                }

                if (loginBiometricBtn != null) loginBiometricBtn.setVisibility(View.VISIBLE);
                if (saveSeedPhraseBtn != null) saveSeedPhraseBtn.setVisibility(View.VISIBLE);
                if (generatePhraseBtn != null) generatePhraseBtn.setVisibility(View.VISIBLE);

            } else {
                showToast(getString(R.string.phrase_restore_error));
            }
        } catch (Exception e) {
            showToast(getString(R.string.loading_error));
        }
    }

    private void generateNewPhrase() {
        try {
            String[] words = org.minima.utils.BIP39.getNewWordList();
            String phrase = org.minima.utils.BIP39.convertWordListToString(words);
            if (seedPhraseEdit != null) {
                seedPhraseEdit.setText(phrase);
                updateSaveButtonState();
                showToast(getString(R.string.new_phrase_generated));
            }
        } catch (Exception e) {
            showToast(getString(R.string.error_generating_phrase));
        }
    }

    private void saveCurrentPhrase() {
        try {
            if (secureStorage == null || !secureStorage.isKeyStoreAvailable()) {
                showToast(getString(R.string.keystore_not_available));
                return;
            }

            if (seedPhraseEdit != null) {
                String phrase = seedPhraseEdit.getText().toString().trim();
                if (!phrase.isEmpty()) {
                    boolean saved = secureStorage.savePhrase(phrase);
                    if (saved) {
                        // Update ViewModel with saved phrase
                        walletViewModel.setSeedPhrase(phrase);

                        showToast(getString(R.string.phrase_saved));
                        if (progressText != null) {
                            progressText.setText(getString(R.string.phrase_saved));
                        }
                        checkSavedPhrase();
                    } else {
                        showToast(getString(R.string.phrase_save_error));
                    }
                }
            }
        } catch (Exception e) {
            showToast(getString(R.string.save_error));
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
        if (secureStorage != null) {
            checkSavedPhrase();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        biometricPrompt = null;
        executor = null;
        isFragmentInitialized = false;
    }
}
