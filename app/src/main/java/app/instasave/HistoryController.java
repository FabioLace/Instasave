package app.instasave;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executor;

/** Renders and manages the persisted download history. */
final class HistoryController {
    interface StatusReporter {
        void show(String message, boolean isError);
        void clear();
    }

    private final Activity activity;
    private final HistoryRepository repository;
    private final ImageLoader imageLoader;
    private final Executor imageExecutor;
    private final Handler uiHandler;
    private final StatusReporter statusReporter;
    private final LinearLayout container;
    private final TextView emptyHistory;
    private final TextView historyToggle;
    private final TextView clearHistoryButton;
    private final Runnable refreshRunnable = this::render;

    HistoryController(Activity activity, HistoryRepository repository, ImageLoader imageLoader,
                      Executor imageExecutor, Handler uiHandler, StatusReporter statusReporter) {
        this.activity = activity;
        this.repository = repository;
        this.imageLoader = imageLoader;
        this.imageExecutor = imageExecutor;
        this.uiHandler = uiHandler;
        this.statusReporter = statusReporter;
        container = activity.findViewById(R.id.historyContainer);
        emptyHistory = activity.findViewById(R.id.emptyHistory);
        View historyHeader = activity.findViewById(R.id.historyHeader);
        historyToggle = activity.findViewById(R.id.historyToggle);
        clearHistoryButton = activity.findViewById(R.id.clearHistoryButton);
        historyHeader.setOnClickListener(v -> setExpanded(!repository.isExpanded()));
        clearHistoryButton.setOnClickListener(v -> confirmClear());
    }

    void render() {
        uiHandler.removeCallbacks(refreshRunnable);
        container.removeAllViews();
        try {
            JSONArray history = repository.entries();
            boolean expanded = repository.isExpanded();
            String countLabel = history.length() == 0 ? "No downloads"
                    : history.length() == 1 ? "1 download" : history.length() + " downloads";
            historyToggle.setText(countLabel + "  " + (expanded ? "▲" : "▼"));
            historyToggle.setContentDescription(expanded ? "Collapse download history" : "Expand download history");
            container.setVisibility(expanded ? View.VISIBLE : View.GONE);
            emptyHistory.setVisibility(expanded && history.length() == 0 ? View.VISIBLE : View.GONE);
            clearHistoryButton.setVisibility(expanded && history.length() > 0 ? View.VISIBLE : View.GONE);
            if (expanded) {
                for (int i = 0; i < history.length(); i++) addItem(history.getJSONObject(i), i);
                scheduleRefresh(history);
            }
        } catch (Exception ignored) {
            emptyHistory.setVisibility(View.VISIBLE);
            clearHistoryButton.setVisibility(View.GONE);
        }
    }

    void dispose() { uiHandler.removeCallbacks(refreshRunnable); }

