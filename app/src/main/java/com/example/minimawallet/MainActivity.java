package com.example.minimawallet;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.view.MenuItem;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private MaterialToolbar toolbar;
    private ActionBarDrawerToggle drawerToggle;

    private WalletViewModel walletViewModel;
    private SecureStorage secureStorage;

    private boolean isLanguageChanging = false;
    private Locale currentLocale;
    private int currentNavItemId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            setContentView(R.layout.activity_main);

            currentLocale = getCurrentLocale();
            applySavedLanguage();

            walletViewModel = new ViewModelProvider(this).get(WalletViewModel.class);

            initializeUI();

            if (savedInstanceState == null) {
                handleStartup();
            }
        } catch (Exception e) {
            finish();
        }
    }

    private Locale getCurrentLocale() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return getResources().getConfiguration().getLocales().get(0);
        }
        return getResources().getConfiguration().locale;
    }

    private void applySavedLanguage() {
        try {
            String savedLanguage = getSharedPreferences("app_settings", MODE_PRIVATE)
                    .getString("app_language", "ru");

            Locale savedLocale = new Locale(savedLanguage);
            if (!currentLocale.getLanguage().equals(savedLocale.getLanguage())) {
                setLocale(this, savedLanguage);
                currentLocale = savedLocale;
            }
        } catch (Exception e) {
            // Ignore locale errors
        }
    }

    private void initializeUI() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.navigation_view);
        toolbar = findViewById(R.id.toolbar);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }

        drawerToggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.open_drawer, R.string.close_drawer);
        drawerLayout.addDrawerListener(drawerToggle);
        drawerToggle.syncState();

        navigationView.setNavigationItemSelectedListener(this);

        // Set version in nav header
        try {
            TextView versionText = navigationView.getHeaderView(0).findViewById(R.id.nav_header_version);
            if (versionText != null) {
                String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                versionText.setText(String.format(getString(R.string.app_version), versionName));
            }
        } catch (Exception e) {
            // Ignore version errors
        }
    }

    private void handleStartup() {
        try {
            secureStorage = new SecureStorage(this);

            if (secureStorage.hasSavedPhrase()) {
                if (isBiometricAvailable()) {
                    showStartupBiometricPrompt();
                } else {
                    // No biometric but phrase exists — load directly
                    String phrase = secureStorage.getPhrase();
                    if (phrase != null) {
                        walletViewModel.setSeedPhrase(phrase);
                    }
                    navigateTo(new WalletFragment(), R.id.nav_wallet);
                }
            } else {
                navigateTo(new SeedPhraseFragment(), R.id.nav_seed_phrase);
            }
        } catch (Exception e) {
            navigateTo(new SeedPhraseFragment(), R.id.nav_seed_phrase);
        }
    }

    private boolean isBiometricAvailable() {
        try {
            BiometricManager biometricManager = BiometricManager.from(this);
            int result = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG |
                    BiometricManager.Authenticators.BIOMETRIC_WEAK);
            return result == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private void showStartupBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // On error/cancel — open SeedPhraseFragment
                        navigateTo(new SeedPhraseFragment(), R.id.nav_seed_phrase);
                    }

                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        try {
                            String phrase = secureStorage.getPhrase();
                            if (phrase != null) {
                                walletViewModel.setSeedPhrase(phrase);
                                navigateTo(new WalletFragment(), R.id.nav_wallet);
                            } else {
                                navigateTo(new SeedPhraseFragment(), R.id.nav_seed_phrase);
                            }
                        } catch (Exception e) {
                            navigateTo(new SeedPhraseFragment(), R.id.nav_seed_phrase);
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        // Let user retry — BiometricPrompt handles this internally
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_auth))
                .setSubtitle(getString(R.string.biometric_auth_subtitle))
                .setNegativeButtonText(getString(R.string.cancel))
                .setConfirmationRequired(true)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();

        if (itemId == currentNavItemId) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        }

        Fragment fragment = null;

        if (itemId == R.id.nav_wallet) {
            fragment = new WalletFragment();
        } else if (itemId == R.id.nav_send) {
            fragment = new SendFragment();
        } else if (itemId == R.id.nav_seed_phrase) {
            fragment = new SeedPhraseFragment();
        } else if (itemId == R.id.nav_settings) {
            fragment = new SettingsFragment();
        } else if (itemId == R.id.nav_future) {
            fragment = new FutureCashFragment();
        } else if (itemId == R.id.nav_maximize) {
            fragment = new MaximizeFragment();
        } else if (itemId == R.id.nav_log) {
            fragment = new LogFragment();
        } else if (itemId == R.id.nav_explorer) {
            fragment = new ExplorerFragment();
        }

        if (fragment != null) {
            navigateTo(fragment, itemId);
        }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    public void navigateTo(Fragment fragment, int menuItemId) {
        try {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, fragment);
            transaction.commit();

            currentNavItemId = menuItemId;

            // Update checked state in drawer
            if (navigationView != null) {
                navigationView.setCheckedItem(menuItemId);
            }

            // Update toolbar title
            if (toolbar != null) {
                if (menuItemId == R.id.nav_wallet) {
                    toolbar.setTitle(R.string.drawer_wallet);
                } else if (menuItemId == R.id.nav_send) {
                    toolbar.setTitle(R.string.drawer_send);
                } else if (menuItemId == R.id.nav_seed_phrase) {
                    toolbar.setTitle(R.string.drawer_seed_phrase);
                } else if (menuItemId == R.id.nav_settings) {
                    toolbar.setTitle(R.string.drawer_settings);
                } else if (menuItemId == R.id.nav_future) {
                    toolbar.setTitle(R.string.drawer_future);
                } else if (menuItemId == R.id.nav_maximize) {
                    toolbar.setTitle(R.string.drawer_maximize);
                } else if (menuItemId == R.id.nav_log) {
                    toolbar.setTitle(R.string.drawer_log);
                } else if (menuItemId == R.id.nav_explorer) {
                    toolbar.setTitle(R.string.drawer_explorer);
                }
            }
        } catch (Exception e) {
            // Ignore fragment transaction errors
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    public static void setLocale(Context context, String languageCode) {
        try {
            Locale locale = new Locale(languageCode);
            Locale.setDefault(locale);

            Resources resources = context.getResources();
            Configuration configuration = new Configuration(resources.getConfiguration());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.setLocale(locale);
                configuration.setLocales(new LocaleList(locale));
            } else {
                configuration.setLocale(locale);
            }

            resources.updateConfiguration(configuration, resources.getDisplayMetrics());
        } catch (Exception e) {
            // Ignore locale errors
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        String savedLanguage = newBase.getSharedPreferences("app_settings", MODE_PRIVATE)
                .getString("app_language", "ru");
        Locale locale = new Locale(savedLanguage);
        Locale.setDefault(locale);

        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.setLocale(locale);
        Context context = newBase.createConfigurationContext(config);
        super.attachBaseContext(context);
    }

    public void updateLanguage(String languageCode) {
        Locale newLocale = new Locale(languageCode);

        if (currentLocale != null && currentLocale.getLanguage().equals(newLocale.getLanguage())) {
            return;
        }

        if (isLanguageChanging) {
            return;
        }

        try {
            isLanguageChanging = true;

            getSharedPreferences("app_settings", MODE_PRIVATE)
                    .edit()
                    .putString("app_language", languageCode)
                    .apply();

            setLocale(this, languageCode);
            currentLocale = newLocale;

            new Handler().postDelayed(() -> {
                try {
                    recreate();
                } catch (Exception e) {
                    // Ignore recreate errors
                } finally {
                    isLanguageChanging = false;
                }
            }, 300);

        } catch (Exception e) {
            isLanguageChanging = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
