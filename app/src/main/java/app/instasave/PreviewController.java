package app.instasave;

import android.app.Activity;
import android.content.Context;
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

import static app.instasave.UiMotion.dp;

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
        title.setText(readyTitle(result));
        image.animate().cancel();
        image.setAlpha(1f);
        image.setImageDrawable(null);
        renderSelections(result);
        refreshDetails(result);
        container.setVisibility(View.VISIBLE);
        UiMotion.reveal(container, 14, 0);
        String previewUrl = result.items.get(0).previewUrl;
        if (previewUrl != null) imageLoader.loadInto(image, previewUrl, dp(activity, 88), imageExecutor, null);
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
            downloadButton.setText(R.string.download_started);
            return;
        }
        if (resetPending) {
            downloadButton.setEnabled(false);
            return;
        }
        int selected = selectedCount();
        downloadButton.setEnabled(selected > 0);
        downloadButton.setText(selected == 0 ? activity.getString(R.string.download_select_items)
                : selected == 1 ? activity.getString(R.string.download_action)
                : activity.getString(R.string.download_selected, selected));
    }

    void showDownloadFinished(boolean failed) {
        downloadButton.setEnabled(false);
        downloadButton.setText(failed ? R.string.download_failed : R.string.download_completed);
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
        int gap = dp(activity, 6);
        int cellSize = (activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 72) - gap * 2) / 3;
        for (int i = 0; i < result.items.size(); i++) addSelectionCell(grid, result.items.get(i), i, gap, cellSize);
    }

    // STYLE: carousel grid cell built in code: thumbnail on drawable/bg_thumbnail,
    // checkbox drawable/carousel_checkbox, caption 11sp muted. Grid gap and cell size are in the method above.
    private void addSelectionCell(GridLayout grid, MediaResolver.MediaItem item, int position, int gap, int cellSize) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        FrameLayout cell = new FrameLayout(activity);
        cell.setBackgroundResource(R.drawable.bg_thumbnail);
        tile.addView(cell, new LinearLayout.LayoutParams(cellSize, cellSize));
        ImageView thumbnail = new ImageView(activity);
        thumbnail.setContentDescription(activity.getString(R.string.preview_item_thumbnail, position + 1));
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cell.addView(thumbnail, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        CheckBox choice = new CheckBox(activity);
        choice.setChecked(true);
        choice.setContentDescription(selectionDescription(position, item));
        choice.setButtonDrawable(R.drawable.carousel_checkbox);
        choice.setPadding(0, 0, 0, 0);
        choice.setOnCheckedChangeListener((button, checked) -> {
            UiMotion.selection(cell, checked);
            if (selectionListener != null) selectionListener.onSelectionChanged();
        });
        selections.add(choice);
        cell.addView(choice, new FrameLayout.LayoutParams(dp(activity, 42), dp(activity, 42), Gravity.END | Gravity.BOTTOM));
        cell.setOnClickListener(v -> choice.setChecked(!choice.isChecked()));
        TextView details = new TextView(activity);
        details.setTextColor(activity.getColor(R.color.muted));
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        details.setMaxLines(3);
        details.setEllipsize(TextUtils.TruncateAt.END);
        details.setPadding(0, dp(activity, 4), 0, 0);
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
        if (item.previewUrl != null) imageLoader.loadInto(thumbnail, item.previewUrl, cellSize, imageExecutor, null);
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
            String description = sourceLabel(activity, result);
            String details = formatDetails(result.items.get(0));
            meta.setText(details.isEmpty() ? description : description + "\n" + details);
            return;
        }
        meta.setText(sourceLabel(activity, result));
        for (int i = 0; i < result.items.size() && i < detailsLabels.size(); i++) {
            String details = formatDetails(result.items.get(i));
            TextView label = detailsLabels.get(i);
            label.setText(details);
            label.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
            if (!details.isEmpty()) {
                CheckBox choice = selections.get(i);
                choice.setContentDescription(selectionDescription(i, result.items.get(i)) + " · " + details);
            }
        }
    }

    /** The card's title, also used by the bubble's sheet. */
    static int readyTitle(MediaResolver.Result result) {
        if (result.items.size() > 1) return R.string.preview_carousel_ready;
        return "video".equals(result.type) ? R.string.preview_video_ready : R.string.preview_photo_ready;
    }

    /**
     * Where the content comes from, also used by the bubble's sheet. The author, when the response
     * exposed it, says more than the generic "from the public post".
     */
    static String sourceLabel(Context context, MediaResolver.Result result) {
        int count = result.items.size();
        if (result.username != null) {
            return count > 1 ? context.getString(R.string.preview_items_by, count, result.username) : "@" + result.username;
        }
        if (count > 1) return context.getString(R.string.preview_items_source, count);
        return context.getString("video".equals(result.items.get(0).type) ? R.string.preview_video_source : R.string.preview_photo_source);
    }

    private String selectionDescription(int position, MediaResolver.MediaItem item) {
        return activity.getString(R.string.preview_select_item, position + 1) + " · "
                + activity.getString("video".equals(item.type) ? R.string.type_video : R.string.type_photo);
    }

    private String formatDetails(MediaResolver.MediaItem item) {
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
            parts.add(bytes >= 1_000_000 ? activity.getString(R.string.preview_source_mb, bytes / 1_000_000)
                    : bytes >= 1_000 ? activity.getString(R.string.preview_source_kb, bytes / 1_000)
                    : activity.getString(R.string.preview_source_bytes, item.sourceSizeBytes));
        }
        return TextUtils.join(" · ", parts);
    }

    private int selectedCount() {
        if (selections.isEmpty()) return container.getVisibility() == View.VISIBLE ? 1 : 0;
        int count = 0;
        for (CheckBox choice : selections) if (choice.isChecked()) count++;
        return count;
    }
}
