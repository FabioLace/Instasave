package app.instasave;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves public Instagram permalinks using only public page data (no login/session). */
final class MediaResolver {
    private static final Pattern JSON_SCRIPT = Pattern.compile(
            "<script[^>]+type=[\\\"']application/json[\\\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static final class MediaItem {
        final String downloadUrl;
        final String filename;
        final String type;
        final String previewUrl;

        MediaItem(String downloadUrl, String filename, String type, String previewUrl) {
            this.downloadUrl = downloadUrl;
            this.filename = filename;
            this.type = type;
            this.previewUrl = previewUrl;
        }
    }

    static final class Result {
        final List<MediaItem> items;
        final String type;

        Result(List<MediaItem> items) {
            this.items = items;
            this.type = items.size() > 1 ? "carousel" : items.get(0).type;
        }
    }

    Result resolve(String sourceUrl, String requestedType) throws Exception {
        if (isDirectMediaUrl(sourceUrl)) {
            String type = sourceUrl.toLowerCase(Locale.ROOT).contains(".mp4") ? "video" : "photo";
            MediaItem item = new MediaItem(sourceUrl, "instasave_" + System.currentTimeMillis() + extensionFor(sourceUrl),
                    type, type.equals("photo") ? sourceUrl : null);
            return new Result(Collections.singletonList(item));
        }
        if (!isInstagramPermalink(sourceUrl)) {
            throw new IllegalArgumentException("Incolla un permalink Instagram pubblico o un URL media diretto.");
        }

        String html = fetchHtml(sourceUrl);

        // Instagram embeds the page's own GraphQL response as inline JSON; when present it gives
        // full-resolution URLs and carousel items, so it's tried before falling back to og: tags.
        JSONObject mediaNode = findMediaNode(html);
        if (mediaNode != null) {
            List<MediaItem> items = mediaItemsFrom(mediaNode);
            if (!items.isEmpty()) return new Result(items);
        }

        String videoUrl = openGraphContent(html, "og:video:secure_url");
        if (videoUrl == null) videoUrl = openGraphContent(html, "og:video");
        String imageUrl = openGraphContent(html, "og:image");
        String mediaUrl = videoUrl != null ? videoUrl : imageUrl;
        if (mediaUrl == null) {
            throw new IllegalStateException("Instagram non ha esposto un media scaricabile per questo contenuto.");
        }
        String type = videoUrl != null ? "video" : "photo";
        String filename = "instasave_" + System.currentTimeMillis() + (type.equals("video") ? ".mp4" : ".jpg");
        return new Result(Collections.singletonList(new MediaItem(mediaUrl, filename, type, imageUrl)));
    }

    private static String fetchHtml(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Instasave/1.0 (Android)");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("Il contenuto pubblico non è disponibile.");
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }
        return body.toString();
    }

    private static JSONObject findMediaNode(String html) {
        Matcher matcher = JSON_SCRIPT.matcher(html);
        while (matcher.find()) {
            String content = matcher.group(1);
            if (!content.contains("shortcode_media")) continue;
            try {
                JSONObject found = searchForMediaNode(new JSONObject(content));
                if (found != null) return found;
            } catch (Exception ignored) { }
        }
        return null;
    }

    // Field names below (xdt_shortcode_media / shortcode_media) come from Instagram's own internal
    // GraphQL response, embedded for client-side rendering. Unofficial and undocumented, so any
    // resolve() call that doesn't find this shape falls back to og: tags instead of failing outright.
    private static JSONObject searchForMediaNode(Object node) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Object direct = obj.opt("xdt_shortcode_media");
            if (direct == null) direct = obj.opt("shortcode_media");
            if (direct instanceof JSONObject) return (JSONObject) direct;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONObject found = searchForMediaNode(obj.opt(keys.next()));
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = searchForMediaNode(array.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<MediaItem> mediaItemsFrom(JSONObject mediaNode) {
        List<MediaItem> items = new ArrayList<>();
        JSONObject sidecar = mediaNode.optJSONObject("edge_sidecar_to_children");
        JSONArray edges = sidecar != null ? sidecar.optJSONArray("edges") : null;
        if (edges != null) {
            for (int i = 0; i < edges.length(); i++) {
                JSONObject child = edges.optJSONObject(i);
                JSONObject childNode = child != null ? child.optJSONObject("node") : null;
                if (childNode != null) addMediaItem(items, childNode, i);
            }
        } else {
            addMediaItem(items, mediaNode, 0);
        }
        return items;
    }

    private static void addMediaItem(List<MediaItem> items, JSONObject node, int index) {
        boolean isVideo = node.optBoolean("is_video", false);
        String displayUrl = node.optString("display_url", null);
        String videoUrl = node.optString("video_url", null);
        String url = isVideo && videoUrl != null ? videoUrl : displayUrl;
        if (url == null) return;
        String type = isVideo && videoUrl != null ? "video" : "photo";
        String filename = "instasave_" + System.currentTimeMillis() + "_" + index + (type.equals("video") ? ".mp4" : ".jpg");
        items.add(new MediaItem(url, filename, type, displayUrl));
    }

    private static boolean isDirectMediaUrl(String url) {
        String clean = url.toLowerCase(Locale.ROOT).split("\\?")[0];
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") || clean.endsWith(".webp") || clean.endsWith(".mp4");
    }

    private static String extensionFor(String url) {
        String clean = url.toLowerCase(Locale.ROOT).split("\\?")[0];
        int index = clean.lastIndexOf('.');
        return index > clean.lastIndexOf('/') ? clean.substring(index) : ".mp4";
    }

    private static boolean isInstagramPermalink(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null && (host.equals("instagram.com") || host.equals("www.instagram.com"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String openGraphContent(String html, String property) {
        String escaped = Pattern.quote(property);
        Pattern propertyFirst = Pattern.compile("<meta[^>]+(?:property|name)=[\\\"']" + escaped + "[\\\"'][^>]+content=[\\\"']([^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE);
        Pattern contentFirst = Pattern.compile("<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+(?:property|name)=[\\\"']" + escaped + "[\\\"']", Pattern.CASE_INSENSITIVE);
        Matcher match = propertyFirst.matcher(html);
        boolean found = match.find();
        if (!found) {
            match = contentFirst.matcher(html);
            found = match.find();
        }
        return found ? Html.fromHtml(match.group(1), Html.FROM_HTML_MODE_LEGACY).toString() : null;
    }
}
