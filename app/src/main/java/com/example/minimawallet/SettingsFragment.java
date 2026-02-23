package com.example.minimawallet;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
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

    private static final String DEFAULT_API_URL = "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD";

    private SecureStorage secureStorage;

    private TextInputEditText apiUrlEdit;
    private RadioGroup languageRadioGroup;
    private MaterialButton clearDataBtn;

    private SharedPreferences sharedPreferences;
    private boolean isLanguageSettingProgrammatically = false;

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
            if (!hasFocus) {
                saveApiUrlSetting();
            }
        });
    }

    private void loadSavedSettings() {
        String savedApiUrl = sharedPreferences.getString("api_url", DEFAULT_API_URL);
        if (apiUrlEdit != null) {
            apiUrlEdit.setText(savedApiUrl);
        }

        String savedLanguage = sharedPreferences.getString("app_language", "ru");
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

    private void saveApiUrlSetting() {
        if (apiUrlEdit != null) {
            String apiUrl = apiUrlEdit.getText().toString().trim();
            if (!apiUrl.isEmpty()) {
                if (apiUrl.startsWith("http://") || apiUrl.startsWith("https://")) {
                    sharedPreferences.edit().putString("api_url", apiUrl).apply();
                    Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.url_validation_error, Toast.LENGTH_SHORT).show();
                }
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
            apiUrlEdit.setText(DEFAULT_API_URL);
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
