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
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.util.ArrayList;
import java.util.List;

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
        default void onServerLog(String command, String response) {}
    }

    public KeyGenerator(KeyGeneratorCallback callback) {
        this.callback = callback;
    }

    public void initialize(String apiUrlParam, String phrase, int addressNumber) {
        cancelCurrentKeyTask();
        cancelled.set(false);

        currentKeyTask = executor.submit(() -> {
            try {
                postProgress("Setting up API...");

                if (apiUrlParam != null && !apiUrlParam.isEmpty()) {
                    apiUrl = apiUrlParam;
                }

                String seedPhrase = phrase;
                String generatedPhrase = "";

                if (seedPhrase.isEmpty()) {
                    String[] words = BIP39.getNewWordList();
                    seedPhrase = BIP39.convertWordListToString(words);
                    generatedPhrase = seedPhrase;
                    postProgress("New phrase generated");
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
                        if (callback != null) callback.onError("Key generation error");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Key generation error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Key generation error");
                });
            }
        });
    }

    /**
     * Fetches the current block number from the Minima API.
     * Calls FutureSendFragment.onBlockLoaded() via the callback's onProgressUpdate
     * with a special prefix "BLOCK:" so the fragment can parse it.
     */
    public void getBlockNumber(String apiUrlParam) {
        if (apiUrlParam != null && !apiUrlParam.isEmpty()) apiUrl = apiUrlParam;
        executor.submit(() -> {
            try {
                String response = ApiHelper.post(apiUrl, "block");
                postServerLog("block", response);

                JSONParser parser = new JSONParser();
                Object parsed = parser.parse(response);
                if (parsed instanceof JSONObject) {
                    Object resp = ((JSONObject) parsed).get("response");
                    if (resp instanceof JSONObject) {
                        Object block = ((JSONObject) resp).get("block");
                        if (block != null) {
                            long blockNum = Long.parseLong(block.toString());
                            mainHandler.post(() -> {
                                if (callback instanceof FutureSendFragment) {
                                    ((FutureSendFragment) callback).onBlockLoaded(blockNum);
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "getBlockNumber error: " + e.getMessage());
            }
        });
    }

    /**
     * Sends a FutureCash time-locked transaction.
     * Uses createfrom with the time-lock script and state JSON containing
     * the owner public key, target block, and target timestamp.
     */
    public void sendFutureTransaction(String apiUrlParam, KeyData kd,
                                      String amount, String tokenId,
                                      String script, String stateJson) {
        cancelCurrentTxTask();
        cancelled.set(false);
        if (apiUrlParam != null && !apiUrlParam.isEmpty()) apiUrl = apiUrlParam;

        currentTxTask = executor.submit(() -> {
            try {
                postProgress("Creating future transaction...");

                // Build createfrom command with script and state
                StringBuilder cmd = new StringBuilder();
                cmd.append("createfrom fromaddress:").append(kd.miniAddress)
                   .append(" amount:").append(amount)
                   .append(" script:\"").append(script).append("\"")
                   .append(" state:").append(stateJson);
                if (tokenId != null && !tokenId.isEmpty() && !"0x00".equals(tokenId)) {
                    cmd.append(" tokenid:").append(tokenId);
                }

                String response = ApiHelper.post(apiUrl, cmd.toString());
                postServerLog("createfrom future", response);

                JSONParser parser = new JSONParser();
                Object parsed = parser.parse(response);
                String unsignedData = null;
                if (parsed instanceof JSONObject) {
                    Object resp = ((JSONObject) parsed).get("response");
                    if (resp instanceof JSONObject) {
                        unsignedData = (String) ((JSONObject) resp).get("data");
                    }
                }

                if (unsignedData == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("No unsigned tx data"); });
                    return;
                }

                postProgress("Signing future transaction...");
                int selectedUse = secureRandom.nextInt(kd.treeKey.getMaxUses());
                kd.treeKey.setUses(selectedUse);
                String signed = createAndSignTransactionLocally(
                        kd.miniAddress, null, amount, tokenId, kd.script, kd.treeKey);

                // For future tx we need the signed form of the future script tx, not normal tx.
                // Re-sign the unsigned future data directly.
                String signedFuture = signFutureData(unsignedData, kd.treeKey);
                if (signedFuture == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Signing failed"); });
                    return;
                }

                postProgress("Broadcasting future transaction...");
                boolean sent = sendSignedTransaction(signedFuture);
                if (sent) {
                    mainHandler.post(() -> { if (callback != null) callback.onTransactionCreated("future_ok"); });
                } else {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Broadcast failed"); });
                }
            } catch (Exception e) {
                Log.e(TAG, "sendFutureTransaction error: " + e.getMessage());
                mainHandler.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    /** Signs raw future transaction data using the tree key. */
    private String signFutureData(String unsignedData, TreeKey treekey) {
        try {
            return signTransactionLocally(unsignedData, treekey) != null
                    ? signTransactionLocally(unsignedData, treekey)
                    : null;
        } catch (Exception e) {
            Log.e(TAG, "signFutureData error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates, signs and sends a transaction to the Minima network.
     * tokenId "0x00" means Minima; any other value targets a custom token.
     */
    public void sendTransaction(String apiUrlParam, String fromAddress, String toAddress,
                                String amount, String tokenId, String script, TreeKey treekey) {
        cancelCurrentTxTask();
        cancelled.set(false);

        currentTxTask = executor.submit(() -> {
            try {
                postProgress("Preparing transaction...");

                if (apiUrlParam != null && !apiUrlParam.isEmpty()) {
                    apiUrl = apiUrlParam;
                }

                int maxUses = treekey.getMaxUses();
                int selectedKeyUse = secureRandom.nextInt(maxUses);
                treekey.setUses(selectedKeyUse);

                if (cancelled.get()) return;

                String result = createAndSignTransactionLocally(fromAddress, toAddress, amount, tokenId, script, treekey);

                if (cancelled.get()) return;

                if (result != null) {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onTransactionCreated(result);
                    });
                } else {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onError("Transaction creation error");
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Transaction error: " + e.getMessage());
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Transaction creation error");
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

    private void postServerLog(String command, String response) {
        mainHandler.post(() -> {
            if (callback != null) callback.onServerLog(command, response);
        });
    }

    private KeyData processAddressNumber(MiniData seed, int addressNumber) {
        try {
            postProgress("Address #" + addressNumber);

            MiniData addressNumberData = new MiniData(BigInteger.valueOf(addressNumber).toByteArray());
            MiniData privseed = Crypto.getInstance().hashObjects(seed, addressNumberData);

            if (cancelled.get()) return null;

            TreeKey treekey = TreeKey.createDefault(privseed);
            String script = "RETURN SIGNEDBY(" + treekey.getPublicKey().to0xString() + ")";
            Address address = new Address(script);
            String miniAddress = address.getMinimaAddress();

            if (cancelled.get()) return null;

            postProgress("Checking balance...");
            List<TokenBalance> tokens = checkBalance(miniAddress);

            if (cancelled.get()) return null;

            // First token (Minima) holds the main balance
            String balance = "0";
            if (tokens != null && !tokens.isEmpty()) {
                balance = tokens.get(0).confirmed;
            }

            KeyData keyData = new KeyData();
            keyData.privateKey = privseed.to0xString();
            keyData.publicKey = treekey.getPublicKey().to0xString();
            keyData.script = script;
            keyData.address = address.getAddressData().to0xString();
            keyData.miniAddress = miniAddress;
            keyData.currentUses = treekey.getUses();
            keyData.maxUses = treekey.getMaxUses();
            keyData.balance = balance;
            keyData.tokens = tokens;
            keyData.treeKey = treekey;

            return keyData;

        } catch (Exception e) {
            Log.e(TAG, "Error processing address #" + addressNumber + ": " + e.getMessage());
            return null;
        }
    }

    private List<TokenBalance> checkBalance(String miniAddress) {
        if (cancelled.get()) return null;

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

                    String responseStr = response.toString();
                    postServerLog("balance address:" + miniAddress, responseStr);
                    return parseTokens(responseStr);
                }
            } else {
                postServerLog("balance address:" + miniAddress, "HTTP " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Balance check error: " + e.getMessage());
            postServerLog("balance address:" + miniAddress, "Error: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }

        return null;
    }

    private List<TokenBalance> parseTokens(String responseStr) {
        List<TokenBalance> result = new ArrayList<>();
        try {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(responseStr);
            if (!(parsed instanceof JSONObject)) return result;

            Object responseObj = ((JSONObject) parsed).get("response");
            if (!(responseObj instanceof JSONArray)) return result;

            JSONArray arr = (JSONArray) responseObj;
            for (Object item : arr) {
                if (!(item instanceof JSONObject)) continue;
                JSONObject obj = (JSONObject) item;

                String tokenId = obj.get("tokenid") != null ? obj.get("tokenid").toString() : "";

                // Resolve token name and optional image
                String tokenName;
                String imageData = null;
                String imageUrl = null;
                Object tokenField = obj.get("token");
                if ("0x00".equals(tokenId)) {
                    tokenName = "Minima";
                } else if (tokenField instanceof String) {
                    tokenName = (String) tokenField;
                } else if (tokenField instanceof JSONObject) {
                    JSONObject tokenObj = (JSONObject) tokenField;
                    Object nameObj = tokenObj.get("name");
                    tokenName = (nameObj != null && !nameObj.toString().trim().isEmpty())
                            ? nameObj.toString().trim()
                            : tokenId;
                    // Extract base64 image from the url field if wrapped in <artimage> tags
                    Object urlObj = tokenObj.get("url");
                    if (urlObj != null) {
                        String url = urlObj.toString();
                        if (url.startsWith("<artimage>")) {
                            int start = "<artimage>".length();
                            int end = url.indexOf("</artimage>");
                            imageData = end > start ? url.substring(start, end) : url.substring(start);
                        } else if (url.startsWith("http://") || url.startsWith("https://")) {
                            imageUrl = url;
                        }
                    }
                } else {
                    tokenName = tokenId;
                }
                String confirmed = obj.get("confirmed") != null ? obj.get("confirmed").toString() : "0";
                String unconfirmed = obj.get("unconfirmed") != null ? obj.get("unconfirmed").toString() : "0";
                String sendable = obj.get("sendable") != null ? obj.get("sendable").toString() : "0";

                result.add(new TokenBalance(tokenName, tokenId, confirmed, unconfirmed, sendable, imageData, imageUrl));
            }
        } catch (Exception e) {
            Log.e(TAG, "Token parse error: " + e.getMessage());
        }
        return result;
    }

    private String createAndSignTransactionLocally(String fromAddress, String toAddress,
                                                    String amount, String tokenId, String script, TreeKey treekey) {
        try {
            postProgress("Creating transaction...");
            String unsignedData = createTransaction(fromAddress, toAddress, amount, tokenId, script);
            if (unsignedData == null) {
                postProgress("Transaction creation failed");
                return null;
            }

            if (cancelled.get()) return null;

            String signedTransaction = signTransactionLocally(unsignedData, treekey);
            if (signedTransaction != null) {
                postProgress("Transaction signed");

                boolean sent = sendSignedTransaction(signedTransaction);
                return sent ? "Transaction sent successfully" : null;
            } else {
                postProgress("Signing failed");
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Transaction creation error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Requests an unsigned transaction from the server (createfrom command).
     * For Minima (tokenId=0x00) the tokenid parameter is omitted — server uses it by default.
     * For other tokens tokenid is appended to the request.
     */
    private String createTransaction(String fromAddress, String toAddress, String amount, String tokenId, String script) {
        if (cancelled.get()) return null;

        StringBuilder requestBody = new StringBuilder();
        requestBody.append("createfrom fromaddress:").append(fromAddress)
                .append(" address:").append(toAddress)
                .append(" amount:").append(amount);
        // tokenid 0x00 is Minima — omit it, server uses Minima by default
        if (tokenId != null && !tokenId.isEmpty() && !"0x00".equals(tokenId)) {
            requestBody.append(" tokenid:").append(tokenId);
        }
        requestBody.append(" script:\"").append(script).append("\"");

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
                os.write(requestBody.toString().getBytes());
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

                    String responseStr = response.toString();
                    postServerLog("createfrom amount:" + amount, responseStr);

                    JSONParser parser = new JSONParser();
                    Object parsedResponse = parser.parse(responseStr);

                    if (parsedResponse instanceof JSONObject) {
                        JSONObject jsonResponse = (JSONObject) parsedResponse;
                        JSONObject responseObj = (JSONObject) jsonResponse.get("response");
                        if (responseObj != null) {
                            return (String) responseObj.get("data");
                        }
                    }
                }
            } else {
                postServerLog("createfrom amount:" + amount, "HTTP " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Create transaction error: " + e.getMessage());
            postServerLog("createfrom amount:" + amount, "Error: " + e.getMessage());
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
            postProgress("Broadcasting to network...");

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
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    postServerLog("postfrom", response.toString());
                }
                postProgress("Transaction broadcast to network");
                return true;
            } else {
                postServerLog("postfrom", "HTTP " + responseCode);
                postProgress("Broadcast failed: code " + responseCode);
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Send transaction error: " + e.getMessage());
            postServerLog("postfrom", "Error: " + e.getMessage());
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

    public static class TokenBalance {
        public String tokenName;
        public String tokenId;
        public String confirmed;
        public String unconfirmed;
        public String sendable;
        public String imageData; // base64 image from <artimage> tag
        public String imageUrl;  // plain image URL (stored but not used for display)

        public TokenBalance(String tokenName, String tokenId, String confirmed,
                            String unconfirmed, String sendable, String imageData, String imageUrl) {
            this.tokenName = tokenName;
            this.tokenId = tokenId;
            this.confirmed = confirmed;
            this.unconfirmed = unconfirmed;
            this.sendable = sendable;
            this.imageData = imageData;
            this.imageUrl = imageUrl;
        }
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
        public List<TokenBalance> tokens;
        public TreeKey treeKey;
        public String generatedPhrase;
    }
}
