package app.instasave;

import android.app.Activity;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Owns the preview surface and the selection state of carousel items. */
final class PreviewController {
    interface SelectionListener { void onSelectionChanged(); }

    private final Activity activity;
    private final ImageLoader imageLoader;
    private final Executor imageExecutor;
    private final Executor metadataExecutor;
    private final LinearLayout container;
    private final ImageView image;
    private final TextView title;
    private final TextView meta;
    private final Button downloadButton;
    private final TextView selectionLabel;
    private final LinearLayout selectionsContainer;
    private final List<CheckBox> selections = new ArrayList<>();
    private final List<TextView> detailsLabels = new ArrayList<>();
    private SelectionListener selectionListener;
    private MediaResolver.Result displayedResult;

    PreviewController(Activity activity, ImageLoader imageLoader, Executor imageExecutor, Executor metadataExecutor) {
        this.activity = activity;
        this.imageLoader = imageLoader;
        this.imageExecutor = imageExecutor;
        this.metadataExecutor = metadataExecutor;
        container = activity.findViewById(R.id.previewContainer);
        image = activity.findViewById(R.id.previewImage);
        title = activity.findViewById(R.id.previewTitle);
        meta = activity.findViewById(R.id.previewMeta);
        downloadButton = activity.findViewById(R.id.downloadButton);
        selectionLabel = activity.findViewById(R.id.selectionLabel);
        selectionsContainer = activity.findViewById(R.id.carouselSelectionContainer);
    }

    void setSelectionListener(SelectionListener listener) { selectionListener = listener; }
    void setDownloadClickListener(View.OnClickListener listener) {
        downloadButton.setOnClickListener(v -> {
            UiMotion.tap(v);
            listener.onClick(v);
        });
    }

    void show(MediaResolver.Result result, boolean downloadInProgress) {
        displayedResult = result;
        boolean carousel = result.items.size() > 1;
        title.setText(carousel ? "Carousel ready" : "video".equals(result.type) ? "Video ready" : "Photo ready");
        image.animate().cancel();
        image.setAlpha(1f);
        image.setImageDrawable(null);
        renderSelections(result);
        refreshDetails(result);
        container.setVisibility(View.VISIBLE);
        UiMotion.reveal(container, 14, 0);
        String previewUrl = result.items.get(0).previewUrl;
        if (previewUrl != null) loadPreview(previewUrl);
        probeDetails(result);
        updateDownloadButton(downloadInProgress, false);
    }

    void clear() {
        displayedResult = null;
        container.animate().cancel();
        container.setVisibility(View.GONE);
        container.setAlpha(1f);
        container.setTranslationY(0f);
        image.animate().cancel();
        image.setTag(null);
        image.setImageDrawable(null);
        selectionsContainer.removeAllViews();
        selections.clear();
        detailsLabels.clear();
    }

    List<MediaResolver.MediaItem> selectedItems(MediaResolver.Result result) {
        List<MediaResolver.MediaItem> selected = new ArrayList<>();
        if (result.items.size() == 1) {
            selected.add(result.items.get(0));
            return selected;
        }
        for (int i = 0; i < result.items.size() && i < selections.size(); i++) {
            if (selections.get(i).isChecked()) selected.add(result.items.get(i));
        }
        return selected;
    }

    void updateDownloadButton(boolean inProgress, boolean resetPending) {
        if (inProgress) {
            downloadButton.setEnabled(false);
            downloadButton.setText("Download started");
            return;
        }
        if (resetPending) {
            downloadButton.setEnabled(false);
            return;
        }
        int selected = selectedCount();
        downloadButton.setEnabled(selected > 0);
        downloadButton.setText(selected == 0 ? "Select items" : selected == 1 ? "Download" : "Download " + selected + " selected");
    }

    void showDownloadFinished(boolean failed) {
        downloadButton.setEnabled(false);
        downloadButton.setText(failed ? "Download failed" : "Download completed");
    }

    private void renderSelections(MediaResolver.Result result) {
        selections.clear();
        detailsLabels.clear();
        selectionsContainer.removeAllViews();
        boolean carousel = result.items.size() > 1;
        selectionLabel.setVisibility(carousel ? View.VISIBLE : View.GONE);
        selectionsContainer.setVisibility(carousel ? View.VISIBLE : View.GONE);
        if (!carousel) return;
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        selectionsContainer.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int gap = dp(6);
        int cellSize = (activity.getResources().getDisplayMetrics().widthPixels - dp(72) - gap * 2) / 3;
        for (int i = 0; i < result.items.size(); i++) addSelectionCell(grid, result.items.get(i), i, gap, cellSize);
    }

