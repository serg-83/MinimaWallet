package com.example.minimawallet;

import android.content.Context;

import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed wrapper over {@link ApiHelper}: one method per MEG endpoint used by the wallet.
 * See /storage/emulated/0/Download/termux/qw/meg_replacement_commands.md for the
 * endpoint contract. All methods are synchronous and must be called from a background thread.
 */
public final class MegApi {

    private MegApi() {}

    /** GET-equivalent for current chain head. Returns the parsed "response" object. */
    public static JSONObject block(Context ctx) throws Exception {
        JSONObject obj = ApiHelper.call(ctx, "block", new HashMap<>());
        return responseObject(obj);
    }

    /** Convenience: extracts the "block" field as a long. */
    public static long blockNumber(Context ctx) throws Exception {
        JSONObject resp = block(ctx);
        if (resp == null) return -1;
        Object b = resp.get("block");
        return b != null ? Long.parseLong(b.toString()) : -1;
    }

    /** /wallet/balance — returns the response as-is (server may return array or object). */
    public static Object balance(Context ctx, String address) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("address", address);
        form.put("confirmations", "1");
        form.put("coinlist", "true");
        form.put("tokendetails", "true");
        JSONObject obj = ApiHelper.call(ctx, "balance", form);
        return obj.get("response");
    }

    /**
     * /wallet/listcoins. state and tokenid are optional — pass null/empty to omit.
     * Returns the "response" JSONArray of coins.
     */
    public static JSONArray listcoins(Context ctx, String address, String state, String tokenid) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("address", address);
        if (state != null && !state.isEmpty()) form.put("state", state);
        if (tokenid != null && !tokenid.isEmpty()) form.put("tokenid", tokenid);
        JSONObject obj = ApiHelper.call(ctx, "listcoins", form);
        Object r = obj.get("response");
        if (r instanceof JSONArray) return (JSONArray) r;
        if (r instanceof JSONObject) {
            Object coins = ((JSONObject) r).get("coins");
            if (coins instanceof JSONArray) return (JSONArray) coins;
        }
        return new JSONArray();
    }

    /** Result of local coin selection: the chosen coin ids and the change to return to sender. */
    public static final class CoinSelection {
        public final JSONArray inputCoinIds = new JSONArray(); // list of "0x.." coinid strings
        public MiniNumber total = MiniNumber.ZERO;             // sum of selected coins
        public MiniNumber change = MiniNumber.ZERO;            // total - target (>= 0)
    }

    /**
     * Selects spendable coins from {@code address} (optionally for a given token) whose summed
     * amount covers {@code target}. Uses /wallet/listcoins under the hood. Throws if the address
     * does not hold enough funds. The caller is responsible for building inputs/outputs and a
     * change output of {@code change} back to the sender.
     */
    public static CoinSelection selectCoins(Context ctx, String address, String tokenid,
                                            MiniNumber target) throws Exception {
        String tid = (tokenid == null || tokenid.isEmpty()) ? "0x00" : tokenid;
        JSONArray coins = listcoins(ctx, address, null, tid);
        CoinSelection sel = new CoinSelection();
        for (Object item : coins) {
            if (!(item instanceof JSONObject)) continue;
            JSONObject c = (JSONObject) item;
            Object cid = c.get("coinid");
            Object amt = c.get("amount");
            if (cid == null || amt == null) continue;
            sel.inputCoinIds.add(cid.toString());
            sel.total = sel.total.add(new MiniNumber(amt.toString()));
            if (sel.total.isMoreEqual(target)) break;
        }
        if (sel.total.isLess(target)) {
            throw new ApiHelper.MegException(
                    "Insufficient funds: have " + sel.total.toString() + ", need " + target.toString());
        }
        sel.change = sel.total.sub(target);
        return sel;
    }

    /**
     * /wallet/unsignedtxn — simple transfer flow. Returns the hex string of the unsigned tx
     * (extracted from response.data or response.txn).
     */
    public static String unsignedTxn(Context ctx, String fromAddress, String toAddress,
                                     String amount, String tokenid, String script) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("fromaddress", fromAddress);
        form.put("toaddress", toAddress);
        form.put("amount", amount);
        if (tokenid != null && !tokenid.isEmpty()) form.put("tokenid", tokenid);
        if (script != null && !script.isEmpty()) form.put("script", script);
        JSONObject obj = ApiHelper.call(ctx, "unsignedtxn", form);
        return extractHex(obj);
    }

    /**
     * /wallet/rawtxn — custom MEG endpoint ("rawtxnfrom"). Contract verified against the live
     * server (minimask.org:8888):
     *   inputs:  JSONArray of coinid STRINGS, e.g. ["0xCOINID", ..].
     *   outputs: JSONArray of {"address":"0x..","amount":".."[,"tokenid":"0x00"]}.
     *   scripts: JSONArray of script-source STRINGS, e.g. ["RETURN ..", ..] — REQUIRED.
     *   state:   optional JSONObject {"port":"value"}.
     * NOTE: it does NOT accept input objects with "script"/"address", nor a "scripts" object,
     * nor "storestate" on outputs — those shapes trigger a server-side ClassCastException.
     * Returns the unsigned hex (response.data).
     */
    public static String rawTxn(Context ctx, JSONArray inputCoinIds, JSONArray outputs,
                                JSONArray scripts, JSONObject state) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        if (inputCoinIds != null) form.put("inputs",  inputCoinIds.toJSONString());
        if (outputs != null)      form.put("outputs", outputs.toJSONString());
        if (scripts != null)      form.put("scripts", scripts.toJSONString());
        if (state != null)        form.put("state",   state.toJSONString());
        String raw = ApiHelper.callRaw(ctx, "rawtxn", form);
        lastRawTxnResponse = raw;
        Object parsed;
        try {
            parsed = new org.minima.utils.json.parser.JSONParser().parse(raw);
        } catch (Exception pe) {
            throw new ApiHelper.MegException("rawtxn parse failed: " + raw);
        }
        if (!(parsed instanceof JSONObject)) {
            throw new ApiHelper.MegException("rawtxn unexpected response: " + raw);
        }
        JSONObject obj = (JSONObject) parsed;
        Object status = obj.get("status");
        if (status != null && "false".equalsIgnoreCase(String.valueOf(status))) {
            Object msg = obj.get("message");
            if (msg == null) msg = obj.get("error");
            throw new ApiHelper.MegException(msg != null ? msg.toString() : raw);
        }
        return extractHex(obj);
    }

    /** Raw body of the most recent rawtxn call, for diagnostics in LogFragment. */
    public static volatile String lastRawTxnResponse;

    /** /wallet/posttxn — broadcast a signed tx hex. Returns the parsed top-level object. */
    public static JSONObject postTxn(Context ctx, String dataHex) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("data", dataHex);
        return ApiHelper.call(ctx, "posttxn", form);
    }

    /** /wallet/signtxn — server-side signing (kept for completeness; the app signs locally). */
    public static JSONObject signTxn(Context ctx, String dataHex, String privateKey, int keyUses, boolean post) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("data", dataHex);
        form.put("privatekey", privateKey);
        form.put("keyuses", String.valueOf(keyUses));
        form.put("post", String.valueOf(post));
        return ApiHelper.call(ctx, "signtxn", form);
    }

    /** /wallet/consolidate — collapse coins at an address. */
    public static JSONObject consolidate(Context ctx, String fromAddress, String privateKey,
                                         String script, int maxCoins, boolean mine) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("fromaddress", fromAddress);
        form.put("privatekey", privateKey);
        if (script != null && !script.isEmpty()) form.put("script", script);
        form.put("maxcoins", String.valueOf(maxCoins));
        form.put("mine", String.valueOf(mine));
        return ApiHelper.call(ctx, "consolidate", form);
    }

    /** /wallet/checkaddress */
    public static JSONObject checkAddress(Context ctx, String address) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("address", address);
        return ApiHelper.call(ctx, "checkaddress", form);
    }

    // ---------- helpers ----------

    private static JSONObject responseObject(JSONObject top) {
        Object r = top.get("response");
        return r instanceof JSONObject ? (JSONObject) r : null;
    }

    /** MEG sometimes returns the unsigned hex as response.data, response.txn, or response.hex. */
    private static String extractHex(JSONObject top) {
        Object r = top.get("response");
        if (!(r instanceof JSONObject)) return null;
        JSONObject resp = (JSONObject) r;
        for (String key : new String[] {"data", "txn", "hex", "transaction"}) {
            Object v = resp.get(key);
            if (v != null && !v.toString().isEmpty()) return v.toString();
        }
        return null;
    }
}
