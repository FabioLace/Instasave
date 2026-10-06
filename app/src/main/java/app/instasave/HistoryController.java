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
    private int lastRenderedCount = -1;

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
            String countLabel = history.length() == 0 ? activity.getString(R.string.history_count_none)
                    : quantity(R.plurals.history_count, history.length());
            historyToggle.setText(countLabel + "  " + (expanded ? "▲" : "▼"));
            historyToggle.setContentDescription(activity.getString(expanded ? R.string.history_collapse : R.string.history_expand));
            container.setVisibility(expanded ? View.VISIBLE : View.GONE);
            emptyHistory.setVisibility(expanded && history.length() == 0 ? View.VISIBLE : View.GONE);
            clearHistoryButton.setVisibility(expanded && history.length() > 0 ? View.VISIBLE : View.GONE);
            if (expanded) {
                for (int i = 0; i < history.length(); i++) addItem(history.getJSONObject(i), i);
                if (lastRenderedCount >= 0 && history.length() > lastRenderedCount && container.getChildCount() > 0) {
                    UiMotion.reveal(container.getChildAt(0), 10, 0);
                }
                scheduleRefresh(history);
            }
            lastRenderedCount = history.length();
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
        ((TextView) row.findViewById(R.id.itemIcon)).setText(isCarousel ? R.string.type_multi : labelFor(type));
        ((TextView) row.findViewById(R.id.itemTitle)).setText(isCarousel ? R.string.history_item_carousel : R.string.history_item_content);
        String downloadedAt = downloadAge(item.optLong("createdAt", 0L));
        ((TextView) row.findViewById(R.id.itemMeta)).setText(isCarousel
                ? quantity(R.plurals.history_file_count, files.length()) + " · " + downloadedAt
                : activity.getString(labelFor(type)) + " · " + downloadedAt);
        if (isCarousel && files.length() > 0) {
            expandIndicator.setVisibility(View.VISIBLE);
            addFiles(filesContainer, files);
            itemHeader.setContentDescription(activity.getString(R.string.history_files_expand));
            itemHeader.setOnClickListener(v -> toggleFiles(itemHeader, filesContainer, expandIndicator));
        } else if (files.length() > 0) {
            JSONObject file = files.optJSONObject(0);
            itemHeader.setContentDescription(activity.getString("photo".equals(type) ? R.string.history_open_photo
                    : "story".equals(type) ? R.string.history_open_story : R.string.history_open_video));
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
            String name = file.optString("name", activity.getString(R.string.history_file_fallback_name));
            ((TextView) child.findViewById(R.id.historyFileName)).setText(name);
            ((TextView) child.findViewById(R.id.historyFileType)).setText("video".equals(type) ? R.string.type_video : R.string.type_photo);
            child.setContentDescription(activity.getString(R.string.history_open_file, name));
            child.setOnClickListener(v -> openFile(file));
            filesContainer.addView(child);
            updateFileAvailability(child, file);
        }
    }

    private void toggleFiles(View header, LinearLayout files, TextView indicator) {
        boolean expand = files.getVisibility() != View.VISIBLE;
        files.setVisibility(expand ? View.VISIBLE : View.GONE);
        if (expand) UiMotion.reveal(files, 8, 0);
        indicator.setText(expand ? "▲" : "▼");
        header.setContentDescription(activity.getString(expand ? R.string.history_files_collapse : R.string.history_files_expand));
    }

    private void setExpanded(boolean expanded) {
        repository.setExpanded(expanded);
        render();
        if (expanded) UiMotion.reveal(container.getChildCount() > 0 ? container : emptyHistory, 10, 0);
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity).setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_message)
                .setNegativeButton(R.string.history_clear_cancel, null)
                .setPositiveButton(R.string.history_clear, (dialog, which) -> {
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
            statusReporter.show(activity.getString(R.string.history_remove_failed), true);
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
                    UiMotion.fadeIn(target);
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
                    type.setContentDescription(activity.getString(isVideo ? R.string.history_file_video : R.string.history_file_photo));
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
                    UiMotion.fadeIn(target);
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
                    statusReporter.show(activity.getString(R.string.history_file_missing), true);
                    return;
                }
                Intent viewFile = new Intent(Intent.ACTION_VIEW).setDataAndType(fileUri,
                        "video".equals(type) ? "video/*" : "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { activity.startActivity(viewFile); }
                catch (Exception error) { statusReporter.show(activity.getString(R.string.history_no_viewer), true); }
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

    private String downloadAge(long createdAt) {
        if (createdAt <= 0L) return activity.getString(R.string.downloaded_previously);
        long elapsedMinutes = Math.max(0L, System.currentTimeMillis() - createdAt) / 60_000L;
        if (elapsedMinutes < 1L) return activity.getString(R.string.downloaded_just_now);
        if (elapsedMinutes < 60L) return quantity(R.plurals.downloaded_minutes_ago, elapsedMinutes);
        long elapsedHours = elapsedMinutes / 60L;
        if (elapsedHours < 24L) return quantity(R.plurals.downloaded_hours_ago, elapsedHours);
        return quantity(R.plurals.downloaded_days_ago, elapsedHours / 24L);
    }

    private String quantity(int pluralRes, long count) {
        return activity.getResources().getQuantityString(pluralRes, (int) count, count);
    }

    private static int labelFor(String type) {
        if ("photo".equals(type)) return R.string.type_photo;
        if ("story".equals(type)) return R.string.type_story;
        if ("carousel".equals(type)) return R.string.type_carousel;
        return R.string.type_video;
    }
}
