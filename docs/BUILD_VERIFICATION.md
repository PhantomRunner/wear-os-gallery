# Watch gallery hotfix v0.2.1 — 2026-10-21

## Reproduction and root cause

A synthetic valid PNG was placed in private `files/photos/` on a disposable Wear OS API 35 emulator with a 384 × 384 display. The unmodified v0.2 APK displayed “No photos yet” despite that existing file. Navigation destinations had captured the initial plain photo list before asynchronous disk loading completed. Repository/storage updates could succeed without invalidating that destination's rendered content.

The fix captures the Compose State holder and reads its value inside each gallery/viewer destination, so disk loading, receipt and deletion invalidate the actual screen. It preserves the navigation graph, original bytes, storage paths, repository caching and transfer/ACK protocol. The phone remains v0.2; only the watch is updated to v0.2.1 / version code 3.

## Verified hotfix results

- `:wear:assembleDebug :wear:assembleDebugAndroidTest` passed.
- `:wear:assembleOptimized :wear:lintDebug :wear:lintOptimized :mobile:testDebugUnitTest :shared:testDebugUnitTest` passed (50 seconds). The existing 35 JVM tests remain passing; unchanged test tasks were up-to-date.
- Two new real Compose UI tests failed against the saved pre-fix APK, then the identical tests passed against the fixed APK on the disposable emulator (4.088 seconds). They cover arrival/deletion in an already open gallery and incoming/deleted photos in the open viewer.
- A pre-existing synthetic photo survived same-key `install -r` updates from old debug to fixed debug to fixed optimized. The optimized APK cold-started and rendered “1 photo” and its blue image thumbnail; the old APK's UI dump showed “No photos yet”.
- Both watch APKs physically exist, pass signature verification, use `com.pixelwatchgallery`, version name `0.2.1`, version code `3`, min SDK 30 and target SDK 36.
- Signing certificate SHA-256 is unchanged: `b64fa9573e5aa2aeaddefd2528c3c01be7caf9520fd6fb8ef9340fb01ce3bb35`.
- Watch lint has zero errors and five version-update warnings in each variant (the original three Wear notices plus notices for the explicitly aligned Compose test BOM and JUnit extension).
- Nothing was installed on a physical device. No real phone/watch data was cleared or modified. Paired-device transfer remains a physical-device acceptance test; the new tests enter through the same receiving repository rather than emulating Bluetooth/Data Layer.

| Latest watch APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `wear/build/outputs/apk/debug/wear-debug.apk` | 30,364,740 | `86052ad196a8c37bae1e1a9224b1b2c94921dc3c739785bbb2b3a8019f1bfca1` |
| `wear/build/outputs/apk/optimized/wear-optimized.apk` | 2,906,245 | `fa1b4009e77d1b3d65936bbee003d98be63632a558f3c1741ca3377c93171d38` |

The recommended update is `wear-optimized.apk`. The phone APK does not need replacing. Use explicit watch serial plus `adb install -r`, then reopen the gallery to see previously stored photos. No re-transfer or file migration is required for files already in private photo storage. See the README for update commands and `TESTING.md` for the instrumented regression command.

The record below is historical v0.2 verification; its watch APK hashes/version have been superseded by the table above.

## Previous v0.2 verification (before the gallery fix)

The v0.2 update was built and tested using JDK 17, Gradle 8.13, AGP 8.13.0, Kotlin 2.2.10 and Android SDK 36. Debug verification ran before optimized verification. The final combined build/test/lint command succeeded in 1 minute 1 second.

## Verified results

- Both debug and both optimized APKs physically exist and pass signature verification.
- All four use `com.pixelwatchgallery`, version `0.2`/code `2`, and the same signing certificate as the original v0.1 APKs.
- Phone min SDK 26; watch min SDK 30; target SDK 36.
- Optimized builds use R8 and resource shrinking, retain the receiver capability and listener services, and are not debuggable. The watch is shell-profileable.
- 35 tests passed: 14 batch progress, 5 ACK transitions, 12 storage/protocol and 4 Asset lifetime tests. No failed, errored or skipped tests.
- Debug and optimized lint: no errors; phone 1 and watch 3 dependency-version notices per variant.

## APKs

| Path | Bytes | SHA-256 |
| --- | ---: | --- |
| `mobile/build/outputs/apk/debug/mobile-debug.apk` | 11,638,766 | `ada8a51a34245efc42e35b94b321988d95352403b5c5f7af12be52e16cab24c7` |
| `wear/build/outputs/apk/debug/wear-debug.apk` | 30,364,736 | `9cdb07acb4776da2be91780b1b04db0e10e851b42bb62616fcf0d857a58be17e` |
| `mobile/build/outputs/apk/optimized/mobile-optimized.apk` | 1,504,429 | `27d3329bdaeb8442bd70d329a9d2145ee40c88f40f0e6ad8b63636f40e72a721` |
| `wear/build/outputs/apk/optimized/wear-optimized.apk` | 2,906,245 | `55c55079c7e7abc7e74dc17307c85537e939500ad83e1f1b5f87824dd01fd313` |

Signing certificate SHA-256:

```text
b64fa9573e5aa2aeaddefd2528c3c01be7caf9520fd6fb8ef9340fb01ce3bb35
```

The certificate matches the pre-update APKs. Physical installed certificates and runtime updates were not checked because `adb devices -l` showed no attached devices. No APK was installed and no user data was changed.

## Changes and limitations

The watch reuses screen-sized image requests and its bounded 8 MiB cache, avoids duplicate/resume directory scans and uses direct UUID file lookup/deletion. The phone shows durable per-batch ACK-weighted bytes, percentage and photo receipt counts, with honest unknown-size/failure states and non-blocking warnings above 5 MB. Original image bytes, storage paths and transfer wire protocol are preserved. The journal reader supports legacy v0.1 selections.

See [PERFORMANCE.md](PERFORMANCE.md) for the initial audit, exact final build commands, measured baseline/final APK sizes, runtime measurement boundaries, profiling commands and update commands. See [TESTING.md](TESTING.md) for existing-feature and v0.2 hardware regressions. Physical transfer, gesture, memory, idle CPU/battery and actual update-data preservation remain unverified in this session.
