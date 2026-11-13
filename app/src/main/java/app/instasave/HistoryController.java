package app.instasave;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static app.instasave.UiMotion.dp;

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
    /** Keys of the entries none of whose files are on the device any more, e.g. deleted in a file manager. */
    private final Set<String> missingEntries = new HashSet<>();
    /** Photos are saved within seconds and have no status to ask for, so a newer entry isn't reported missing yet. */
    private static final long PHOTO_SAVE_GRACE_MS = 2L * 60L * 1000L;
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
            missingEntries.retainAll(keys);
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
        showMeta(row, item);
        List<View> fileRows = new ArrayList<>();
        if (isCarousel && files.length() > 0) {
            expandIndicator.setVisibility(View.VISIBLE);
            fileRows = addFiles(filesContainer, files);
            showFiles(itemHeader, filesContainer, expandIndicator, expandedEntries.contains(key));
            itemHeader.setOnClickListener(v -> toggleFiles(key, itemHeader, filesContainer, expandIndicator));
        } else if (files.length() > 0) {
            JSONObject file = files.optJSONObject(0);
            itemHeader.setContentDescription(activity.getString("photo".equals(type) ? R.string.history_open_photo : R.string.history_open_video));
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
        checkFiles(row, item, files, fileRows);
        String preview = item.optString("preview", null);
        if (preview != null && !preview.isEmpty()) {
            imageLoader.loadInto(row.findViewById(R.id.itemPreview), preview, dp(activity, 42), imageExecutor,
                    () -> row.findViewById(R.id.itemIcon).setVisibility(View.GONE));
        }
    }

    /** One row per file, at the file's index; null where the stored file is unreadable. */
    private List<View> addFiles(LinearLayout filesContainer, JSONArray files) {
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            rows.add(null);
            if (file == null) continue;
            View child = LayoutInflater.from(activity).inflate(R.layout.item_history_file, filesContainer, false);
            String type = file.optString("type", "photo");
            String name = file.optString("name", activity.getString(R.string.history_file_fallback_name));
            ((TextView) child.findViewById(R.id.historyFileName)).setText(name);
            ((TextView) child.findViewById(R.id.historyFileType)).setText("video".equals(type) ? R.string.type_video : R.string.type_photo);
            child.setContentDescription(activity.getString(R.string.history_open_file, name));
            child.setOnClickListener(v -> openFile(file));
            filesContainer.addView(child);
            rows.set(i, child);
        }
        return rows;
    }

    /** "Photo · 5 minutes ago", or "Photo · File not found" in the error color once the files are gone. */
    private void showMeta(View row, JSONObject item) {
        TextView meta = row.findViewById(R.id.itemMeta);
        try {
            String type = item.optString("type", "auto");
            int fileCount = HistoryRepository.files(item).length();
            boolean missing = missingEntries.contains(HistoryRepository.keyOf(item));
            String state = missing ? activity.getString(fileCount > 1 ? R.string.history_files_not_found : R.string.history_file_not_found)
                    : downloadAge(item.optLong("createdAt", 0L));
            meta.setText(("carousel".equals(type) ? quantity(R.plurals.history_file_count, fileCount)
                    : activity.getString(labelFor(type))) + " · " + state);
            // STYLE: history row meta, muted or error when the files are gone (values/colors.xml).
            meta.setTextColor(activity.getColor(missing ? R.color.error : R.color.muted));
        } catch (Exception ignored) { }
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
            showMeta(row, item);
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

    /**
     * Looks for an entry's files in the background, then marks the entry missing when none is on
     * the device and none is still being saved, and updates its carousel file rows.
     */
    private void checkFiles(View row, JSONObject item, JSONArray files, List<View> fileRows) {
        if (files.length() == 0) return;
        String key = HistoryRepository.keyOf(item);
        long createdAt = item.optLong("createdAt", 0L);
        imageExecutor.execute(() -> {
            Uri[] found = new Uri[files.length()];
            boolean[] saving = new boolean[files.length()];
            boolean missing = true;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null) continue;
                found[i] = findLocalFile(file);
                saving[i] = found[i] == null && isStillSaving(file, createdAt);
                if (found[i] != null || saving[i]) missing = false;
            }
            boolean entryMissing = missing;
            activity.runOnUiThread(() -> {
                if (entryMissing) missingEntries.add(key); else missingEntries.remove(key);
                showMeta(row, item);
                for (int i = 0; i < fileRows.size(); i++) {
                    if (fileRows.get(i) != null) showFileState(fileRows.get(i), files.optJSONObject(i), found[i], saving[i]);
                }
            });
        });
    }

    /** A saved file shows its thumbnail; one being saved, its type icon; a missing one says so. */
    private void showFileState(View row, JSONObject file, Uri localFile, boolean saving) {
        TextView type = row.findViewById(R.id.historyFileType);
        ImageView preview = row.findViewById(R.id.historyFilePreview);
        boolean isVideo = "video".equals(file.optString("type", "photo"));
        if (localFile != null) {
            type.setCompoundDrawables(null, null, null, null);
            if (!isVideo) imageLoader.loadInto(preview, localFile, dp(activity, 36), imageExecutor, null);
            return;
        }
        preview.setTag(null);
        preview.setVisibility(View.GONE);
        if (saving) {
            type.setText("");
            // STYLE: photo or video icon of a history file (drawable/ic_photo, drawable/ic_video).
            type.setCompoundDrawablesWithIntrinsicBounds(isVideo ? R.drawable.ic_video : R.drawable.ic_photo, 0, 0, 0);
            type.setContentDescription(activity.getString(isVideo ? R.string.history_file_video : R.string.history_file_photo));
            return;
        }
        type.setCompoundDrawables(null, null, null, null);
        type.setText(R.string.history_file_not_found);
        type.setContentDescription(null);
        // STYLE: label of a history file that is no longer on the device (values/colors.xml).
        type.setTextColor(activity.getColor(R.color.error));
    }

    /**
     * Videos report their state through DownloadManager. Photos have none: a missing photo counts as
     * still being saved only while its entry is recent.
     */
    private boolean isStillSaving(JSONObject file, long createdAt) {
        long downloadId = file.optLong("downloadId", -1L);
        if ("video".equals(file.optString("type", "photo")) && downloadId >= 0) {
            int status = downloadStatus(downloadId);
            return status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;
        }
        return System.currentTimeMillis() - createdAt < PHOTO_SAVE_GRACE_MS;
    }

    private int downloadStatus(long downloadId) {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        } catch (Exception ignored) { }
        return -1;
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
        if ("video".equals(type) && downloadId >= 0) {
            // DownloadManager keeps answering for a download whose file was deleted afterwards, so
            // the file itself is opened to check it's still there.
            Uri localFile = ((DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE)).getUriForDownloadedFile(downloadId);
            if (localFile != null && canOpen(localFile)) return localFile;
        }
        return findDownloadedFile(filename, type);
    }

    private boolean canOpen(Uri uri) {
        try (ParcelFileDescriptor descriptor = activity.getContentResolver().openFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (Exception ignored) {
            return false;
        }
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
        if ("carousel".equals(type)) return R.string.type_carousel;
        return R.string.type_video;
    }
}
