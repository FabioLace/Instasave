package app.instasave;

import android.app.Activity;
import android.graphics.Bitmap;
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
import java.util.concurrent.Executor;

/** Owns the preview surface and the selection state of carousel items. */
final class PreviewController {
    interface SelectionListener { void onSelectionChanged(); }

    private final Activity activity;
    private final ImageLoader imageLoader;
    private final Executor imageExecutor;
    private final LinearLayout container;
    private final ImageView image;
    private final TextView title;
    private final TextView meta;
    private final Button downloadButton;
    private final TextView selectionLabel;
    private final LinearLayout selectionsContainer;
    private final List<CheckBox> selections = new ArrayList<>();
    private SelectionListener selectionListener;

    PreviewController(Activity activity, ImageLoader imageLoader, Executor imageExecutor) {
        this.activity = activity;
        this.imageLoader = imageLoader;
        this.imageExecutor = imageExecutor;
        container = activity.findViewById(R.id.previewContainer);
        image = activity.findViewById(R.id.previewImage);
        title = activity.findViewById(R.id.previewTitle);
        meta = activity.findViewById(R.id.previewMeta);
        downloadButton = activity.findViewById(R.id.downloadButton);
        selectionLabel = activity.findViewById(R.id.selectionLabel);
        selectionsContainer = activity.findViewById(R.id.carouselSelectionContainer);
    }

    void setSelectionListener(SelectionListener listener) { selectionListener = listener; }
    void setDownloadClickListener(View.OnClickListener listener) { downloadButton.setOnClickListener(listener); }

    void show(MediaResolver.Result result, boolean downloadInProgress) {
        boolean carousel = result.items.size() > 1;
        title.setText(carousel ? "Carousel ready" : "video".equals(result.type) ? "Video ready" : "Photo ready");
        meta.setText(carousel ? result.items.size() + " items from the public post"
                : "video".equals(result.type) ? "Preview from the public post" : "Image from the public post");
        image.setImageDrawable(null);
        renderSelections(result);
        container.setVisibility(View.VISIBLE);
        String previewUrl = result.items.get(0).previewUrl;
        if (previewUrl != null) loadPreview(previewUrl);
        updateDownloadButton(downloadInProgress, false);
    }

    void clear() {
        container.setVisibility(View.GONE);
        image.setTag(null);
        image.setImageDrawable(null);
        selectionsContainer.removeAllViews();
        selections.clear();
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
        FrameLayout cell = new FrameLayout(activity);
        cell.setBackgroundResource(R.drawable.bg_history_icon);
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
            if (selectionListener != null) selectionListener.onSelectionChanged();
        });
        selections.add(choice);
        cell.addView(choice, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END | Gravity.BOTTOM));
        cell.setOnClickListener(v -> choice.setChecked(!choice.isChecked()));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = cellSize;
        params.height = cellSize;
        params.setMargins(position % 3 == 0 ? 0 : gap, 0, 0, gap);
        grid.addView(cell, params);
        if (item.previewUrl != null) loadThumbnail(thumbnail, item.previewUrl, cellSize);
    }

    private void loadPreview(String imageUrl) { loadImage(image, imageUrl, dp(88)); }
    private void loadThumbnail(ImageView target, String imageUrl, int size) { loadImage(target, imageUrl, size); }

    private void loadImage(ImageView target, String imageUrl, int size) {
        target.setTag(imageUrl);
        imageExecutor.execute(() -> {
            try {
                Bitmap bitmap = imageLoader.remoteThumbnail(imageUrl, size, size);
                if (bitmap != null) activity.runOnUiThread(() -> {
                    if (imageUrl.equals(target.getTag())) target.setImageBitmap(bitmap);
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
