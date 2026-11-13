# Code review

Review of the whole codebase at commit `e8bc5d4` (branch `develop`, 2026-11-12), covering the 12 Java
classes in `app/src/main/java/app/instasave/` plus the manifest, build script, strings, styles and the
quick download layout.

The findings come from reading the code: the app was not built and the scenarios were not reproduced
on an emulator. Findings marked **(plausible)** depend on platform behavior that still needs to be
confirmed.

Line numbers refer to the reviewed commit. Items marked **Fixed** have been addressed since.

## Bugs

### 1. Nothing can be saved on Android 8 and 9 (API 26–28) — Fixed

`minSdk` is 26, but every save path requires Android 10:

- Photos: `DownloadController.java:83` throws `error_jpeg_unsupported` below Q, and only after the full
  image has already been downloaded at line 81.
- Videos: below Q, `DownloadManager` with `setDestinationInExternalPublicDir`
  (`DownloadController.java:66`) needs `WRITE_EXTERNAL_STORAGE`, which the manifest does not declare,
  so `enqueue` fails.
- History: `HistoryController.findDownloadedFile` (`:302`) always returns null below Q.

Fix: raise `minSdk` to 29, or add a legacy save path (with the storage permission) for API 26–28.
At minimum, move the SDK check before the image download.

**Fixed** with a legacy save path, keeping `minSdk` 26. The manifest declares `WRITE_EXTERNAL_STORAGE`
with `maxSdkVersion="28"`, and both activities ask for it on the first download. Photos are written by
path to `Download/Instasave` and handed to the media scanner. Videos are also scanned, and the history finds
both in MediaStore by path. Verified on an Android 9 emulator (API 28), from the main screen and from the
bubble's sheet.

### 2. Stale history, and removing an entry can delete the wrong one — Fixed

The bubble's sheet uses a translucent theme (`Theme.Instasave.QuickDownload`). When it opens over
`MainActivity`, the activity is only paused, so `onRestart()` (`MainActivity.java:133`) does not run and
the history is not re-rendered. Meanwhile `HistoryRepository.add` has inserted the new entry at index 0,
while each row's remove button still deletes by its old index (`HistoryController.java:126` →
`HistoryRepository.java:26`). The user deletes the entry above the one they tapped.

Fix: re-render in `onResume()` (or listen for changes to the shared preferences), and remove entries
by a stable key such as `createdAt` instead of their position.

**Fixed:** the history is re-rendered in `onResume()`, and entries are removed by their stored JSON,
which also identifies legacy entries without `createdAt`.

### 3. The once-a-minute refresh collapses expanded carousels — Fixed

`scheduleRefresh` (`HistoryController.java:183`) calls `render()`, which runs `removeAllViews()` and
inflates every row again. While any entry is less than an hour old, a carousel file list the user
expanded collapses on its own every minute. Each refresh also repeats the MediaStore queries and the
thumbnail loads for every file.

Fix: update only the age labels on refresh, or keep the expanded state across renders.

**Fixed:** both. The minute refresh only rewrites the age labels, and open carousels stay open when the
list is rebuilt (for example on `onResume()`, which now re-renders it).

### 4. Status stuck on "Analyzing…" — Fixed

If the URL changes while an analysis is running, `showResolvedResult` (`MainActivity.java:214`)
discards the result but leaves the status unchanged. The error branch (`:208`) does not check `source`
at all, so it shows the error of a link that is no longer in the field.

Fix: check the source in both branches and clear the status when the result is stale.

**Fixed:** both branches go through one check. A generation counter also drops the result of an earlier
analysis once a newer one has started, so it can't overwrite that one's status.

### 5. The specific failure message is lost — Fixed

`DownloadController.finish` (`:113-118`) hands the listener only the message of the last item to
finish. If the first item fails and the last succeeds, the user sees the generic message.

Fix: keep the first non-null failure message.

**Fixed** as proposed. Not reproduced at runtime: it needs one item of a carousel to fail.

### 6. Rotating `MainActivity` resets the screen — Fixed

There is no `onSaveInstanceState` and no `configChanges`. On rotation the preview and `pendingResult`
are lost, the download receiver is unregistered, and `saveExecutor.shutdownNow()` (`:287`) interrupts
photo saves. `QuickDownloadActivity` uses `shutdown()` precisely so saves can finish, so the two
activities are inconsistent.

**Fixed:** `MainActivity` declares the same orientation and size `configChanges` as the sheet, so rotation
no longer recreates it (verified on the emulator with a preview shown), and `saveExecutor` uses `shutdown()`.
Other recreations, such as a dark mode switch or process death, still reset the screen: surviving them would
need the analysis result to be saved in the instance state.

