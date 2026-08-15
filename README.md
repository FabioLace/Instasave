<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Instasave Logo" width="100"/>

  <h1>Instasave</h1>

  <p><strong>Download photo, carousels and videos from Instagram</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android"/>
    <img src="https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java"/>
  </p>
</div>

## Features
Native Android app for analyzing public Instagram permalinks and saving photos, videos, or selected carousel items to the device's `Download/Instasave` folder.

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
    MainActivity.java       # UI coordination and downloads
    MediaResolver.java      # Public media resolution
    ImageLoader.java        # Remote/local thumbnail loading and cache
    HistoryRepository.java  # Local download history persistence
  res/
    layout/                 # XML layouts
    drawable/               # UI styles and resources
    mipmap-*/               # Launcher icon at each density
  assets/branding/
    logo.png                # High-resolution source logo
```

`assets/branding/logo.png` is the high-resolution source logo file. It is excluded from APK
packaging; the optimized copies in `res/mipmap-*` are used by Android for the launcher icon and
the logo shown in the app header.

## Usage and Disclaimer

Instasave is a personal hobby project, provided for educational and personal use only. It is not affiliated with, endorsed by, or sponsored by Instagram or Meta.

Use it only for content you own or for which you have explicit permission to download and retain. You are responsible for complying with the platform's terms, applicable law, and the rights of content owners. This project does not include Instagram credentials, cookies, access tokens, or downloaded content.

Availability may change without notice because it depends on third-party services and their policies.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
