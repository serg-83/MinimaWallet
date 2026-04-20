package com.example.minimawallet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the publicsessionid (UID) from a Minima Public MDS host page
 * and builds the full API URL for subsequent commands.
 */
public class UidResolver {

    private static final int TIMEOUT_MS = 15_000;
    private static final Pattern SESSION_ID_PATTERN =
            Pattern.compile("publicsessionid\\s*=\\s*\"([^\"]+)\"");
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onSuccess(String apiUrl);
        void onError(String message);
    }

    /**
     * Resolves the full API URL from a host name.
     * Makes a GET request to https://{host}/ and parses the publicsessionid from the HTML.
     * Runs on a background thread; callback is invoked on the same background thread.
     */
    public static void resolveApiUrl(String host, Callback callback) {
        executor.execute(() -> {
            try {
                String result = resolveSync(host);
                callback.onSuccess(result);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    /**
     * Synchronous version — call from a background thread only.
     * @return full API URL like https://host/mdscommand_/cmd?uid=0x...
     */
    public static String resolveSync(String host) throws Exception {
        String baseUrl = "https://" + host + "/";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            Matcher matcher = SESSION_ID_PATTERN.matcher(sb.toString());
            if (!matcher.find()) {
                throw new Exception("UID not found on server page");
            }

            String sessionId = matcher.group(1);
            return "https://" + host + "/mdscommand_/cmd?uid=" + sessionId;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
