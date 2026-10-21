# Architecture

## Modules

- `mobile`: Material 3 Compose selection UI, durable file-based outbox journal, manual sender, and background acknowledgment listener.
- `wear`: Wear Compose Material 3 gallery, Wear swipe-dismiss navigation and horizontal pager, bounded Coil image cache, photo repository, and background Data Layer receiver.
- `shared`: protocol constants, strict canonical UUID/path validation, image signature and sampled decode validation, and atomic filesystem storage. JVM tests cover protocol and storage failure cases.

Both apps use `com.pixelwatchgallery` and Gradle's default shared debug keystore. Different app IDs or signing certificates prevent Data Layer communication. Android application classes use their own namespaces (`.mobile` and `.wear`).

## Transfer

A picker selection is represented by a new UUID plus its document URI and display name. The phone attempts to retain URI read access and saves selection/status in an `AtomicFile` JSON journal. No database or original-gallery mutation is involved.

When the user sends or retries, an application-scoped IO coroutine serializes the batch. Selected originals are staged first so exact file lengths are known before new submission. Reachability discovery happens once per manual operation; files are submitted serially and retain the same destination for retries. It verifies Google Play services and calls `CapabilityClient.getCapability(..., FILTER_REACHABLE)` for `pixel_watch_gallery_receiver_v1`. The watch advertises that capability in `android_wear_capabilities`. Failure becomes a visible, retryable state. No device identifiers are hardcoded.

The phone streams the selected bytes to a private `.part` staging file, flushes it, validates MIME/signature plus a small sampled decode, and renames it into `filesDir/outbox/{uuid}`. This copy is never resized, re-encoded, cropped, or compressed. A file-descriptor-backed Asset keeps original file size out of the phone's Java heap. The descriptor stays open through `putDataItem()` completion.

Each urgent DataItem is `/transfer/photo/{uuid}` with DataMap fields:

| Field | Value |
| --- | --- |
| `uuid` | Canonical UUID, matching the path |
| `mime` | Original supported MIME: `image/jpeg`, `image/png`, or `image/webp` |
| `image` | Original image Asset |
| `attempt` | New random token for every manual attempt |
| `receiver` | Capability-discovered destination node ID |

DataClient replicates DataItems; the destination field prevents other reachable watches from storing a transfer intended for a different watch. The phone records the destination and its local DataItem URI before submission, allowing a fast ACK to be matched. A successful `putDataItem()` never sets Confirmed.

The watch's manifest-filtered `WearableListenerService` runs with its Activity closed. Its callbacks execute on a background thread. It completes IO work before returning so it does not launch unowned coroutines after the service may be unbound. The receiver checks the path, UUID, receiver node, MIME and bytes; opens the Asset stream; saves it; closes the stream; and releases the Asset result. Resource-aware Task awaiting also releases a response that arrives after its 60-second acquisition timeout or cancellation. The response is assigned inside the timeout and released by an outer `finally` so cancellation at the timeout boundary cannot strand a descriptor.

A JSON `MessageClient` payload on `/transfer/ack` contains `uuid`, `attempt`, and `ok`; failures also include a bounded `error` message. The phone accepts ACKs only from the discovered destination for an existing UUID. Success is persisted before the staged original and DataItem are cleaned up. An ACK arriving during a manual retry cannot regress a confirmed transfer to queued or failed. Error ACKs must match the latest attempt; validation and journal updates share one lock with retry state changes, preventing a stale failure from racing a newer attempt. An ACK delivery error is logged separately from receipt/storage errors and never generates a contradictory negative ACK for an already saved photo.

MessageClient is not durable. If an ACK is lost, the phone stays Queued (or displays a failure). **Retry confirmation** changes the DataItem attempt token, causing a new receive event even for identical bytes. The watch recognizes the saved UUID and sends another successful ACK without saving it again. It retains receipts after deletion so this retry cannot resurrect deleted images. Selecting the same image again creates a new UUID and deliberately permits another copy. A negative ACK after an unsuccessful write provides an actionable error; the phone keeps staged bytes for retry.

## Storage and recovery

Watch images are `filesDir/photos/{uuid}.jpg|png|webp`. Extension is derived from verified MIME. Deduplication checks the receipt or up to three UUID-based candidate files directly, and deletion checks those candidates without listing the collection. Duplicate delivery does not rescan or decode an already loaded gallery. The repository lists only supported filenames with strict UUID basenames and sorts by newest modification time. It exposes a StateFlow of file references and provides receipt lookup, receive, refresh, and delete operations. UI decoding reads those individual private files.

