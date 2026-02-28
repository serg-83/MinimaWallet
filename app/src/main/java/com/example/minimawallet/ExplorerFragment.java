package com.example.minimawallet;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;

public class ExplorerFragment extends Fragment {

    private static final String DEFAULT_EXPLORER_URL = "https://explorer.minima.global/search?q=";

    private WebView webView;
    private ProgressBar progressBar;
    private WalletViewModel walletViewModel;
    private SharedPreferences sharedPreferences;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_explorer, container, false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        walletViewModel = new ViewModelProvider(requireActivity()).get(WalletViewModel.class);
        sharedPreferences = requireContext().getSharedPreferences("app_settings", 0);

        webView = view.findViewById(R.id.explorer_webview);
        progressBar = view.findViewById(R.id.explorer_progress);

        MaterialButton backBtn = view.findViewById(R.id.explorer_back_btn);
        MaterialButton refreshBtn = view.findViewById(R.id.explorer_refresh_btn);

        backBtn.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });

        refreshBtn.setOnClickListener(v -> webView.reload());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar == null) return;
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        loadExplorer();
    }

    private void loadExplorer() {
        String baseUrl = sharedPreferences.getString("explorer_url", DEFAULT_EXPLORER_URL);
        if (baseUrl == null || baseUrl.isEmpty()) baseUrl = DEFAULT_EXPLORER_URL;

        KeyGenerator.KeyData kd = walletViewModel.getCurrentKeyData();
        String address = kd != null ? kd.miniAddress : "";

        webView.loadUrl(baseUrl + address);
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        progressBar = null;
        super.onDestroyView();
    }
}
