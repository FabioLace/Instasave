"""Builds docs/code-guide.html: an interactive class map plus annotated excerpts read from the Java sources.

Run it from anywhere after changing the code:

    python3 docs/build-guide.py

Each excerpt is anchored to a line of the source (usually a method signature) and its notes cover lines
counted from there, so code that moves elsewhere in a file is followed automatically. If an anchor is no
longer found exactly once, the build stops and names it: update the anchor, and check the offsets if the
method itself changed.
"""
import datetime
import html
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/app/instasave"
OUT = ROOT / "docs/code-guide.html"
TEMPLATE = ROOT / "docs/code-guide.template.html"

LINES = {p.name: p.read_text().split("\n") for p in SRC.glob("*.java")}


def anchor_line(name, anchor):
    """The 1-based line of the only occurrence of anchor in the file."""
    found = [i + 1 for i, line in enumerate(LINES[name]) if anchor in line]
    if len(found) != 1:
        sys.exit(f"{name}: anchor {anchor!r} found {len(found)} times, expected once")
    return found[0]


def excerpt(name, anchor, chunks):
    """Turns (first, last, note) offsets from the anchor line into absolute line numbers."""
    base = anchor_line(name, anchor)
    return name, [(base + first, base + last, note) for first, last, note in chunks]


def line_count(name):
    text = (SRC / name).read_text()
    return text.count("\n") + (0 if text.endswith("\n") else 1)


def inline(text):
    """Escapes text and turns `x` into <code>x</code>."""
    escaped = html.escape(text, quote=False)
    return re.sub(r"`([^`]+)`", r"<code>\1</code>", escaped)


# ---------------------------------------------------------------- Java highlighting

KEYWORDS = set("""abstract boolean break byte case catch char class continue default do double else enum extends
final finally float for if implements import instanceof int interface long new null package private protected
public return short static super switch synchronized this throw throws try void volatile while true false var""".split())

TOKEN = re.compile(r"""
    (?P<comment>//.*$)
  | (?P<blockstart>/\*.*?(\*/|$))
  | (?P<string>"(?:\\.|[^"\\])*")
  | (?P<char>'(?:\\.|[^'\\])')
  | (?P<annotation>@\w+)
  | (?P<word>\b[A-Za-z_]\w*\b)
  | (?P<number>\b\d[\d_]*[Ll]?\b)
""", re.VERBOSE)


def highlight(lines):
    out = []
    in_block = False
    for raw in lines:
        if in_block:
            end = raw.find("*/")
            if end < 0:
                out.append(f'<span class="tc">{html.escape(raw)}</span>')
                continue
            head, raw = raw[:end + 2], raw[end + 2:]
            in_block = False
            prefix = f'<span class="tc">{html.escape(head)}</span>'
        else:
            prefix = ""
        parts, pos = [], 0
        for m in TOKEN.finditer(raw):
            parts.append(html.escape(raw[pos:m.start()]))
            text = html.escape(m.group())
            kind = m.lastgroup
            if kind in ("comment", "blockstart"):
                parts.append(f'<span class="tc">{text}</span>')
                if kind == "blockstart" and not m.group().endswith("*/"):
                    in_block = True
            elif kind in ("string", "char"):
                parts.append(f'<span class="ts">{text}</span>')
            elif kind == "annotation":
                parts.append(f'<span class="ta">{text}</span>')
            elif kind == "word" and m.group() in KEYWORDS:
                parts.append(f'<span class="tk">{text}</span>')
            else:
                parts.append(text)
            pos = m.end()
        parts.append(html.escape(raw[pos:]))
        out.append(prefix + "".join(parts))
    return out


# ---------------------------------------------------------------- Walkthroughs

GROUPS = [
    ("download", "The path of a download",
     "From the moment a link is analyzed to the moment it's saved and recorded."),
    ("bubble", "The bubble",
     "How the floating shortcut is switched on, dragged around, and turned into a download."),
]