`AtomicPhotoStore` synchronizes reception and deletion. It writes into `{uuid}.part`, flushes and `fsync`s the file, validates it, then uses a same-directory `Files.move(..., ATOMIC_MOVE)` to publish a complete image. Exceptions remove the partial file. A restart removes abandoned `.part` files. If a crash occurs after publishing but before creating a receipt, the UUID filename itself proves that the photo was saved.

Tiny empty files under `photos/.receipts/{uuid}` persist deduplication knowledge. Deletion records a receipt before deleting the image, so replaying a historical transfer cannot undo a deletion. All operations stay inside app-private storage. There is no deletion message to the phone and no access to the phone's original beyond read-only selection and staging.

The phone uses `AtomicFile` for its JSON outbox journal. v0.2 reads the legacy v0.1 array unchanged and writes an object containing that selection array plus an active batch snapshot. Optional byte sizes default to unknown for legacy selections. This is a backward-compatible reader and additive phone metadata update, with no photo-file or receipt migration. The snapshot fixes photo UUIDs, sizes and receipt states for a Send action; reconciliations ignore unrelated UUIDs. Duplicate ACKs leave its counts unchanged. Removed historical cards keep their snapshot receipt. A new Send action replaces the displayed batch; retrying an unrelated older photo only updates that card. Confirmed-byte totals include only saved-photo ACKs, and 100%/Completed requires all snapshot photos to be confirmed. Percentage remains unknown until total size is known. Photo size warnings are non-blocking and never modify bytes. Preparing, waiting-for-watch and submitting entries become retryable failures after a process restart; queued entries remain queued. Only user actions submit or retry. Staged files are deleted after success or removal of a failed/idle selection. Confirmed entries remain as historical receipts until removed. Persisted Photo Picker grants support ordinary restarts; if a provider withdraws access before staging, the user must remove and reselect the image.

## UI and memory

The phone uses the AndroidX multiple-photo picker contract, a thumbnail grid, per-photo state and error text, manual retry, and a prominent send button. No runtime storage permission is needed. Activity recreation does not cancel work already started in the application-scoped sender.

The watch uses a two-column lazy grid with circular-display padding, crown scrolling, an offline empty state, a fullscreen fit-to-display horizontal pager, and Wear Material 3 confirmation dialogs. A custom pager gesture inclusion policy reserves the left edge on every page for `SwipeDismissableNavHost`; the library default only reserves it on page zero. Center swipes page photos. Touch exploration retains the library's accessibility behavior. Repository StateFlow emissions refresh an open gallery; the first load in each process indexes files once. Later resumes reuse the authoritative in-process snapshot; receipt/deletion refreshes happen only after a mutation. Filesystem mutations and snapshot publication share a coroutine mutex so an old refresh cannot overwrite a newer receipt/deletion. A restored viewer waits until initial disk loading completes. Deleting the final photo exits the viewer safely.

Only visible/lazily composed images are decoded. Coil requests are remembered by file, dimensions and display mode. Thumbnails use the actual two-column available pixel width; viewer requests use the watch's smaller screen dimension (384 pixels on Pixel Watch 2). FIT decoding bounds both axes even for extreme-aspect originals, with crop/fit applied only during display. Small images are not enlarged at decode time. Stable UUID/dimension cache keys avoid per-recomposition file key/stat work; grid entries have content types. No extra pager pages or custom preload jobs are added. The watch memory cache is capped at 8 MiB with at most two BitmapFactory decodes in parallel; no extra image disk cache is used. MIME validation decodes to approximately 256 pixels per dimension and immediately recycles the bitmap. Originals are retained as files and streams, never as a full-resolution bitmap collection.

## Platform and intentional limits

No application INTERNET permission, broad media permission, cloud backup, migration backup, database, backend, periodic worker, gallery scanner or automatic transfer is configured. Play services owns the paired Data Layer transport. Both manifest listeners have narrow path filters and are exported so Play services can bind; protocol fields and sender identity are checked before accepting a receipt.

Transfers use platform delivery semantics, not a synchronization engine. A successful ACK means the UUID was saved at least once, including a copy subsequently deleted by the user. DataItem cleanup is best effort and does not affect the stored photo. Receipt markers are intentionally retained indefinitely. Uninstalling or clearing app data removes files and receipts. Updating an APK with the same signing certificate preserves them.

Official API references: [DataItems](https://developer.android.com/training/wearables/data/data-items), [Assets](https://developer.android.com/training/wearables/data/sync), [listener services](https://developer.android.com/training/wearables/data/events), and [Wear Material 3](https://developer.android.com/jetpack/androidx/releases/wear-compose-m3).
