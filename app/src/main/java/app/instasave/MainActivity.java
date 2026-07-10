package app.instasave;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
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
    private RadioGroup typeGroup;
    private LinearLayout historyContainer;
    private TextView emptyHistory;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        urlInput = findViewById(R.id.urlInput);
        analyzeButton = findViewById(R.id.analyzeButton);
        statusText = findViewById(R.id.statusText);
        typeGroup = findViewById(R.id.typeGroup);
        historyContainer = findViewById(R.id.historyContainer);
        emptyHistory = findViewById(R.id.emptyHistory);

        findViewById(R.id.pasteButton).setOnClickListener(v -> pasteLink());
        analyzeButton.setOnClickListener(v -> resolveAndDownload());
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                analyzeButton.setEnabled(isValidUrl(s.toString()));
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

    private void pasteLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                urlInput.setText(extractUrl(String.valueOf(clip.getItemAt(0).coerceToText(this))));
            }
        }
    }

    private void resolveAndDownload() {
        String source = urlInput.getText().toString().trim();
        String type = selectedType();
        analyzeButton.setEnabled(false);
        showStatus("Analisi del contenuto in corso...", false);
        executor.execute(() -> {
            try {
                MediaResolver.Result result = new MediaResolver().resolve(source, type);
                enqueueDownload(result);
                runOnUiThread(() -> {
                    remember(source, result.type);
                    renderHistory();
                    showStatus("Download avviato. Lo trovi nelle notifiche.", false);
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

    private void enqueueDownload(MediaResolver.Result result) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(result.downloadUrl));
        request.setTitle(result.filename);
        request.setDescription("Salvataggio in Download/Instasave");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS + "/Instasave", result.filename);
        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
    }

    private String selectedType() {
        int id = typeGroup.getCheckedRadioButtonId();
        if (id == R.id.typePhoto) return "photo";
        if (id == R.id.typeVideo) return "video";
        if (id == R.id.typeStory) return "story";
        return "auto";
    }

    private void remember(String url, String type) {
        try {
            JSONArray history = new JSONArray(getPreferences(MODE_PRIVATE).getString(HISTORY_KEY, "[]"));
            JSONArray next = new JSONArray();
            JSONObject current = new JSONObject().put("url", url).put("type", type);
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
        ((TextView) row.findViewById(R.id.itemIcon)).setText(type.equals("photo") ? "Foto" : type.equals("story") ? "Storia" : "Video");
        ((TextView) row.findViewById(R.id.itemTitle)).setText(extractUrl(item.getString("url")).replace("https://", ""));
        ((TextView) row.findViewById(R.id.itemMeta)).setText(labelFor(type) + " · Appena aggiunto");
        historyContainer.addView(row);
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
        return "Video";
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
