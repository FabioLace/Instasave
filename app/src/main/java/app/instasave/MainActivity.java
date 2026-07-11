package app.instasave;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.graphics.BitmapFactory;
import android.content.Context;
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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

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

        analyzeButton.setOnClickListener(v -> resolveAndDownload());
        downloadButton.setOnClickListener(v -> downloadPending());
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                analyzeButton.setEnabled(isValidUrl(s.toString()));
                previewContainer.setVisibility(View.GONE);
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
        executor.execute(() -> {
            try {
                MediaResolver.Result result = new MediaResolver().resolve(source, "auto");
                runOnUiThread(() -> {
                    pendingResult = result;
                    pendingSource = source;
                    showPreview(result);
                    showStatus("Contenuto pronto per il download.", false);
                    analyzeButton.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showStatus(error.getMessage(), true);
                    analyzeButton.setEnabled(true);
                });
            }
        });
    }

    private void downloadPending() {
        if (pendingResult == null) return;
        for (MediaResolver.MediaItem item : pendingResult.items) enqueueDownload(item);
        remember(pendingSource, pendingResult);
        renderHistory();
        downloadButton.setEnabled(false);
        downloadButton.setText("Download avviato");
        boolean isCarousel = pendingResult.items.size() > 1;
        showStatus(isCarousel ? "Download avviati. Li trovi nelle notifiche." : "Download avviato. Lo trovi nelle notifiche.", false);
    }

    private void showPreview(MediaResolver.Result result) {
        boolean isCarousel = result.items.size() > 1;
        previewTitle.setText(isCarousel ? "Carosello pronto" : result.type.equals("video") ? "Video pronto" : "Foto pronta");
        previewMeta.setText(isCarousel ? result.items.size() + " elementi dal post pubblico"
                : result.type.equals("video") ? "Anteprima dal post pubblico" : "Immagine dal post pubblico");
        previewImage.setImageDrawable(null);
        downloadButton.setEnabled(true);
        downloadButton.setText(isCarousel ? "Scarica tutto" : "Scarica");
        previewContainer.setVisibility(View.VISIBLE);
        String previewUrl = result.items.get(0).previewUrl;
        if (previewUrl != null) loadPreview(previewUrl);
    }

    private void loadPreview(String imageUrl) {
        executor.execute(() -> {
            try {
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(imageUrl).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
                android.graphics.Bitmap image;
                try (java.io.InputStream stream = connection.getInputStream()) {
                    image = BitmapFactory.decodeStream(stream);
                } finally {
                    connection.disconnect();
                }
                runOnUiThread(() -> previewImage.setImageBitmap(image));
            } catch (Exception ignored) { }
        });
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
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(item.downloadUrl).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(20_000);
                connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
                android.graphics.Bitmap image;
                try (java.io.InputStream stream = connection.getInputStream()) {
                    image = BitmapFactory.decodeStream(stream);
                } finally {
                    connection.disconnect();
                }
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
                    if (output == null || !image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, output)) {
                        throw new IllegalStateException("Impossibile convertire l'immagine in JPEG.");
                    }
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(destination, values, null, null);
                runOnUiThread(() -> showStatus("Foto JPEG salvata in Download/Instasave.", false));
            } catch (Exception error) {
                if (destination != null) getContentResolver().delete(destination, null, null);
                runOnUiThread(() -> showStatus(error.getMessage(), true));
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
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(imageUrl).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
                android.graphics.Bitmap image;
                try (java.io.InputStream stream = connection.getInputStream()) {
                    image = BitmapFactory.decodeStream(stream);
                } finally {
                    connection.disconnect();
                }
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
