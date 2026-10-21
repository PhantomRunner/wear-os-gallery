# PixelWatchGallery v0.2 performance audit

## Baseline inspection (before implementation)

Audited commit `ade795a` on 2026-10-21. Existing functionality is reported by the user as working on physical devices. This session has not yet performed device measurements or installed anything.

- Three modules: phone and watch apps, shared protocol/storage library. Kotlin/Compose, Coil 2.7.0, Wear Compose 1.5.0, Wearable Data Layer 20.0.1. Keep the pinned dependencies and existing UI/navigation.
- Both application IDs are `com.pixelwatchgallery`. Both existing debug APKs pass signature verification with certificate SHA-256 `b64fa9573e5aa2aeaddefd2528c3c01be7caf9520fd6fb8ef9340fb01ce3bb35`. Keep this signing key for the optimized local variant.
- Baseline debug APK sizes: phone **11,550,724 bytes**, watch **30,364,358 bytes**. These are measured artifact sizes, not runtime measurements.
- Transfer already streams originals to private staging files and sends file-descriptor-backed Assets serially with `setUrgent()`. Stable UUID paths, per-photo ACKs, wrong-node rejection, duplicate protection, persistent receipts, lost-ACK manual retry and safe DataItem cleanup already exist. No transport change is needed.
- The watch listener processes events off main and keeps callbacks alive through storage and ACK work. Asset responses and streams are released, including responses arriving after cancellation. No polling, workers, foreground service, wake lock, or continuous discovery exists.
- Storage uses private `filesDir/photos/`, atomic same-directory moves, and durable deduplication/deletion receipts. Keep all paths and original file bytes. `received()` currently lists/sorts the directory to find a UUID when there is no receipt; delete also scans all files. Duplicate delivery refreshes/scans the gallery unnecessarily.
- Gallery state is already serialized and observed through lifecycle-aware StateFlow. Lazy grid/pager use stable keys. Resume always refreshes from disk, including navigation/resume within an unchanged process. Thumbnail and viewer requests are rebuilt during recomposition; grid content types are unspecified.
- Coil already decodes asynchronously, uses an **8 MiB** bounded watch memory cache, disables its disk cache and limits BitmapFactory parallelism to two. Validation decodes a small sample. Preserve these working safeguards. Thumbnails request 180 pixels and viewer images 512 pixels, exceeding the Pixel Watch 2 display dimension (384 pixels). Request dimensions should follow actual screen/grid size without changing original files.
- There is no aggregate batch receipt counter or byte progress, and no known-size large-file warning. The durable phone journal has only per-photo transfer state. Progress needs a stable batch snapshot, exact staged sizes and ACK-only completion; old batch ACKs must stay isolated.
- Only development debug builds are configured. A local optimized variant should enable R8 and resource shrinking with the existing debug signing configuration and unchanged application ID. Dynamic capability resources must be explicitly retained.

## Implementation scope

Improve the existing Coil requests and event-driven filesystem operations, add durable ACK-based batch progress and non-blocking size warnings, then build debug and same-key optimized APKs. Do not add a thumbnail database, disk thumbnail cache, preload engine, synchronization engine, alternate transport, or image rewriting.

Runtime startup, scrolling, navigation, PSS, and idle CPU measurements require a connected device. No runtime speedup will be claimed without measurements. Final build/artifact results and exact device measurement commands will be appended after verification.

## Implemented v0.2 changes

