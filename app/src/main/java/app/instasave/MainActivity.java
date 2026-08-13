package app.instasave;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    // Keep user-facing analysis independent from optional image loading and file conversion.
    // A slow history thumbnail must never delay a newly submitted link.
    private final ExecutorService resolverExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ImageLoader imageLoader;
    private HistoryRepository historyRepository;
    private EditText urlInput;
    private ImageButton pasteButton;
    private ImageButton clearUrlButton;
    private Button analyzeButton;
    private TextView statusText;
    private LinearLayout historyContainer;
    private TextView emptyHistory;
    private View historyHeader;
    private TextView historyToggle;
    private TextView clearHistoryButton;
    private LinearLayout previewContainer;
    private ImageView previewImage;
    private TextView previewTitle;
    private TextView previewMeta;
    private Button downloadButton;
    private TextView selectionLabel;
    private LinearLayout carouselSelectionContainer;
    // Checkboxes are created dynamically, one for each displayed carousel item.
    private final List<CheckBox> carouselSelections = new ArrayList<>();
    private final Set<Long> activeDownloadIds = new HashSet<>();
    private int unfinishedDownloads;
    private boolean downloadFailed;
    private boolean downloadInProgress;
    private boolean downloadResetPending;
    private boolean downloadReceiverRegistered;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (!activeDownloadIds.remove(id)) return;
            finishDownload(downloadSucceeded(id), null);
        }
    };
    // The latest analysis result stays in memory until download or a new link.
    private MediaResolver.Result pendingResult;
    private String pendingSource;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        imageLoader = new ImageLoader(getContentResolver());
        historyRepository = new HistoryRepository(getPreferences(MODE_PRIVATE));
        setContentView(R.layout.activity_main);
        urlInput = findViewById(R.id.urlInput);
        pasteButton = findViewById(R.id.pasteButton);
        clearUrlButton = findViewById(R.id.clearUrlButton);
        analyzeButton = findViewById(R.id.analyzeButton);
        statusText = findViewById(R.id.statusText);
        historyContainer = findViewById(R.id.historyContainer);
        emptyHistory = findViewById(R.id.emptyHistory);
        historyHeader = findViewById(R.id.historyHeader);
        historyToggle = findViewById(R.id.historyToggle);
        clearHistoryButton = findViewById(R.id.clearHistoryButton);
        previewContainer = findViewById(R.id.previewContainer);
        previewImage = findViewById(R.id.previewImage);
        previewTitle = findViewById(R.id.previewTitle);
        previewMeta = findViewById(R.id.previewMeta);
        downloadButton = findViewById(R.id.downloadButton);
        selectionLabel = findViewById(R.id.selectionLabel);
        carouselSelectionContainer = findViewById(R.id.carouselSelectionContainer);

        registerDownloadReceiver();

        analyzeButton.setOnClickListener(v -> resolveAndDownload());
        downloadButton.setOnClickListener(v -> downloadPending());
        pasteButton.setOnClickListener(v -> pasteLink());
        clearUrlButton.setOnClickListener(v -> {
            urlInput.setText("");
            clearStatus();
        });
        historyHeader.setOnClickListener(v -> setHistoryExpanded(!isHistoryExpanded()));
        clearHistoryButton.setOnClickListener(v -> confirmClearHistory());
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                analyzeButton.setEnabled(isValidUrl(s.toString()));
                clearUrlButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                previewContainer.setVisibility(View.GONE);
                previewImage.setTag(null);
                previewImage.setImageDrawable(null);
                carouselSelectionContainer.removeAllViews();
                pendingResult = null;
                carouselSelections.clear();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        receiveSharedLink(getIntent());
        renderHistory();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDownloadReceiver() {
        IntentFilter downloads = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, downloads, Context.RECEIVER_EXPORTED);
        } else {
            // The exported/not-exported flags do not exist before Android 13.
            registerReceiver(downloadReceiver, downloads);
        }
        downloadReceiverRegistered = true;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveSharedLink(intent);
    }

    private void receiveSharedLink(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                urlInput.setText(extractUrl(shared));
                urlInput.setSelection(urlInput.length());
                showStatus("Instagram link received. Ready to analyze.", false);
            }
        }
    }

    private void pasteLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            showStatus("Clipboard is empty.", true);
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            showStatus("Clipboard is empty.", true);
            return;
        }
        CharSequence content = clip.getItemAt(0).coerceToText(this);
        String link = content == null ? "" : extractUrl(content.toString());
        if (!isValidUrl(link)) {
            showStatus("Clipboard doesn't contain a valid link.", true);
            return;
        }
        urlInput.setText(link);
        urlInput.setSelection(urlInput.length());
        showStatus("Link pasted. Ready to analyze.", false);
    }

    private void resolveAndDownload() {
        String source = urlInput.getText().toString().trim();
        analyzeButton.setEnabled(false);
        showStatus("Analyzing content...", false);
        // Keep the network request off the UI thread to avoid blocking the interface.
        resolverExecutor.execute(() -> {
            try {
                MediaResolver.Result result = new MediaResolver().resolve(source);
                runOnUiThread(() -> {
                    if (!source.equals(urlInput.getText().toString().trim())) return;
                    pendingResult = result;
                    pendingSource = source;
                    showPreview(result);
                    showStatus("Content ready to download.", false);
                    analyzeButton.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showStatus(errorMessage(error), true);
                    analyzeButton.setEnabled(true);
                });
            }
        });
    }

    private void downloadPending() {
        if (pendingResult == null) return;
        // A single post always selects its only item; carousels use the checkboxes.
        List<MediaResolver.MediaItem> selected = selectedItems();
        if (selected.isEmpty()) {
            showStatus("Select at least one item to download.", true);
            return;
        }
        unfinishedDownloads = selected.size();
        downloadFailed = false;
        downloadInProgress = true;
        activeDownloadIds.clear();
        downloadButton.setEnabled(false);
        downloadButton.setText("Download started");
        showStatus(selected.size() > 1 ? "Downloads started. Find them in notifications."
                : "Download started. Find it in notifications.", false);
        Map<MediaResolver.MediaItem, Long> downloadIds = new HashMap<>();
        for (MediaResolver.MediaItem item : selected) {
            long id = enqueueDownload(item);
            if (id >= 0) downloadIds.put(item, id);
        }
        try { historyRepository.add(pendingSource, pendingResult, selected, downloadIds); } catch (Exception ignored) { }
        renderHistory();
    }

    private void showPreview(MediaResolver.Result result) {
        boolean isCarousel = result.items.size() > 1;
        previewTitle.setText(isCarousel ? "Carousel ready" : result.type.equals("video") ? "Video ready" : "Photo ready");
        previewMeta.setText(isCarousel ? result.items.size() + " items from the public post"
                : result.type.equals("video") ? "Preview from the public post" : "Image from the public post");
        previewImage.setImageDrawable(null);
        downloadButton.setEnabled(!downloadInProgress);
        downloadButton.setText(downloadInProgress ? "Download started"
                : isCarousel ? "Download selected" : "Download");
        renderCarouselSelections(result); // Adds choices only when there is more than one item.
        previewContainer.setVisibility(View.VISIBLE);
        String previewUrl = result.items.get(0).previewUrl;
        if (previewUrl != null) loadPreview(previewUrl);
    }

    private void renderCarouselSelections(MediaResolver.Result result) {
        carouselSelections.clear();
        carouselSelectionContainer.removeAllViews();
        boolean isCarousel = result.items.size() > 1;
        selectionLabel.setVisibility(isCarousel ? View.VISIBLE : View.GONE);
        carouselSelectionContainer.setVisibility(isCarousel ? View.VISIBLE : View.GONE);
        if (!isCarousel) return;

        // Three columns of square thumbnails, like the Instagram profile grid.
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        carouselSelectionContainer.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int gap = dp(6);
        int cellSize = (getResources().getDisplayMetrics().widthPixels - dp(72) - gap * 2) / 3;

        // Cells are created here because their count depends on the analyzed carousel.
        for (int i = 0; i < result.items.size(); i++) {
            MediaResolver.MediaItem item = result.items.get(i);
            FrameLayout cell = new FrameLayout(this);
            cell.setBackgroundResource(R.drawable.bg_history_icon);
            ImageView thumbnail = new ImageView(this);
            thumbnail.setContentDescription("Item preview " + (i + 1));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(thumbnail, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            CheckBox choice = new CheckBox(this);
            choice.setChecked(true);
            choice.setContentDescription("Select item " + (i + 1) + " · "
                    + ("video".equals(item.type) ? "Video" : "Photo"));
            // Custom icon: purple background and white checkmark for active selection.
            choice.setButtonDrawable(R.drawable.carousel_checkbox);
            FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(42), dp(42),
                    android.view.Gravity.END | android.view.Gravity.BOTTOM);
            choice.setPadding(0, 0, 0, 0);
            choice.setOnCheckedChangeListener((button, checked) -> updateDownloadButton());
            carouselSelections.add(choice);
            cell.addView(choice, checkParams);
            cell.setOnClickListener(v -> choice.setChecked(!choice.isChecked()));
            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams();
            cellParams.width = cellSize;
            cellParams.height = cellSize;
            cellParams.setMargins(i % 3 == 0 ? 0 : gap, 0, 0, gap);
            grid.addView(cell, cellParams);
            if (item.previewUrl != null) loadCarouselThumbnail(thumbnail, item.previewUrl, cellSize);
        }
    }

    private List<MediaResolver.MediaItem> selectedItems() {
        List<MediaResolver.MediaItem> selected = new ArrayList<>();
        if (pendingResult == null) return selected;
        if (pendingResult.items.size() == 1) {
            selected.add(pendingResult.items.get(0));
            return selected;
        }
        // Checkbox order matches the order of media returned by the resolver.
        for (int i = 0; i < pendingResult.items.size() && i < carouselSelections.size(); i++) {
            if (carouselSelections.get(i).isChecked()) selected.add(pendingResult.items.get(i));
        }
        return selected;
    }

    private void updateDownloadButton() {
        // Keeps the button label and state in sync with the user's selections.
        int count = 0;
        for (CheckBox choice : carouselSelections) if (choice.isChecked()) count++;
        if (downloadInProgress) {
            downloadButton.setEnabled(false);
            downloadButton.setText("Download started");
            return;
        }
        if (downloadResetPending) {
            downloadButton.setEnabled(false);
            return;
        }
        downloadButton.setEnabled(count > 0);
        downloadButton.setText(count == 0 ? "Select items" : "Download " + count + " selected");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadPreview(String imageUrl) {
        previewImage.setTag(imageUrl);
        imageExecutor.execute(() -> {
            try {
                Bitmap image = imageLoader.remoteThumbnail(imageUrl, dp(88), dp(88));
                if (image != null) runOnUiThread(() -> {
                    if (imageUrl.equals(previewImage.getTag())) previewImage.setImageBitmap(image);
                });
            } catch (Exception ignored) { }
        });
    }

    private void loadCarouselThumbnail(ImageView target, String imageUrl, int targetSize) {
        // Thumbnails are optional: the checkbox remains usable if one is unavailable.
        target.setTag(imageUrl);
        imageExecutor.execute(() -> {
            try {
                Bitmap image = imageLoader.remoteThumbnail(imageUrl, targetSize, targetSize);
                if (image != null) runOnUiThread(() -> {
                    if (imageUrl.equals(target.getTag())) target.setImageBitmap(image);
                });
            } catch (Exception ignored) { }
        });
    }

    private long enqueueDownload(MediaResolver.MediaItem item) {
        if ("photo".equals(item.type)) {
            savePhotoAsJpeg(item);
            return -1L;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.downloadUrl));
        request.setTitle(item.filename);
        request.setDescription("Saving to Download/Instasave");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instasave/" + item.filename);
        try {
            long id = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            activeDownloadIds.add(id);
            return id;
        } catch (Exception error) {
            finishDownload(false, errorMessage(error));
            return -1L;
        }
    }

    private void savePhotoAsJpeg(MediaResolver.MediaItem item) {
        saveExecutor.execute(() -> {
            Uri destination = null;
            try {
                Bitmap image = ImageLoader.fetchOriginal(item.downloadUrl);
                if (image == null) throw new IllegalStateException("The received file is not a valid image.");
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw new IllegalStateException("Saving as JPEG requires Android 10 or later.");
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, jpegFilename(item.filename));
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Instasave");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IllegalStateException("Unable to create the JPEG file.");
                try (java.io.OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null || !image.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                        throw new IllegalStateException("Unable to convert the image to JPEG.");
                    }
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(destination, values, null, null);
                runOnUiThread(() -> finishDownload(true, null));
            } catch (Exception error) {
                if (destination != null) getContentResolver().delete(destination, null, null);
                runOnUiThread(() -> finishDownload(false, errorMessage(error)));
            }
        });
    }

    private boolean downloadSucceeded(long id) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            return cursor != null && cursor.moveToFirst()
                    && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    == DownloadManager.STATUS_SUCCESSFUL;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void finishDownload(boolean succeeded, String failureMessage) {
        if (unfinishedDownloads <= 0) return;
        if (!succeeded) {
            downloadFailed = true;
            if (failureMessage != null) showStatus(failureMessage, true);
        }
        unfinishedDownloads--;
        if (unfinishedDownloads > 0) return;
        downloadInProgress = false;
        downloadResetPending = true;
        downloadButton.setEnabled(false);
        downloadButton.setText(downloadFailed ? "Download failed" : "Download completed");
        if (downloadFailed) {
            if (failureMessage == null) showStatus("One or more downloads could not be completed.", true);
        } else {
            showStatus("Download completed. The file is in Download/Instasave.", false);
        }
        // Refresh availability and thumbnails once all asynchronous saves have settled.
        renderHistory();
        uiHandler.postDelayed(this::resetDownloadButton, 3_000L);
    }

    private void resetDownloadButton() {
        downloadResetPending = false;
        if (pendingResult != null && pendingResult.items.size() > 1) {
            int count = 0;
            for (CheckBox choice : carouselSelections) if (choice.isChecked()) count++;
            downloadButton.setEnabled(count > 0);
            downloadButton.setText(count > 0 ? "Download" : "Select items");
        } else {
            downloadButton.setEnabled(pendingResult != null);
            downloadButton.setText("Download");
        }
    }

    private static String jpegFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename) + ".jpg";
    }

    private void renderHistory() {
        historyContainer.removeAllViews();
        try {
            JSONArray history = historyRepository.entries();
            boolean expanded = isHistoryExpanded();
            String countLabel = history.length() == 0 ? "No downloads"
                    : history.length() == 1 ? "1 download" : history.length() + " download";
            historyToggle.setText(countLabel + "  " + (expanded ? "▲" : "▼"));
            historyToggle.setContentDescription(expanded ? "Collapse download history" : "Expand download history");
            historyContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            emptyHistory.setVisibility(expanded && history.length() == 0 ? View.VISIBLE : View.GONE);
            clearHistoryButton.setVisibility(expanded && history.length() > 0 ? View.VISIBLE : View.GONE);
            if (expanded) {
                for (int i = 0; i < history.length(); i++) addHistoryItem(history.getJSONObject(i), i);
            }
        } catch (Exception ignored) {
            emptyHistory.setVisibility(View.VISIBLE);
            clearHistoryButton.setVisibility(View.GONE);
        }
    }

    private void addHistoryItem(JSONObject item, int position) throws Exception {
        View row = LayoutInflater.from(this).inflate(R.layout.item_history, historyContainer, false);
        View itemHeader = row.findViewById(R.id.historyItemHeader);
        LinearLayout filesContainer = row.findViewById(R.id.historyFilesContainer);
        TextView expandIndicator = row.findViewById(R.id.itemExpandIndicator);
        String type = item.optString("type", "auto");
        JSONArray files = HistoryRepository.files(item);
        boolean isCarousel = "carousel".equals(type);
        ((TextView) row.findViewById(R.id.itemIcon)).setText(type.equals("photo") ? "Photo" : type.equals("story") ? "Story" : type.equals("carousel") ? "Multi" : "Video");
        ((TextView) row.findViewById(R.id.itemTitle)).setText(isCarousel ? "Instagram carousel" : "Instagram content");
        String meta = isCarousel ? files.length() + (files.length() == 1 ? " file" : " files")
                : labelFor(type) + " · Downloaded just now";
        ((TextView) row.findViewById(R.id.itemMeta)).setText(meta);
        if (isCarousel && files.length() > 0) {
            expandIndicator.setVisibility(View.VISIBLE);
            addHistoryFiles(filesContainer, files);
            itemHeader.setContentDescription("Expand downloaded carousel files");
            itemHeader.setOnClickListener(v -> toggleHistoryFiles(itemHeader, filesContainer, expandIndicator));
        } else if (files.length() > 0) {
            JSONObject file = files.optJSONObject(0);
            itemHeader.setContentDescription("Open downloaded " + type);
            itemHeader.setOnClickListener(v -> openDownloadedFile(file));
        }
        row.findViewById(R.id.removeHistoryItem).setOnClickListener(v -> removeHistoryItem(position));
        historyContainer.addView(row);
        String preview = item.optString("preview", null);
        if (preview != null && !preview.isEmpty()) loadHistoryPreview(row, preview);
    }

    private void addHistoryFiles(LinearLayout container, JSONArray files) {
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            View child = LayoutInflater.from(this).inflate(R.layout.item_history_file, container, false);
            String type = file.optString("type", "photo");
            String name = file.optString("name", "Downloaded file");
            ((TextView) child.findViewById(R.id.historyFileName)).setText(name);
            ((TextView) child.findViewById(R.id.historyFileType)).setText(
                    "video".equals(type) ? "Video" : "Photo");
            child.setContentDescription("Open " + name);
            child.setOnClickListener(v -> openDownloadedFile(file));
            container.addView(child);
            updateHistoryFileAvailability(child, file);
        }
    }

    private void toggleHistoryFiles(View header, LinearLayout files, TextView indicator) {
        boolean expand = files.getVisibility() != View.VISIBLE;
        files.setVisibility(expand ? View.VISIBLE : View.GONE);
        indicator.setText(expand ? "▲" : "▼");
        header.setContentDescription((expand ? "Collapse" : "Expand")
                + " downloaded carousel files");
    }

    private boolean isHistoryExpanded() {
        return historyRepository.isExpanded();
    }

    private void setHistoryExpanded(boolean expanded) {
        historyRepository.setExpanded(expanded);
        renderHistory();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("Downloaded files will not be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    historyRepository.clear();
                    renderHistory();
                    clearStatus();
                })
                .show();
    }

    private void removeHistoryItem(int positionToRemove) {
        try {
            historyRepository.remove(positionToRemove);
            renderHistory();
            clearStatus();
        } catch (Exception error) {
            showStatus("Unable to remove the item from history.", true);
        }
    }

    private void loadHistoryPreview(View row, String imageUrl) {
        ImageView target = row.findViewById(R.id.itemPreview);
        target.setTag(imageUrl);
        imageExecutor.execute(() -> {
            try {
                Bitmap image = imageLoader.remoteThumbnail(imageUrl, dp(42), dp(42));
                if (image == null) return;
                runOnUiThread(() -> {
                    if (!imageUrl.equals(target.getTag())) return;
                    target.setImageBitmap(image);
                    target.setVisibility(View.VISIBLE);
                    row.findViewById(R.id.itemIcon).setVisibility(View.GONE);
                });
            } catch (Exception ignored) { }
        });
    }

    private void loadHistoryFilePreview(View row, Uri imageUri) {
        ImageView target = row.findViewById(R.id.historyFilePreview);
        String imageKey = imageUri.toString();
        target.setTag(imageKey);
        imageExecutor.execute(() -> {
            try {
                Bitmap image = imageLoader.localThumbnail(imageUri, dp(36), dp(36));
                if (image == null) return;
                runOnUiThread(() -> {
                    if (!imageKey.equals(target.getTag())) return;
                    target.setImageBitmap(image);
                    target.setVisibility(View.VISIBLE);
                });
            } catch (Exception ignored) { }
        });
    }

    private void updateHistoryFileAvailability(View row, JSONObject file) {
        imageExecutor.execute(() -> {
            Uri localFile = findLocalFile(file);
            runOnUiThread(() -> {
                TextView type = row.findViewById(R.id.historyFileType);
                ImageView preview = row.findViewById(R.id.historyFilePreview);
                if (localFile == null) {
                    preview.setTag(null);
                    preview.setVisibility(View.GONE);
                    type.setText("");
                    boolean isVideo = "video".equals(file.optString("type", "photo"));
                    type.setCompoundDrawablesWithIntrinsicBounds(
                            isVideo ? R.drawable.ic_video : R.drawable.ic_photo, 0, 0, 0);
                    type.setContentDescription(isVideo ? "Video file" : "Photo file");
                    return;
                }
                type.setCompoundDrawables(null, null, null, null);
                if ("photo".equals(file.optString("type", "photo"))) {
                    // Use the saved JPEG itself: each carousel row gets its real image.
                    loadHistoryFilePreview(row, localFile);
                }
            });
        });
    }

    private void openDownloadedFile(JSONObject file) {
        if (file == null) return;
        String type = file.optString("type", "photo");
        String filename = file.optString("name", null);
        if (filename == null) return;
        imageExecutor.execute(() -> {
            Uri localFile = findLocalFile(file);
            Uri fileUri = localFile;
            runOnUiThread(() -> {
                if (fileUri == null) {
                    showStatus("File not found. The download may still be finishing or the file was deleted.", true);
                    return;
                }
                String mimeType = "video".equals(type) ? "video/*" : "image/*";
                Intent viewFile = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(fileUri, mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(viewFile);
                } catch (Exception error) {
                    showStatus("No local viewer is available to open this file.", true);
                }
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
            localFile = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE))
                    .getUriForDownloadedFile(downloadId);
        }
        return localFile != null ? localFile : findDownloadedFile(filename, type);
    }

    private Uri findDownloadedFile(String filename, String type) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.MIME_TYPE + " LIKE ?";
        String[] args = {filename, "video".equals(type) ? "video/%" : "image/%"};
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        Long.toString(id));
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void showStatus(String message, boolean isError) {
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.error : R.color.muted));
        statusText.setVisibility(View.VISIBLE);
    }

    private void clearStatus() {
        statusText.setText(null);
        statusText.setVisibility(View.GONE);
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Unable to process this public content." : message;
    }

    private static boolean isValidUrl(String raw) {
        try {
            Uri uri = Uri.parse(extractUrl(raw));
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (Exception ignored) { return false; }
    }

    private static String extractUrl(String text) {
        int start = text.indexOf("http");
        if (start < 0) return text.trim();
        int end = text.indexOf(' ', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }

    private static String labelFor(String type) {
        if ("photo".equals(type)) return "Photo";
        if ("story".equals(type)) return "Story";
        if ("carousel".equals(type)) return "Carousel";
        return "Video";
    }

    @Override protected void onDestroy() {
        if (isFinishing()) urlInput.setText("");
        if (downloadReceiverRegistered) unregisterReceiver(downloadReceiver);
        uiHandler.removeCallbacksAndMessages(null);
        resolverExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        saveExecutor.shutdownNow();
        super.onDestroy();
    }
}
