package com.example.minimawallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecureStorage {
    private static final String TAG = "SecureStorage";
    private static final String SHARED_PREFS_NAME = "minima_wallet_secure";
    private static final String KEY_ALIAS = "minima_wallet_key";
    private static final String PHRASE_KEY = "encrypted_phrase";
    private static final String IV_KEY = "encryption_iv";
    
    private final SharedPreferences sharedPreferences;
    private KeyStore keyStore;
    private boolean isKeyStoreInitialized = false;
    private final Object keyStoreLock = new Object();
    
    public SecureStorage(Context context) {
        this.sharedPreferences = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    private void ensureKeyStoreInitialized() {
        synchronized (keyStoreLock) {
            if (!isKeyStoreInitialized) {
                initializeKeyStore();
                isKeyStoreInitialized = true;
            }
        }
    }
    
    private void initializeKeyStore() {
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            Log.d(TAG, "KeyStore initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize KeyStore", e);
            keyStore = null;
        }
    }
    
    public boolean savePhrase(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) {
            Log.e(TAG, "Cannot save empty phrase");
            return false;
        }
        
        ensureKeyStoreInitialized();
        
        if (keyStore == null) {
            Log.e(TAG, "KeyStore is not initialized, cannot save phrase");
            return false;
        }
        
        try {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(phrase.getBytes(StandardCharsets.UTF_8));
            
            String encryptedBase64 = Base64.encodeToString(encrypted, Base64.DEFAULT);
            String ivBase64 = Base64.encodeToString(iv, Base64.DEFAULT);
            
            sharedPreferences.edit()
                .putString(PHRASE_KEY, encryptedBase64)
                .putString(IV_KEY, ivBase64)
                .apply();
                
            Log.d(TAG, "Phrase saved securely");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving phrase", e);
            return false;
        }
    }
    
    public String getPhrase() {
        ensureKeyStoreInitialized();
        
        if (keyStore == null) {
            Log.e(TAG, "KeyStore is not initialized, cannot get phrase");
            return null;
        }
        
        try {
            String encryptedBase64 = sharedPreferences.getString(PHRASE_KEY, null);
            String ivBase64 = sharedPreferences.getString(IV_KEY, null);
            
            if (encryptedBase64 == null || ivBase64 == null) {
                return null;
            }
            
            byte[] encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT);
            byte[] iv = Base64.decode(ivBase64, Base64.DEFAULT);
            
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Error getting phrase", e);
            return null;
        }
    }
    
    public void clearAllData() {
        try {
            // Удаляем ключ из KeyStore
            if (keyStore != null && keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
                Log.d(TAG, "Key deleted from KeyStore");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting key from KeyStore", e);
        }
        
        // Очищаем SharedPreferences
        sharedPreferences.edit()
            .remove(PHRASE_KEY)
            .remove(IV_KEY)
            .apply();
        Log.d(TAG, "All secure data cleared");
    }
    
    public boolean hasSavedPhrase() {
        return sharedPreferences.contains(PHRASE_KEY) && sharedPreferences.contains(IV_KEY);
    }
    
    public boolean isKeyStoreAvailable() {
        if (!isKeyStoreInitialized) {
            initializeKeyStore();
            isKeyStoreInitialized = true;
        }
        return keyStore != null;
    }
    
    private SecretKey getOrCreateKey() throws Exception {
        if (keyStore == null) {
            throw new IllegalStateException("KeyStore is not initialized");
        }
        
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .setKeySize(256);
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                builder.setUnlockedDeviceRequired(false)
                       .setIsStrongBoxBacked(false);
            }
                
            keyGenerator.init(builder.build());
            return keyGenerator.generateKey();
        } else {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
    }
}
