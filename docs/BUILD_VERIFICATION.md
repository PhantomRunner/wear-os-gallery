# Audit and packaging verification — 2026-10-21

This record describes the final audited source and APKs, replacing the earlier incremental-build record. The audit started from commit `aa35b90`; existing feature commits were preserved. No redesign, new application features, dependency upgrades, or functionality removals were made.

## Environment and clean build

- Linux x86_64, Eclipse Temurin JDK 17.0.20.1 (`/home/srgwx/.jdks/temurin-17`).
- Android SDK `/home/srgwx/Android/Sdk`, Platform 36, Build Tools 35.0.0.
- Gradle 8.13 wrapper, Android Gradle Plugin 8.13.0, Kotlin 2.2.10.
- Existing memory limits retained: 1536 MiB heap, 512 MiB metaspace, two workers, sequential project builds, in-process Kotlin compiler.

Executed from the project root:

```bash
export JAVA_HOME=/home/srgwx/.jdks/temurin-17
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x gradlew
./gradlew clean :mobile:assembleDebug :wear:assembleDebug --stacktrace
```

**BUILD SUCCESSFUL in 12 seconds; 97 tasks executed.** Both APKs were regenerated from clean build outputs. The executable bit on `gradlew` was already tracked and remains set.

An intermediate compiler check caught an unsupported Compose accessibility accessor in the gesture fix. It was replaced with the platform `AccessibilityManager` API, preserving accessibility behavior. The final clean build contains no compiler errors or suppression of compiler checks.

## Automated verification

```bash
./gradlew :shared:testDebugUnitTest :mobile:testDebugUnitTest \
  :mobile:lintDebug :wear:lintDebug --stacktrace
```

**BUILD SUCCESSFUL in 10 seconds; 106 actionable tasks.**

| Test suite | Passed | Failed / errors / skipped |
| --- | ---: | --- |
| Shared photo storage/protocol | 10 | 0 / 0 / 0 |
| Shared Asset resource lifetime | 4 | 0 / 0 / 0 |
| Phone acknowledgment transitions | 5 | 0 / 0 / 0 |
| Total | 19 | 0 / 0 / 0 |

Storage tests cover exact bytes, restart persistence, duplicate delivery, atomic publication, interrupted input, invalid input cleanup, strict UUID/path validation, and delete-then-retry behavior. New tests cover timeout/late-result cleanup, cancellation before dispatch, resource ownership on success, task failure propagation, stale failure ACKs, historical successful ACKs, finality of confirmation, and wrong-node rejection. A test assertion was corrected to account for coroutine stack-trace recovery copying exceptions while retaining the original cause; error type, message, and cause remain checked.

Phone lint: **0 errors, 1 warning**. Watch lint: **0 errors, 3 warnings**. All four warnings only advertise newer Gradle/Wear Compose versions. No lint errors are suppressed. Reports:

- `shared/build/reports/tests/testDebugUnitTest/index.html`
- `mobile/build/reports/tests/testDebugUnitTest/index.html`
- `mobile/build/reports/lint-results-debug.html`
- `wear/build/reports/lint-results-debug.html`

## Source audit and fixes

- Phone ACK validation and journal updates now share the same lock as retry updates. An old failure ACK cannot overwrite a newer retry; confirmed receipts remain final. Historical success from the selected watch still confirms the same UUID.
- Staged-original MIME metadata is saved before node lookup/submission so a connection failure does not force the original provider URI to be readable again on retry.
- Watch storage failures and ACK transport failures are handled separately. Losing a successful ACK no longer causes a contradictory failure ACK for a photo already saved.
- Asset Task results arriving after timeout/cancellation are released. Acquisition assigns ownership inside the timeout and uses outer `finally` cleanup; normal input streams and responses remain closed/released.
- Watch refresh, receive, delete, and state publication are serialized. Photos and initial-load status are published together in one StateFlow, preventing a stale refresh or partially observed loading state from replacing a newer gallery state.
- Restored viewers wait for initial disk loading before treating the gallery as empty. Lifecycle cancellation propagates rather than appearing as a storage error.
- The left-edge Back gesture is reserved on every viewer page. The pinned Wear pager default only reserves it on page zero. Touch exploration continues to allow pager handling, matching the library's accessibility behavior.

