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
                                } else if (callback instanceof MaximizeStakeFragment) {
                                    ((MaximizeStakeFragment) callback).onBlockLoaded(blockNum);
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
     *
     * Step 1 (1 API call): newscript — get deterministic script address.
     * Step 2 (1 API call): build unsigned tx on server —
     *     txncreate → txnaddamount → txnstate(×3) → txnscript → txnmmr → txnexport → txndelete
     *     txnaddamount automatically selects coins and adds change output.
     * Step 3: sign the exported hex locally with the tree key.
     * Step 4 (1 API call): postfrom data:<signedHex> — broadcast signed tx.
     *
     * Total: 3 API calls. createfrom is not used because it does not
     * support the state parameter required for the time-lock script.
     */
    public void sendFutureTransaction(String apiUrlParam, KeyData kd,
                                      String amount, String tokenId,
                                      String script, String stateJson) {
        cancelCurrentTxTask();
        cancelled.set(false);
        if (apiUrlParam != null && !apiUrlParam.isEmpty()) apiUrl = apiUrlParam;

        // Parse state values from stateJson: {"1":"<block>","2":"<address>","3":"<ms>","4":"<coinage>"}
        final String targetBlock;
        final String recipientAddress;
        final String targetMs;
        final String targetCoinage;
        try {
            JSONParser preParser = new JSONParser();
            Object pre = preParser.parse(stateJson);
            if (pre instanceof JSONObject) {
                JSONObject jo = (JSONObject) pre;
                targetBlock      = jo.get("1").toString();
                recipientAddress = jo.get("2").toString();
                targetMs         = jo.get("3").toString();
                targetCoinage    = jo.get("4").toString();
            } else {
                mainHandler.post(() -> { if (callback != null) callback.onError("Invalid stateJson"); });
                return;
            }
        } catch (Exception e) {
            mainHandler.post(() -> { if (callback != null) callback.onError("State parse error: " + e.getMessage()); });
            return;
        }

        currentTxTask = executor.submit(() -> {
            try {
                JSONParser parser = new JSONParser();
                String tid = (tokenId != null && !tokenId.isEmpty()) ? tokenId : "0x00";

                // Step 1: register script, get its deterministic address
                postProgress("Getting script address...");
                String newscriptResp = ApiHelper.post(apiUrl,
                        "newscript script:\"" + script + "\" trackall:false");
                postServerLog("newscript", newscriptResp);

                String scriptAddress = null;
                Object parsed1 = parser.parse(newscriptResp);
                if (parsed1 instanceof JSONObject) {
                    Object resp = ((JSONObject) parsed1).get("response");
                    if (resp instanceof JSONObject) {
                        Object addr = ((JSONObject) resp).get("address");
                        if (addr != null) scriptAddress = addr.toString();
                    }
                }
                if (scriptAddress == null || scriptAddress.isEmpty()) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Could not get script address"); });
                    return;
                }
                if (cancelled.get()) return;

                // Step 2: build unsigned transaction on server and export as hex
                // txnaddamount selects coins automatically and adds change output
                postProgress("Building unsigned transaction...");
                String txId = "future_" + System.currentTimeMillis();
                String addressScript = "RETURN SIGNEDBY(" + kd.treeKey.getPublicKey().to0xString() + ")";
                String buildCmds =
                    "txncreate id:" + txId + ";" +
                    "txnaddamount id:" + txId +
                        " fromaddress:" + kd.miniAddress +
                        " address:" + scriptAddress +
                        " amount:" + amount +
                        " tokenid:" + tid + ";" +
                    "txnstate id:" + txId + " port:1 value:" + targetBlock + ";" +
                    "txnstate id:" + txId + " port:2 value:" + recipientAddress + ";" +
                    "txnstate id:" + txId + " port:3 value:" + targetMs + ";" +
                    "txnstate id:" + txId + " port:4 value:" + targetCoinage + ";" +
                    "txnscript id:" + txId + " scripts:{\"" + addressScript + "\":\"\"};" +
                    "txnmmr id:" + txId + ";" +
                    "txnexport id:" + txId + ";" +
                    "txndelete id:" + txId;

                String buildResp = ApiHelper.post(apiUrl, buildCmds);
                postServerLog("future build", buildResp);

                // Extract hex data from the txnexport response (last successful item)
                String unsignedHex = null;
                Object parsed2 = parser.parse(buildResp);
                if (parsed2 instanceof JSONArray) {
                    JSONArray arr = (JSONArray) parsed2;
                    // txnexport is 8th command (index 7); find it by response.data
                    for (Object item : arr) {
                        if (!(item instanceof JSONObject)) continue;
                        JSONObject jo = (JSONObject) item;
                        Object resp = jo.get("response");
                        if (resp instanceof JSONObject) {
                            Object data = ((JSONObject) resp).get("data");
                            if (data != null && !data.toString().isEmpty()) {
                                unsignedHex = data.toString();
                            }
                        }
                    }
                }
                if (unsignedHex == null || unsignedHex.isEmpty()) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("No unsigned tx data from server"); });
                    return;
                }
                if (cancelled.get()) return;

                // Step 3: sign locally with tree key
                postProgress("Signing transaction...");
                int selectedUse = secureRandom.nextInt(kd.treeKey.getMaxUses());
                kd.treeKey.setUses(selectedUse);
                String signedHex = signTransactionLocally(unsignedHex, kd.treeKey);
                if (signedHex == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Local signing failed"); });
                    return;
                }
                if (cancelled.get()) return;

                // Step 4: broadcast signed transaction via postfrom
                postProgress("Broadcasting transaction...");
                String postResp = ApiHelper.post(apiUrl, "postfrom data:" + signedHex);
                postServerLog("postfrom future", postResp);

                boolean success = false;
                Object parsed3 = parser.parse(postResp);
                if (parsed3 instanceof JSONObject) {
                    Object st = ((JSONObject) parsed3).get("status");
                    success = "true".equals(String.valueOf(st));
                }

                if (success) {
                    mainHandler.post(() -> { if (callback != null) callback.onTransactionCreated("future_ok"); });
                } else {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Broadcast failed: " + postResp); });
                }

            } catch (Exception e) {
                Log.e(TAG, "sendFutureTransaction error: " + e.getMessage());
                mainHandler.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
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

    /**
     * Sends a Maximize staking transaction using local signing.
     *
     * Flow (same as FutureCash):
     * 1. txncreate → txnaddamount → txnstate(×5) → txnscript → txnmmr → txnexport → txndelete
     * 2. Sign exported hex locally with tree key
     * 3. postfrom data:<signedHex>
     *
     * Bond address is hardcoded (Maximize contract).
     * State ports: 100=pubkey, 101=maxblock, 102=userAddress, 104=maxcoinage, 105=rate
     */
    public void sendMaximizeStake(String apiUrlParam, KeyData kd,
                                   String amount, int timeframeMonths, double rate, long currentBlock) {
        cancelCurrentTxTask();
        cancelled.set(false);
        if (apiUrlParam != null && !apiUrlParam.isEmpty()) apiUrl = apiUrlParam;

        currentTxTask = executor.submit(() -> {
            try {
                JSONParser parser = new JSONParser();
                int DAY_OF_BLOCKS = 1728;
                long days = (long) timeframeMonths * 30;
                long maxcoinage = days * DAY_OF_BLOCKS + DAY_OF_BLOCKS;
                long maxblock = currentBlock + maxcoinage;

                String pubkey = kd.treeKey.getPublicKey().to0xString();
                String userAddress = kd.address;
                String bondAddress = "MxG0861MPQ3ZQTM4GFTZ0UJA74Y48A4GDPYM1NTVKDTU0B34BFDV86G5A0PD21N";

                // Step 1: build unsigned transaction on server and export as hex
                postProgress("Building staking transaction...");
                String txId = "maximize_" + System.currentTimeMillis();
                String addressScript = "RETURN SIGNEDBY(" + pubkey + ")";
                String buildCmds =
                    "txncreate id:" + txId + ";" +
                    "txnaddamount id:" + txId +
                        " fromaddress:" + kd.miniAddress +
                        " address:" + bondAddress +
                        " amount:" + amount + ";" +
                    "txnstate id:" + txId + " port:100 value:" + pubkey + ";" +
                    "txnstate id:" + txId + " port:101 value:" + maxblock + ";" +
                    "txnstate id:" + txId + " port:102 value:" + userAddress + ";" +
                    "txnstate id:" + txId + " port:104 value:" + maxcoinage + ";" +
                    "txnstate id:" + txId + " port:105 value:" + rate + ";" +
                    "txnscript id:" + txId + " scripts:{\"" + addressScript + "\":\"\"};" +
                    "txnmmr id:" + txId + ";" +
                    "txnexport id:" + txId + ";" +
                    "txndelete id:" + txId;

                String buildResp = ApiHelper.post(apiUrl, buildCmds);
                postServerLog("maximize build", buildResp);

                // Extract hex data from the txnexport response
                String unsignedHex = null;
                Object parsed2 = parser.parse(buildResp);
                if (parsed2 instanceof JSONArray) {
                    JSONArray arr = (JSONArray) parsed2;
                    for (Object item : arr) {
                        if (!(item instanceof JSONObject)) continue;
                        JSONObject jo = (JSONObject) item;
                        Object resp = jo.get("response");
                        if (resp instanceof JSONObject) {
                            Object data = ((JSONObject) resp).get("data");
                            if (data != null && !data.toString().isEmpty()) {
                                unsignedHex = data.toString();
                            }
                        }
                    }
                }
                if (unsignedHex == null || unsignedHex.isEmpty()) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("No unsigned tx data from server"); });
                    return;
                }
                if (cancelled.get()) return;

                // Step 2: sign locally with tree key
                postProgress("Signing transaction...");
                int selectedUse = secureRandom.nextInt(kd.treeKey.getMaxUses());
                kd.treeKey.setUses(selectedUse);
                String signedHex = signTransactionLocally(unsignedHex, kd.treeKey);
                if (signedHex == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Local signing failed"); });
                    return;
                }
                if (cancelled.get()) return;

                // Step 3: broadcast signed transaction via postfrom
                postProgress("Broadcasting transaction...");
                String postResp = ApiHelper.post(apiUrl, "postfrom data:" + signedHex);
                postServerLog("postfrom maximize", postResp);

                boolean success = false;
                Object parsed3 = parser.parse(postResp);
                if (parsed3 instanceof JSONObject) {
                    Object st = ((JSONObject) parsed3).get("status");
                    success = "true".equals(String.valueOf(st));
                }

                if (success) {
                    mainHandler.post(() -> { if (callback != null) callback.onTransactionCreated("maximize_ok"); });
                } else {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Broadcast failed: " + postResp); });
                }
            } catch (Exception e) {
                Log.e(TAG, "sendMaximizeStake error: " + e.getMessage());
                mainHandler.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    /**
     * Cancels a Maximize bond using local signing.
     *
     * The input coin lives at the Maximize bond address, so we must provide
     * that address's script (fetched via "scripts address:...") in txnscript,
     * NOT the user's RETURN SIGNEDBY() script.
     *
     * Flow:
     * 1. scripts address:<bondAddress> — get the bond contract script
     * 2. txncreate → txninput → txnoutput(storestate:false) → txnscript(bondScript + userScript) → txnmmr → txnexport → txndelete
     * 3. Sign exported hex locally with tree key
     * 4. postfrom data:<signedHex>
     *
     * Cancel is only valid when coin @COINAGE is between 3 and 10 blocks.
     */
    public void cancelMaximizeBond(String apiUrlParam, KeyData kd,
                                    String coinId, String amount) {
        cancelCurrentTxTask();
        cancelled.set(false);
        if (apiUrlParam != null && !apiUrlParam.isEmpty()) apiUrl = apiUrlParam;

        currentTxTask = executor.submit(() -> {
            try {
                JSONParser parser = new JSONParser();
                String bondScript = "LET yourkey=PREVSTATE(100) IF SIGNEDBY(yourkey) THEN RETURN TRUE ENDIF LET maxblock=PREVSTATE(101) LET youraddress=PREVSTATE(102) LET maxcoinage=PREVSTATE(104) LET yourrate=PREVSTATE(105) LET fcfinish=STATE(1) LET fcpayout=STATE(2) LET fcmilli=STATE(3) LET fccoinage=STATE(4) LET rate=STATE(5) ASSERT yourrate EQ rate ASSERT fcpayout EQ youraddress ASSERT fcfinish LTE maxblock ASSERT fccoinage LTE maxcoinage LET fcaddress=0xEA8823992AB3CEBBA855D68006F0D05B0C4838FE55885375837D90F98954FA13 LET fullvalue=@AMOUNT*rate RETURN VERIFYOUT(@INPUT fcaddress fullvalue @TOKENID TRUE)";

                // Step 1: register the bond script so the node knows it
                postProgress("Registering bond script...");
                String newscriptResp = ApiHelper.post(apiUrl,
                        "newscript script:\"" + bondScript + "\" trackall:false");
                postServerLog("newscript bond", newscriptResp);
                if (cancelled.get()) return;

                // Step 2: build unsigned cancel transaction
                postProgress("Building cancel transaction...");
                String txId = "maxcancel_" + System.currentTimeMillis();
                String buildCmds =
                    "txncreate id:" + txId + ";" +
                    "txninput id:" + txId + " coinid:" + coinId + ";" +
                    "txnoutput id:" + txId +
                        " amount:" + amount +
                        " address:" + kd.miniAddress +
                        " storestate:false;" +
                    "txnscript id:" + txId + " scripts:{\"" + bondScript + "\":\"\"};" +
                    "txnmmr id:" + txId + ";" +
                    "txnexport id:" + txId + ";" +
                    "txndelete id:" + txId;

                String buildResp = ApiHelper.post(apiUrl, buildCmds);
                postServerLog("maximize cancel build", buildResp);

                // Extract hex from txnexport
                String unsignedHex = null;
                Object parsed2 = parser.parse(buildResp);
                if (parsed2 instanceof JSONArray) {
                    JSONArray arr = (JSONArray) parsed2;
                    for (Object item : arr) {
                        if (!(item instanceof JSONObject)) continue;
                        JSONObject jo = (JSONObject) item;
                        Object resp = jo.get("response");
                        if (resp instanceof JSONObject) {
                            Object data = ((JSONObject) resp).get("data");
                            if (data != null && !data.toString().isEmpty()) {
                                unsignedHex = data.toString();
                            }
                        }
                    }
                }
                if (unsignedHex == null || unsignedHex.isEmpty()) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("No unsigned tx data"); });
                    return;
                }
                if (cancelled.get()) return;

                // Step 3: sign locally
                postProgress("Signing cancel transaction...");
                int selectedUse = secureRandom.nextInt(kd.treeKey.getMaxUses());
                kd.treeKey.setUses(selectedUse);
                String signedHex = signTransactionLocally(unsignedHex, kd.treeKey);
                if (signedHex == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Local signing failed"); });
                    return;
                }
                if (cancelled.get()) return;

                // Step 4: broadcast
                postProgress("Broadcasting cancel...");
                String postResp = ApiHelper.post(apiUrl, "postfrom data:" + signedHex);
                postServerLog("postfrom cancel", postResp);

                boolean success = false;
                Object parsed3 = parser.parse(postResp);
                if (parsed3 instanceof JSONObject) {
                    Object st = ((JSONObject) parsed3).get("status");
                    success = "true".equals(String.valueOf(st));
                }

                if (success) {
                    mainHandler.post(() -> { if (callback != null) callback.onTransactionCreated("cancel_ok"); });
                } else {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Cancel broadcast failed: " + postResp); });
                }
            } catch (Exception e) {
                Log.e(TAG, "cancelMaximizeBond error: " + e.getMessage());
                mainHandler.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
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
