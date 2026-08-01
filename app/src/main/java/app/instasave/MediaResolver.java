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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves public Instagram permalinks using only public page data (no login/session). */
final class MediaResolver {
    private static final Pattern JSON_SCRIPT = Pattern.compile(
            "<script[^>]+type=[\\\"']application/json[\\\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT = Pattern.compile(
            "<script[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern EMBED_CONTEXT_JSON = Pattern.compile(
            "\\\"contextJSON\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"",
            Pattern.DOTALL);
    private static final Pattern EMBEDDED_MEDIA_IMAGE = Pattern.compile(
            "<img(?=[^>]*\\bclass=[\\\"'][^\\\"']*\\bEmbeddedMediaImage\\b[^\\\"']*[\\\"'])[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile(
            "\\b%s=[\\\"'](.*?)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SRCSET_ENTRY = Pattern.compile("(.+?)\\s+(\\d+)w");

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

    Result resolve(String sourceUrl) throws Exception {
        if (isDirectMediaUrl(sourceUrl)) {
            String type = sourceUrl.toLowerCase(Locale.ROOT).contains(".mp4") ? "video" : "photo";
            String extension = type.equals("photo") ? ".jpg" : extensionFor(sourceUrl, ".mp4");
            MediaItem item = new MediaItem(sourceUrl, "instasave_" + System.currentTimeMillis() + extension,
                    type, type.equals("photo") ? sourceUrl : null);
            return new Result(Collections.singletonList(item));
        }
        if (!isInstagramPermalink(sourceUrl)) {
            throw new IllegalArgumentException("Paste a public Instagram permalink or a direct media URL.");
        }

        // Instagram embeds the page's own GraphQL response as inline JSON; when present it gives
        // full-resolution URLs. Keep it as a fallback: the public embed is more complete for
        // carousel children.
        String shortcode = shortcodeFrom(sourceUrl);
        // Both documents are needed for the complete fallback chain. Fetch them concurrently so
        // their network latency is not paid back-to-back on every analysis.
        String html;
        String embedHtml;
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            Future<String> pageRequest = requests.submit(() -> fetchHtml(sourceUrl));
            Future<String> embedRequest = requests.submit(() -> publicEmbedHtml(sourceUrl, shortcode));
            html = getPage(pageRequest);
            embedHtml = getPage(embedRequest);
        } finally {
            requests.shutdownNow();
        }

        JSONObject mediaNode = findMediaNode(html, shortcode);
        List<MediaItem> pageItems = null;
        if (mediaNode != null) {
            pageItems = mediaItemsFrom(mediaNode);
        }

        // The public embed carries carousel children in its contextJSON. That JSON is itself
        // stored as a string inside the embed bootstrap response, so it needs the extra pass in
        // searchForMediaNode() below.
        if (embedHtml != null) {
            JSONObject embedMediaNode = findMediaNode(embedHtml, shortcode);
            if (embedMediaNode != null) {
                List<MediaItem> items = mediaItemsFrom(embedMediaNode);
                if (!items.isEmpty()) return new Result(items);
            }
        }
        if (pageItems != null && !pageItems.isEmpty()) return new Result(pageItems);

        String videoUrl = openGraphContent(html, "og:video:secure_url");
        if (videoUrl == null) videoUrl = openGraphContent(html, "og:video");
        if (videoUrl != null) {
            String filename = "instasave_" + System.currentTimeMillis() + extensionFor(videoUrl, ".mp4");
            return new Result(Collections.singletonList(new MediaItem(videoUrl, filename, "video", null)));
        }

        // The permalink page exposes og:image only as a small, often cropped preview. Instagram's
        // public embed exposes the post image and its responsive sources, including the largest one.
        String imageUrl = imageFromPublicEmbed(embedHtml);
        if (imageUrl == null) {
            throw new IllegalStateException("Instagram did not expose downloadable media for this content.");
        }
        String filename = "instasave_" + System.currentTimeMillis() + ".jpg";
        return new Result(Collections.singletonList(new MediaItem(imageUrl, filename, "photo", imageUrl)));
    }