WALKS = [
    dict(id="analyze", group="download", title="Analyzing a link", shot="main-screen.jpg",
         shot_alt="The main screen after analyzing a reel: the link field, a Video ready card with the reel's cover and a Download button.",
         intro="The main screen hands the link to `MediaResolver` on a background thread and waits for the answer.",
         excerpts=[excerpt("MainActivity.java", 'private void resolveLink() {', [
             (0, 4, "The button is disabled first, so a second tap can't start a second analysis. Each analysis takes a new generation number."),
             (5, 8, "`MediaResolver` does network work, so it runs on `resolverExecutor`. `runOnUiThread` brings the result back to the only thread allowed to touch views."),
             (9, 13, "Errors come back through the same thread hop."),
             (14, 24, "A result is dropped when a newer analysis has started, or when the link was edited while it ran. In the second case the \"Analyzing…\" status is cleared too, so it doesn't stay on screen."),
             (25, 30, "Errors go through `UserFacingException.messageFor()`, which picks the translated message when there is one."),
             (31, 39, "From here `PreviewController` draws the card. `pendingResult` is what the Download button will use."),
         ])]),
    dict(id="resolve", group="download", title="resolve(), top to bottom",
         intro="The entry point of the whole analysis. Most of its length handles responses that Instagram serves inconsistently.",
         excerpts=[excerpt("MediaResolver.java", 'Result resolve(String sourceUrl) throws Exception {', [
             (0, 7, "A direct link to a file needs no analysis: it becomes a single item as is."),
             (8, 11, "Anything that isn't instagram.com stops here with a translated error."),
             (12, 28, "The post page and the `/embed/` page are fetched at the same time. They don't always carry the same versions of a file, so both are needed."),
             (29, 45, "Reels and `/tv/` links must produce a video. When the page arrives without its media data, it's fetched again, at most twice, waiting longer each time."),
             (46, 50, "An explicit error is better than a reel's cover saved as if it were the reel."),
         ])]),
    dict(id="json", group="download", title="Where the data hides",
         intro="Instagram embeds the post's data as JSON, in a different place depending on the response. `findMediaNode()` tries them in order, then `searchForMediaNode()` digs the post out.",
         excerpts=[excerpt("MediaResolver.java", 'private static JSONObject findMediaNode(String html', [
             (0, 6, "Most pages declare the JSON in `application/json` script tags."),
             (7, 17, "Carousels often arrive in ordinary script tags instead. The keyword check avoids parsing every script; `firstJsonObject()` cuts the JSON out by counting braces."),
             (18, 29, "The embed keeps its data as JSON inside a JSON string, so it's decoded twice."),
         ]), excerpt("MediaResolver.java", 'private static JSONObject searchForMediaNode(Object node', [
             (0, 9, "A node is the post when its shortcode matches the link and it has media fields. The two known wrapper keys are checked directly."),
             (10, 20, "Otherwise the search walks every key and every array element."),
             (21, 29, "Some values are strings that contain JSON. They're parsed only when they mention `shortcode_media`, to keep this cheap."),
         ])]),
    dict(id="items", group="download", title="Choosing the best file",
         intro="Each response lists several sizes of the same file, and the page and the embed don't always agree. These two methods keep the best of both.",
         excerpts=[excerpt("MediaResolver.java", 'private static void addMediaItem(List<MediaItem> items', [
             (0, 8, "`bestImage()` compares `display_url`, `display_resources` and `image_versions2` and keeps the largest. For videos, `video_versions` declares sizes and wins over `video_url`, which doesn't."),
             (9, 14, "An item is a video whenever a video URL exists: some recent reel payloads leave out `is_video`."),
             (15, 22, "File names get the author's username later, in the `Result` constructor."),
             (23, 28, "A node that declares a video but has no URL for it is flagged, so `resolve()` retries instead of saving the cover."),
         ]), excerpt("MediaResolver.java", 'private static List<MediaItem> bestAvailableItems(', [
             (0, 6, "Starting from the longer list means no carousel item is lost."),
             (7, 26, "Items are paired by media ID. `mediaIdOf()` reduces Instagram's three ID shapes to one; before it did, the page's video and the embed's cover never matched. Without IDs, items are paired by position."),
             (27, 40, "In each pair a video beats a photo, otherwise the larger resolution wins. A duration known to only one side is kept."),
         ])]),
    dict(id="saving", group="download", title="Saving photos and videos",
         intro="Photos and videos take different routes, and the screen hears back once, when all of them are done.",
         excerpts=[excerpt("DownloadController.java", 'Map<MediaResolver.MediaItem, Long> start(List<MediaResolver.MediaItem> items)', [
             (0, 10, "`unfinished` counts the files still in flight. The returned map holds the DownloadManager IDs of the videos; the history stores them to find the files later."),
             (11, 16, "Called by the activity's `BroadcastReceiver` when the system reports a finished download. IDs that belong to other apps are ignored."),
             (17, 22, "Photos skip DownloadManager: they're re-encoded below."),
             (23, 36, "Videos are queued in DownloadManager, which shows its own notification and carries on even if the app closes."),
         ]), excerpt("DownloadController.java", 'private void savePhotoAsJpeg(MediaResolver.MediaItem item)', [
             (0, 6, "On `saveExecutor`, the full image is fetched. Writing to Download through MediaStore needs Android 10."),
             (7, 13, "`IS_PENDING` hides the file from other apps until it's complete."),
             (14, 20, "Compressed at quality 100, then made visible."),
             (21, 26, "A failed save removes the half-written entry."),
         ]), excerpt("DownloadController.java", 'private void finish(boolean succeeded, String failureMessage)', [
             (0, 5, "Every file passes through here once, whether it succeeded or not. At zero the screen gets a single callback."),
         ])]),
    dict(id="history", group="download", title="The history is one string",
         intro="Downloads are recorded when they start, not when they finish, as a JSON array in SharedPreferences.",
         excerpts=[excerpt("HistoryRepository.java", 'void add(String url, MediaResolver.Result result', [
             (0, 8, "One entry per download: the link, the type, the time, a thumbnail and the author when known."),
             (9, 15, "Photo names end in `.jpg` because that's what `DownloadController` writes. The name comes from `DownloadController.jpegFilename()` itself, so the history always looks for the file that was actually saved."),
             (16, 20, "The new entry goes first, followed by at most four older ones."),
             (21, 26, "Entries written by older versions listed images by name only; they're converted when read."),
         ])]),
    dict(id="bubble-on", group="bubble", title="Switching the bubble on",
         intro="Two permissions, asked in order, and a round trip through the system settings.",
         excerpts=[excerpt("MainActivity.java", '@Override protected void onRestart() {', [
             (0, 7, "Runs when the user comes back from the settings page opened further down. `onResume()` would also fire after the notification dialog, which is why it isn't used. The history is re-rendered in `onResume()` instead, because the bubble's translucent sheet only pauses this activity."),
             (8, 14, "The switch only starts or stops the service."),
             (15, 21, "On Android 13 and later, notifications are asked for once: the Hide bubble action lives in the notification."),
             (22, 28, "Drawing over other apps is granted on a settings page, not in a dialog. The flag remembers that the user was sent there."),
             (29, 34, "Whatever the answer about notifications, it carries on: the bubble works without them."),
         ])]),
    dict(id="drag", group="bubble", title="Dragging the bubble", shot="bubble.jpg",
         shot_alt="The Android home screen with the Instasave bubble, a round gradient button with a download arrow, on the right edge.",
         intro="One touch listener tells taps from drags and handles the ✕ target at the bottom of the screen.",
         excerpts=[excerpt("BubbleService.java", '@Override public boolean onTouch(View view, MotionEvent event) {', [
             (0, 9, "The starting point of the finger and of the window."),
             (10, 13, "Movements smaller than the system's touch slop still count as a tap."),
             (14, 24, "Once it's a drag, the window follows the finger and the ✕ appears, growing when the bubble is close enough."),
             (25, 30, "A tap is delivered with `performClick()`, which keeps the bubble usable with TalkBack. Dropped on the ✕, the service stops; anywhere else, the bubble snaps to the nearest edge."),
             (31, 37, "A cancelled gesture snaps back too."),
         ])]),
    dict(id="clipboard", group="bubble", title="Reading the clipboard", shot="quick-sheet.jpg",
         shot_alt="The quick download sheet over the home screen: the reel's cover, Video ready, a Download button and an Open in Instasave link.",
         intro="The reason the bubble opens an activity at all: since Android 10, only the focused app can read the clipboard.",
         excerpts=[excerpt("QuickDownloadActivity.java", '@Override public void onWindowFocusChanged(boolean hasFocus) {', [
             (0, 11, "Focus arrives after `onResume()`, so the clipboard is read here, once."),
             (13, 20, "The same link rules as the main screen, borrowed from `MainActivity`."),
         ]), excerpt("QuickDownloadActivity.java", '@Override protected void onStop() {', [
             (0, 3, "The sheet closes as soon as the user leaves it, so the next tap on the bubble reads the clipboard again."),
             (5, 13, "`shutdown()` rather than `shutdownNow()` lets a photo that's being saved finish after the sheet is gone."),
         ])]),
]