- Kept Coil 2.7.0, the two-column grid, Wear pager, existing 8 MiB cache, decoder parallelism of two, and lazy pager default (zero extra pages). No preload engine or second disk cache was added.
- Requests are remembered by UUID file/dimensions/mode. Decode dimensions follow actual screen pixels and grid cell width. FIT decode scaling bounds both axes of extreme-aspect originals; grid cropping happens only in rendering. Small originals are not upscaled at decode time. Explicit UUID/dimension memory keys avoid repeated file key/stat work during recomposition. Grid headers/photos have content types for reuse.
- Pixel Watch 2's viewer request is 384 × 384 instead of 512 × 512. A square ARGB_8888 bitmap at those dimensions has a nominal payload of 576 KiB instead of 1 MiB (43.75% less). This is an arithmetic pixel-payload comparison, **not measured PSS or peak process memory**. Actual decoder/hardware allocation depends on format and aspect ratio.
- Receipt lookup and deletion check at most three UUID file paths rather than listing and sorting the collection. A regression test verifies zero directory scans for lookup, first save, duplicate save and delete after store initialization. Existing JPEG/PNG/WebP UUID files without markers remain recognized and byte-identical.
- Initial gallery indexing happens once per process; Activity resumes/navigation reuse its snapshot. Successful receive/delete refresh the index once per mutation. Duplicate delivery does not scan or decode again. Storage and snapshot publication remain serialized. If initial indexing fails, a later resume can retry it.
- Manual transfer preparation streams originals once to the existing private outbox and determines exact file sizes before submitting new DataItems. URI metadata supplies early warnings; files above 5,000,000 bytes remain allowed. No image bytes are changed.
- The current Send batch fixes its UUID set. A lightweight immutable snapshot is stored atomically with the phone journal; its byte totals and receipt counter count only confirmed photos. Old unrelated ACKs/new picker selections cannot alter that snapshot, duplicate ACKs are idempotent, and removing history does not erase a receipt. Unknown totals do not show fabricated percentages. Only all ACKs produce Completed/100%. Retries retain UUIDs and the existing attempt-token protocol.
- Capability discovery is once per manual operation; Asset submissions remain serial/urgent. All seven requested stages are visible in the phone UI. There are no network-byte progress estimates or new synchronization jobs.
- The v0.2 journal reads existing v0.1 arrays and writes an additive object with selections and batch metadata. Existing `filesDir/photos/`, outbox paths, receipt files, MIME formats, application IDs and signing key are unchanged. No photo migration is required. Downgrading the phone to v0.1 is not supported after writing the v0.2 journal.
- Added non-debuggable `optimized` builds with R8 optimization/resource shrinking and the existing debug signing configuration, without an application ID suffix. The dynamic Wear capability is explicitly kept. The optimized watch is shell-profileable for measurement. Debug remains available. Removed the unused direct lifecycle-viewmodel-compose dependency; retained compatible pinned versions.
- A real Gradle metaspace-pressure warning occurred during verification. The limit is now 768 MiB (previously 512 MiB); the heap remains 1536 MiB and workers remain limited to two. No memory warning was hidden or disabled.

## On-device measurements: not performed

`adb devices -l` returned no attached devices. The initial read-only discovery attempt hit an automatic approval-review timeout; its permitted retry succeeded. Nothing was installed, uninstalled, cleared or profiled on a device. User-reported v0.1 physical functionality is preserved as context, not relabeled as a v0.2 test result.

| Runtime metric | Baseline v0.1 | v0.2 optimized |
| --- | --- | --- |
| Cold startup / gallery opening | Unmeasured | Unmeasured |
| Scroll/pager frame timings | Unmeasured | Unmeasured |
| PSS with 10 / 50 images | Unmeasured | Unmeasured |
| PSS after repeated navigation | Unmeasured | Unmeasured |
| Idle/background CPU | Unmeasured | Unmeasured |

Collect baseline on the installed v0.1 app **before updating**, then repeat on the same watch, OS, image corpus, connection conditions and temperature with the optimized v0.2 APK. Avoid debugger attachment during performance captures. Use the same 10-photo collection, then the same 50-photo collection; do not clear app data. For a lower photo count, delete only disposable test copies through the watch UI.

### Exact measurement commands