    private void addItem(JSONObject item, int position) throws Exception {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_history, container, false);
        View itemHeader = row.findViewById(R.id.historyItemHeader);
        LinearLayout filesContainer = row.findViewById(R.id.historyFilesContainer);
        TextView expandIndicator = row.findViewById(R.id.itemExpandIndicator);
        String type = item.optString("type", "auto");
        JSONArray files = HistoryRepository.files(item);
        boolean isCarousel = "carousel".equals(type);
        ((TextView) row.findViewById(R.id.itemIcon)).setText(type.equals("photo") ? "Photo" : type.equals("story") ? "Story" : type.equals("carousel") ? "Multi" : "Video");
        ((TextView) row.findViewById(R.id.itemTitle)).setText(isCarousel ? "Instagram carousel" : "Instagram content");
        String downloadedAt = downloadAge(item.optLong("createdAt", 0L));
        ((TextView) row.findViewById(R.id.itemMeta)).setText(isCarousel
                ? files.length() + (files.length() == 1 ? " file" : " files") + " · " + downloadedAt
                : labelFor(type) + " · " + downloadedAt);
        if (isCarousel && files.length() > 0) {
            expandIndicator.setVisibility(View.VISIBLE);
            addFiles(filesContainer, files);
            itemHeader.setContentDescription("Expand downloaded carousel files");
            itemHeader.setOnClickListener(v -> toggleFiles(itemHeader, filesContainer, expandIndicator));
        } else if (files.length() > 0) {
            JSONObject file = files.optJSONObject(0);
            itemHeader.setContentDescription("Open downloaded " + type);
            itemHeader.setOnClickListener(v -> openFile(file));
        }
        row.findViewById(R.id.removeHistoryItem).setOnClickListener(v -> removeItem(position));
        container.addView(row);
        String preview = item.optString("preview", null);
        if (preview != null && !preview.isEmpty()) loadRemotePreview(row, preview);
    }

    private void addFiles(LinearLayout filesContainer, JSONArray files) {
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            View child = LayoutInflater.from(activity).inflate(R.layout.item_history_file, filesContainer, false);
            String type = file.optString("type", "photo");
            String name = file.optString("name", "Downloaded file");
            ((TextView) child.findViewById(R.id.historyFileName)).setText(name);
            ((TextView) child.findViewById(R.id.historyFileType)).setText("video".equals(type) ? "Video" : "Photo");
            child.setContentDescription("Open " + name);
            child.setOnClickListener(v -> openFile(file));
            filesContainer.addView(child);
            updateFileAvailability(child, file);
        }
    }

    private void toggleFiles(View header, LinearLayout files, TextView indicator) {
        boolean expand = files.getVisibility() != View.VISIBLE;
        files.setVisibility(expand ? View.VISIBLE : View.GONE);
        indicator.setText(expand ? "▲" : "▼");
        header.setContentDescription((expand ? "Collapse" : "Expand") + " downloaded carousel files");
    }

    private void setExpanded(boolean expanded) { repository.setExpanded(expanded); render(); }

    private void confirmClear() {
        new AlertDialog.Builder(activity).setTitle("Clear history?")
                .setMessage("Downloaded files will not be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    repository.clear();
                    render();
                    statusReporter.clear();
                }).show();
    }

    private void removeItem(int position) {
        try {
            repository.remove(position);
            render();
            statusReporter.clear();
        } catch (Exception error) {
            statusReporter.show("Unable to remove the item from history.", true);
        }
    }

    private void scheduleRefresh(JSONArray history) {
        long now = System.currentTimeMillis();
        long nextRefresh = Long.MAX_VALUE;
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            long createdAt = item == null ? 0L : item.optLong("createdAt", 0L);
            if (createdAt > 0L && now - createdAt < 60L * 60L * 1000L) {
                long delay = 60_000L - (Math.max(0L, now - createdAt) % 60_000L);
                nextRefresh = Math.min(nextRefresh, Math.max(1_000L, delay));
            }
        }
        if (nextRefresh != Long.MAX_VALUE) uiHandler.postDelayed(refreshRunnable, nextRefresh);
    }

    private void loadRemotePreview(View row, String imageUrl) {
        ImageView target = row.findViewById(R.id.itemPreview);
        target.setTag(imageUrl);
        imageExecutor.execute(() -> {
            try {
                Bitmap image = imageLoader.remoteThumbnail(imageUrl, dp(42), dp(42));
                if (image != null) activity.runOnUiThread(() -> {
                    if (!imageUrl.equals(target.getTag())) return;
                    target.setImageBitmap(image);
                    target.setVisibility(View.VISIBLE);
                    row.findViewById(R.id.itemIcon).setVisibility(View.GONE);
                });
            } catch (Exception ignored) { }
        });
    }

    private void updateFileAvailability(View row, JSONObject file) {
        imageExecutor.execute(() -> {
            Uri localFile = findLocalFile(file);
            activity.runOnUiThread(() -> {
                TextView type = row.findViewById(R.id.historyFileType);
                ImageView preview = row.findViewById(R.id.historyFilePreview);
                if (localFile == null) {
                    preview.setTag(null);
                    preview.setVisibility(View.GONE);
                    type.setText("");
                    boolean isVideo = "video".equals(file.optString("type", "photo"));
                    type.setCompoundDrawablesWithIntrinsicBounds(isVideo ? R.drawable.ic_video : R.drawable.ic_photo, 0, 0, 0);
                    type.setContentDescription(isVideo ? "Video file" : "Photo file");
                    return;
                }
                type.setCompoundDrawables(null, null, null, null);
                if ("photo".equals(file.optString("type", "photo"))) loadLocalPreview(row, localFile);
            });
        });
    }

    private void loadLocalPreview(View row, Uri imageUri) {
        ImageView target = row.findViewById(R.id.historyFilePreview);
        String imageKey = imageUri.toString();
        target.setTag(imageKey);
        imageExecutor.execute(() -> {
            try {
                Bitmap image = imageLoader.localThumbnail(imageUri, dp(36), dp(36));
                if (image != null) activity.runOnUiThread(() -> {
                    if (!imageKey.equals(target.getTag())) return;
                    target.setImageBitmap(image);
                    target.setVisibility(View.VISIBLE);
                });
            } catch (Exception ignored) { }
        });
    }

    private void openFile(JSONObject file) {
        if (file == null) return;
        String type = file.optString("type", "photo");
        String filename = file.optString("name", null);
        if (filename == null) return;
        imageExecutor.execute(() -> {
            Uri fileUri = findLocalFile(file);
            activity.runOnUiThread(() -> {
                if (fileUri == null) {
                    statusReporter.show("File not found. The download may still be finishing or the file was deleted.", true);
                    return;
                }
                Intent viewFile = new Intent(Intent.ACTION_VIEW).setDataAndType(fileUri,
                        "video".equals(type) ? "video/*" : "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { activity.startActivity(viewFile); }
                catch (Exception error) { statusReporter.show("No local viewer is available to open this file.", true); }
            });
        });
    }

    private Uri findLocalFile(JSONObject file) {
        String filename = file.optString("name", null);
        String type = file.optString("type", "photo");
        long downloadId = file.optLong("downloadId", -1L);
        if (filename == null) return null;
        Uri localFile = null;
        if ("video".equals(type) && downloadId >= 0) {
            localFile = ((DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE)).getUriForDownloadedFile(downloadId);
        }
        return localFile != null ? localFile : findDownloadedFile(filename, type);
    }

    private Uri findDownloadedFile(String filename, String type) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.MIME_TYPE + " LIKE ?";
        String[] args = {filename, "video".equals(type) ? "video/%" : "image/%"};
        try (Cursor cursor = activity.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Long.toString(id));
            }
        } catch (Exception ignored) { }
        return null;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private static String downloadAge(long createdAt) {
        if (createdAt <= 0L) return "Downloaded previously";
        long elapsedMinutes = Math.max(0L, System.currentTimeMillis() - createdAt) / 60_000L;
        if (elapsedMinutes < 1L) return "Downloaded just now";
        if (elapsedMinutes < 60L) return "Downloaded " + elapsedMinutes + (elapsedMinutes == 1L ? " min" : " mins") + " ago";
        long elapsedHours = elapsedMinutes / 60L;
        if (elapsedHours < 24L) return "Downloaded " + elapsedHours + (elapsedHours == 1L ? " hour" : " hours") + " ago";
        long elapsedDays = elapsedHours / 24L;
        return "Downloaded " + elapsedDays + (elapsedDays == 1L ? " day" : " days") + " ago";
    }

    private static String labelFor(String type) {
        if ("photo".equals(type)) return "Photo";
        if ("story".equals(type)) return "Story";
        if ("carousel".equals(type)) return "Carousel";
        return "Video";
    }
}
