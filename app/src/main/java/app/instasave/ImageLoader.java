package app.instasave;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Decodes remote and local thumbnails off the UI thread, with a shared memory cache. */
final class ImageLoader {
    private final ContentResolver contentResolver;
    private final LruCache<String, Bitmap> cache = createCache();

    ImageLoader(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
    }

    Bitmap remoteThumbnail(String url, int width, int height) throws Exception {
        Bitmap cached = cached(url, width, height);
        if (cached != null) return cached;
        byte[] encoded = fetchBytes(url, 10_000);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
        return cache(url, BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options));
    }

    Bitmap localThumbnail(Uri uri, int width, int height) throws Exception {
        String key = uri.toString();
        Bitmap cached = cached(key, width, height);
        if (cached != null) return cached;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = contentResolver.openInputStream(uri)) {
            if (input == null) return null;
            BitmapFactory.decodeStream(input, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
        try (InputStream input = contentResolver.openInputStream(uri)) {
            return input == null ? null : cache(key, BitmapFactory.decodeStream(input, null, options));
        }
    }

    static Bitmap fetchOriginal(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
        try (InputStream stream = connection.getInputStream()) {
            return BitmapFactory.decodeStream(stream);
        } finally { connection.disconnect(); }
    }

    private Bitmap cached(String key, int width, int height) {
        synchronized (cache) {
            Bitmap bitmap = cache.get(key);
            return bitmap != null && bitmap.getWidth() >= width && bitmap.getHeight() >= height ? bitmap : null;
        }
    }

    private Bitmap cache(String key, Bitmap bitmap) {
        if (bitmap == null) return null;
        synchronized (cache) { cache.put(key, bitmap); }
        return bitmap;
    }

    private static byte[] fetchBytes(String url, int timeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
        try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            for (int count; (count = stream.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally { connection.disconnect(); }
    }

    private static int sampleSize(int width, int height, int requestedWidth, int requestedHeight) {
        int sample = 1;
        while (width / (sample * 2) >= requestedWidth && height / (sample * 2) >= requestedHeight) sample *= 2;
        return sample;
    }

    private static LruCache<String, Bitmap> createCache() {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        return new LruCache<String, Bitmap>(Math.min(16 * 1024, maxMemoryKb / 8)) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return Math.max(1, bitmap.getByteCount() / 1024); }
        };
    }
}
