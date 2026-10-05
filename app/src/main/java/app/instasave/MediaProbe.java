package app.instasave;

import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Reads optional file metadata without delaying the initial post preview. */
final class MediaProbe {
    private MediaProbe() { }

    static void inspect(MediaResolver.MediaItem item) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(item.downloadUrl).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
            connection.setRequestProperty("Accept-Encoding", "identity");
            boolean imageBounds = "photo".equals(item.type) && (item.width <= 0 || item.height <= 0);
            connection.setRequestMethod(imageBounds ? "GET" : "HEAD");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return;
            long bytes = connection.getContentLengthLong();
            if (bytes > 0) item.sourceSizeBytes = bytes;
            if (imageBounds) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream stream = connection.getInputStream()) {
                    BitmapFactory.decodeStream(stream, null, bounds);
                }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    item.width = bounds.outWidth;
                    item.height = bounds.outHeight;
                }
            }
        } catch (Exception ignored) {
            // Public CDNs may not provide file headers or allow this request.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
