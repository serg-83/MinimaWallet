package com.example.minimawallet;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class WalletViewModel extends ViewModel {

    private final MutableLiveData<KeyGenerator.KeyData> keyData = new MutableLiveData<>();
    private final MutableLiveData<String> seedPhrase = new MutableLiveData<>();

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
}
