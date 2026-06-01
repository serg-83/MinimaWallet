package com.example.minimawallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.minima.utils.json.JSONObject;
import org.minima.utils.json.parser.JSONParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/**
 * Low-level MEG REST client. All MEG endpoints live under https://<host>/wallet/.
 * Auth is HTTP Basic with the hard-coded apicaller credentials.
 * Bodies are application/x-www-form-urlencoded. Must be called from a background thread.
 */
public class ApiHelper {

    private static final int TIMEOUT_MS = 15_000;
    private static final String DEFAULT_HOST = "minimask.org:8888";
    private static final String BASIC_AUTH =
            "Basic " + Base64.encodeToString("apicaller:apicaller".getBytes(), Base64.NO_WRAP);

    /** Builds the MEG base URL from SharedPreferences ("api_host"). */
    public static String baseUrl(Context ctx) {
        String host = DEFAULT_HOST;
        if (ctx != null) {
            SharedPreferences prefs = ctx.getSharedPreferences("app_settings", 0);
            host = prefs.getString("api_host", DEFAULT_HOST);
            if (host == null || host.isEmpty()) host = DEFAULT_HOST;
        }
        host = host.replaceFirst("^https?://", "");
        int slash = host.indexOf('/');
        if (slash > 0) host = host.substring(0, slash);
        return "https://" + host + "/wallet/";
    }

    /** Calls /wallet/<endpoint> with the given form fields, returns the parsed top-level JSON object. */
    public static JSONObject call(Context ctx, String endpoint, Map<String, String> form) throws Exception {
        String body = encodeForm(form);
        String raw = postRaw(baseUrl(ctx) + endpoint, body);
        Object parsed = new JSONParser().parse(raw);
        if (!(parsed instanceof JSONObject)) {
            throw new MegException("Unexpected response: " + raw);
        }
        JSONObject obj = (JSONObject) parsed;
        Object status = obj.get("status");
        if (status != null && "false".equalsIgnoreCase(String.valueOf(status))) {
            Object msg = obj.get("message");
            if (msg == null) msg = obj.get("error");
            throw new MegException(msg != null ? msg.toString() : raw);
        }
        return obj;
    }

    /** Same as {@link #call} but returns the unparsed body (for diagnostics / LogFragment). */
    public static String callRaw(Context ctx, String endpoint, Map<String, String> form) throws Exception {
        return postRaw(baseUrl(ctx) + endpoint, encodeForm(form));
    }

    private static String encodeForm(Map<String, String> form) throws Exception {
        if (form == null || form.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static String postRaw(String fullUrl, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Authorization", BASIC_AUTH);
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
            }
            String responseBody = sb.toString();
            if (code < 200 || code >= 300) {
                throw new MegException("HTTP " + code + ": " + responseBody);
            }
            return responseBody;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Thrown when MEG returns status:false or a non-2xx HTTP code. */
    public static class MegException extends Exception {
        public MegException(String msg) { super(msg); }
    }
}
