# Pixel Watch Gallery

A personal photo gallery for Pixel Watch 2 with an Android phone companion. Manually send original JPEG, PNG, and WebP photos, then browse and delete watch copies offline.

## Build

Requires JDK 17 and Android SDK 36.

```bash
./gradlew :mobile:assembleOptimized :wear:assembleOptimized
```

For debug builds, replace `assembleOptimized` with `assembleDebug`.

## Install

Enable debugging and connect your phone and watch through ADB.

```bash
adb devices -l
adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/optimized/mobile-optimized.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/optimized/wear-optimized.apk
```

Replace the serial placeholders with your devices from `adb devices -l`. Build both apps with the same signing key. Updates using `install -r` keep existing app data.

## Use

1. Open both apps.
2. Select photos on your phone and tap **Send to Watch**.
3. Wait for confirmation, then open the watch gallery.
4. Tap a photo to view it, swipe to browse, or choose **Delete** to remove only the watch copy.

Pinch or double-tap to zoom; drag to pan while zoomed. Turn the crown to cycle through photos, wrapping at either end.