    private static String getPage(Future<String> request) throws Exception {
        try {
            return request.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Unable to read this public content.", cause);
        }
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
            throw new IllegalStateException("This public content is unavailable.");
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

    private static JSONObject findMediaNode(String html, String expectedShortcode) {
        // Primo tentativo: script JSON dichiarati esplicitamente nella pagina.
        Matcher matcher = JSON_SCRIPT.matcher(html);
        while (matcher.find()) {
            JSONObject found = mediaNodeFromJson(matcher.group(1), expectedShortcode);
            if (found != null) return found;
        }
        // Depending on the web response, Instagram can place the same bootstrap JSON in a
        // regular JavaScript tag instead of type="application/json". In particular this is
        // common for carousel posts, so inspect those tags as well.
        Matcher scriptMatcher = SCRIPT.matcher(html);
        while (scriptMatcher.find()) {
            String content = scriptMatcher.group(1);
            if (!content.contains("shortcode_media") && !content.contains("edge_sidecar_to_children")
                    && !content.contains("carousel_media")) continue;
            JSONObject found = mediaNodeFromJson(firstJsonObject(content), expectedShortcode);
            if (found != null) return found;
        }
        Matcher contextMatcher = EMBED_CONTEXT_JSON.matcher(html);
        while (contextMatcher.find()) {
            try {
                String encodedValue = contextMatcher.group(1);
                String contextJson = new JSONObject("{\"value\":\"" + encodedValue + "\"}")
                        .getString("value");
                JSONObject found = searchForMediaNode(new JSONObject(contextJson), expectedShortcode);
                if (found != null) return found;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static JSONObject mediaNodeFromJson(String json, String expectedShortcode) {
        if (json == null) return null;
        try {
            return searchForMediaNode(new JSONObject(json), expectedShortcode);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns the first complete JSON object embedded in a JavaScript expression. */
    private static String firstJsonObject(String source) {
        if (source == null) return null;
        int start = source.indexOf('{');
        if (start < 0) return null;
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        return null;
    }

    // Field names below (xdt_shortcode_media / shortcode_media) come from Instagram's own internal
    // GraphQL response, embedded for client-side rendering. Unofficial and undocumented, so any
    // resolve() call that doesn't find this shape falls back to og: tags instead of failing outright.
    private static JSONObject searchForMediaNode(Object node, String expectedShortcode) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (matchesShortcode(obj, expectedShortcode) && isMediaObject(obj)) return obj;
            Object direct = obj.opt("xdt_shortcode_media");
            if (direct == null) direct = obj.opt("shortcode_media");
            if (direct instanceof JSONObject) {
                JSONObject media = (JSONObject) direct;
                if (matchesShortcode(media, expectedShortcode)) return media;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONObject found = searchForMediaNode(obj.opt(keys.next()), expectedShortcode);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = searchForMediaNode(array.opt(i), expectedShortcode);
                if (found != null) return found;
            }
        } else if (node instanceof String) {
            String value = (String) node;
            if (!value.contains("shortcode_media")) return null;
            try {
                return searchForMediaNode(new JSONObject(value), expectedShortcode);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static List<MediaItem> mediaItemsFrom(JSONObject mediaNode) {
        List<MediaItem> items = new ArrayList<>();
        // Instagram usa sia il formato GraphQL "edges" sia il vecchio array "carousel_media".
        JSONObject sidecar = mediaNode.optJSONObject("edge_sidecar_to_children");
        JSONArray edges = sidecar != null ? sidecar.optJSONArray("edges") : null;
        if (edges != null) {
            for (int i = 0; i < edges.length(); i++) {
                JSONObject child = edges.optJSONObject(i);
                JSONObject childNode = child != null ? child.optJSONObject("node") : null;
                if (childNode != null) addMediaItem(items, childNode, i);
            }
        } else if (mediaNode.optJSONArray("carousel_media") != null) {
            JSONArray children = mediaNode.optJSONArray("carousel_media");
            for (int i = 0; i < children.length(); i++) {
                JSONObject childNode = children.optJSONObject(i);
                if (childNode != null) addMediaItem(items, childNode, i);
            }
        } else {
            addMediaItem(items, mediaNode, 0);
        }
        return items;
    }

    private static void addMediaItem(List<MediaItem> items, JSONObject node, int index) {
        boolean isVideo = node.optBoolean("is_video", false);
        String displayUrl = bestImageUrl(node);
        String videoUrl = nonEmpty(node.optString("video_url", null));
        if (videoUrl == null) {
            // Nei payload meno recenti le URL video sono raccolte in una lista di versioni.
            JSONArray versions = node.optJSONArray("video_versions");
            if (versions != null && versions.length() > 0) {
                JSONObject version = versions.optJSONObject(0);
                if (version != null) videoUrl = nonEmpty(version.optString("url", null));
            }
        }
        String url = isVideo && videoUrl != null ? videoUrl : displayUrl;
        if (url == null) return;
        String type = isVideo && videoUrl != null ? "video" : "photo";
        String filename = "instasave_" + System.currentTimeMillis() + "_" + index
                + (type.equals("video") ? extensionFor(url, ".mp4") : ".jpg");
        items.add(new MediaItem(url, filename, type, displayUrl));
    }

    private static String publicEmbedHtml(String sourceUrl, String shortcode) {
        try {
            String embedUrl = sourceUrl.replaceFirst("[?#].*$", "");
            if (!embedUrl.endsWith("/")) embedUrl += "/";
            String html = fetchHtml(embedUrl + "embed/");
            return shortcode == null || !html.contains(shortcode) || isInstagramErrorPage(html) ? null : html;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String imageFromPublicEmbed(String html) {
        if (html == null) return null;
        Matcher imageMatch = EMBEDDED_MEDIA_IMAGE.matcher(html);
        if (!imageMatch.find()) return null;
        String tag = imageMatch.group();
        String best = bestFromSrcSet(attribute(tag, "srcset"));
        return best != null ? best : attribute(tag, "src");
    }

    private static String attribute(String tag, String name) {
        Matcher match = Pattern.compile(String.format(Locale.ROOT, HTML_ATTRIBUTE.pattern(), Pattern.quote(name)),
                HTML_ATTRIBUTE.flags()).matcher(tag);
        return match.find() ? Html.fromHtml(match.group(1), Html.FROM_HTML_MODE_LEGACY).toString() : null;
    }

    private static String bestFromSrcSet(String srcSet) {
        if (srcSet == null) return null;
        String bestUrl = null;
        int bestWidth = -1;
        for (String entry : srcSet.split(",")) {
            Matcher match = SRCSET_ENTRY.matcher(entry.trim());
            if (!match.matches()) continue;
            int width = Integer.parseInt(match.group(2));
            if (width > bestWidth) {
                bestWidth = width;
                bestUrl = match.group(1).trim();
            }
        }
        return bestUrl;
    }

    private static boolean matchesShortcode(JSONObject media, String expectedShortcode) {
        if (expectedShortcode == null) return false;
        String shortcode = nonEmpty(media.optString("shortcode", null));
        if (shortcode == null) shortcode = nonEmpty(media.optString("code", null));
        return expectedShortcode.equals(shortcode);
    }

    private static boolean isMediaObject(JSONObject object) {
        return object.has("edge_sidecar_to_children") || object.has("carousel_media")
                || object.has("display_url") || object.has("image_versions2")
                || object.has("video_url") || object.has("video_versions");
    }

    /**
     * Instagram can expose several resized versions. Pick the largest declared one rather than
     * blindly using the first thumbnail returned by the page data.
     */
    private static String bestImageUrl(JSONObject node) {
        String bestUrl = nonEmpty(node.optString("display_url", null));
        if (bestUrl == null) bestUrl = nonEmpty(node.optString("thumbnail_src", null));
        long bestArea = 0;
        JSONArray displayResources = node.optJSONArray("display_resources");
        ImageCandidate displayCandidate = bestCandidate(displayResources, "src", "config_width", "config_height");
        if (displayCandidate != null) {
            bestUrl = displayCandidate.url;
            bestArea = displayCandidate.area;
        }

        JSONObject imageVersions = node.optJSONObject("image_versions2");
        if (imageVersions != null) {
            ImageCandidate versionCandidate = bestCandidate(imageVersions.optJSONArray("candidates"), "url", "width", "height");
            if (versionCandidate != null && versionCandidate.area >= bestArea) bestUrl = versionCandidate.url;
        }
        return bestUrl;
    }

    private static ImageCandidate bestCandidate(JSONArray candidates, String urlKey, String widthKey, String heightKey) {
        if (candidates == null) return null;
        ImageCandidate best = null;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;
            String url = nonEmpty(candidate.optString(urlKey, null));
            if (url == null) continue;
            long area = (long) candidate.optInt(widthKey, 0) * candidate.optInt(heightKey, 0);
            if (best == null || area > best.area) best = new ImageCandidate(url, area);
        }
        return best;
    }

    private static String shortcodeFrom(String url) {
        if (url == null) return null;
        try {
            String[] segments = new URI(url).getPath().split("/");
            for (int i = 0; i + 1 < segments.length; i++) {
                if ("p".equals(segments[i]) || "reel".equals(segments[i]) || "tv".equals(segments[i])) {
                    return nonEmpty(segments[i + 1]);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean isInstagramErrorPage(String html) {
        return html.contains("PolarisErrorRoot.entrypoint")
                || html.contains("\"pageID\":\"httpErrorPage\"");
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static final class ImageCandidate {
        final String url;
        final long area;

        ImageCandidate(String url, long area) {
            this.url = url;
            this.area = area;
        }
    }

    private static boolean isDirectMediaUrl(String url) {
        String clean = url.toLowerCase(Locale.ROOT).split("\\?")[0];
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") || clean.endsWith(".webp") || clean.endsWith(".mp4");
    }

    private static String extensionFor(String url) {
        return extensionFor(url, ".mp4");
    }

    private static String extensionFor(String url, String fallback) {
        String clean = url.toLowerCase(Locale.ROOT).split("\\?")[0];
        int index = clean.lastIndexOf('.');
        return index > clean.lastIndexOf('/') ? clean.substring(index) : fallback;
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