def render_excerpt(name, chunks):
    src = LINES[name]
    first, last = chunks[0][0], chunks[-1][1]
    body = [src[i - 1] for i in range(first, last + 1)]
    indents = [len(l) - len(l.lstrip(" ")) for l in body if l.strip()]
    cut = min(indents) if indents else 0
    lit = highlight([l[cut:] if len(l) >= cut else l.lstrip(" ") for l in body])
    rows = []
    for start, end, note in chunks:
        code = "".join(
            f'<span class="ln">{n}</span><span class="lc">{lit[n - first] or " "}</span>'
            for n in range(start, end + 1))
        rows.append(f'<div class="chunk"><pre class="code"><code>{code}</code></pre>'
                    f'<p class="note">{inline(note)}</p></div>')
    head = (f'<div class="excerpt-head"><span class="file">{name}</span> '
            f'<a class="vscode" data-file="{name}" data-line="{first}">lines {first}–{last}</a></div>')
    return f'<div class="excerpt">{head}{"".join(rows)}</div>'


def render_walk(w):
    shot = ""
    if w.get("shot"):
        shot = (f'<figure class="shot"><img src="img/{w["shot"]}" alt="{html.escape(w["shot_alt"])}" '
                f'></figure>')
    excerpts = "".join(render_excerpt(n, c) for n, c in w["excerpts"])
    return (f'<article class="walk" id="{w["id"]}"><div class="walk-head"><div>'
            f'<h3>{inline(w["title"])}</h3><p class="walk-intro">{inline(w["intro"])}</p></div>{shot}</div>'
            f'{excerpts}</article>')


