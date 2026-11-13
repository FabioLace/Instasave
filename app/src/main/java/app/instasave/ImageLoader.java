package app.instasave;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * Decodes remote and local thumbnails off the UI thread. Each screen creates one, so its memory
 * cache is shared by that screen's components, not across screens.
 */
final class ImageLoader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
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

    /**
     * Shows a remote thumbnail in target, decoded on executor; onShown, if any, runs once it's on
     * screen. The view's tag remembers the latest request, so a slow answer never replaces a newer
     * image in the same view.
     */
    void loadInto(ImageView target, String url, int size, Executor executor, Runnable onShown) {
        load(target, url, () -> remoteThumbnail(url, size, size), executor, onShown);
    }

    /** Like the remote variant, for a file saved on the device. */
    void loadInto(ImageView target, Uri uri, int size, Executor executor, Runnable onShown) {
        load(target, uri.toString(), () -> localThumbnail(uri, size, size), executor, onShown);
    }

    private static void load(ImageView target, String key, Callable<Bitmap> decode, Executor executor, Runnable onShown) {
        target.setTag(key);
        executor.execute(() -> {
            Bitmap image;
            try { image = decode.call(); } catch (Exception ignored) { return; }
            if (image == null) return;
            MAIN.post(() -> {
                if (!key.equals(target.getTag())) return;
                target.setImageBitmap(image);
                target.setVisibility(View.VISIBLE);
                UiMotion.fadeIn(target);
                if (onShown != null) onShown.run();
            });
        });
    }

    static Bitmap fetchOriginal(String url) throws Exception {
        HttpURLConnection connection = Http.open(url, 20_000);
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
        HttpURLConnection connection = Http.open(url, timeoutMs);
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
