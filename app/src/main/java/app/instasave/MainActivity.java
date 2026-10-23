package app.instasave;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates link analysis with the preview, download, and history components. */
public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1;
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
    private boolean downloadInProgress;
    private boolean downloadResetPending;
    private boolean downloadReceiverRegistered;
    private boolean notificationsRequested;
    private boolean overlayPermissionRequested;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            downloadController.handleCompleted(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L));
        }
    };

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
        registerDownloadReceiver();

        analyzeButton.setOnClickListener(v -> { UiMotion.tap(v); resolveLink(); });
        pasteButton.setOnClickListener(v -> { UiMotion.tap(v); pasteLink(); });
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
        receiveSharedLink(getIntent());
        historyController.render();
        UiMotion.reveal(findViewById(R.id.appHeader), 8, 0);
        UiMotion.reveal(findViewById(R.id.linkCard), 14, 70);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDownloadReceiver() {
        IntentFilter downloads = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(downloadReceiver, downloads, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, downloads);
        downloadReceiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        bubbleSwitch.setChecked(BubbleService.isEnabled());
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
        // Downloads started from the bubble are written to the same history while this activity is stopped.
        historyController.render();
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
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveSharedLink(intent);
    }

    private void receiveSharedLink(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction()) || !"text/plain".equals(intent.getType())) return;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        urlInput.setText(extractUrl(shared));
        urlInput.setSelection(urlInput.length());
        showStatus(R.string.status_link_received, false);
    }

    private void pasteLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) { showStatus(R.string.status_clipboard_empty, true); return; }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { showStatus(R.string.status_clipboard_empty, true); return; }
        CharSequence content = clip.getItemAt(0).coerceToText(this);
        String link = content == null ? "" : extractUrl(content.toString());
        if (!isValidUrl(link)) { showStatus(R.string.status_clipboard_invalid, true); return; }
        urlInput.setText(link);
        urlInput.setSelection(urlInput.length());
        showStatus(R.string.status_link_pasted, false);
    }

    private void resolveLink() {
        String source = urlInput.getText().toString().trim();
        analyzeButton.setEnabled(false);
        showStatus(R.string.status_analyzing, false);
        resolverExecutor.execute(() -> {
            try {
                MediaResolver.Result result = new MediaResolver().resolve(source);
                runOnUiThread(() -> showResolvedResult(source, result));
            } catch (Exception error) {
                runOnUiThread(() -> { showStatus(UserFacingException.messageFor(this, error), true); analyzeButton.setEnabled(true); });
            }
        });
    }

    private void showResolvedResult(String source, MediaResolver.Result result) {
        if (!source.equals(urlInput.getText().toString().trim())) return;
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
        statusText.setTextColor(getColor(isError ? R.color.error : R.color.muted));
        statusText.setVisibility(View.VISIBLE);
        UiMotion.reveal(statusText, 5, 0);
    }

    private void clearStatus() {
        statusText.animate().cancel();
        statusText.setText(null);
        statusText.setVisibility(View.GONE);
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
        int end = text.indexOf(' ', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }

    @Override protected void onDestroy() {
        if (isFinishing()) urlInput.setText("");
        if (downloadReceiverRegistered) unregisterReceiver(downloadReceiver);
        historyController.dispose();
        uiHandler.removeCallbacksAndMessages(null);
        resolverExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        metadataExecutor.shutdownNow();
        saveExecutor.shutdownNow();
        super.onDestroy();
    }
}