# ---------------------------------------------------------------- Map

W, H = 170, 36
NODES = {
    # id: (x, y, width, kind, label)
    "launcher": (0, 52, 150, "entry", "Launcher icon"),
    "share": (0, 112, 150, "entry", "Share sheet"),
    "BubbleService": (0, 380, 150, "class", "BubbleService"),
    "MainActivity": (200, 60, 184, "class", "MainActivity"),
    "PreviewController": (200, 150, 184, "class", "PreviewController"),
    "HistoryController": (200, 206, 184, "class", "HistoryController"),
    "QuickDownloadActivity": (200, 380, 184, "class", "QuickDownloadActivity"),
    "MediaResolver": (440, 52, W, "class", "MediaResolver"),
    "MediaProbe": (440, 130, W, "class", "MediaProbe"),
    "ImageLoader": (440, 200, W, "class", "ImageLoader"),
    "DownloadController": (440, 290, W, "class", "DownloadController"),
    "HistoryRepository": (440, 380, W, "class", "HistoryRepository"),
    "instagram": (650, 52, 145, "system", "instagram.com"),
    "cdn": (650, 165, 145, "system", "Instagram CDN"),
    "DownloadManager": (650, 265, 145, "system", "DownloadManager"),
    "MediaStore": (650, 325, 145, "system", "MediaStore"),
    "SharedPreferences": (650, 385, 145, "system", "SharedPreferences"),
}
EDGES = [
    ("launcher", "MainActivity"), ("share", "MainActivity"), ("BubbleService", "QuickDownloadActivity"),
    ("MainActivity", "MediaResolver"), ("MainActivity", "DownloadController"), ("MainActivity", "HistoryRepository"),
    ("QuickDownloadActivity", "MediaResolver"), ("QuickDownloadActivity", "DownloadController"),
    ("QuickDownloadActivity", "HistoryRepository"), ("QuickDownloadActivity", "ImageLoader"),
    ("PreviewController", "ImageLoader"), ("PreviewController", "MediaProbe"),
    ("HistoryController", "HistoryRepository"), ("HistoryController", "ImageLoader"),
    ("HistoryController", "MediaStore"), ("HistoryController", "DownloadManager"),
    ("MediaResolver", "instagram"), ("MediaProbe", "cdn"), ("ImageLoader", "cdn"),
    ("DownloadController", "ImageLoader"), ("DownloadController", "DownloadManager"), ("DownloadController", "MediaStore"),
    ("HistoryRepository", "SharedPreferences"),
]
# MainActivity owns these two; they sit inside its group instead of getting edges.
OWNED = ["PreviewController", "HistoryController"]

