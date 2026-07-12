package app.instasave;

import android.app.Activity;
import android.app.DownloadManager;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private Button analyzeButton;
    private TextView statusText;
    private LinearLayout historyContainer;
    private TextView emptyHistory;
    private LinearLayout previewContainer;
    private ImageView previewImage;
    private TextView previewTitle;
    private TextView previewMeta;
    private Button downloadButton;
    private TextView selectionLabel;
    private LinearLayout carouselSelectionContainer;
    // Checkbox create dinamicamente, uno per elemento del carosello mostrato.
    private final List<CheckBox> carouselSelections = new ArrayList<>();
    // Risultato dell'ultima analisi: rimane in memoria fino al download o a un nuovo link.
    private MediaResolver.Result pendingResult;
    private String pendingSource;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        urlInput = findViewById(R.id.urlInput);
        analyzeButton = findViewById(R.id.analyzeButton);
        statusText = findViewById(R.id.statusText);
        historyContainer = findViewById(R.id.historyContainer);
        emptyHistory = findViewById(R.id.emptyHistory);
        previewContainer = findViewById(R.id.previewContainer);
        previewImage = findViewById(R.id.previewImage);
        previewTitle = findViewById(R.id.previewTitle);
        previewMeta = findViewById(R.id.previewMeta);
        downloadButton = findViewById(R.id.downloadButton);
        selectionLabel = findViewById(R.id.selectionLabel);
        carouselSelectionContainer = findViewById(R.id.carouselSelectionContainer);

        analyzeButton.setOnClickListener(v -> resolveAndDownload());
        downloadButton.setOnClickListener(v -> downloadPending());
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                analyzeButton.setEnabled(isValidUrl(s.toString()));
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
                showStatus("Link ricevuto da Instagram. Pronto per l'analisi.", false);
            }
        }
    }

    private void resolveAndDownload() {
        String source = urlInput.getText().toString().trim();
        analyzeButton.setEnabled(false);
        showStatus("Analisi del contenuto in corso...", false);
        // La richiesta di rete resta fuori dal thread grafico per non bloccare l'interfaccia.
        executor.execute(() -> {
            try {
                MediaResolver.Result result = new MediaResolver().resolve(source);
                runOnUiThread(() -> {
                    pendingResult = result;
                    pendingSource = source;
                    showPreview(result);
                    showStatus("Contenuto pronto per il download.", false);
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
        // Per un post singolo l'unico elemento è sempre selezionato; per un carousel si usano le checkbox.
        List<MediaResolver.MediaItem> selected = selectedItems();
        if (selected.isEmpty()) {
            showStatus("Seleziona almeno un elemento da scaricare.", true);
            return;
        }
        for (MediaResolver.MediaItem item : selected) enqueueDownload(item);
        remember(pendingSource, pendingResult);
        renderHistory();
        downloadButton.setEnabled(false);
        downloadButton.setText("Download avviato");
        showStatus(selected.size() > 1 ? "Download avviati. Li trovi nelle notifiche."
                : "Download avviato. Lo trovi nelle notifiche.", false);
    }

    private void showPreview(MediaResolver.Result result) {
        boolean isCarousel = result.items.size() > 1;
        previewTitle.setText(isCarousel ? "Carosello pronto" : result.type.equals("video") ? "Video pronto" : "Foto pronta");
        previewMeta.setText(isCarousel ? result.items.size() + " elementi dal post pubblico"
                : result.type.equals("video") ? "Anteprima dal post pubblico" : "Immagine dal post pubblico");
        previewImage.setImageDrawable(null);
        downloadButton.setEnabled(true);
        downloadButton.setText(isCarousel ? "Scarica selezionati" : "Scarica");
        renderCarouselSelections(result); // Aggiunge le opzioni solo quando ci sono più elementi.
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

        // Tre colonne di miniature quadrate, come la griglia della bacheca Instagram.
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        carouselSelectionContainer.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int gap = dp(6);
        int cellSize = (getResources().getDisplayMetrics().widthPixels - dp(72) - gap * 2) / 3;

        // Le celle sono create qui perché il loro numero dipende dal carousel analizzato.
        for (int i = 0; i < result.items.size(); i++) {
            MediaResolver.MediaItem item = result.items.get(i);
            FrameLayout cell = new FrameLayout(this);
            cell.setBackgroundResource(R.drawable.bg_history_icon);
            ImageView thumbnail = new ImageView(this);
            thumbnail.setContentDescription("Anteprima elemento " + (i + 1));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(thumbnail, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            CheckBox choice = new CheckBox(this);
            choice.setChecked(true);
            choice.setContentDescription("Seleziona elemento " + (i + 1) + " · "
                    + ("video".equals(item.type) ? "Video" : "Foto"));
            // Icona personalizzata: fondo viola e spunta bianca per la selezione attiva.
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
        // L'ordine delle checkbox corrisponde all'ordine dei media restituiti dal resolver.
        for (int i = 0; i < pendingResult.items.size() && i < carouselSelections.size(); i++) {
            if (carouselSelections.get(i).isChecked()) selected.add(pendingResult.items.get(i));
        }
        return selected;
    }

    private void updateDownloadButton() {
        // Mantiene testo e stato del pulsante coerenti con le selezioni dell'utente.
        int count = 0;
        for (CheckBox choice : carouselSelections) if (choice.isChecked()) count++;
        downloadButton.setEnabled(count > 0);
        downloadButton.setText(count == 0 ? "Seleziona elementi" : "Scarica " + count + " selezionati");
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
        // Le miniature sono facoltative: se una non è disponibile, la checkbox resta comunque usabile.
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
        request.setDescription("Salvataggio in Download/Instasave");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instasave/" + item.filename);
        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
    }

    private void savePhotoAsJpeg(MediaResolver.MediaItem item) {
        executor.execute(() -> {
            Uri destination = null;
            try {
                Bitmap image = fetchBitmap(item.downloadUrl, 20_000);
                if (image == null) throw new IllegalStateException("Il file ricevuto non è un'immagine valida.");
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    throw new IllegalStateException("Il salvataggio JPEG richiede Android 10 o successivo.");
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, jpegFilename(item.filename));
                values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Instasave");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IllegalStateException("Impossibile creare il file JPEG.");
                try (java.io.OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null || !image.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                        throw new IllegalStateException("Impossibile convertire l'immagine in JPEG.");
                    }
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(destination, values, null, null);
                runOnUiThread(() -> showStatus("Foto JPEG salvata in Download/Instasave.", false));
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
            emptyHistory.setVisibility(history.length() == 0 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < history.length(); i++) addHistoryItem(history.getJSONObject(i));
        } catch (Exception ignored) {
            emptyHistory.setVisibility(View.VISIBLE);
        }
    }

    private void addHistoryItem(JSONObject item) throws Exception {
        View row = LayoutInflater.from(this).inflate(R.layout.item_history, historyContainer, false);
        String type = item.optString("type", "auto");
        ((TextView) row.findViewById(R.id.itemIcon)).setText(type.equals("photo") ? "Foto" : type.equals("story") ? "Storia" : type.equals("carousel") ? "Multi" : "Video");
        ((TextView) row.findViewById(R.id.itemTitle)).setText(type.equals("carousel") ? "Carosello Instagram" : "Contenuto Instagram");
        ((TextView) row.findViewById(R.id.itemMeta)).setText(labelFor(type) + " · Scaricato ora");
        historyContainer.addView(row);
        String preview = item.optString("preview", null);
        if (preview != null && !preview.isEmpty()) loadHistoryPreview(row, preview);
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

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Non è stato possibile elaborare questo contenuto pubblico." : message;
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
        if ("photo".equals(type)) return "Foto";
        if ("story".equals(type)) return "Storia";
        if ("carousel".equals(type)) return "Carosello";
        return "Video";
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
