package com.example.minimawallet;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsFragment extends Fragment {

    static final String DEFAULT_HOST = "wallet.minima.global";
    private static final String DEFAULT_EXPLORER_URL = "https://explorer.minima.global/search?q=";

    private SecureStorage secureStorage;

    private TextInputEditText apiUrlEdit;
    private TextInputEditText explorerUrlEdit;
    private RadioGroup languageRadioGroup;
    private MaterialButton clearDataBtn;

    private SharedPreferences sharedPreferences;
    private boolean isLanguageSettingProgrammatically = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            initializeViews(view);
            setupClickListeners();

            secureStorage = new SecureStorage(requireContext());
            sharedPreferences = requireContext().getSharedPreferences("app_settings", 0);

            loadSavedSettings();

        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.error_initialization, Toast.LENGTH_LONG).show();
        }
    }

    private void initializeViews(View view) {
        apiUrlEdit = view.findViewById(R.id.api_url_edit);
        explorerUrlEdit = view.findViewById(R.id.explorer_url_edit);
        languageRadioGroup = view.findViewById(R.id.language_radio_group);
        clearDataBtn = view.findViewById(R.id.clear_data_btn);
    }

    private void setupClickListeners() {
        clearDataBtn.setOnClickListener(v -> clearAllDataWithConfirmation());

        languageRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isLanguageSettingProgrammatically) {
                return;
            }

            if (getActivity() instanceof MainActivity) {
                MainActivity activity = (MainActivity) getActivity();

                if (checkedId == R.id.language_russian) {
                    saveLanguageSetting("ru");
                    activity.updateLanguage("ru");
                } else if (checkedId == R.id.language_english) {
                    saveLanguageSetting("en");
                    activity.updateLanguage("en");
                }
            }
        });

        apiUrlEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveApiHostSetting();
        });
        explorerUrlEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveExplorerUrlSetting();
        });
    }

    private void loadSavedSettings() {
        String savedHost = sharedPreferences.getString("api_host", DEFAULT_HOST);
        if (apiUrlEdit != null) {
            apiUrlEdit.setText(savedHost.equals(DEFAULT_HOST) ? "" : savedHost);
        }
        String savedExplorerUrl = sharedPreferences.getString("explorer_url", DEFAULT_EXPLORER_URL);
        if (explorerUrlEdit != null) {
            explorerUrlEdit.setText(savedExplorerUrl.equals(DEFAULT_EXPLORER_URL) ? "" : savedExplorerUrl);
        }

        String savedLanguage = sharedPreferences.getString("app_language",
                MainActivity.getDefaultLanguage(requireContext()));
        if (languageRadioGroup != null) {
            isLanguageSettingProgrammatically = true;

            if (savedLanguage.equals("en")) {
                languageRadioGroup.check(R.id.language_english);
            } else {
                languageRadioGroup.check(R.id.language_russian);
            }

            new Handler().postDelayed(() -> {
                isLanguageSettingProgrammatically = false;
            }, 100);
        }
    }

    private void saveApiHostSetting() {
        if (apiUrlEdit == null) return;

        String input = apiUrlEdit.getText().toString().trim();
        String host = input.isEmpty() ? DEFAULT_HOST : input;

        // Strip protocol prefix if user accidentally added it
        host = host.replaceFirst("^https?://", "");
        // Strip trailing slash/path
        int slashIdx = host.indexOf('/');
        if (slashIdx > 0) host = host.substring(0, slashIdx);

        sharedPreferences.edit().putString("api_host", host).apply();

        Toast.makeText(requireContext(), R.string.uid_resolving, Toast.LENGTH_SHORT).show();

        final String finalHost = host;
        UidResolver.resolveApiUrl(finalHost, new UidResolver.Callback() {
            @Override
            public void onSuccess(String apiUrl) {
                sharedPreferences.edit().putString("api_url", apiUrl).apply();
                mainHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), R.string.uid_resolved, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(),
                                getString(R.string.uid_error) + ": " + message,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void saveExplorerUrlSetting() {
        if (explorerUrlEdit != null) {
            String url = explorerUrlEdit.getText().toString().trim();
            if (url.isEmpty()) {
                sharedPreferences.edit().putString("explorer_url", DEFAULT_EXPLORER_URL).apply();
                Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                sharedPreferences.edit().putString("explorer_url", url).apply();
                Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.url_validation_error, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void saveLanguageSetting(String languageCode) {
        sharedPreferences.edit().putString("app_language", languageCode).apply();
    }

    private void clearAllDataWithConfirmation() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_data_title)
                .setMessage(R.string.clear_data_message)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    clearAllData();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void clearAllData() {
        if (secureStorage != null) {
            secureStorage.clearAllData();
        }
        if (sharedPreferences != null) {
            sharedPreferences.edit().clear().apply();
        }

        Toast.makeText(requireContext(), R.string.all_data_cleared, Toast.LENGTH_SHORT).show();

        if (apiUrlEdit != null) {
            apiUrlEdit.setText("");
        }
        if (languageRadioGroup != null) {
            isLanguageSettingProgrammatically = true;
            languageRadioGroup.check(R.id.language_russian);
            new Handler().postDelayed(() -> {
                isLanguageSettingProgrammatically = false;
            }, 100);
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateLanguage("ru");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadSavedSettings();
    }
}
