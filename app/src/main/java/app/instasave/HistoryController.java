package app.instasave;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentUris;
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

import java.util.HashSet;
import java.util.Set;
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
    private final Runnable refreshRunnable = this::refreshAges;
    /** Keys of the carousels whose file list is open, kept so a re-render doesn't close them. */
    private final Set<String> expandedEntries = new HashSet<>();
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
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < history.length(); i++) keys.add(HistoryRepository.keyOf(history.getJSONObject(i)));
            expandedEntries.retainAll(keys);
            if (expanded) {
                for (int i = 0; i < history.length(); i++) addItem(history.getJSONObject(i));
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

    private void addItem(JSONObject item) throws Exception {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_history, container, false);
        String key = HistoryRepository.keyOf(item);
        row.setTag(item);
        View itemHeader = row.findViewById(R.id.historyItemHeader);
        LinearLayout filesContainer = row.findViewById(R.id.historyFilesContainer);
        TextView expandIndicator = row.findViewById(R.id.itemExpandIndicator);
        String type = item.optString("type", "auto");
        JSONArray files = HistoryRepository.files(item);
        boolean isCarousel = "carousel".equals(type);
        ((TextView) row.findViewById(R.id.itemIcon)).setText(isCarousel ? R.string.type_multi : labelFor(type));
        // Entries saved before usernames were stored, or whose response hid the author, keep
        // the generic title.
        String username = item.optString("username", "");
        TextView title = row.findViewById(R.id.itemTitle);
        if (!username.isEmpty()) title.setText("@" + username);
        else title.setText(isCarousel ? R.string.history_item_carousel : R.string.history_item_content);
        ((TextView) row.findViewById(R.id.itemMeta)).setText(metaText(item));
        if (isCarousel && files.length() > 0) {
            expandIndicator.setVisibility(View.VISIBLE);
            addFiles(filesContainer, files);
            showFiles(itemHeader, filesContainer, expandIndicator, expandedEntries.contains(key));
            itemHeader.setOnClickListener(v -> toggleFiles(key, itemHeader, filesContainer, expandIndicator));
        } else if (files.length() > 0) {
            JSONObject file = files.optJSONObject(0);
            itemHeader.setContentDescription(activity.getString("photo".equals(type) ? R.string.history_open_photo
                    : "story".equals(type) ? R.string.history_open_story : R.string.history_open_video));
            itemHeader.setOnClickListener(v -> openFile(file));
        }
        Uri postUri = instagramPostUri(item.optString("url", null));
        if (postUri != null) {
            View openPost = row.findViewById(R.id.openHistoryPost);
            openPost.setVisibility(View.VISIBLE);
            openPost.setOnClickListener(v -> { UiMotion.tap(v); openPost(postUri); });
        }
        row.findViewById(R.id.removeHistoryItem).setOnClickListener(v -> removeItem(key));
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

    private String metaText(JSONObject item) throws Exception {
        String type = item.optString("type", "auto");
        String downloadedAt = downloadAge(item.optLong("createdAt", 0L));
        return "carousel".equals(type)
                ? quantity(R.plurals.history_file_count, HistoryRepository.files(item).length()) + " · " + downloadedAt
                : activity.getString(labelFor(type)) + " · " + downloadedAt;
    }

    private void toggleFiles(String key, View header, LinearLayout files, TextView indicator) {
        boolean expand = files.getVisibility() != View.VISIBLE;
        if (expand) expandedEntries.add(key); else expandedEntries.remove(key);
        showFiles(header, files, indicator, expand);
        if (expand) UiMotion.reveal(files, 8, 0);
    }

    private void showFiles(View header, LinearLayout files, TextView indicator, boolean expanded) {
        files.setVisibility(expanded ? View.VISIBLE : View.GONE);
        indicator.setText(expanded ? "▲" : "▼");
        header.setContentDescription(activity.getString(expanded ? R.string.history_files_collapse : R.string.history_files_expand));
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

    private void removeItem(String key) {
        try {
            repository.remove(key);
            render();
            statusReporter.clear();
        } catch (Exception error) {
            statusReporter.show(activity.getString(R.string.history_remove_failed), true);
        }
    }

    /**
     * Updates only the "n minutes ago" labels: a full render would rebuild every row and repeat the
     * MediaStore lookups and thumbnail loads.
     */
    private void refreshAges() {
        JSONArray shown = new JSONArray();
        for (int i = 0; i < container.getChildCount(); i++) {
            View row = container.getChildAt(i);
            if (!(row.getTag() instanceof JSONObject)) continue;
            JSONObject item = (JSONObject) row.getTag();
            try { ((TextView) row.findViewById(R.id.itemMeta)).setText(metaText(item)); } catch (Exception ignored) { }
            shown.put(item);
        }
        scheduleRefresh(shown);
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
                    // STYLE: photo or video icon of a history file (drawable/ic_photo, drawable/ic_video).
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

    /** The permalink without share-tracking parameters, or null for direct media links. */
    private static Uri instagramPostUri(String url) {
        if (url == null || url.isEmpty()) return null;
        Uri uri = Uri.parse(url);
        if (!MediaResolver.isInstagramHost(uri.getHost())) return null;
        return uri.buildUpon().clearQuery().fragment(null).build();
    }

    private void openPost(Uri postUri) {
        // The Instagram app claims instagram.com links, so it opens the post when installed;
        // otherwise the browser does.
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, postUri)); }
        catch (Exception error) { statusReporter.show(activity.getString(R.string.history_open_post_failed), true); }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return findLegacyFile(filename, type);
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

    /**
     * Android 9 and earlier have no MediaStore.Downloads: files are found by their path in the
     * image or video table, filled by the media scanner. A content URI, unlike a file path, can be
     * handed to a viewer app.
     */
    @SuppressWarnings("deprecation")
    private Uri findLegacyFile(String filename, String type) {
        Uri table = "video".equals(type) ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns._ID};
        String[] args = {DownloadController.legacyFile(filename).getAbsolutePath()};
        try (Cursor cursor = activity.getContentResolver().query(table, projection, MediaStore.MediaColumns.DATA + "=?", args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(table, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)));
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
