package app.instasave;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** Performs asynchronous saves and reports their aggregate outcome. */
final class DownloadController {
    /** Where every file is saved, inside the public Download folder. */
    static final String FOLDER = "Instasave";

    interface Listener {
        void onFinished(boolean failed, String failureMessage);
    }

    private final Activity activity;
    private final Executor saveExecutor;
    private final Listener listener;
    private final Set<Long> activeIds = new HashSet<>();
    private int unfinished;
    private boolean failed;
    /** The first specific reason a file failed; later failures don't replace it. */
    private String failureMessage;

    DownloadController(Activity activity, Executor saveExecutor, Listener listener) {
        this.activity = activity;
        this.saveExecutor = saveExecutor;
        this.listener = listener;
    }

    Map<MediaResolver.MediaItem, Long> start(List<MediaResolver.MediaItem> items) {
        unfinished = items.size();
        failed = false;
        failureMessage = null;
        activeIds.clear();
        Map<MediaResolver.MediaItem, Long> ids = new HashMap<>();
        for (MediaResolver.MediaItem item : items) {
            long id = enqueue(item);
            if (id >= 0) ids.put(item, id);
        }
        return ids;
    }

    /**
     * Below Android 10 the public Download folder is written to directly, which needs the storage
     * permission; from Android 10 on, MediaStore and DownloadManager need no permission.
     */
    static boolean needsStoragePermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /** The saved photo on Android 9 and earlier, where files are written by path. */
    static File legacyFile(String filename) {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER + "/" + filename);
    }

    boolean handleCompleted(long id) {
        if (!activeIds.remove(id)) return false;
        finish(downloadSucceeded(id), null);
        return true;
    }

    @SuppressWarnings("deprecation")
    private long enqueue(MediaResolver.MediaItem item) {
        // The activities ask for the permission first; this only covers a denial.
        if (needsStoragePermission(activity)) {
            finish(false, activity.getString(R.string.error_storage_permission));
            return -1L;
        }
        if ("photo".equals(item.type)) {
            savePhotoAsJpeg(item);
            return -1L;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.downloadUrl));
        request.setTitle(item.filename);
        request.setDescription(activity.getString(R.string.download_notification));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FOLDER + "/" + item.filename);
        // Without it, videos saved below Android 10 don't reach the gallery; later versions ignore it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) request.allowScanningByMediaScanner();
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
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { savePhotoToFile(image, jpegFilename(item.filename)); return; }
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, jpegFilename(item.filename));
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                destination = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IllegalStateException(activity.getString(R.string.error_jpeg_create));
                try (OutputStream output = activity.getContentResolver().openOutputStream(destination)) {
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

    /**
     * Android 9 and earlier: MediaStore can't place files in Download there, so the photo is
     * written by path and then handed to the media scanner, which adds it to the gallery and to
     * MediaStore, where the history looks for it. The save counts as done once the scan is over.
     */
    private void savePhotoToFile(Bitmap image, String filename) throws Exception {
        File file = legacyFile(filename);
        File folder = file.getParentFile();
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) throw new IllegalStateException(activity.getString(R.string.error_jpeg_create));
        try (OutputStream output = new FileOutputStream(file)) {
            if (!image.compress(Bitmap.CompressFormat.JPEG, 100, output)) throw new IllegalStateException(activity.getString(R.string.error_jpeg_convert));
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw error;
        }
        MediaScannerConnection.scanFile(activity.getApplicationContext(), new String[]{file.getAbsolutePath()},
                new String[]{"image/jpeg"}, (path, uri) -> activity.runOnUiThread(() -> finish(true, null)));
    }

    private boolean downloadSucceeded(long id) {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            return cursor != null && cursor.moveToFirst()
                    && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL;
        } catch (Exception ignored) { return false; }
    }

    private void finish(boolean succeeded, String message) {
        if (unfinished <= 0) return;
        if (!succeeded) {
            failed = true;
            if (failureMessage == null) failureMessage = message;
        }
        unfinished--;
        if (unfinished == 0) listener.onFinished(failed, failureMessage);
    }

    /** The name a photo is saved under; the history stores it to find the file again. */
    static String jpegFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename) + ".jpg";
    }

    private String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? activity.getString(R.string.error_generic) : message;
    }
}
