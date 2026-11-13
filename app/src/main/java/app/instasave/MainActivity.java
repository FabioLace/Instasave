package app.instasave;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates link analysis with the preview, download, and history components. */
public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final int REQUEST_STORAGE = 2;
    private static final String PROJECT_URL = "https://github.com/FabioLace/Instasave";
    /** Set by the bubble's "Open in Instasave": the shared link is analyzed right away, not only pasted. */
    static final String EXTRA_ANALYZE = "app.instasave.extra.ANALYZE";
    private final ExecutorService resolverExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService metadataExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private EditText urlInput;
    private ImageButton clearUrlButton;
    private Button analyzeButton;
    private TextView statusText;
    private Switch bubbleSwitch;
    private PreviewController previewController;
    private HistoryRepository historyRepository;
    private HistoryController historyController;
    private DownloadController downloadController;
    private MediaResolver.Result pendingResult;
    private String pendingSource;
    /** Incremented by every analysis, so a slower earlier one can't touch the screen. */
    private int resolveGeneration;
    private boolean downloadInProgress;
    private boolean downloadResetPending;
    private boolean notificationsRequested;
    private boolean overlayPermissionRequested;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        urlInput = findViewById(R.id.urlInput);
        clearUrlButton = findViewById(R.id.clearUrlButton);
        analyzeButton = findViewById(R.id.analyzeButton);
        statusText = findViewById(R.id.statusText);
        bubbleSwitch = findViewById(R.id.bubbleSwitch);
        ImageButton pasteButton = findViewById(R.id.pasteButton);
        historyRepository = HistoryRepository.of(this);
        ImageLoader imageLoader = new ImageLoader(getContentResolver());
        previewController = new PreviewController(this, imageLoader, imageExecutor, metadataExecutor);
        previewController.setDownloadClickListener(v -> downloadPending());
        previewController.setSelectionListener(() -> previewController.updateDownloadButton(downloadInProgress, downloadResetPending));
        historyController = new HistoryController(this, historyRepository, imageLoader, imageExecutor, uiHandler,
                new HistoryController.StatusReporter() {
                    @Override public void show(String message, boolean isError) { showStatus(message, isError); }
                    @Override public void clear() { clearStatus(); }
                });
        downloadController = new DownloadController(this, saveExecutor, this::finishDownload);
        downloadController.register();

        analyzeButton.setOnClickListener(v -> { UiMotion.tap(v); resolveLink(); });
        pasteButton.setOnClickListener(v -> { UiMotion.tap(v); pasteLink(); });
        findViewById(R.id.githubButton).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, R.string.open_github_failed, Toast.LENGTH_SHORT).show();
            }
        });
        clearUrlButton.setOnClickListener(v -> { urlInput.setText(""); clearStatus(); });
        bubbleSwitch.setOnClickListener(v -> setBubbleEnabled(bubbleSwitch.isChecked()));
        findViewById(R.id.bubbleCard).setOnClickListener(v -> { bubbleSwitch.toggle(); setBubbleEnabled(bubbleSwitch.isChecked()); });
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                analyzeButton.setEnabled(isValidUrl(s.toString()));
                clearUrlButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                previewController.clear();
                pendingResult = null;
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        // A recreated activity gets its old intent again; analyzing it once more would be unexpected.
        receiveSharedLink(getIntent(), state == null);
        UiMotion.reveal(findViewById(R.id.appHeader), 8, 0);
        UiMotion.reveal(findViewById(R.id.linkCard), 14, 70);
    }

    @Override protected void onResume() {
        super.onResume();
        bubbleSwitch.setChecked(BubbleService.isEnabled());
        // Also covers the bubble's sheet: it is translucent, so this activity is only paused under it
        // and onRestart() doesn't run, yet the sheet's downloads are written to the same history.
        historyController.render();
    }

    // Runs when coming back from a full-screen activity, such as the overlay permission settings,
    // unlike onResume(), which also follows the notification permission dialog.
    @Override protected void onRestart() {
        super.onRestart();
        if (overlayPermissionRequested) {
            overlayPermissionRequested = false;
            if (Settings.canDrawOverlays(this)) BubbleService.start(this);
            else showStatus(R.string.bubble_permission_denied, true);
        }
    }

    private void setBubbleEnabled(boolean enabled) {
        if (enabled) enableBubble();
        else BubbleService.stop(this);
    }

    /** Asks once for notifications (the bubble's stop action lives there), then for the overlay permission. */
    private void enableBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsRequested
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsRequested = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            BubbleService.start(this);
            return;
        }
        overlayPermissionRequested = true;
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    // The bubble works without notifications, so it continues whatever the answer.
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS) enableBubble();
        if (requestCode == REQUEST_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) downloadPending();
            else showStatus(R.string.error_storage_permission, true);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveSharedLink(intent, true);
    }

    private void receiveSharedLink(Intent intent, boolean mayAnalyze) {
        if (!Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) return;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        urlInput.setText(extractUrl(shared));
        urlInput.setSelection(urlInput.length());
        clearStatus();
        if (mayAnalyze && intent.getBooleanExtra(EXTRA_ANALYZE, false) && analyzeButton.isEnabled()) resolveLink();
    }

    private void pasteLink() {
        String content = clipboardText(this);
        if (content == null) { showStatus(R.string.status_clipboard_empty, true); return; }
        String link = extractUrl(content);
        if (!isValidUrl(link)) { showStatus(R.string.status_clipboard_invalid, true); return; }
        urlInput.setText(link);
        urlInput.setSelection(urlInput.length());
        clearStatus();
    }

    private void resolveLink() {
        String source = urlInput.getText().toString().trim();
        int generation = ++resolveGeneration;
        analyzeButton.setEnabled(false);
        showStatus(R.string.status_analyzing, false);
        resolverExecutor.execute(() -> {
            try {
                MediaResolver.Result result = new MediaResolver().resolve(source);
                runOnUiThread(() -> showResolvedResult(generation, source, result));
            } catch (Exception error) {
                runOnUiThread(() -> showResolveError(generation, source, error));
            }
        });
    }

    /**
     * False when a newer analysis has started, which owns the screen, or when the link was edited
     * meanwhile: then the "Analyzing…" status is cleared, and the button is left to the text watcher.
     */
    private boolean isCurrentAnalysis(int generation, String source) {
        if (generation != resolveGeneration) return false;
        if (source.equals(urlInput.getText().toString().trim())) return true;
        clearStatus();
        return false;
    }

    private void showResolveError(int generation, String source, Exception error) {
        if (!isCurrentAnalysis(generation, source)) return;
        showStatus(UserFacingException.messageFor(this, error), true);
        analyzeButton.setEnabled(true);
    }

    private void showResolvedResult(int generation, String source, MediaResolver.Result result) {
        if (!isCurrentAnalysis(generation, source)) return;
        pendingResult = result;
        pendingSource = source;
        previewController.show(result, downloadInProgress);
        showStatus(R.string.status_content_ready, false);
        analyzeButton.setEnabled(true);
    }

    private void downloadPending() {
        if (pendingResult == null) return;
        List<MediaResolver.MediaItem> selected = previewController.selectedItems(pendingResult);
        if (selected.isEmpty()) { showStatus(R.string.status_select_item, true); return; }
        // Android 9 and earlier: asked on the first download, which resumes once it is granted.
        if (DownloadController.needsStoragePermission(this)) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        downloadInProgress = true;
        previewController.updateDownloadButton(true, false);
        showStatus(selected.size() > 1 ? R.string.status_downloads_started : R.string.status_download_started, false);
        Map<MediaResolver.MediaItem, Long> ids = downloadController.start(selected);
        try { historyRepository.add(pendingSource, pendingResult, selected, ids); } catch (Exception ignored) { }
        historyController.render();
    }

    private void finishDownload(boolean failed, String failureMessage) {
        downloadInProgress = false;
        downloadResetPending = true;
        previewController.showDownloadFinished(failed);
        if (failed) showStatus(failureMessage == null ? getString(R.string.status_downloads_failed) : failureMessage, true);
        else showStatus(R.string.status_download_completed, false);
        historyController.render();
        uiHandler.postDelayed(this::resetDownloadButton, 3_000L);
    }

    private void resetDownloadButton() {
        downloadResetPending = false;
        previewController.updateDownloadButton(false, false);
    }

    private void showStatus(int messageRes, boolean isError) { showStatus(getString(messageRes), isError); }

    private void showStatus(String message, boolean isError) {
        statusText.setText(message);
        // STYLE: status line color, muted or error (values/colors.xml).
        statusText.setTextColor(getColor(isError ? R.color.error : R.color.muted));
        statusText.setVisibility(View.VISIBLE);
        UiMotion.reveal(statusText, 5, 0);
    }

    private void clearStatus() {
        statusText.animate().cancel();
        statusText.setText(null);
        statusText.setVisibility(View.GONE);
    }

    /** The text of the clipboard's first item, or null when it holds nothing. Also used by the bubble's sheet. */
    static String clipboardText(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence content = clip.getItemAt(0).coerceToText(context);
        return content == null ? null : content.toString();
    }

    static boolean isValidUrl(String raw) {
        try {
            Uri uri = Uri.parse(extractUrl(raw));
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (Exception ignored) { return false; }
    }

    static String extractUrl(String text) {
        int start = text.indexOf("http");
        if (start < 0) return text.trim();
        // Shared text often puts the caption on the next line, so the link ends at any whitespace.
        return text.substring(start).split("\\s", 2)[0];
    }

    @Override protected void onDestroy() {
        if (isFinishing()) urlInput.setText("");
        downloadController.unregister();
        historyController.dispose();
        uiHandler.removeCallbacksAndMessages(null);
        resolverExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        metadataExecutor.shutdownNow();
        // Like the bubble's sheet: a photo that's being saved finishes after the screen is closed.
        saveExecutor.shutdown();
        super.onDestroy();
    }
}
