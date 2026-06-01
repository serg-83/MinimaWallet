package com.example.minimawallet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.minima.objects.Address;
import org.minima.objects.Transaction;
import org.minima.objects.Witness;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.objects.keys.Signature;
import org.minima.objects.keys.TreeKey;
import org.minima.database.userprefs.txndb.TxnRow;
import org.minima.utils.BIP39;
import org.minima.utils.Crypto;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class KeyGenerator {
    private static final String TAG = "KeyGenerator";
    private static final SecureRandom secureRandom = new SecureRandom();

    private final Context appContext;
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

    /** Preferred constructor — keeps a Context reference for MEG calls. */
    public KeyGenerator(Context context, KeyGeneratorCallback callback) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.callback = callback;
    }

    /** Back-compat constructor: tries to obtain a context from the callback if it is a Fragment/Activity. */
    public KeyGenerator(KeyGeneratorCallback callback) {
        this(contextFromCallback(callback), callback);
    }

    private static Context contextFromCallback(KeyGeneratorCallback cb) {
        try {
            if (cb instanceof androidx.fragment.app.Fragment) {
                return ((androidx.fragment.app.Fragment) cb).getContext();
            }
            if (cb instanceof android.content.Context) {
                return (android.content.Context) cb;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void initialize(String ignoredApiUrl, String phrase, int addressNumber) {
        cancelCurrentKeyTask();
        cancelled.set(false);

        currentKeyTask = executor.submit(() -> {
            try {
                postProgress("Setting up API...");

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

    /** Fetches the current block number from MEG. */
    public void getBlockNumber(String ignoredApiUrl) {
        executor.submit(() -> {
            try {
                long blockNum = MegApi.blockNumber(appContext);
                postServerLog("block", "block=" + blockNum);
                if (blockNum >= 0) {
                    mainHandler.post(() -> {
                        if (callback instanceof FutureSendFragment) {
                            ((FutureSendFragment) callback).onBlockLoaded(blockNum);
                        } else if (callback instanceof MaximizeStakeFragment) {
                            ((MaximizeStakeFragment) callback).onBlockLoaded(blockNum);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "getBlockNumber error: " + e.getMessage());
            }
        });
    }

    /**
     * Sends a FutureCash time-locked transaction via MEG /wallet/rawtxn + /wallet/posttxn.
     * State ports: 1=targetBlock, 2=recipient hex, 3=ms, 4=coinage.
     */
    public void sendFutureTransaction(String ignoredApiUrl, KeyData kd,
                                      String amount, String tokenId,
                                      String script, String stateJson) {
        cancelCurrentTxTask();
        cancelled.set(false);

        final String targetBlock;
        final String recipientAddress;
        final String targetMs;
        final String targetCoinage;
        try {
            Object pre = new org.minima.utils.json.parser.JSONParser().parse(stateJson);
            if (!(pre instanceof JSONObject)) {
                mainHandler.post(() -> { if (callback != null) callback.onError("Invalid stateJson"); });
                return;
            }
            JSONObject jo = (JSONObject) pre;
            targetBlock      = jo.get("1").toString();
            recipientAddress = jo.get("2").toString();
            targetMs         = jo.get("3").toString();
            targetCoinage    = jo.get("4").toString();
        } catch (Exception e) {
            mainHandler.post(() -> { if (callback != null) callback.onError("State parse error: " + e.getMessage()); });
            return;
        }

        currentTxTask = executor.submit(() -> {
            try {
                String tid = (tokenId != null && !tokenId.isEmpty()) ? tokenId : "0x00";

                postProgress("Getting script address...");
                String scriptAddress = ScriptAddress.hex(script);
                postServerLog("scriptaddress (local)", scriptAddress);
                if (cancelled.get()) return;

                postProgress("Selecting coins...");
                String addressScript = "RETURN SIGNEDBY(" + kd.treeKey.getPublicKey().to0xString() + ")";

                MegApi.CoinSelection sel = MegApi.selectCoins(
                        appContext, kd.miniAddress, tid, new MiniNumber(amount));

                JSONArray inputs = new JSONArray();
                for (Object cid : sel.inputCoinIds) {
                    inputs.add(cid.toString());
                }

                JSONArray scripts = new JSONArray();
                scripts.add(addressScript);

                postProgress("Building unsigned transaction...");
                JSONArray outputs = new JSONArray();
                JSONObject out = new JSONObject();
                out.put("address", scriptAddress);
                out.put("amount", amount);
                out.put("tokenid", tid);
                outputs.add(out);

                if (sel.change.isMore(MiniNumber.ZERO)) {
                    JSONObject change = new JSONObject();
                    change.put("address", kd.miniAddress);
                    change.put("amount", sel.change.toString());
                    change.put("tokenid", tid);
                    outputs.add(change);
                }

                JSONObject state = new JSONObject();
                state.put("1", targetBlock);
                state.put("2", recipientAddress);
                state.put("3", targetMs);
                state.put("4", targetCoinage);

                String unsignedHex = MegApi.rawTxn(appContext, inputs, outputs, scripts, state);
                postServerLog("rawtxn future", String.valueOf(unsignedHex));
                if (unsignedHex == null || unsignedHex.isEmpty()) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("No unsigned tx data from server"); });
                    return;
                }
                if (cancelled.get()) return;

                postProgress("Signing transaction...");
                int selectedUse = secureRandom.nextInt(kd.treeKey.getMaxUses());
                kd.treeKey.setUses(selectedUse);
                String signedHex = signTransactionLocally(unsignedHex, kd.treeKey);
                if (signedHex == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Local signing failed"); });
                    return;
                }
                if (cancelled.get()) return;

                postProgress("Broadcasting transaction...");
                JSONObject postResp = MegApi.postTxn(appContext, signedHex);
                postServerLog("posttxn future", postResp.toJSONString());

                boolean success = "true".equals(String.valueOf(postResp.get("status")));
                if (success) {
                    mainHandler.post(() -> { if (callback != null) callback.onTransactionCreated("future_ok"); });
                } else {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Broadcast failed: " + postResp.toJSONString()); });
                }

            } catch (Exception e) {
                Log.e(TAG, "sendFutureTransaction error: " + e.getMessage());
                mainHandler.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    /** Simple transfer: MEG /wallet/unsignedtxn → local sign → /wallet/posttxn. */
    public void sendTransaction(String ignoredApiUrl, String fromAddress, String toAddress,
                                String amount, String tokenId, String script, TreeKey treekey) {
        cancelCurrentTxTask();
        cancelled.set(false);

        currentTxTask = executor.submit(() -> {
            try {
                postProgress("Preparing transaction...");

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
     * Maximize staking via MEG /wallet/rawtxn (state 100..105) + posttxn.
     */
    public void sendMaximizeStake(String ignoredApiUrl, KeyData kd,
                                   String amount, int timeframeMonths, double rate, long currentBlock) {
        cancelCurrentTxTask();
        cancelled.set(false);

        currentTxTask = executor.submit(() -> {
            try {
                int DAY_OF_BLOCKS = 1728;
                long days = (long) timeframeMonths * 30;
                long maxcoinage = days * DAY_OF_BLOCKS + DAY_OF_BLOCKS;
                long maxblock = currentBlock + maxcoinage;

                String pubkey = kd.treeKey.getPublicKey().to0xString();
                String userAddress = kd.address;
                String bondAddress = "MxG0861MPQ3ZQTM4GFTZ0UJA74Y48A4GDPYM1NTVKDTU0B34BFDV86G5A0PD21N";

                postProgress("Selecting coins...");
                String addressScript = "RETURN SIGNEDBY(" + pubkey + ")";

                MegApi.CoinSelection sel = MegApi.selectCoins(
                        appContext, kd.miniAddress, "0x00", new MiniNumber(amount));

                JSONArray inputs = new JSONArray();
                for (Object cid : sel.inputCoinIds) {
                    inputs.add(cid.toString());
                }

                JSONArray scripts = new JSONArray();
                scripts.add(addressScript);

                postProgress("Building staking transaction...");
                JSONArray outputs = new JSONArray();
                JSONObject out = new JSONObject();
                out.put("address", bondAddress);
                out.put("amount", amount);
                out.put("tokenid", "0x00");
                outputs.add(out);

                if (sel.change.isMore(MiniNumber.ZERO)) {
                    JSONObject change = new JSONObject();
                    change.put("address", kd.miniAddress);
                    change.put("amount", sel.change.toString());
                    change.put("tokenid", "0x00");
                    outputs.add(change);
                }

                JSONObject state = new JSONObject();
                state.put("100", pubkey);
                state.put("101", String.valueOf(maxblock));
                state.put("102", userAddress);
                state.put("104", String.valueOf(maxcoinage));
                state.put("105", String.valueOf(rate));

                String unsignedHex = MegApi.rawTxn(appContext, inputs, outputs, scripts, state);
                postServerLog("rawtxn maximize", String.valueOf(unsignedHex));
                if (unsignedHex == null || unsignedHex.isEmpty()) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("No unsigned tx data from server"); });
                    return;
                }
                if (cancelled.get()) return;

                postProgress("Signing transaction...");
                int selectedUse = secureRandom.nextInt(kd.treeKey.getMaxUses());
                kd.treeKey.setUses(selectedUse);
                String signedHex = signTransactionLocally(unsignedHex, kd.treeKey);
                if (signedHex == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Local signing failed"); });
                    return;
                }
                if (cancelled.get()) return;

                postProgress("Broadcasting transaction...");
                JSONObject postResp = MegApi.postTxn(appContext, signedHex);
                postServerLog("posttxn maximize", postResp.toJSONString());

                boolean success = "true".equals(String.valueOf(postResp.get("status")));
                if (success) {
                    mainHandler.post(() -> { if (callback != null) callback.onTransactionCreated("maximize_ok"); });
                } else {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Broadcast failed: " + postResp.toJSONString()); });
                }
            } catch (Exception e) {
                Log.e(TAG, "sendMaximizeStake error: " + e.getMessage());
                mainHandler.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    /**
     * Cancels a Maximize bond via MEG /wallet/rawtxn + posttxn.
     * The input coin's script is the bond contract; the witness needs the user's RETURN SIGNEDBY() script.
     */
    public void cancelMaximizeBond(String ignoredApiUrl, KeyData kd,
                                    String coinId, String amount) {
        cancelCurrentTxTask();
        cancelled.set(false);

        currentTxTask = executor.submit(() -> {
            try {
                String bondScript = "LET yourkey=PREVSTATE(100) IF SIGNEDBY(yourkey) THEN RETURN TRUE ENDIF LET maxblock=PREVSTATE(101) LET youraddress=PREVSTATE(102) LET maxcoinage=PREVSTATE(104) LET yourrate=PREVSTATE(105) LET fcfinish=STATE(1) LET fcpayout=STATE(2) LET fcmilli=STATE(3) LET fccoinage=STATE(4) LET rate=STATE(5) ASSERT yourrate EQ rate ASSERT fcpayout EQ youraddress ASSERT fcfinish LTE maxblock ASSERT fccoinage LTE maxcoinage LET fcaddress=0xEA8823992AB3CEBBA855D68006F0D05B0C4838FE55885375837D90F98954FA13 LET fullvalue=@AMOUNT*rate RETURN VERIFYOUT(@INPUT fcaddress fullvalue @TOKENID TRUE)";

                postProgress("Building cancel transaction...");

                JSONArray inputs = new JSONArray();
                inputs.add(coinId);

                JSONArray scripts = new JSONArray();
                scripts.add(bondScript);

                JSONArray outputs = new JSONArray();
                JSONObject out = new JSONObject();
                out.put("address", kd.miniAddress);
                out.put("amount", amount);
                outputs.add(out);

                String unsignedHex = MegApi.rawTxn(appContext, inputs, outputs, scripts, null);
                postServerLog("rawtxn cancel", String.valueOf(unsignedHex));
                if (unsignedHex == null || unsignedHex.isEmpty()) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("No unsigned tx data"); });
                    return;
                }
                if (cancelled.get()) return;

                postProgress("Signing cancel transaction...");
                int selectedUse = secureRandom.nextInt(kd.treeKey.getMaxUses());
                kd.treeKey.setUses(selectedUse);
                String signedHex = signTransactionLocally(unsignedHex, kd.treeKey);
                if (signedHex == null) {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Local signing failed"); });
                    return;
                }
                if (cancelled.get()) return;

                postProgress("Broadcasting cancel...");
                JSONObject postResp = MegApi.postTxn(appContext, signedHex);
                postServerLog("posttxn cancel", postResp.toJSONString());

                boolean success = "true".equals(String.valueOf(postResp.get("status")));
                if (success) {
                    mainHandler.post(() -> { if (callback != null) callback.onTransactionCreated("cancel_ok"); });
                } else {
                    mainHandler.post(() -> { if (callback != null) callback.onError("Cancel broadcast failed: " + postResp.toJSONString()); });
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
        try {
            Object resp = MegApi.balance(appContext, miniAddress);
            postServerLog("balance address:" + miniAddress, String.valueOf(resp));
            return parseTokens(resp);
        } catch (Exception e) {
            Log.e(TAG, "Balance check error: " + e.getMessage());
            postServerLog("balance address:" + miniAddress, "Error: " + e.getMessage());
            return null;
        }
    }

    private List<TokenBalance> parseTokens(Object respObject) {
        List<TokenBalance> result = new ArrayList<>();
        try {
            JSONArray arr;
            if (respObject instanceof JSONArray) {
                arr = (JSONArray) respObject;
            } else if (respObject instanceof JSONObject) {
                Object coins = ((JSONObject) respObject).get("balance");
                if (!(coins instanceof JSONArray)) coins = ((JSONObject) respObject).get("tokens");
                if (!(coins instanceof JSONArray)) return result;
                arr = (JSONArray) coins;
            } else {
                return result;
            }

            for (Object item : arr) {
                if (!(item instanceof JSONObject)) continue;
                JSONObject obj = (JSONObject) item;

                String tokenId = obj.get("tokenid") != null ? obj.get("tokenid").toString() : "";

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

    /** Asks MEG for an unsigned transaction (replaces the old "createfrom" plain-text command). */
    private String createTransaction(String fromAddress, String toAddress, String amount, String tokenId, String script) {
        if (cancelled.get()) return null;
        try {
            String tid = (tokenId != null && !"0x00".equals(tokenId)) ? tokenId : null;
            String unsigned = MegApi.unsignedTxn(appContext, fromAddress, toAddress, amount, tid, script);
            postServerLog("unsignedtxn amount:" + amount, String.valueOf(unsigned));
            return unsigned;
        } catch (Exception e) {
            Log.e(TAG, "Create transaction error: " + e.getMessage());
            postServerLog("unsignedtxn amount:" + amount, "Error: " + e.getMessage());
            return null;
        }
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
        try {
            postProgress("Broadcasting to network...");
            JSONObject resp = MegApi.postTxn(appContext, signedTransaction);
            postServerLog("posttxn", resp.toJSONString());
            postProgress("Transaction broadcast to network");
            return "true".equals(String.valueOf(resp.get("status")));
        } catch (Exception e) {
            Log.e(TAG, "Send transaction error: " + e.getMessage());
            postServerLog("posttxn", "Error: " + e.getMessage());
            return false;
        }
    }

    public static class TokenBalance {
        public String tokenName;
        public String tokenId;
        public String confirmed;
        public String unconfirmed;
        public String sendable;
        public String imageData;
        public String imageUrl;

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