Use Linux Platform Tools. Set the intended explicit serial from `adb devices -l` and confirm the model:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices -l
WATCH_SERIAL='REPLACE_WITH_WATCH_SERIAL'
adb -s "$WATCH_SERIAL" shell getprop ro.product.model
adb -s "$WATCH_SERIAL" shell getprop ro.build.version.release
```

Cold startup (repeat five times and record `TotalTime` and `WaitTime`). Force-stop is for a cold-start measurement only, and the next command immediately reopens the app without clearing data:

```bash
adb -s "$WATCH_SERIAL" shell am force-stop com.pixelwatchgallery
adb -s "$WATCH_SERIAL" shell am start -W -n com.pixelwatchgallery/.wear.MainActivity
```

Measure gallery scroll and then fullscreen navigation separately. Reset before each run, interact for 30 seconds, then capture frame stats. Repeat three times. Report frame time percentiles/janky frames from actual output; do not equate build speed or APK size with rendering speed:

```bash
adb -s "$WATCH_SERIAL" shell dumpsys gfxinfo com.pixelwatchgallery reset
# Scroll the gallery for 30 seconds (then repeat for 30 seconds of viewer swipes).
adb -s "$WATCH_SERIAL" shell dumpsys gfxinfo com.pixelwatchgallery framestats > /tmp/pwg-framestats.txt
```

PSS and memory stability: use the 10-image corpus, repeat with 50, and capture again after 100 photo swipes and returns to the grid. Leave the app idle for 30 seconds before each snapshot:

```bash
adb -s "$WATCH_SERIAL" shell dumpsys meminfo com.pixelwatchgallery > /tmp/pwg-meminfo.txt
adb -s "$WATCH_SERIAL" shell pidof com.pixelwatchgallery
```

Use the printed PID to inspect idle CPU. Measure once with the gallery untouched and once after pressing Home. If the OS has reclaimed the process, record that instead of reporting a fabricated zero:

```bash
adb -s "$WATCH_SERIAL" shell top -b -n 3 -d 5 -p APP_PID
adb -s "$WATCH_SERIAL" shell input keyevent 3
adb -s "$WATCH_SERIAL" shell top -b -n 3 -d 5 -p APP_PID
```

For a trace while scrolling/swiping, run this in one terminal and interact with the watch during its 20-second capture. Then open the pulled trace at [Perfetto UI](https://ui.perfetto.dev/):

```bash
adb -s "$WATCH_SERIAL" shell perfetto -o /data/misc/perfetto-traces/pwg.trace -t 20s sched freq idle am wm gfx view
adb -s "$WATCH_SERIAL" pull /data/misc/perfetto-traces/pwg.trace /tmp/pwg.trace
```

For allocation investigation, use Android Studio Memory Profiler with a debug build. Timing runs should use optimized; its shell-profileable flag allows supported profiling without making it debuggable. Investigate heap growth before changing the 8 MiB cache; do not add broad preloading on the basis of APK-size results.

### Required manual v0.2 regressions

Use `docs/TESTING.md` for existing selection/transfer/background receipt/offline viewing/deletion tests plus mixed-size progress, duplicate/lost ACKs, previous-batch isolation, journal restart recovery and same-key updates. Physical image decoding, real gestures, paired delivery, idle battery and optimized runtime behavior remain unverified in this session.

Official references: [code/resource optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization), [Coil image pipeline](https://github.com/coil-kt/coil/blob/2.7.0/docs/image_pipeline.md), and [Android performance tools](https://developer.android.com/topic/performance).

## Final measured artifacts and verification

Final verification on 2026-10-21 used JDK 17.0.20.1, Gradle 8.13 and the pinned Android toolchain. Debug builds were verified first; the same-key optimized builds then passed. The final combined check (all four APKs, tests and both variants' lint) completed successfully in 1 minute 1 second.

```bash
export JAVA_HOME=/home/srgwx/.jdks/temurin-17
./gradlew --no-daemon :mobile:assembleDebug :wear:assembleDebug \
  :mobile:testDebugUnitTest :shared:testDebugUnitTest \
  :mobile:lintDebug :wear:lintDebug \
  :mobile:assembleOptimized :wear:assembleOptimized \
  :mobile:lintOptimized :wear:lintOptimized --stacktrace