Existing configuration/storage was checked and retained: identical application IDs, shared debug keystore, strict UUID paths, background listener services, private `filesDir/photos/` files, atomic writes, durable deduplication/deletion receipts, sampled validation, 180-pixel thumbnails, 512-pixel viewer requests, an 8 MiB watch bitmap cache, and round-screen padding. No original image bytes are modified.

## Verified APKs

Both files were checked with `test -s`, `stat`, `sha256sum`, `apksigner verify --verbose --print-certs`, and `aapt dump badging`.

| Artifact | Exact size | SHA-256 |
| --- | ---: | --- |
| `mobile/build/outputs/apk/debug/mobile-debug.apk` | 11,550,724 bytes | `b7be486afddf99be84ea0a2a205edcc397b0a8ee1eeec225ce8f2cca5ead4cc1` |
| `wear/build/outputs/apk/debug/wear-debug.apk` | 30,364,358 bytes | `25842c54f4414f820ce814290eab3ad064489d4130890a3c902c3b3f77aa88a9` |

Both signature verifications passed. Both have exactly one signer and the same RSA debug certificate SHA-256:

```text
b64fa9573e5aa2aeaddefd2528c3c01be7caf9520fd6fb8ef9340fb01ce3bb35
```

Package metadata confirms `com.pixelwatchgallery`, version `0.1`/code `1`, target SDK 36, phone min SDK 26, and watch min SDK 30. The watch declares `android.hardware.type.watch`. Neither APK requests INTERNET or broad storage/media permissions; AndroidX adds its normal app-specific dynamic receiver permission.

Inspection of the compiled watch resource table confirms `android_wear_capabilities` contains `pixel_watch_gallery_receiver_v1`. Compiled manifests confirm the exported watch `PhotoReceiverService` filters `DATA_CHANGED` for `/transfer/photo/`, and the phone `AcknowledgmentService` filters `MESSAGE_RECEIVED` for `/transfer/ack`.

These hashes identify this session's debug artifacts. Keep the existing debug signing key for future `adb install -r` updates; a different key cannot update these packages in place.

## Files changed

- `mobile/build.gradle.kts`: JUnit dependency for ACK regression tests.
- `mobile/src/main/java/com/pixelwatchgallery/mobile/TransferRepository.kt`: atomic ACK handling and staged MIME persistence.
- `mobile/src/test/java/com/pixelwatchgallery/mobile/TransferAcknowledgmentTest.kt`: five receipt-state regression tests (new).
- `shared/src/main/java/com/pixelwatchgallery/shared/AssetTasks.kt`: resource-aware Task waiting (new).
- `shared/src/test/java/com/pixelwatchgallery/shared/AssetTasksTest.kt`: four lifetime/cancellation regression tests (new).
- `wear/src/main/java/com/pixelwatchgallery/wear/PhotoReceiverService.kt`: Asset ownership and separate ACK delivery error handling.
- `wear/src/main/java/com/pixelwatchgallery/wear/PhotoRepository.kt`: serialized storage/state publication and initial-load state.
- `wear/src/main/java/com/pixelwatchgallery/wear/MainActivity.kt`: viewer restoration, every-page Back gestures, and cancellation handling.
- `README.md`: clean build, explicit two-device install commands, Wireless Debugging pairing, and verification instructions.
- `docs/ARCHITECTURE.md`: audited ACK, Asset, repository, and navigation behavior.
- `docs/TESTING.md`: requested 12-step hardware checklist plus targeted regressions.
- `docs/BUILD_VERIFICATION.md`: this audit and artifact record.

## Physical hardware boundary and installation

`adb devices -l` returned no attached devices or emulators. No APK was installed. Photo Picker interactions, paired Data Layer delivery, background hardware receipt, actual round-display gestures/TalkBack, offline hardware persistence, physical watch deletion/restart, and device memory behavior remain untested. JVM tests do not execute Android BitmapFactory, Compose UI, or Google Play services delivery.

The APKs are debug-signed and ready for sideloading. Use the [Linux installation and Wireless Debugging instructions](../README.md#install-on-a-phone-and-pixel-watch-2), select the intended phone and watch by explicit ADB serial, and run the [12-step physical acceptance checklist](TESTING.md#minimum-acceptance-run). Updating with the same signing key preserves private photos; uninstalling or clearing app data removes them.
