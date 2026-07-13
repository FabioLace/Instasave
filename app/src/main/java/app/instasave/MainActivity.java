package app.instasave;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String HISTORY_KEY = "history";
    private static final String HISTORY_EXPANDED_KEY = "history_expanded";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
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
    // The latest analysis result stays in memory until download or a new link.
    private MediaResolver.Result pendingResult;
    private String pendingSource;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
                pendingResult = null;
                carouselSelections.clear();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        receiveSharedLink(getIntent());
        renderHistory();
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
        executor.execute(() -> {
            try {
                MediaResolver.Result result = new MediaResolver().resolve(source);
                runOnUiThread(() -> {
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
        for (MediaResolver.MediaItem item : selected) enqueueDownload(item);
        remember(pendingSource, pendingResult);
        renderHistory();
        downloadButton.setEnabled(false);
        downloadButton.setText("Download started");
        showStatus(selected.size() > 1 ? "Downloads started. Find them in notifications."
                : "Download started. Find it in notifications.", false);
    }

    private void showPreview(MediaResolver.Result result) {
        boolean isCarousel = result.items.size() > 1;
        previewTitle.setText(isCarousel ? "Carousel ready" : result.type.equals("video") ? "Video ready" : "Photo ready");
        previewMeta.setText(isCarousel ? result.items.size() + " items from the public post"
                : result.type.equals("video") ? "Preview from the public post" : "Image from the public post");
        previewImage.setImageDrawable(null);
        downloadButton.setEnabled(true);
        downloadButton.setText(isCarousel ? "Download selected" : "Download");
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
            if (item.previewUrl != null) loadCarouselThumbnail(thumbnail, item.previewUrl);
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
        downloadButton.setEnabled(count > 0);
        downloadButton.setText(count == 0 ? "Select items" : "Download " + count + " selected");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadPreview(String imageUrl) {
        executor.execute(() -> {
            try {
                Bitmap image = fetchBitmap(imageUrl, 10_000);
                if (image != null) runOnUiThread(() -> previewImage.setImageBitmap(image));
            } catch (Exception ignored) { }
        });
    }

    private void loadCarouselThumbnail(ImageView target, String imageUrl) {
        // Thumbnails are optional: the checkbox remains usable if one is unavailable.
        executor.execute(() -> {
            try {
                Bitmap image = fetchBitmap(imageUrl, 10_000);
                if (image != null) runOnUiThread(() -> target.setImageBitmap(image));
            } catch (Exception ignored) { }
        });
    }

    private static Bitmap fetchBitmap(String imageUrl, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
        try (InputStream stream = connection.getInputStream()) {
            return BitmapFactory.decodeStream(stream);
        } finally {
            connection.disconnect();
        }
    }

    private void enqueueDownload(MediaResolver.MediaItem item) {
        if ("photo".equals(item.type)) {
            savePhotoAsJpeg(item);
            return;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.downloadUrl));
        request.setTitle(item.filename);
        request.setDescription("Saving to Download/Instasave");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instasave/" + item.filename);
        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
    }

    private void savePhotoAsJpeg(MediaResolver.MediaItem item) {
        executor.execute(() -> {
            Uri destination = null;
            try {
                Bitmap image = fetchBitmap(item.downloadUrl, 20_000);
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
                runOnUiThread(() -> showStatus("JPEG photo saved to Download/Instasave.", false));
            } catch (Exception error) {
                if (destination != null) getContentResolver().delete(destination, null, null);
                runOnUiThread(() -> showStatus(errorMessage(error), true));
            }
        });
    }

    private static String jpegFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename) + ".jpg";
    }

    private void remember(String url, MediaResolver.Result result) {
        try {
            JSONArray history = new JSONArray(getPreferences(MODE_PRIVATE).getString(HISTORY_KEY, "[]"));
            JSONArray next = new JSONArray();
            String preview = result.items.isEmpty() ? null : result.items.get(0).previewUrl;
            JSONObject current = new JSONObject().put("url", url).put("type", result.type);
            if (preview != null) current.put("preview", preview);
            next.put(current);
            for (int i = 0; i < history.length() && i < 4; i++) next.put(history.getJSONObject(i));
            getPreferences(MODE_PRIVATE).edit().putString(HISTORY_KEY, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void renderHistory() {
        historyContainer.removeAllViews();
        try {
            JSONArray history = new JSONArray(getPreferences(MODE_PRIVATE).getString(HISTORY_KEY, "[]"));
            boolean expanded = isHistoryExpanded();
            String countLabel = history.length() == 0 ? "No downloads"
                    : history.length() == 1 ? "1 download" : history.length() + " download";
            historyToggle.setText(countLabel + "  " + (expanded ? "▲" : "▼"));
            historyToggle.setContentDescription(expanded ? "Collapse download history" : "Expand download history");
            historyContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            emptyHistory.setVisibility(expanded && history.length() == 0 ? View.VISIBLE : View.GONE);
            clearHistoryButton.setVisibility(expanded && history.length() > 0 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < history.length(); i++) addHistoryItem(history.getJSONObject(i), i);
        } catch (Exception ignored) {
            emptyHistory.setVisibility(View.VISIBLE);
            clearHistoryButton.setVisibility(View.GONE);
        }
    }

    private void addHistoryItem(JSONObject item, int position) throws Exception {
        View row = LayoutInflater.from(this).inflate(R.layout.item_history, historyContainer, false);
        String type = item.optString("type", "auto");
        ((TextView) row.findViewById(R.id.itemIcon)).setText(type.equals("photo") ? "Photo" : type.equals("story") ? "Story" : type.equals("carousel") ? "Multi" : "Video");
        ((TextView) row.findViewById(R.id.itemTitle)).setText(type.equals("carousel") ? "Instagram carousel" : "Instagram content");
        ((TextView) row.findViewById(R.id.itemMeta)).setText(labelFor(type) + " · Downloaded just now");
        row.findViewById(R.id.removeHistoryItem).setOnClickListener(v -> removeHistoryItem(position));
        historyContainer.addView(row);
        String preview = item.optString("preview", null);
        if (preview != null && !preview.isEmpty()) loadHistoryPreview(row, preview);
    }

    private boolean isHistoryExpanded() {
        return getPreferences(MODE_PRIVATE).getBoolean(HISTORY_EXPANDED_KEY, true);
    }

    private void setHistoryExpanded(boolean expanded) {
        getPreferences(MODE_PRIVATE).edit().putBoolean(HISTORY_EXPANDED_KEY, expanded).apply();
        renderHistory();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("Downloaded files will not be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    getPreferences(MODE_PRIVATE).edit().remove(HISTORY_KEY).apply();
                    renderHistory();
                    clearStatus();
                })
                .show();
    }

    private void removeHistoryItem(int positionToRemove) {
        try {
            JSONArray history = new JSONArray(getPreferences(MODE_PRIVATE).getString(HISTORY_KEY, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.getJSONObject(i);
                if (i != positionToRemove) next.put(item);
            }
            getPreferences(MODE_PRIVATE).edit().putString(HISTORY_KEY, next.toString()).apply();
            renderHistory();
            clearStatus();
        } catch (Exception error) {
            showStatus("Unable to remove the item from history.", true);
        }
    }

    private void loadHistoryPreview(View row, String imageUrl) {
        executor.execute(() -> {
            try {
                Bitmap image = fetchBitmap(imageUrl, 10_000);
                if (image == null) return;
                runOnUiThread(() -> {
                    ((ImageView) row.findViewById(R.id.itemPreview)).setImageBitmap(image);
                    row.findViewById(R.id.itemPreview).setVisibility(View.VISIBLE);
                    row.findViewById(R.id.itemIcon).setVisibility(View.GONE);
                });
            } catch (Exception ignored) { }
        });
    }

    private void showStatus(String message, boolean isError) {
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.accent_dark : R.color.muted));
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
        executor.shutdownNow();
        super.onDestroy();
    }
}
