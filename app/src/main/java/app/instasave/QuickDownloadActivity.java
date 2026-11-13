package app.instasave;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sheet opened by the floating bubble: downloads the link copied in Instagram without leaving it. */
public final class QuickDownloadActivity extends Activity {
    private static final int REQUEST_STORAGE = 1;
    private final ExecutorService resolverExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private View card;
    private ImageView preview;
    private TextView title;
    private TextView message;
    private ProgressBar progress;
    private Button downloadButton;
    private TextView openAppButton;
    private ImageLoader imageLoader;
    private DownloadController downloadController;
    private String source;
    private MediaResolver.Result result;
    private boolean clipboardRead;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_download);
        card = findViewById(R.id.quickCard);
        preview = findViewById(R.id.quickPreview);
        title = findViewById(R.id.quickTitle);
        message = findViewById(R.id.quickMessage);
        progress = findViewById(R.id.quickProgress);
        downloadButton = findViewById(R.id.quickDownloadButton);
        openAppButton = findViewById(R.id.quickOpenAppButton);
        imageLoader = new ImageLoader(getContentResolver());
        downloadController = new DownloadController(this, saveExecutor, this::finishDownload);
        downloadController.register();

        findViewById(R.id.quickRoot).setOnClickListener(v -> finish());
        downloadButton.setOnClickListener(v -> { UiMotion.tap(v); download(); });
        openAppButton.setOnClickListener(v -> openInApp());
        showState(R.string.history_item_content, getString(R.string.status_analyzing), false);
        progress.setVisibility(View.VISIBLE);
        UiMotion.reveal(card, 24, 0);
    }

    // Since Android 10 the clipboard is readable only by the app that has input focus, which this
    // activity gets after onResume(); reading it any earlier returns nothing.
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || clipboardRead) return;
        clipboardRead = true;
        source = clipboardLink();
        if (source == null) {
            preview.setVisibility(View.GONE);
            showState(R.string.quick_no_link_title, getString(R.string.quick_no_link_message), false);
        } else {
            resolve();
        }
    }

    private String clipboardLink() {
        String content = MainActivity.clipboardText(this);
        String link = content == null ? null : MainActivity.extractUrl(content);
        return link != null && MainActivity.isValidUrl(link) ? link : null;
    }

    private void resolve() {
        resolverExecutor.execute(() -> {
            try {
                MediaResolver.Result resolved = new MediaResolver().resolve(source);
                runOnUiThread(() -> showResult(resolved));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showState(R.string.quick_error_title, UserFacingException.messageFor(this, error), true);
                    openAppButton.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showResult(MediaResolver.Result resolved) {
        result = resolved;
        int count = resolved.items.size();
        showState(PreviewController.readyTitle(resolved), PreviewController.sourceLabel(this, resolved), false);
        downloadButton.setText(count > 1 ? getString(R.string.quick_download_all, count) : getString(R.string.download_action));
        downloadButton.setVisibility(View.VISIBLE);
        openAppButton.setVisibility(View.VISIBLE);
        String previewUrl = resolved.items.get(0).previewUrl;
        if (previewUrl != null) imageLoader.loadInto(preview, previewUrl, preview.getLayoutParams().width, imageExecutor, null);
    }

    private void download() {
        if (result == null) return;
        // Android 9 and earlier: the system dialog only pauses the sheet, which stays open behind it.
        if (DownloadController.needsStoragePermission(this)) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        downloadButton.setEnabled(false);
        downloadButton.setText(R.string.download_started);
        openAppButton.setVisibility(View.GONE);
        message.setText(result.items.size() > 1 ? R.string.status_downloads_started : R.string.status_download_started);
        Map<MediaResolver.MediaItem, Long> ids = downloadController.start(result.items);
        try { HistoryRepository.of(this).add(source, result, result.items, ids); } catch (Exception ignored) { }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) download();
        else showMessage(getString(R.string.error_storage_permission), true);
    }

    private void finishDownload(boolean failed, String failureMessage) {
        downloadButton.setText(failed ? R.string.download_failed : R.string.download_completed);
        if (failed) {
            showMessage(failureMessage == null ? getString(R.string.status_downloads_failed) : failureMessage, true);
            return;
        }
        showMessage(getString(R.string.status_download_completed), false);
        uiHandler.postDelayed(this::finish, 1_500L);
    }

    /** Hands the link to the full app, where carousel items can be picked one by one. */
    private void openInApp() {
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void showState(int titleRes, String text, boolean isError) {
        progress.setVisibility(View.GONE);
        title.setText(titleRes);
        showMessage(text, isError);
    }

    private void showMessage(String text, boolean isError) {
        message.setText(text);
        // STYLE: message color, muted or error (values/colors.xml).
        message.setTextColor(getColor(isError ? R.color.error : R.color.muted));
    }

    // The sheet belongs to the moment the bubble was tapped: once the user goes elsewhere it closes,
    // so the next tap reads the clipboard again.
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        downloadController.unregister();
        uiHandler.removeCallbacksAndMessages(null);
        resolverExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        // Let photo saves that already started complete even when the sheet is dismissed.
        saveExecutor.shutdown();
        super.onDestroy();
    }
}