INFO = {
    "launcher": dict(text="Opening the app from the home screen starts `MainActivity` with an empty field.", walks=[]),
    "share": dict(text="Instagram's Share menu sends an `ACTION_SEND` intent. `MainActivity` reads the link from it in `receiveSharedLink()`, whether the app was closed or already open.", walks=["analyze"]),
    "BubbleService": dict(file="BubbleService.java", shot="bubble.jpg",
        text="A foreground service that keeps the bubble above other apps. Dragging, edge snapping and the ✕ target that hides it all live here. A tap opens `QuickDownloadActivity`.",
        walks=["drag", "bubble-on"]),
    "MainActivity": dict(file="MainActivity.java", shot="main-screen.jpg",
        text="The full screen. It holds the state of an analysis (the link, the result, whether a download is running) and connects the other classes. It owns `PreviewController` and `HistoryController`, which draw parts of its layout.",
        walks=["analyze", "bubble-on"]),
    "PreviewController": dict(file="PreviewController.java",
        text="Draws the preview card and, for carousels, a three-column grid of checkboxes built in code. Sizes and durations arrive later from `MediaProbe` and are filled in only if the same post is still on screen.",
        walks=["analyze"]),
    "HistoryController": dict(file="HistoryController.java",
        text="Rebuilds the whole history list on every change: with five entries at most, that's simpler than a `RecyclerView`. It checks in the background whether each file still exists, and updates the age labels every minute while an entry is less than an hour old. Open carousels stay open across rebuilds.",
        walks=["history"]),
    "QuickDownloadActivity": dict(file="QuickDownloadActivity.java", shot="quick-sheet.jpg",
        text="The translucent sheet the bubble opens. It downloads every item at once; Open in Instasave hands the link to `MainActivity` to pick carousel items one by one.",
        walks=["clipboard"]),
    "MediaResolver": dict(file="MediaResolver.java",
        text="Turns a link into files. It reads the data Instagram embeds in its own pages, which is undocumented, so this is the class that breaks when Instagram changes.",
        walks=["resolve", "json", "items"]),
    "MediaProbe": dict(file="MediaProbe.java",
        text="One `HEAD` request per file to show its size, and a partial `GET` for photos whose dimensions are unknown. Failures are silent: the details just don't appear.",
        walks=[]),
    "ImageLoader": dict(file="ImageLoader.java",
        text="Downloads thumbnails and decodes them at the size they're shown, with a memory cache of at most 16 MB. `fetchOriginal()` returns the full image for saving.",
        walks=[]),
    "DownloadController": dict(file="DownloadController.java",
        text="Photos are re-encoded as JPEG and written through MediaStore; videos are handed to DownloadManager. It counts completions and calls back once.",
        walks=["saving"]),
    "HistoryRepository": dict(file="HistoryRepository.java",
        text="The whole history is one JSON string in SharedPreferences, newest first, five entries at most. The bubble's sheet writes to the same file.",
        walks=["history"]),
    "instagram": dict(text="The post page and its `/embed/` version, both public. `MediaResolver` fetches them in parallel without logging in.", walks=["resolve"]),
    "cdn": dict(text="Where the actual photo and video files live. The URLs come out of `MediaResolver`.", walks=[]),
    "DownloadManager": dict(text="Android's download service. It downloads videos, shows its own notification and broadcasts when each one finishes.", walks=["saving"]),
    "MediaStore": dict(text="Android's index of media files. Photos are written through it, and the history uses it to find saved files again by name.", walks=["saving"]),
    "SharedPreferences": dict(text="Private key–value storage. It holds the history and whether its section is expanded.", walks=["history"]),
}


def mid(node_id, side):
    x, y, w, _, _ = NODES[node_id]
    return {"right": (x + w, y + H / 2), "left": (x, y + H / 2),
            "top": (x + w / 2, y), "bottom": (x + w / 2, y + H)}[side]


def edge_path(a, b):
    ax = NODES[a][0]
    bx = NODES[b][0]
    if ax == bx:  # same column: vertical
        (x1, y1), (x2, y2) = (mid(a, "top"), mid(b, "bottom")) if NODES[b][1] < NODES[a][1] else (mid(a, "bottom"), mid(b, "top"))
        return f"M{x1:.0f} {y1:.0f} L{x2:.0f} {y2:.0f}"
    (x1, y1), (x2, y2) = mid(a, "right"), mid(b, "left")
    dx = (x2 - x1) / 2
    return f"M{x1:.0f} {y1:.0f} C{x1 + dx:.0f} {y1:.0f} {x2 - dx:.0f} {y2:.0f} {x2:.0f} {y2:.0f}"


