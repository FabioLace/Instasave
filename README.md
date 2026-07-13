# Instasave

Native Android app for analyzing public Instagram permalinks and saving photos, videos, or selected carousel items to the device's `Download/Instasave` folder.

## Features

- Paste an Instagram link or receive one through Android's share sheet.
- Analyze public posts, reels, and carousels.
- Preview content before downloading it.
- Select individual items from a carousel.
- Keep a local history of initiated downloads.

The app uses only publicly available data and does not require an Instagram login. Content availability depends on Instagram and the post's privacy settings.

## Requirements

- Android Studio or the Android SDK.
- JDK 17.
- Android SDK Platform 34.
- Gradle Wrapper 8.2.1, included in this repository.

Android configuration:

| Item | Value |
| --- | --- |
| Application ID | `app.instasave` |
| Min SDK | 26 (Android 8.0) |
| Target / Compile SDK | 34 |
| Language | Java |

## Build

From the project root:

```bash
./gradlew :app:assembleDebug
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To use only dependencies already available in the local cache:

```bash
./gradlew :app:assembleDebug --offline --no-daemon --console=plain
```

## Install on an Emulator or Device

To build, start the default emulator (`Pixel_5`), install, and open the app in one command:

```bash
./run-emulator.sh
```

To use another AVD, pass its name:

```bash
./run-emulator.sh Medium_Phone_API_35
```

The script uses `ANDROID_SDK_ROOT`/`ANDROID_HOME` when set, otherwise `~/Android/Sdk`.

With an Android device connected and ADB available:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p app.instasave 1
```

## Project Structure

```text
app/src/main/
  java/app/instasave/
    MainActivity.java       # UI, downloads, and history
    MediaResolver.java      # Public media resolution
  res/
    layout/                 # XML layouts
    drawable/               # UI styles and resources
    mipmap-*/               # Launcher icon at each density
  assets/branding/
    logo.png                # High-resolution source logo
```

`assets/branding/logo.png` is the source logo file. The copies in `res/mipmap-*` are resized versions used by Android for the launcher icon and the logo shown in the app header.

## Usage Note

Use the app only for content you have the right to download and keep, in compliance with the platform's terms and the respective authors' rights.
