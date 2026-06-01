package com.example.minimawallet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.content.SharedPreferences;
import android.widget.ImageView;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.minimawallet.KeyGenerator.KeyData;
import com.example.minimawallet.KeyGenerator.TokenBalance;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.ArrayList;
import java.util.List;

public class WalletFragment extends Fragment implements KeyGenerator.KeyGeneratorCallback {

    private KeyGenerator keyGenerator;
    private SharedPreferences sharedPreferences;
    private WalletViewModel walletViewModel;

    // UI elements
    private TextInputEditText addressNumberEdit;
    private MaterialTextView progressText, addressText, balanceText, seedStatusText;
    private MaterialButton generateKeysBtn, refreshBalanceBtn;
    private ImageView qrCodeBtn;
    private ProgressBar loadingProgress;
    private RecyclerView tokensRecycler;
    private MaterialCardView tokensCard;
    private TokenAdapter tokenAdapter;

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

                        keyGenerator = new KeyGenerator(ctx, this);
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

        // Restore address number from ViewModel
        if (addressNumberEdit != null) {
            String saved = walletViewModel.getAddressNumber();
            if (saved != null && !saved.isEmpty()) {
                addressNumberEdit.setText(saved);
            }
            addressNumberEdit.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    walletViewModel.setAddressNumber(s.toString());
                }
            });
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
            refreshBalanceBtn = view.findViewById(R.id.refresh_balance_btn);
            loadingProgress = view.findViewById(R.id.loading_progress);
            seedStatusText = view.findViewById(R.id.seed_status_text);
            qrCodeBtn = view.findViewById(R.id.qr_code_btn);
            tokensCard = view.findViewById(R.id.tokens_card);
            tokensRecycler = view.findViewById(R.id.tokens_recycler);

            tokenAdapter = new TokenAdapter();
            tokensRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
            tokensRecycler.setAdapter(tokenAdapter);
            tokensRecycler.addItemDecoration(
                    new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));
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
        if (refreshBalanceBtn != null) {
            refreshBalanceBtn.setOnClickListener(v -> refreshBalance());
        }
        if (qrCodeBtn != null) {
            qrCodeBtn.setOnClickListener(v -> showQrCode());
        }
    }

    private void showQrCode() {
        KeyData currentKeyData = walletViewModel.getCurrentKeyData();
        if (currentKeyData == null || currentKeyData.miniAddress == null) {
            showToast(getString(R.string.generate_keys_first));
            return;
        }
        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.encodeBitmap(currentKeyData.miniAddress, BarcodeFormat.QR_CODE, 512, 512);

            ImageView imageView = new ImageView(requireContext());
            imageView.setImageBitmap(bitmap);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            imageView.setPadding(pad, pad, pad, pad);

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.qr_code_title)
                    .setView(imageView)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        } catch (Exception e) {
            showToast(getString(R.string.error) + ": " + e.getMessage());
        }
    }

    private void refreshBalance() {
        KeyData currentKeyData = walletViewModel.getCurrentKeyData();
        if (currentKeyData == null || currentKeyData.miniAddress == null) {
            showToast(getString(R.string.generate_keys_first));
            return;
        }

        String phrase = walletViewModel.getCurrentSeedPhrase();
        if (phrase == null || phrase.isEmpty()) {
            showToast(getString(R.string.seed_phrase_not_loaded));
            return;
        }

        if (refreshBalanceBtn != null) refreshBalanceBtn.setEnabled(false);
        if (progressText != null) progressText.setText(R.string.checking_balance);

        if (addressNumberEdit != null && !addressNumberEdit.getText().toString().isEmpty()) {
            try {
                int addressNumber = Integer.parseInt(addressNumberEdit.getText().toString());
                if (keyGenerator != null) {
                    keyGenerator.initialize(null, phrase, addressNumber);
                }
            } catch (NumberFormatException e) {
                if (refreshBalanceBtn != null) refreshBalanceBtn.setEnabled(true);
            }
        } else {
            if (refreshBalanceBtn != null) refreshBalanceBtn.setEnabled(true);
            showToast(getString(R.string.enter_address_number));
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
                keyGenerator.initialize(null, phrase, addressNumber);
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
                if (refreshBalanceBtn != null) refreshBalanceBtn.setEnabled(true);

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
                if (refreshBalanceBtn != null) refreshBalanceBtn.setEnabled(true);
                showToast(getString(R.string.error) + ": " + error);
            });
        }
    }

    @Override
    public void onServerLog(String command, String response) {
        if (walletViewModel != null) {
            walletViewModel.addServerLog("CMD: " + command + "\nRES: " + response);
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
            // Update token list, excluding Minima which is shown in the balance field
            if (tokensCard != null && tokenAdapter != null) {
                List<TokenBalance> tokens = currentKeyData.tokens;
                List<TokenBalance> filtered = new java.util.ArrayList<>();
                if (tokens != null) {
                    for (TokenBalance t : tokens) {
                        if (!"0x00".equals(t.tokenId)) filtered.add(t);
                    }
                }
                if (!filtered.isEmpty()) {
                    tokenAdapter.setTokens(filtered);
                    tokensCard.setVisibility(View.VISIBLE);
                    if (tokensRecycler != null) tokensRecycler.requestLayout();
                } else {
                    tokensCard.setVisibility(View.GONE);
                }
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

    // --- Adapter ---
    static class TokenAdapter extends RecyclerView.Adapter<TokenAdapter.VH> {

        private List<TokenBalance> tokens = new ArrayList<>();

        void setTokens(List<TokenBalance> newTokens) {
            tokens = newTokens;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_token, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            TokenBalance t = tokens.get(position);
            holder.name.setText(t.tokenName);
            // Hide the id row when it duplicates the name
            if (t.tokenName.equals(t.tokenId)) {
                holder.id.setVisibility(android.view.View.GONE);
            } else {
                holder.id.setVisibility(android.view.View.VISIBLE);
                holder.id.setText(t.tokenId);
            }
            holder.balance.setText(t.confirmed);
            // Show token image on click if artimage data is available
            holder.itemView.setOnClickListener(v -> {
                if (t.imageData != null && !t.imageData.isEmpty()) {
                    showTokenImage(v.getContext(), t.tokenName, t.imageData, null);
                }
            });
        }

        private void showTokenImage(Context ctx, String tokenName, String imageData, String imageUrl) {
            android.widget.ImageView imageView = new android.widget.ImageView(ctx);
            imageView.setAdjustViewBounds(true);
            int pad = (int)(16 * ctx.getResources().getDisplayMetrics().density);
            imageView.setPadding(pad, pad, pad, pad);

            com.google.android.material.dialog.MaterialAlertDialogBuilder dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(tokenName)
                    .setView(imageView)
                    .setPositiveButton(android.R.string.ok, null);

            if (imageData != null && !imageData.isEmpty()) {
                // artimage — base64
                try {
                    byte[] bytes = Base64.decode(imageData, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                        dialog.show();
                    }
                } catch (Exception e) { /* ignore */ }
            }
        }

        @Override
        public int getItemCount() {
            return tokens.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView name, id, balance;
            VH(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.token_name);
                id = v.findViewById(R.id.token_id);
                balance = v.findViewById(R.id.token_balance);
            }
        }
    }
}