def render_map():
    parts = ['<svg class="map" viewBox="0 0 800 430" role="group" aria-label="Class map">']
    parts.append('<defs><marker id="tip" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto">'
                 '<path d="M0 0 L8 4 L0 8 z" class="tip"/></marker>'
                 '<marker id="tip-on" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto">'
                 '<path d="M0 0 L8 4 L0 8 z" class="tip-on"/></marker></defs>')
    parts.append('<text class="col-label" x="0" y="20">Ways in</text>'
                 '<text class="col-label" x="200" y="20">Screens</text>'
                 '<text class="col-label" x="440" y="20">Logic</text>'
                 '<text class="col-label" x="650" y="20">Android and Instagram</text>')
    parts.append('<rect class="group" x="188" y="40" width="208" height="216" rx="10"/>'
                 '<text class="group-label" x="200" y="56">Main screen</text>')
    parts.append('<g class="edges">')
    for a, b in EDGES:
        parts.append(f'<path class="edge" data-a="{a}" data-b="{b}" d="{edge_path(a, b)}" marker-end="url(#tip)"/>')
    # Quick sheet hands the link back to the main screen, routed outside the group.
    x1, y1 = mid("QuickDownloadActivity", "left")
    x2, y2 = mid("MainActivity", "left")
    parts.append(f'<path class="edge dashed" data-a="QuickDownloadActivity" data-b="MainActivity" '
                 f'd="M{x1:.0f} {y1:.0f} C168 {y1:.0f} 168 {y2:.0f} {x2 - 2:.0f} {y2:.0f}" marker-end="url(#tip)"/>')
    parts.append('<text class="edge-label" transform="translate(166 318) rotate(-90)">Open in Instasave</text>')
    parts.append('</g>')
    for node_id, (x, y, w, kind, label) in NODES.items():
        parts.append(
            f'<g class="node {kind}" data-id="{node_id}" tabindex="0" role="button" aria-pressed="false" aria-label="{label}">'
            f'<rect x="{x}" y="{y}" width="{w}" height="{H}" rx="6"/>'
            f'<text x="{x + 11}" y="{y + 23}">{label}</text></g>')
    parts.append('</svg>')
    return "".join(parts)


def panel_data():
    data = {}
    titles = {w["id"]: w["title"] for w in WALKS}
    for node_id, info in INFO.items():
        entry = dict(label=NODES[node_id][4], text=inline(info["text"]),
                     walks=[[w, inline(titles[w])] for w in info["walks"]])
        if info.get("file"):
            entry["file"] = info["file"]
            entry["lines"] = line_count(info["file"])
        if info.get("shot"):
            entry["shot"] = info["shot"]
        data[node_id] = entry
    return data


# ---------------------------------------------------------------- Page

def render_contents():
    out = []
    for gid, title, _ in GROUPS:
        items = "".join(f'<li><a href="#{w["id"]}">{inline(w["title"])}</a></li>' for w in WALKS if w["group"] == gid)
        out.append(f'<div><h3><a href="#{gid}">{title}</a></h3><ul>{items}</ul></div>')
    return "".join(out)


def render_groups():
    out = []
    for gid, title, intro in GROUPS:
        walks = "".join(render_walk(w) for w in WALKS if w["group"] == gid)
        out.append(f'<section class="group-section" id="{gid}"><h2>{title}</h2><p class="lede">{intro}</p>{walks}</section>')
    return "".join(out)


def current_branch():
    try:
        return subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT,
                              capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


today = datetime.date.today()
page = (TEMPLATE.read_text()
        .replace("{{BRANCH}}", html.escape(current_branch()))
        .replace("{{DATE}}", f"{today.day} {today:%B %Y}")
        .replace("{{MAP}}", render_map())
        .replace("{{CONTENTS}}", render_contents())
        .replace("{{GROUPS}}", render_groups())
        .replace("{{PANEL_DATA}}", json.dumps(panel_data(), ensure_ascii=False)))
OUT.write_text(page)
print(f"wrote {OUT} ({len(page)} bytes)")