```

**35 tests passed**: 14 progress, 5 acknowledgment, 12 storage/protocol, and 4 Asset lifetime tests. Zero failures/errors/skips. Each variant has zero lint errors; remaining notices advertise newer versions (phone: 1, watch: 3). No compiler/lint errors were suppressed.

| App | Baseline v0.1 debug | v0.2 debug | v0.2 optimized | Optimized reduction vs baseline debug |
| --- | ---: | ---: | ---: | ---: |
| mobile | 11,550,724 bytes | 11,638,766 bytes | 1,504,429 bytes | 87.0% |
| wear | 30,364,358 bytes | 30,364,736 bytes | 2,906,245 bytes | 90.4% |

These are actual APK file-size measurements. The baseline is a debug build and the final small artifact is an optimized build; this comparison does not measure frame rate, startup, runtime memory, or battery.

All APKs physically exist and pass `apksigner verify`. `aapt dump badging` verifies application ID `com.pixelwatchgallery`, version `0.2`/code `2`, target 36, minimum SDK 26 (phone) / 30 (watch). Optimized APKs are not debuggable. The optimized watch resource table retains `android_wear_capabilities = pixel_watch_gallery_receiver_v1`; both compiled manifests retain their background listener services and path filters. No application INTERNET or broad media/storage permission was introduced.

All four certificate SHA-256 digests match each other and the pre-change v0.1 APKs:

```text
b64fa9573e5aa2aeaddefd2528c3c01be7caf9520fd6fb8ef9340fb01ce3bb35
```

This verifies compatibility with installations made from the prior APKs using that certificate. No connected installation certificate was read because no devices were attached. There was no uninstall, clear-data operation, key replacement or automatic install.

| Artifact | Exact bytes | SHA-256 |
| --- | ---: | --- |
| `mobile/build/outputs/apk/debug/mobile-debug.apk` | 11,638,766 | `ada8a51a34245efc42e35b94b321988d95352403b5c5f7af12be52e16cab24c7` |
| `wear/build/outputs/apk/debug/wear-debug.apk` | 30,364,736 | `9cdb07acb4776da2be91780b1b04db0e10e851b42bb62616fcf0d857a58be17e` |
| `mobile/build/outputs/apk/optimized/mobile-optimized.apk` | 1,504,429 | `27d3329bdaeb8442bd70d329a9d2145ee40c88f40f0e6ad8b63636f40e72a721` |
| `wear/build/outputs/apk/optimized/wear-optimized.apk` | 2,906,245 | `55c55079c7e7abc7e74dc17307c85537e939500ad83e1f1b5f87824dd01fd313` |

The optimized variants are the recommended personal update artifacts. Exact update commands, after identifying devices and pairing Wireless Debugging as described in the README:

```bash
PHONE_SERIAL='REPLACE_WITH_PHONE_SERIAL'
WATCH_SERIAL='REPLACE_WITH_WATCH_SERIAL'
adb -s "$PHONE_SERIAL" install -r mobile/build/outputs/apk/optimized/mobile-optimized.apk
adb -s "$WATCH_SERIAL" install -r wear/build/outputs/apk/optimized/wear-optimized.apk
adb -s "$PHONE_SERIAL" shell am start -n com.pixelwatchgallery/.mobile.MainActivity
adb -s "$WATCH_SERIAL" shell am start -n com.pixelwatchgallery/.wear.MainActivity
```

No wire-protocol change was needed: `/transfer/photo/{uuid}`, original MIME/Asset, destination, retry attempt token and `/transfer/ack` remain compatible. Original bytes and private photo/receipt paths remain unchanged. Runtime performance, on-device upgrade preservation, actual progress UI, background paired delivery, 10/50-photo memory, round-screen gestures and battery behavior still require the physical checklist.