    private void addSelectionCell(GridLayout grid, MediaResolver.MediaItem item, int position, int gap, int cellSize) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        FrameLayout cell = new FrameLayout(activity);
        cell.setBackgroundResource(R.drawable.bg_history_icon);
        tile.addView(cell, new LinearLayout.LayoutParams(cellSize, cellSize));
        ImageView thumbnail = new ImageView(activity);
        thumbnail.setContentDescription("Item preview " + (position + 1));
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cell.addView(thumbnail, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        CheckBox choice = new CheckBox(activity);
        choice.setChecked(true);
        choice.setContentDescription("Select item " + (position + 1) + " · " + ("video".equals(item.type) ? "Video" : "Photo"));
        choice.setButtonDrawable(R.drawable.carousel_checkbox);
        choice.setPadding(0, 0, 0, 0);
        choice.setOnCheckedChangeListener((button, checked) -> {
            UiMotion.selection(cell, checked);
            if (selectionListener != null) selectionListener.onSelectionChanged();
        });
        selections.add(choice);
        cell.addView(choice, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END | Gravity.BOTTOM));
        cell.setOnClickListener(v -> choice.setChecked(!choice.isChecked()));
        TextView details = new TextView(activity);
        details.setTextColor(activity.getColor(R.color.muted));
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        details.setMaxLines(3);
        details.setEllipsize(TextUtils.TruncateAt.END);
        details.setPadding(0, dp(4), 0, 0);
        details.setVisibility(View.GONE);
        tile.addView(details, new LinearLayout.LayoutParams(cellSize, LinearLayout.LayoutParams.WRAP_CONTENT));
        detailsLabels.add(details);
        tile.setOnClickListener(v -> choice.setChecked(!choice.isChecked()));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = cellSize;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.setMargins(position % 3 == 0 ? 0 : gap, 0, 0, gap);
        grid.addView(tile, params);
        UiMotion.reveal(tile, 8, Math.min(position, 6) * 45L);
        if (item.previewUrl != null) loadThumbnail(thumbnail, item.previewUrl, cellSize);
    }

    private void probeDetails(MediaResolver.Result result) {
        for (MediaResolver.MediaItem item : result.items) {
            metadataExecutor.execute(() -> {
                MediaProbe.inspect(item);
                activity.runOnUiThread(() -> {
                    if (displayedResult == result) refreshDetails(result);
                });
            });
        }
    }

    private void refreshDetails(MediaResolver.Result result) {
        boolean carousel = result.items.size() > 1;
        if (!carousel) {
            MediaResolver.MediaItem item = result.items.get(0);
            String description = "video".equals(item.type) ? "Preview from the public post" : "Image from the public post";
            String details = formatDetails(item);
            meta.setText(details.isEmpty() ? description : description + "\n" + details);
            return;
        }
        meta.setText(result.items.size() + " items from the public post");
        for (int i = 0; i < result.items.size() && i < detailsLabels.size(); i++) {
            String details = formatDetails(result.items.get(i));
            TextView label = detailsLabels.get(i);
            label.setText(details);
            label.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
            if (!details.isEmpty()) {
                CheckBox choice = selections.get(i);
                choice.setContentDescription("Select item " + (i + 1) + " · "
                        + ("video".equals(result.items.get(i).type) ? "Video" : "Photo") + " · " + details);
            }
        }
    }

    private static String formatDetails(MediaResolver.MediaItem item) {
        List<String> parts = new ArrayList<>(3);
        if (item.width > 0 && item.height > 0) parts.add(item.width + "×" + item.height);
        if (Double.isFinite(item.durationSeconds) && item.durationSeconds > 0) {
            long seconds = Math.max(1, Math.round(item.durationSeconds));
            parts.add(seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600,
                    (seconds / 60) % 60, seconds % 60)
                    : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
        }
        if (item.sourceSizeBytes > 0) {
            double bytes = item.sourceSizeBytes;
            parts.add(bytes >= 1_000_000 ? String.format(Locale.ROOT, "Source %.1f MB", bytes / 1_000_000)
                    : bytes >= 1_000 ? String.format(Locale.ROOT, "Source %.0f KB", bytes / 1_000)
                    : "Source " + item.sourceSizeBytes + " B");
        }
        return TextUtils.join(" · ", parts);
    }

    private void loadPreview(String imageUrl) { loadImage(image, imageUrl, dp(88)); }
    private void loadThumbnail(ImageView target, String imageUrl, int size) { loadImage(target, imageUrl, size); }

    private void loadImage(ImageView target, String imageUrl, int size) {
        target.setTag(imageUrl);
        imageExecutor.execute(() -> {
            try {
                Bitmap bitmap = imageLoader.remoteThumbnail(imageUrl, size, size);
                if (bitmap != null) activity.runOnUiThread(() -> {
                    if (imageUrl.equals(target.getTag())) {
                        target.setImageBitmap(bitmap);
                        UiMotion.fadeIn(target);
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private int selectedCount() {
        if (selections.isEmpty()) return container.getVisibility() == View.VISIBLE ? 1 : 0;
        int count = 0;
        for (CheckBox choice : selections) if (choice.isChecked()) count++;
        return count;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
