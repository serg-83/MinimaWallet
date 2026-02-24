package com.example.minimawallet;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WalletViewModel extends ViewModel {

    private final MutableLiveData<KeyGenerator.KeyData> keyData = new MutableLiveData<>();
    private final MutableLiveData<String> seedPhrase = new MutableLiveData<>();
    private final MutableLiveData<List<String>> serverLog = new MutableLiveData<>(new ArrayList<>());

    public LiveData<KeyGenerator.KeyData> getKeyData() {
        return keyData;
    }

    public void setKeyData(KeyGenerator.KeyData data) {
        keyData.setValue(data);
    }

    public KeyGenerator.KeyData getCurrentKeyData() {
        return keyData.getValue();
    }

    public LiveData<String> getSeedPhrase() {
        return seedPhrase;
    }

    public void setSeedPhrase(String phrase) {
        seedPhrase.setValue(phrase);
    }

    public String getCurrentSeedPhrase() {
        return seedPhrase.getValue();
    }

    public LiveData<List<String>> getServerLog() {
        return serverLog;
    }

    public void addServerLog(String entry) {
        List<String> current = serverLog.getValue();
        if (current == null) current = new ArrayList<>();
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        List<String> updated = new ArrayList<>(current);
        updated.add(0, "[" + timestamp + "] " + entry);
        if (updated.size() > 200) {
            updated = updated.subList(0, 200);
        }
        serverLog.setValue(updated);
    }

    public void clearServerLog() {
        serverLog.setValue(new ArrayList<>());
    }
}
