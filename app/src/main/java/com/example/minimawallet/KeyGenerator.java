package com.example.minimawallet;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.minima.objects.Address;
import org.minima.objects.Transaction;
import org.minima.objects.Witness;
import org.minima.objects.base.MiniData;
import org.minima.objects.keys.Signature;
import org.minima.objects.keys.TreeKey;
import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.utils.BIP39;
import org.minima.utils.Crypto;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

public class KeyGenerator {
    private static final String TAG = "KeyGenerator";
    private static final String DEFAULT_API_URL = "https://wallet.minima.global/mdscommand_/cmd?uid=0xFFEEDD";
    private static final SecureRandom secureRandom = new SecureRandom();

    private volatile String apiUrl = DEFAULT_API_URL;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile KeyGeneratorCallback callback;
    private Future<?> currentKeyTask;
    private Future<?> currentTxTask;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public interface KeyGeneratorCallback {
        void onProgressUpdate(String message);
        void onKeyGenerated(KeyData keyData);
        void onTransactionCreated(String txId);
        void onError(String error);
    }

    public KeyGenerator(KeyGeneratorCallback callback) {
        this.callback = callback;
    }

    public void initialize(String apiUrlParam, String phrase, int addressNumber) {
        cancelCurrentKeyTask();
        cancelled.set(false);

        currentKeyTask = executor.submit(() -> {
            try {
                postProgress("Настройка API...");

                if (apiUrlParam != null && !apiUrlParam.isEmpty()) {
                    apiUrl = apiUrlParam;
                }

                String seedPhrase = phrase;
                String generatedPhrase = "";

                if (seedPhrase.isEmpty()) {
                    String[] words = BIP39.getNewWordList();
                    seedPhrase = BIP39.convertWordListToString(words);
                    generatedPhrase = seedPhrase;
                    postProgress("Новая фраза сгенерирована");
                }

                if (cancelled.get()) return;

                MiniData seed = BIP39.convertStringToSeed(seedPhrase);
                KeyData keyData = processAddressNumber(seed, addressNumber);

                if (cancelled.get()) return;

                if (keyData != null) {
                    keyData.generatedPhrase = generatedPhrase;
                    final KeyData result = keyData;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onKeyGenerated(result);
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("Ошибка генерации ключей");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Key generation error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Ошибка генерации ключей");
                });
            }
        });
    }

    public void sendTransaction(String apiUrlParam, String fromAddress, String toAddress,
                                String amount, String script, TreeKey treekey) {
        cancelCurrentTxTask();
        cancelled.set(false);

        currentTxTask = executor.submit(() -> {
            try {
                postProgress("Подготовка транзакции...");

                if (apiUrlParam != null && !apiUrlParam.isEmpty()) {
                    apiUrl = apiUrlParam;
                }

                int maxUses = treekey.getMaxUses();
                int selectedKeyUse = secureRandom.nextInt(maxUses);
                treekey.setUses(selectedKeyUse);

                if (cancelled.get()) return;

                String result = createAndSignTransactionLocally(fromAddress, toAddress, amount, script, treekey);

                if (cancelled.get()) return;

                if (result != null) {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onTransactionCreated(result);
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("Ошибка создания транзакции");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Transaction error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Ошибка создания транзакции");
                });
            }
        });
    }

    public void cleanup() {
        cancelled.set(true);
        cancelCurrentKeyTask();
        cancelCurrentTxTask();
        callback = null;
        executor.shutdownNow();
    }

    private void cancelCurrentKeyTask() {
        if (currentKeyTask != null && !currentKeyTask.isDone()) {
            currentKeyTask.cancel(true);
        }
    }

    private void cancelCurrentTxTask() {
        if (currentTxTask != null && !currentTxTask.isDone()) {
            currentTxTask.cancel(true);
        }
    }

    private void postProgress(String message) {
        mainHandler.post(() -> {
            if (callback != null) callback.onProgressUpdate(message);
        });
    }

    private KeyData processAddressNumber(MiniData seed, int addressNumber) {
        try {
            postProgress("Адрес #" + addressNumber);

            MiniData addressNumberData = new MiniData(BigInteger.valueOf(addressNumber).toByteArray());
            MiniData privseed = Crypto.getInstance().hashObjects(seed, addressNumberData);

            if (cancelled.get()) return null;

            TreeKey treekey = TreeKey.createDefault(privseed);
            String script = "RETURN SIGNEDBY(" + treekey.getPublicKey().to0xString() + ")";
            Address address = new Address(script);
            String miniAddress = address.getMinimaAddress();

            if (cancelled.get()) return null;

            postProgress("Проверка баланса...");
            String balance = checkBalance(miniAddress);

            if (cancelled.get()) return null;

            KeyData keyData = new KeyData();
            keyData.privateKey = privseed.to0xString();
            keyData.publicKey = treekey.getPublicKey().to0xString();
            keyData.script = script;
            keyData.address = address.getAddressData().to0xString();
            keyData.miniAddress = miniAddress;
            keyData.currentUses = treekey.getUses();
            keyData.maxUses = treekey.getMaxUses();
            keyData.balance = balance;
            keyData.treeKey = treekey;

            return keyData;

        } catch (Exception e) {
            Log.e(TAG, "Error processing address #" + addressNumber + ": " + e.getMessage());
            return null;
        }
    }

    private String checkBalance(String miniAddress) {
        if (cancelled.get()) return "0";

        String requestBody = "balance megammr:true address:" + miniAddress;
        HttpURLConnection connection = null;

        try {
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "text/plain");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes());
                os.flush();
            }

            if (cancelled.get()) return "0";

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !cancelled.get()) {
                        response.append(line);
                    }

                    if (cancelled.get()) return "0";

                    String confirmed = extractValue(response.toString(), "confirmed");
                    if (confirmed != null) {
                        return confirmed;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Balance check error: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }

        return "0";
    }

    private String createAndSignTransactionLocally(String fromAddress, String toAddress,
                                                    String amount, String script, TreeKey treekey) {
        try {
            postProgress("Создание транзакции...");
            String unsignedData = createTransaction(fromAddress, toAddress, amount, script);
            if (unsignedData == null) {
                postProgress("Ошибка создания транзакции");
                return null;
            }

            if (cancelled.get()) return null;

            String signedTransaction = signTransactionLocally(unsignedData, treekey);
            if (signedTransaction != null) {
                postProgress("Транзакция подписана");

                boolean sent = sendSignedTransaction(signedTransaction);
                return sent ? "Транзакция успешно отправлена" : null;
            } else {
                postProgress("Ошибка подписи");
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Transaction creation error: " + e.getMessage());
            return null;
        }
    }

    private String createTransaction(String fromAddress, String toAddress, String amount, String script) {
        if (cancelled.get()) return null;

        String requestBody = "createfrom fromaddress:" + fromAddress +
                " address:" + toAddress +
                " amount:" + amount +
                " script:\"" + script + "\"";

        HttpURLConnection connection = null;

        try {
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "text/plain");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes());
                os.flush();
            }

            if (cancelled.get()) return null;

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !cancelled.get()) {
                        response.append(line);
                    }

                    if (cancelled.get()) return null;

                    JSONParser parser = new JSONParser();
                    Object parsedResponse = parser.parse(response.toString());

                    if (parsedResponse instanceof JSONObject) {
                        JSONObject jsonResponse = (JSONObject) parsedResponse;
                        JSONObject responseObj = (JSONObject) jsonResponse.get("response");
                        if (responseObj != null) {
                            return (String) responseObj.get("data");
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Create transaction error: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }

        return null;
    }

    private String signTransactionLocally(String transactionData, TreeKey treekey) {
        try {
            MiniData miniData = new MiniData(transactionData);
            TxnRow unsignedTxn = TxnRow.convertMiniDataVersion(miniData);

            if (unsignedTxn == null) {
                return null;
            }

            if (cancelled.get()) return null;

            Witness witness = unsignedTxn.getWitness();
            Transaction transaction = unsignedTxn.getTransaction();
            MiniData txid = transaction.getTransactionID();

            witness.clearSignatures();
            Signature signature = treekey.sign(txid);
            witness.addSignature(signature);

            TxnRow signedTxn = new TxnRow(
                    unsignedTxn.getID(),
                    transaction,
                    witness
            );

            return serializeTxnRowToHex(signedTxn);

        } catch (Exception e) {
            Log.e(TAG, "Sign transaction error: " + e.getMessage());
            return null;
        }
    }

    private String serializeTxnRowToHex(TxnRow txnRow) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            txnRow.writeDataStream(dos);
            dos.flush();
            byte[] data = baos.toByteArray();
            MiniData miniData = new MiniData(data);
            String hex = miniData.to0xString();
            return hex.length() >= 2 ? hex.substring(2) : hex;
        } catch (Exception e) {
            Log.e(TAG, "Serialization error: " + e.getMessage());
            return null;
        }
    }

    private boolean sendSignedTransaction(String signedTransaction) {
        if (cancelled.get()) return false;

        String requestBody = "postfrom data:" + signedTransaction + " mine:true";
        HttpURLConnection connection = null;

        try {
            postProgress("Отправка в сеть...");

            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "text/plain");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes());
                os.flush();
            }

            if (cancelled.get()) return false;

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                postProgress("Транзакция отправлена в сеть");
                return true;
            } else {
                postProgress("Ошибка отправки: код " + responseCode);
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Send transaction error: " + e.getMessage());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String extractValue(String json, String key) {
        String searchPattern = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchPattern);
        if (startIndex == -1) return null;

        startIndex += searchPattern.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return null;

        return json.substring(startIndex, endIndex);
    }

    public static class KeyData {
        public String privateKey;
        public String publicKey;
        public String script;
        public String address;
        public String miniAddress;
        public int currentUses;
        public int maxUses;
        public String balance;
        public TreeKey treeKey;
        public String generatedPhrase;
    }
}
