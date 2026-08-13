package app.instasave;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/** Owns persistence and backward-compatible parsing of the download history. */
final class HistoryRepository {
    private static final String HISTORY_KEY = "history";
    private static final String EXPANDED_KEY = "history_expanded";
    private final SharedPreferences preferences;

    HistoryRepository(SharedPreferences preferences) { this.preferences = preferences; }
    JSONArray entries() throws Exception { return new JSONArray(preferences.getString(HISTORY_KEY, "[]")); }
    boolean isExpanded() { return preferences.getBoolean(EXPANDED_KEY, true); }
    void setExpanded(boolean expanded) { preferences.edit().putBoolean(EXPANDED_KEY, expanded).apply(); }
    void clear() { preferences.edit().remove(HISTORY_KEY).apply(); }
    void remove(int position) throws Exception {
        JSONArray current = entries(), next = new JSONArray();
        for (int i = 0; i < current.length(); i++) if (i != position) next.put(current.getJSONObject(i));
        preferences.edit().putString(HISTORY_KEY, next.toString()).apply();
    }
    void add(String url, MediaResolver.Result result, List<MediaResolver.MediaItem> items,
             Map<MediaResolver.MediaItem, Long> ids) throws Exception {
        JSONArray next = new JSONArray();
        JSONObject current = new JSONObject().put("url", url).put("type", result.type);
        if (!result.items.isEmpty() && result.items.get(0).previewUrl != null) current.put("preview", result.items.get(0).previewUrl);
        JSONArray files = new JSONArray();
        for (MediaResolver.MediaItem item : items) {
            JSONObject file = new JSONObject().put("name", "photo".equals(item.type) ? jpegName(item.filename) : item.filename).put("type", item.type);
            if (item.previewUrl != null && !item.previewUrl.isEmpty()) file.put("preview", item.previewUrl);
            Long id = ids.get(item); if (id != null) file.put("downloadId", id);
            files.put(file);
        }
        current.put("files", files); next.put(current);
        JSONArray previous = entries();
        for (int i = 0; i < previous.length() && i < 4; i++) next.put(previous.getJSONObject(i));
        preferences.edit().putString(HISTORY_KEY, next.toString()).apply();
    }
    static JSONArray files(JSONObject item) throws Exception {
        JSONArray files = item.optJSONArray("files"); if (files != null) return files;
        files = new JSONArray(); JSONArray legacy = item.optJSONArray("images");
        if (legacy != null) for (int i = 0; i < legacy.length(); i++) { String name = legacy.optString(i, null); if (name != null) files.put(new JSONObject().put("name", name).put("type", "photo")); }
        return files;
    }
    static String jpegName(String filename) { int dot = filename.lastIndexOf('.'); return (dot > 0 ? filename.substring(0, dot) : filename) + ".jpg"; }
}
