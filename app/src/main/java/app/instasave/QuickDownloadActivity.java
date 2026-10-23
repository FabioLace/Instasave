package app.instasave;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Build;
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
    private boolean downloadReceiverRegistered;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            downloadController.handleCompleted(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L));
        }
    };

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
        registerDownloadReceiver();

        findViewById(R.id.quickRoot).setOnClickListener(v -> finish());
        downloadButton.setOnClickListener(v -> { UiMotion.tap(v); download(); });
        openAppButton.setOnClickListener(v -> openInApp());
        showState(R.string.history_item_content, getString(R.string.status_analyzing), false);
        progress.setVisibility(View.VISIBLE);
        UiMotion.reveal(card, 24, 0);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDownloadReceiver() {
        IntentFilter downloads = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(downloadReceiver, downloads, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, downloads);
        downloadReceiverRegistered = true;
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
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence content = clip.getItemAt(0).coerceToText(this);
        String link = content == null ? "" : MainActivity.extractUrl(content.toString());
        return MainActivity.isValidUrl(link) ? link : null;
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
        boolean video = "video".equals(resolved.type);
        showState(count > 1 ? R.string.preview_carousel_ready : video ? R.string.preview_video_ready : R.string.preview_photo_ready,
                count > 1 ? getString(R.string.preview_items_source, count)
                        : getString(video ? R.string.preview_video_source : R.string.preview_photo_source), false);
        downloadButton.setText(count > 1 ? getString(R.string.quick_download_all, count) : getString(R.string.download_action));
        downloadButton.setVisibility(View.VISIBLE);
        openAppButton.setVisibility(View.VISIBLE);
        String previewUrl = resolved.items.get(0).previewUrl;
        if (previewUrl != null) loadPreview(previewUrl);
    }

    private void loadPreview(String imageUrl) {
        int size = preview.getLayoutParams().width;
        imageExecutor.execute(() -> {
            try {
                Bitmap bitmap = imageLoader.remoteThumbnail(imageUrl, size, size);
                if (bitmap != null) runOnUiThread(() -> { preview.setImageBitmap(bitmap); UiMotion.fadeIn(preview); });
            } catch (Exception ignored) { }
        });
    }

    private void download() {
        if (result == null) return;
        downloadButton.setEnabled(false);
        downloadButton.setText(R.string.download_started);
        openAppButton.setVisibility(View.GONE);
        message.setText(result.items.size() > 1 ? R.string.status_downloads_started : R.string.status_download_started);
        Map<MediaResolver.MediaItem, Long> ids = downloadController.start(result.items);
        try { HistoryRepository.of(this).add(source, result, result.items, ids); } catch (Exception ignored) { }
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
        message.setTextColor(getColor(isError ? R.color.error : R.color.muted));
    }

    // The sheet belongs to the moment the bubble was tapped: once the user goes elsewhere it closes,
    // so the next tap reads the clipboard again.
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        if (downloadReceiverRegistered) unregisterReceiver(downloadReceiver);
        uiHandler.removeCallbacksAndMessages(null);
        resolverExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        // Let photo saves that already started complete even when the sheet is dismissed.
        saveExecutor.shutdown();
        super.onDestroy();
    }
}
