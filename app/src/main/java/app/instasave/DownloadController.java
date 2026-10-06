package app.instasave;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** Performs asynchronous saves and reports their aggregate outcome. */
final class DownloadController {
    interface Listener {
        void onFinished(boolean failed, String failureMessage);
    }

    private final Activity activity;
    private final Executor saveExecutor;
    private final Listener listener;
    private final Set<Long> activeIds = new HashSet<>();
    private int unfinished;
    private boolean failed;

    DownloadController(Activity activity, Executor saveExecutor, Listener listener) {
        this.activity = activity;
        this.saveExecutor = saveExecutor;
        this.listener = listener;
    }

    Map<MediaResolver.MediaItem, Long> start(List<MediaResolver.MediaItem> items) {
        unfinished = items.size();
        failed = false;
        activeIds.clear();
        Map<MediaResolver.MediaItem, Long> ids = new HashMap<>();
        for (MediaResolver.MediaItem item : items) {
            long id = enqueue(item);
            if (id >= 0) ids.put(item, id);
        }
        return ids;
    }

    boolean handleCompleted(long id) {
        if (!activeIds.remove(id)) return false;
        finish(downloadSucceeded(id), null);
        return true;
    }

    private long enqueue(MediaResolver.MediaItem item) {
        if ("photo".equals(item.type)) {
            savePhotoAsJpeg(item);
            return -1L;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.downloadUrl));
        request.setTitle(item.filename);
        request.setDescription(activity.getString(R.string.download_notification));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instasave/" + item.filename);
        try {
            long id = ((DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE)).enqueue(request);
            activeIds.add(id);
            return id;
        } catch (Exception error) {
            finish(false, errorMessage(error));
            return -1L;
        }
    }

    private void savePhotoAsJpeg(MediaResolver.MediaItem item) {
        saveExecutor.execute(() -> {
            Uri destination = null;
            try {
                Bitmap image = ImageLoader.fetchOriginal(item.downloadUrl);
                if (image == null) throw new IllegalStateException(activity.getString(R.string.error_invalid_image));
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw new IllegalStateException(activity.getString(R.string.error_jpeg_unsupported));
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, jpegFilename(item.filename));
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Instasave");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                destination = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IllegalStateException(activity.getString(R.string.error_jpeg_create));
                try (java.io.OutputStream output = activity.getContentResolver().openOutputStream(destination)) {
                    if (output == null || !image.compress(Bitmap.CompressFormat.JPEG, 100, output)) throw new IllegalStateException(activity.getString(R.string.error_jpeg_convert));
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                activity.getContentResolver().update(destination, values, null, null);
                activity.runOnUiThread(() -> finish(true, null));
            } catch (Exception error) {
                if (destination != null) activity.getContentResolver().delete(destination, null, null);
                activity.runOnUiThread(() -> finish(false, errorMessage(error)));
            }
        });
    }

    private boolean downloadSucceeded(long id) {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            return cursor != null && cursor.moveToFirst()
                    && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL;
        } catch (Exception ignored) { return false; }
    }

    private void finish(boolean succeeded, String failureMessage) {
        if (unfinished <= 0) return;
        if (!succeeded) failed = true;
        unfinished--;
        if (unfinished == 0) listener.onFinished(failed, failureMessage);
    }

    private static String jpegFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename) + ".jpg";
    }

    private String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? activity.getString(R.string.error_generic) : message;
    }
}