### 7. Foreground service crash on a second start (plausible) — Fixed

`BubbleService.onStartCommand` (`:64`) does not call `startForeground`. If `startForegroundService`
is called again while the service is already running, Android can kill the app with
*"Context.startForegroundService() did not then call Service.startForeground()"*.

Fix: also call `startInForeground()` from `onStartCommand`.

**Fixed** as proposed; the stop action now returns `START_NOT_STICKY`. Starting and stopping the bubble was
verified; the crash itself was not reproduced.

### 8. Shared links followed by a line break — Fixed

`extractUrl` (`MainActivity.java:275`) cuts the URL only at a space. Shared text like
`URL\nText with spaces` yields a URL that contains `\nText`, which is then rejected.

Fix: cut at any whitespace (`\\s`).

**Fixed** and verified with a shared link followed by a caption line.

### 9. Inconsistent Instagram host checks — Fixed

`MediaResolver.isInstagramPermalink` (`:639`) accepts only `instagram.com` and `www.instagram.com`,
so `m.instagram.com` links are rejected. `HistoryController.instagramPostUri` (`:258`) accepts any
`*.instagram.com`.

Fix: share one host check.

**Fixed:** `MediaResolver.isInstagramHost` accepts `instagram.com`, `www.` and `m.`, and the history uses it.
`m.` links are rewritten to `www` before the analysis; verified with a reel.

### 10. Minor

- `MediaResolver.fetchHtml` (`:261`): on a non-200 response the connection is not closed, because the
  `finally` with `disconnect()` only opens after the status check. **Fixed:** the check is inside the `try`.
- `MediaResolver.lacksVideo` (`:222`): one video that arrived without its file fails the whole carousel.
  This is intentional, but the user also loses the valid photos.

## Duplicated code

| Duplicate | Where | Proposal |
|---|---|---|
| `.jpg` file name | `DownloadController.jpegFilename:120`, `HistoryRepository.jpegName:58` | **Fixed:** the history calls `DownloadController.jpegFilename`. |
| Error message fallback | `DownloadController.errorMessage:125`, `UserFacingException.messageFor:19` | Use `messageFor`. |
| Download receiver and its registration | `MainActivity:62-66, 118-124`, `QuickDownloadActivity:47-51, 75-81` | Move into `DownloadController` (`register()` / `unregister()`). |
| Reading a link from the clipboard | `MainActivity.pasteLink:186`, `QuickDownloadActivity.clipboardLink:98` | One helper that returns the link or null. |
| HTTP connection setup and User-Agent (4 times) | `ImageLoader:52` and `:75` (nearly identical), `MediaProbe:16`, `MediaResolver:254` | One `openConnection(url, readTimeout)` helper. |
| `"instasave_" + System.currentTimeMillis()` (4 times) | `MediaResolver:109, 187, 198, 452` | Use `FILENAME_PREFIX`. `Result` relies on `startsWith(FILENAME_PREFIX)`, so changing one literal silently breaks the renaming. |
| `dp()` (3 times) | `BubbleService:192`, `PreviewController:275`, `HistoryController:316` | One shared static helper. |
| Preview title and source label | `PreviewController.show:67` + `refreshDetails:206-214`, `QuickDownloadActivity.showResult:125` + `sourceLabel:135` | One method used by both screens. |
| Async image load with tag check | `PreviewController.loadImage:253`, `HistoryController:197` and `:236`, `QuickDownloadActivity:143` | One `ImageLoader.loadInto(...)`. |
| Instagram host check | `MediaResolver:639`, `HistoryController:258` | **Fixed** with bug 9. |

## Dead or misleading code

- `MediaResolver.extensionFor(String)` (`:626`) is never called.
- The `"story"` type (`HistoryController:117, 334`, strings `type_story` and `history_open_story`) is
  never produced by the resolver.
- `PreviewController.loadPreview` and `loadThumbnail` (`:250-251`) only forward to `loadImage`.
- The `ImageLoader` class comment mentions a "shared" cache, but each activity creates its own.

## Suggested order

1. ~~Bugs 2, 3 and 4, and the `jpegName` duplicate: user-visible and small to fix.~~ Done.
2. ~~Bug 1: decide whether to raise `minSdk` to 29 or support API 26–28.~~ Done: API 26–28 are supported.
3. ~~Bugs 5–9~~ Done. Then the remaining duplicates and dead code.
