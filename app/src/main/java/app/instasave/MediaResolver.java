package app.instasave;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Resolves an Instagram permalink through an app-owned, authorized service. */
final class MediaResolver {
    static final class Result {
        final String downloadUrl;
        final String filename;
        final String type;

        Result(String downloadUrl, String filename, String type) {
            this.downloadUrl = downloadUrl;
            this.filename = filename;
            this.type = type;
        }
    }

    Result resolve(String sourceUrl, String requestedType) throws Exception {
        if (isDirectMediaUrl(sourceUrl)) {
            return new Result(sourceUrl, "instasave_" + System.currentTimeMillis() + extensionFor(sourceUrl), requestedType);
        }
        if (BuildConfig.RESOLVER_BASE_URL.isEmpty()) {
            throw new IllegalStateException("Collega prima un resolver Meta autorizzato nelle impostazioni di build.");
        }

        String encodedUrl = URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8.name());
        String endpoint = BuildConfig.RESOLVER_BASE_URL + "?url=" + encodedUrl + "&type=" + requestedType;
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(endpoint).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("GET");
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("Il contenuto non è disponibile o non hai l'autorizzazione.");
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }
        JSONObject response = new JSONObject(body.toString());
        String downloadUrl = response.getString("downloadUrl");
        String filename = response.optString("filename", "instasave_" + System.currentTimeMillis() + extensionFor(downloadUrl));
        return new Result(downloadUrl, filename, response.optString("type", requestedType));
    }

    private static boolean isDirectMediaUrl(String url) {
        String clean = url.toLowerCase().split("\\?")[0];
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") || clean.endsWith(".webp") || clean.endsWith(".mp4");
    }

    private static String extensionFor(String url) {
        String clean = url.toLowerCase().split("\\?")[0];
        int index = clean.lastIndexOf('.');
        return index > clean.lastIndexOf('/') ? clean.substring(index) : ".mp4";
    }
}
