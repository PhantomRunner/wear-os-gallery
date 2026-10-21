# Build verification — 2026-10-21

The interrupted implementation was resumed from commit `9ed3855`. Existing application features were retained. This session finished documentation, reduced build memory usage, explicitly disabled legacy backups, added three storage failure/recovery tests, and regenerated both debug APKs.

## Environment

- Linux x86_64; Android SDK at `/home/srgwx/Android/Sdk`.
- Eclipse Temurin JDK 17.0.20.1 at `/home/srgwx/.jdks/temurin-17`.
- Gradle 8.13 wrapper, Android Gradle Plugin 8.13.0, Kotlin 2.2.10.
- Compile/target SDK 36; Android Build Tools 35.0.0.
- Gradle heap 1536 MiB, metaspace limit 512 MiB, at most two workers, sequential project builds, Kotlin compilation in-process.

The shell initially had no `java` on PATH. An attempt using `/opt/android-studio/jbr` selected JDK 25.0.3 and failed before compilation with `25.0.3`. Selecting the installed JDK 17 resolved that toolchain failure. Do not replace the pinned toolchain merely to use the Android Studio bundled JDK.

## Commands and outcomes

```bash
JAVA_HOME=/home/srgwx/.jdks/temurin-17 ./gradlew --no-daemon \
  :mobile:assembleDebug :wear:assembleDebug \
  :shared:testDebugUnitTest :mobile:lintDebug :wear:lintDebug
```

**BUILD SUCCESSFUL**, 20 seconds, 142 actionable tasks. Both APKs were produced. The shared test suite passed **10 tests, 0 failures, 0 errors, 0 skipped**.

The first incremental lint run reused old report files. Reports were refreshed with:

```bash
JAVA_HOME=/home/srgwx/.jdks/temurin-17 ./gradlew --no-daemon --rerun-tasks \
  :mobile:lintDebug :wear:lintDebug
```

**BUILD SUCCESSFUL**, 28 seconds, 95 tasks executed. Phone lint: **0 errors, 1 warning**. Watch lint: **0 errors, 3 warnings**. The remaining warnings only advertise newer Gradle/Wear Compose versions; this MVP keeps its compatible pinned versions. Backup manifest warnings are resolved.

Reports are generated locally at:

- `shared/build/reports/tests/testDebugUnitTest/index.html`
- `mobile/build/reports/lint-results-debug.html`
- `wear/build/reports/lint-results-debug.html`

Tests cover exact-byte persistence across restart, duplicate receipt, rejection/cleanup of invalid files, deletion and lost-ACK retry, abandoned temporary files, strict UUID/path validation, supported header detection, interrupted input and retry, publication only after validation, and recovery when a photo was published before its receipt marker.

## APK verification

`apksigner verify --verbose --print-certs` passed for both APKs. Both use the same RSA debug certificate, SHA-256:

```text
b64fa9573e5aa2aeaddefd2528c3c01be7caf9520fd6fb8ef9340fb01ce3bb35
```

`aapt dump badging` verified application ID `com.pixelwatchgallery`, version `0.1`/code `1`, target SDK 36, phone min SDK 26 and watch min SDK 30. The watch declares `android.hardware.type.watch`. Neither packaged application requests INTERNET or broad media/storage permissions; AndroidX adds its normal app-specific dynamic receiver permission.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `mobile/build/outputs/apk/debug/mobile-debug.apk` | 12,110,045 | `8e760cf19253cca0b6882c61836f266f53205d17262e473da56afc8c9708db66` |
| `wear/build/outputs/apk/debug/wear-debug.apk` | 30,736,851 | `b4347d75c634e160cb3cf8e0c740681f453c57d5bcd1cec8269305318559290d` |

These hashes describe this session's debug artifacts. A rebuild with a different signing key or toolchain may produce different APK bytes.

## Runtime verification boundary

`adb devices -l` returned no attached devices or emulators. No phone-to-watch transfer, physical Photo Picker interaction, round-display gesture behavior, device restart, or hardware memory test was performed during this resumed session. Existing temporary emulator files from the earlier session are not treated as proof of runtime success.

The JVM tests exercise filesystem/protocol behavior; they do not execute Android BitmapFactory validation, Compose UI, Google Play services, or Data Layer delivery. Use the [physical-device checklist](TESTING.md) for installation, actual transfer, original-byte hash comparison, background reception, offline browsing, deletion, lost acknowledgment recovery, and same-key update testing.
