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
    private static final Pattern MEDIA_PK = Pattern.compile("\\d+");
    private static final Pattern EMBED_USERNAME = Pattern.compile(
            "class=[\\\"']UsernameText[\\\"'][^>]*>([^<]+)<", Pattern.CASE_INSENSITIVE);

    static final class MediaItem {
        final String downloadUrl;
        /** Set once the post author is known, see {@link Result}. */
        String filename;
        final String type;
        final String previewUrl;
        final long resolutionArea;
        final String sourceId;
        volatile int width;
        volatile int height;
        double durationSeconds;
        volatile long sourceSizeBytes = -1;
        /** The post declares this item as a video, but the response only carried its cover. */
        boolean videoMissing;

        MediaItem(String downloadUrl, String filename, String type, String previewUrl) {
            this(downloadUrl, filename, type, previewUrl, 0, 0, 0, null);
        }

        MediaItem(String downloadUrl, String filename, String type, String previewUrl,
                  int width, int height, double durationSeconds, String sourceId) {
            this.downloadUrl = downloadUrl;
            this.filename = filename;
            this.type = type;
            this.previewUrl = previewUrl;
            this.width = width;
            this.height = height;
            this.resolutionArea = (long) width * height;
            this.durationSeconds = durationSeconds;
            this.sourceId = sourceId;
        }
    }

    static final class Result {
        final List<MediaItem> items;
        final String type;
        /** The post author's username, or null when the response didn't expose it. */
        final String username;

        Result(List<MediaItem> items) { this(items, null); }

        Result(List<MediaItem> items, String username) {
            this.items = items;
            this.type = items.size() > 1 ? "carousel" : items.get(0).type;
            this.username = username;
            // Items are built before the author is known: name the files after them here.
            String label = username == null ? null : FILENAME_UNSAFE.matcher(username).replaceAll("_");
            if (label != null && !label.isEmpty()) {
                for (MediaItem item : items) {
                    if (item.filename.startsWith(FILENAME_PREFIX)) {
                        item.filename = FILENAME_PREFIX + label + "_"
                                + item.filename.substring(FILENAME_PREFIX.length());
                    }
                }
            }
        }
    }

    private static final String FILENAME_PREFIX = "instasave_";
    private static final Pattern FILENAME_UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    Result resolve(String requestedUrl) throws Exception {
        if (isDirectMediaUrl(requestedUrl)) {
            String type = requestedUrl.toLowerCase(Locale.ROOT).contains(".mp4") ? "video" : "photo";
            String extension = type.equals("photo") ? ".jpg" : extensionFor(requestedUrl, ".mp4");
            MediaItem item = new MediaItem(requestedUrl, FILENAME_PREFIX + System.currentTimeMillis() + extension,
                    type, type.equals("photo") ? requestedUrl : null);
            return new Result(Collections.singletonList(item));
        }
        if (!isInstagramPermalink(requestedUrl)) {
            throw new UserFacingException(R.string.error_unsupported_link);
        }
        String sourceUrl = onMainHost(requestedUrl);

        // Both the permalink and its public embed can expose media variants. Compare their
        // declared resolutions instead of assuming either response always has the largest one.
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

        // Reels and IGTV links are always videos. Other posts reveal it through their data.
        boolean videoExpected = isVideoPermalink(sourceUrl);
        Result result = null;
        for (int attempt = 0; ; attempt++) {
            result = resultFrom(html, embedHtml, shortcode, videoExpected);
            if (result != null && !lacksVideo(result, videoExpected)) return result;
            // Instagram intermittently serves logged-out visitors a permalink page without the
            // media node (only the embed is left, and for videos it only has the cover). Those
            // responses are transient, so ask for the page again before giving up.
            if (attempt >= PAGE_RETRIES) break;
            Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
            try {
                html = fetchHtml(sourceUrl);
            } catch (Exception ignored) {
                // Keep the previous response and its partial data for the next attempt.
            }
        }
        if (result == null) throw new UserFacingException(R.string.error_no_media);
        // Saving the cover as a photo is what users perceived as "random images instead of
        // reels". Report the missing video instead, so a later attempt can fetch the real file.
        throw new UserFacingException(R.string.error_video_unavailable);
    }

    private static final int PAGE_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 700;

    /** Builds the best result from one page/embed pair, or null if neither exposes any media. */
    private static Result resultFrom(String html, String embedHtml, String shortcode, boolean videoExpected) {
        JSONObject mediaNode = findMediaNode(html, shortcode);
        List<MediaItem> pageItems = Collections.emptyList();
        if (mediaNode != null) {
            pageItems = mediaItemsFrom(mediaNode);
        }

        // The public embed carries carousel children in its contextJSON. That JSON is itself
        // stored as a string inside the embed bootstrap response, so it needs the extra pass in
        // searchForMediaNode() below.
        List<MediaItem> embedItems = Collections.emptyList();
        JSONObject embedMediaNode = embedHtml == null ? null : findMediaNode(embedHtml, shortcode);
        if (embedMediaNode != null) {
            embedItems = mediaItemsFrom(embedMediaNode);
        }
        String username = usernameOf(mediaNode);
        if (username == null) username = usernameOf(embedMediaNode);
        if (username == null) username = usernameFromPublicEmbed(embedHtml);
        List<MediaItem> items = bestAvailableItems(pageItems, embedItems);
        boolean singleCoverOnly = items.size() == 1 && items.get(0).videoMissing;
        if (!items.isEmpty() && !singleCoverOnly) return new Result(items, username);

        // A single video whose data only carried the cover may still be in the og: tags.
        String videoUrl = openGraphContent(html, "og:video:secure_url");
        if (videoUrl == null) videoUrl = openGraphContent(html, "og:video");
        if (videoUrl != null) {
            String filename = FILENAME_PREFIX + System.currentTimeMillis() + extensionFor(videoUrl, ".mp4");
            String previewUrl = singleCoverOnly ? items.get(0).previewUrl : null;
            return new Result(Collections.singletonList(new MediaItem(videoUrl, filename, "video", previewUrl)),
                    username);
        }
        if (singleCoverOnly) return new Result(items, username);

        // The permalink page exposes og:image only as a small, often cropped preview. Instagram's
        // public embed exposes the post image and its responsive sources, including the largest one.
        String imageUrl = imageFromPublicEmbed(embedHtml);
        if (imageUrl == null) return null;
        String filename = FILENAME_PREFIX + System.currentTimeMillis() + ".jpg";
        MediaItem cover = new MediaItem(imageUrl, filename, "photo", imageUrl);
        // The embed image of a reel is its cover, never the content the user asked for.
        cover.videoMissing = videoExpected || (embedHtml != null && embedDeclaresVideo(embedHtml));
        return new Result(Collections.singletonList(cover), username);
    }

    /** The permalink page nests the author under "user", the embed and GraphQL under "owner". */
    private static String usernameOf(JSONObject mediaNode) {
        if (mediaNode == null) return null;
        for (String key : new String[] {"user", "owner"}) {
            JSONObject author = mediaNode.optJSONObject(key);
            String username = author == null ? null : nonEmpty(author.optString("username", null));
            if (username != null) return username;
        }
        return null;
    }

    private static String usernameFromPublicEmbed(String html) {
        if (html == null) return null;
        Matcher match = EMBED_USERNAME.matcher(html);
        return match.find() ? nonEmpty(match.group(1).trim()) : null;
    }

    private static boolean lacksVideo(Result result, boolean videoExpected) {
        for (MediaItem item : result.items) {
            if (item.videoMissing) return true;
        }
        return videoExpected && result.items.size() == 1 && !"video".equals(result.items.get(0).type);
    }

    private static boolean embedDeclaresVideo(String embedHtml) {
        return embedHtml.contains("\\\"is_video\\\":true") || embedHtml.contains("\"is_video\":true");
    }

    private static boolean isVideoPermalink(String url) {
        try {
            String path = new URI(url).getPath();
            return path != null && (path.startsWith("/reel/") || path.startsWith("/reels/")
                    || path.startsWith("/tv/"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String getPage(Future<String> request) throws Exception {
        try {
            return request.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new UserFacingException(R.string.error_unreadable, cause);
        }
    }

    private static String fetchHtml(String url) throws Exception {
        HttpURLConnection connection = Http.open(url, 15_000);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        StringBuilder body = new StringBuilder();
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new UserFacingException(R.string.error_unavailable);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
        } finally {
            connection.disconnect();
        }
        return body.toString();
    }

    private static JSONObject findMediaNode(String html, String expectedShortcode) {
        // First attempt: JSON scripts explicitly declared on the page.
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
        // Instagram uses both the GraphQL "edges" format and the older "carousel_media" array.
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

    private static List<MediaItem> bestAvailableItems(List<MediaItem> pageItems, List<MediaItem> embedItems) {
        if (pageItems.isEmpty()) return embedItems;
        if (embedItems.isEmpty()) return pageItems;
        // Preserve the complete carousel. Match children by ID where possible, since one
        // response can omit a child and shift all subsequent indexes.
        List<MediaItem> primary = pageItems.size() > embedItems.size() ? pageItems : embedItems;
        List<MediaItem> alternate = primary == pageItems ? embedItems : pageItems;
        List<MediaItem> selected = new ArrayList<>(primary.size());
        for (int i = 0; i < primary.size(); i++) {
            MediaItem item = primary.get(i);
            MediaItem match = null;
            if (item.sourceId != null) {
                for (MediaItem candidate : alternate) {
                    if (item.sourceId.equals(candidate.sourceId)) {
                        match = candidate;
                        break;
                    }
                }
            }
            if (match == null && primary.size() == alternate.size()) {
                MediaItem candidate = alternate.get(i);
                // A single-media post has exactly one item per response: they are the same media
                // even if one response used an ID shape mediaIdOf() doesn't know yet.
                if (primary.size() == 1 || item.sourceId == null || candidate.sourceId == null) {
                    match = candidate;
                }
            }
            if (match == null) {
                selected.add(item);
            } else {
                MediaItem preferred = !item.type.equals(match.type)
                        ? ("video".equals(item.type) ? item : match)
                        : (match.resolutionArea > item.resolutionArea ? match : item);
                if (preferred.durationSeconds <= 0) {
                    preferred.durationSeconds = preferred == item ? match.durationSeconds : item.durationSeconds;
                }
                selected.add(preferred);
            }
        }
        return selected;
    }

    private static void addMediaItem(List<MediaItem> items, JSONObject node, int index) {
        ImageCandidate display = bestImage(node);
        String displayUrl = display == null ? null : display.url;
        String directVideoUrl = nonEmpty(node.optString("video_url", null));
        ImageCandidate version = bestCandidate(node.optJSONArray("video_versions"), "url", "width", "height");
        // video_url has no reliable rendition dimensions. When versions advertise their
        // resolutions, use the largest declared variant instead of the first one.
        ImageCandidate video = version != null ? version
                : directVideoUrl == null ? null : new ImageCandidate(directVideoUrl, 0, 0);
        // Don't rely solely on the "is_video" flag: in more recent reel payloads it can be
        // missing even when video_url/video_versions are present, and without this check the
        // video was being discarded in favor of the preview only.
        boolean isVideo = video != null;
        String url = isVideo ? video.url : displayUrl;
        if (url == null) return;
        String type = isVideo ? "video" : "photo";
        String filename = FILENAME_PREFIX + System.currentTimeMillis() + "_" + index
                + (type.equals("video") ? extensionFor(url, ".mp4") : ".jpg");
        String sourceId = mediaIdOf(node);
        double duration = isVideo ? node.optDouble("video_duration", node.optDouble("duration", 0)) : 0;
        MediaItem item = new MediaItem(url, filename, type, displayUrl,
                isVideo ? video.width : display.width, isVideo ? video.height : display.height,
                duration, sourceId);
        // The public embed marks reels with is_video but never includes the video URL, and the
        // permalink page omits the media node whenever Instagram gates logged-out traffic.
        // Remember that only the cover arrived, so resolve() never saves it in place of the video.
        item.videoMissing = !isVideo && declaresVideo(node);
        items.add(item);
    }

    /**
     * The same media carries differently shaped IDs per response: the permalink page uses
     * "POLARIS_<pk>", the embed "<pk>" and some payloads "<pk>_<ownerId>". Reduce them all to
     * the numeric pk, otherwise the page's video and the embed's cover never match and the
     * cover wins.
     */
    private static String mediaIdOf(JSONObject node) {
        String id = nonEmpty(node.optString("pk", null));
        if (id == null) id = nonEmpty(node.optString("id", null));
        if (id == null) return null;
        Matcher digits = MEDIA_PK.matcher(id);
        return digits.find() ? digits.group() : id;
    }

    private static boolean declaresVideo(JSONObject node) {
        String typename = node.optString("__typename", "");
        String productType = node.optString("product_type", "");
        return node.optBoolean("is_video", false) || node.optInt("media_type", 0) == 2
                || typename.endsWith("GraphVideo") || "clips".equals(productType)
                || "igtv".equals(productType);
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
    private static ImageCandidate bestImage(JSONObject node) {
        String bestUrl = nonEmpty(node.optString("display_url", null));
        JSONObject dimensions = node.optJSONObject("dimensions");
        ImageCandidate best = bestUrl == null ? null : new ImageCandidate(bestUrl,
                dimensions == null ? 0 : dimensions.optInt("width", 0),
                dimensions == null ? 0 : dimensions.optInt("height", 0));
        if (bestUrl == null) {
            bestUrl = nonEmpty(node.optString("thumbnail_src", null));
            if (bestUrl != null) best = new ImageCandidate(bestUrl, 0, 0);
        }
        JSONArray displayResources = node.optJSONArray("display_resources");
        ImageCandidate displayCandidate = bestCandidate(displayResources, "src", "config_width", "config_height");
        if (displayCandidate != null && (best == null || displayCandidate.area > best.area)) best = displayCandidate;

        JSONObject imageVersions = node.optJSONObject("image_versions2");
        if (imageVersions != null) {
            ImageCandidate versionCandidate = bestCandidate(imageVersions.optJSONArray("candidates"), "url", "width", "height");
            if (versionCandidate != null && (best == null || versionCandidate.area > best.area)) best = versionCandidate;
        }
        return best;
    }

    private static ImageCandidate bestCandidate(JSONArray candidates, String urlKey, String widthKey, String heightKey) {
        if (candidates == null) return null;
        ImageCandidate best = null;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate == null) continue;
            String url = nonEmpty(candidate.optString(urlKey, null));
            if (url == null) continue;
            ImageCandidate version = new ImageCandidate(url,
                    candidate.optInt(widthKey, 0), candidate.optInt(heightKey, 0));
            if (best == null || version.area > best.area) best = version;
        }
        return best;
    }

    private static String shortcodeFrom(String url) {
        if (url == null) return null;
        try {
            String[] segments = new URI(url).getPath().split("/");
            for (int i = 0; i + 1 < segments.length; i++) {
                if ("p".equals(segments[i]) || "reel".equals(segments[i]) || "reels".equals(segments[i])
                        || "tv".equals(segments[i])) {
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
        final int width;
        final int height;
        final long area;

        ImageCandidate(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.area = (long) width * height;
        }
    }

    private static boolean isDirectMediaUrl(String url) {
        String clean = url.toLowerCase(Locale.ROOT).split("\\?")[0];
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") || clean.endsWith(".webp") || clean.endsWith(".mp4");
    }

    private static String extensionFor(String url, String fallback) {
        String clean = url.toLowerCase(Locale.ROOT).split("\\?")[0];
        int index = clean.lastIndexOf('.');
        return index > clean.lastIndexOf('/') ? clean.substring(index) : fallback;
    }

    private static boolean isInstagramPermalink(String url) {
        try {
            return isInstagramHost(new URI(url).getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** The hosts that serve post permalinks; other subdomains (help, about…) don't. Shared with the history. */
    static boolean isInstagramHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("instagram.com") || lower.equals("www.instagram.com") || lower.equals("m.instagram.com");
    }

    /** The same permalink on www.instagram.com, the host the page and embed parsing was written against. */
    private static String onMainHost(String url) throws Exception {
        URI uri = new URI(url);
        if ("www.instagram.com".equalsIgnoreCase(uri.getHost())) return url;
        return new URI("https", "www.instagram.com", uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
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
