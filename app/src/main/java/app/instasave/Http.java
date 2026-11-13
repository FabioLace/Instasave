package app.instasave;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Opens every HTTP connection of the app, with the same User-Agent and connect timeout. */
final class Http {
    private static final String USER_AGENT = "Instasave/1.0 (Android)";
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private Http() { }

    static HttpURLConnection open(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }
}
