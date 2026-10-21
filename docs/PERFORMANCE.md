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
