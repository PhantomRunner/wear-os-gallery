# Physical-device test checklist

These are manual acceptance tests, not a record of completed hardware testing. No ADB devices were attached during the audit. Build and automated test results are recorded in [BUILD_VERIFICATION.md](BUILD_VERIFICATION.md).

## Minimum acceptance run

After Wi-Fi ADB pairing and device identification using the README, execute these steps on a paired phone and Pixel Watch 2:

1. [ ] Install `mobile-debug.apk` on the intended phone using its explicit ADB serial.
2. [ ] Install `wear-debug.apk` on the intended watch using its explicit ADB serial; launch the watch app once so the background receiver is available.
3. [ ] Open the phone app.
4. [ ] Select three JPEG or PNG images.
5. [ ] Tap **Send to Watch**.
6. [ ] Confirm all three entries reach **Confirmed** on the phone.
7. [ ] Open the watch gallery; verify three photos appear.
8. [ ] Open a photo and swipe through all three. Check left-edge Back from every page, then return to the gallery.
9. [ ] Disconnect the phone from the watch (turn off the phone's Bluetooth and Wi-Fi, or power it off).
10. [ ] Reopen the watch gallery and confirm all three photos remain accessible offline.
11. [ ] Delete one watch photo and confirm the deletion.
12. [ ] Restart the watch app and verify exactly two photos remain.

All 12 steps require physical devices and remain untested here. The additional checks below cover failures and lifecycle edge cases.

## Setup and installation

1. Use a Pixel Watch 2 running API 30 or newer and its paired Android phone running API 26 or newer. Enable Bluetooth and ensure Google Play services is available on both.
2. Follow the [README](../README.md) to build both debug APKs under the same account/signing key and enable phone USB debugging and watch wireless debugging.
3. Run these commands, replacing the serials and watch addresses with the values shown by your devices:

   ```bash
   export JAVA_HOME=/path/to/jdk-17
   export ANDROID_HOME="$HOME/Android/Sdk"
   export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
   ./gradlew --no-daemon :mobile:assembleDebug :wear:assembleDebug
   adb pair WATCH_IP:PAIRING_PORT
   adb connect WATCH_IP:DEBUG_PORT
   adb devices -l
   adb -s PHONE_SERIAL install -r mobile/build/outputs/apk/debug/mobile-debug.apk
   adb -s WATCH_IP:DEBUG_PORT install -r wear/build/outputs/apk/debug/wear-debug.apk
   adb -s PHONE_SERIAL shell am start -n com.pixelwatchgallery/.mobile.MainActivity
   adb -s WATCH_IP:DEBUG_PORT shell am start -n com.pixelwatchgallery/.wear.MainActivity
   ```

4. Prepare a JPEG, PNG and WebP, including a large high-resolution photo and a portrait image with EXIF orientation. Also prepare an unsupported GIF and a damaged image through a provider that exposes them in the picker.

## Selection and manual transfer

- [ ] The watch starts with a readable empty state on its circular screen; opening and backing out does not crash.
- [ ] **Select photos** opens the system picker without requesting broad gallery/storage permission. Select multiple supported images; check the thumbnails and selected count.
- [ ] Remove one pending selection. Its original remains in the phone gallery. Reopen the app and rotate the phone; remaining selections survive.
- [ ] Select photos and wait without tapping Send. Nothing arrives on the watch.
- [ ] Tap **Send to Watch**. Each supported image progresses through Preparing and Queued, then Confirmed only after it is saved on the watch. A small file may pass through intermediate states too quickly to observe.
- [ ] Keep the watch gallery open while sending another photo. Its grid refreshes without restarting the Activity.
- [ ] Leave the watch Activity using Back/Home (do not force-stop its package). Send another photo. Reopen the watch gallery and confirm background receipt.
- [ ] Verify byte identity for at least one transfer using the procedure below. The phone original is unchanged.

## Browsing, offline storage and deletion

- [ ] Tap a thumbnail. Verify the entire photo fits the viewer and EXIF orientation is respected. Swipe horizontally across the center to browse; left-edge swipe and normal system Back return to the grid from every page. Verify TalkBack navigation separately if enabled.
- [ ] While viewing a middle photo, recreate the Activity or terminate its background process and reopen the task. A restored viewer must wait for disk loading and retain its current page instead of incorrectly returning to an empty gallery.
- [ ] Send a photo while deleting or reopening the gallery. The visible photo count must match stored files after concurrent refresh, receipt, and deletion.
- [ ] Scroll a grid larger than one screen using touch and the crown. Check thumbnails and controls against the round screen edges.
- [ ] Disable the phone/watch connection after confirmation. Restart the watch app and reboot the watch; photos remain available offline.
- [ ] Open **Delete**, cancel, and confirm the photo remains. Repeat and confirm deletion. Only the watch copy disappears; the phone original remains.
- [ ] Delete the last photo. The viewer exits safely to the empty state.
- [ ] Browse many high-resolution originals repeatedly. Check for memory crashes, responsive navigation, and appropriately sized previews.
- [ ] Reinstall both APKs with `adb install -r` using the same signing key. The remaining watch photos and phone selections survive. Do not uninstall or clear app data for this test.

## Disconnection, retries and invalid input

- [ ] Disconnect before sending or test without the watch app installed. Sending displays a clear reachability failure; it never claims confirmation. Reconnect/install and tap **Retry**.
- [ ] Disconnect during a large transfer. Already submitted Data Layer work may finish on reconnection; the UI must not report confirmation before a watch ACK. If it stays queued or fails, manually retry.
- [ ] Select an unsupported or unreadable file. Sending produces a clear error and no watch photo. Other supported selections in the batch can still complete.
- [ ] Interrupt phone preparation by terminating its process. Reopen: unfinished preparation becomes a retryable failure; no fresh transfer starts automatically.
- [ ] Interrupt delivery after watch storage but before the phone receives its ACK. This timing can require several attempts: disconnect the paired transport immediately after the photo appears, then reconnect. If the phone remains Queued, tap **Retry confirmation**. There is only one watch copy and the phone becomes Confirmed.
- [ ] For that lost-ACK case, delete the saved watch photo before retrying. Its receipt is acknowledged again and the photo stays deleted. Select the original again on the phone to deliberately create a new copy.
- [ ] When practical, test insufficient watch storage. The phone displays a storage error rather than Confirmed. Free space and manually retry; no partial image appears in the gallery.
- [ ] Reopen both apps repeatedly after failures. No automatic resubmission or duplicate photos occur.

Force-stopping an app places it in Android's stopped state and may prevent background service delivery until it is opened again. Use Home/Back to test ordinary background receipt. MessageClient ACKs are best effort, so keep the retry path in the acceptance run.

## Verify original bytes

Use these commands with debug builds. First identify the stored UUID filename:

```bash
adb -s WATCH_IP:DEBUG_PORT shell run-as com.pixelwatchgallery ls files/photos
adb -s WATCH_IP:DEBUG_PORT exec-out run-as com.pixelwatchgallery cat files/photos/UUID.jpg > /tmp/watch-photo.jpg
sha256sum /path/to/original.jpg /tmp/watch-photo.jpg
```

Replace `UUID.jpg` with the actual JPEG/PNG/WebP filename and use the matching original. The two SHA-256 values must be identical. Choose a local original whose bytes you can access; some cloud photo providers supply a downloaded/exported representation. For those providers, the app preserves the exact bytes returned by the selected URI.

Deleting a watch photo should leave its UUID in `files/photos/.receipts/`. Private photo files are accessible with `run-as` only because these are debug builds. Do not include private image data in bug reports.

## Diagnostics

Capture logs while reproducing an issue:

```bash
adb -s PHONE_SERIAL logcat -v time GalleryAck:V AndroidRuntime:E '*:S'
adb -s WATCH_IP:DEBUG_PORT logcat -v time GalleryReceiver:V AndroidRuntime:E '*:S'
```

Record the device OS versions, APK version, image format/size, visible transfer state, and whether the watch was reachable. To troubleshoot a transfer that stays queued, verify matching application IDs and signing certificates, launch both apps, reconnect the paired devices, and use **Retry confirmation**.
