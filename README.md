# PixelWatchGallery 0.1

A small, private photo gallery for Pixel Watch 2 with an Android phone companion. Pick JPEG, PNG or WebP images on your phone and explicitly send their **original bytes** to your watch. Browse and delete the watch copies offline.

## Linux setup

Use Android Studio with **JDK 17**, Android SDK Platform **36**, Build Tools **35.0.0 or newer**, and Android SDK Platform Tools (`adb`). The project pins AGP 8.13.0, Kotlin 2.2.10, Gradle 8.13, Compose BOM 2025.08.00, Wear Compose Material 3 1.5.0, and Wearable Data Layer 20.0.1. These are compatible stable versions; newer versions are not required.

Open this directory in Android Studio, choose JDK 17 for Gradle, and let it sync. For a terminal build:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

Set `JAVA_HOME` explicitly if `java` is missing from your shell or Android Studio bundles a different JDK. On the development machine used for verification, JDK 17 is at `/home/srgwx/.jdks/temurin-17`. JDK 25 cannot run this pinned Gradle/Kotlin toolchain. In Android Studio, select JDK 17 under **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**.

Build defaults limit Gradle to a 1536 MiB heap and two workers, disable parallel project builds, and compile Kotlin inside Gradle's process to avoid an additional compiler daemon. On a memory-constrained Linux desktop, use `./gradlew --no-daemon :mobile:assembleDebug :wear:assembleDebug` and run only one emulator at a time.

The Gradle wrapper downloads its pinned distribution and checks its SHA-256. The first build needs access to Google Maven, Maven Central and Gradle distribution servers. `local.properties` is machine specific and ignored by Git. Android Studio can install missing SDK components; accept SDK licenses when prompted.

APKs:

- `mobile/build/outputs/apk/debug/mobile-debug.apk` — Android 8.0 / API 26 or later.
- `wear/build/outputs/apk/debug/wear-debug.apk` — Wear OS with API 30 or later.

Both use application ID `com.pixelwatchgallery` and the same default `$HOME/.android/debug.keystore`. Build both APKs under the same account and keep that signing key for future updates. If you build on multiple computers, securely use the same development signing key. Never publish a private signing key in the repository.

## Install on a phone and Pixel Watch 2

Pair the watch to your Android phone using its normal companion app. Google Play services must work on both devices. Enable Developer options and USB debugging on the phone. Enable Developer options and wireless debugging on the watch. Use a trusted local network and the pairing/connection addresses shown on the watch:

```bash
adb devices -l
adb pair WATCH_IP:PAIRING_PORT       # enter the pairing code shown by the watch
adb connect WATCH_IP:DEBUG_PORT     # this port can differ from the pairing port
adb devices -l
adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s WATCH_IP:DEBUG_PORT install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Replace device identifiers with those from `adb devices -l`. Launch **Pixel Watch Gallery** on both devices once after installation. Android Studio's module selector can also install `mobile` and `wear` on their respective devices. Wireless ADB is for development; normal photo transfers use Wearable Data Layer over the paired connection.

Use `adb install -r` with the same signing key for updates: it preserves `filesDir/photos/` and selections. **Uninstalling, clearing app data, or replacing the signing key does not preserve private photos.** Backups and device migration of app data are disabled. Android will reject an update signed by a different key.

## Use

1. On the phone, tap **Select photos**, select multiple images, and finish the system picker. On devices without the native Photo Picker, the AndroidX contract uses the system document picker. No broad media permission is requested.
2. Check thumbnails and the selected count. **Remove** discards a selection; it never deletes the original phone image.
3. Connect the paired watch and tap **Send to Watch**. The phone first checks the watch app's reachable receiver capability.
4. A photo progresses from **Idle → Preparing → Queued → Confirmed**. Queued means Data Layer submission; **Confirmed** requires an acknowledgment from the watch after private storage succeeds.
5. Use **Retry** after a failure or **Retry confirmation** if an acknowledgment was lost. Retry keeps the transfer UUID and changes an attempt token; saved photos are not duplicated. Large original files can take minutes. The phone retains staged originals until confirmation.
6. On the watch, tap a thumbnail, swipe across the center to browse, and swipe right from the left edge or use system Back to close the viewer. Tap **Delete** and confirm to delete only the watch copy. The crown scrolls the grid.

Confirmed entries remain on the phone until removed. Their receipt is a historical transfer result: deleting a photo on the watch does not change the phone's status. To deliberately send a deleted image again, select it again to create a new transfer UUID.

Transfers start only from **Send to Watch**, **Retry**, or **Retry confirmation**. Android can finish an already queued transfer after reconnection. Restarting the app does not initiate new transfers. A manual batch continues while the phone process remains alive; an interrupted preparation is marked failed at restart and can be retried. Already submitted DataItems do not require the Activity to remain open. A lost ACK after process termination is recovered by a manual retry.

## Verification

```bash
./gradlew :shared:testDebugUnitTest :mobile:lintDebug :wear:lintDebug
```

Tests exercise original-byte storage, atomic failure cleanup, restart recovery, UUID/path validation, duplicate delivery, and delete-then-retry behavior. See [architecture](docs/ARCHITECTURE.md), the [physical-device checklist](docs/TESTING.md), and [build verification record](docs/BUILD_VERIFICATION.md).

## Scope and limits

No cloud, accounts, scanning, automatic synchronization, albums, editing, resizing, compression, analytics, storage permission, or application INTERNET permission. Only JPEG, PNG and WebP are supported. Animated image playback is outside this MVP. Images decode at display size, not full resolution. Very large originals still consume their original storage and Data Layer bandwidth; the app does not impose a file-size cap or silently alter them.

The app chooses one reachable receiver deterministically (nearby first); a transfer remains associated with that watch for retries. There is no multi-watch selection UI. Small receipt markers are retained to prevent deleted photos from reappearing on retries. DataItem cleanup after confirmation is best effort. This is a debug build for personal installation, not a Play Store release.
