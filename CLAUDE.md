# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # JVM unit tests (JUnit 4)
./gradlew lint                   # Android lint
```

Works with any JDK 17+ and an Android SDK pointed to by `ANDROID_HOME` or `sdk.dir` in `local.properties`. If you have no system JDK, Android Studio's bundled one works too, e.g. `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` — that path is only a suggestion, not a requirement.

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` (any `assemble*`) also copies the APK to `release/app-debug.apk` via a `doLast` hook in `app/build.gradle.kts`. That file is deliberately tracked in git by the maintainer — do not remove it from the repo or drop the copy step; after a local build run `git checkout -- release/app-debug.apk` unless you intend to publish a new build.

Unit tests live under `app/src/test/java/com/sackup/` (plain JVM, no Robolectric; `testOptions.unitTests.isReturnDefaultValues = true` lets data classes that carry `android.net.Uri` load). CI (`.github/workflows/android.yml`) runs `lint testDebugUnitTest assembleDebug` on JDK 17, uploads the reports/APK as artifacts, and publishes the APK to a GitHub release on `v*` tags. No formatter is configured.

## What This App Does

SackUp backs up phone files (photos, videos, documents, music) to a USB drive via OTG cable. It targets non-technical users — the UI uses large buttons and simple language. There are no cloud services, accounts, or internet requirements.

## Architecture

Single-activity Android app. Kotlin, Jetpack Compose (Material 3), Room database, no DI framework.

**Data flow:** `SackUpApp` (Application subclass) creates a `BackupRepository` singleton and, on an application-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, seeds the default groups and prunes old history. It exposes `seedingDone: CompletableDeferred<Unit>` which completes after seeding so `MainActivity` can await it before its first group load. `MainActivity` gets the repo from the Application instance and passes it through Compose screens via lambdas. There is no ViewModel layer — state lives in `MainActivity` as `mutableStateOf`/`mutableStateListOf` properties.

**Backup engine:** `service/BackupEngine.kt` does the actual work — scans the phone and the drive, diffs them (`service/Diff.kt`), copies with 2 workers (`WORKER_COUNT`), a 4 MB read buffer and 256 KB write chunks (so cancel is responsive mid-file), fsyncs each file, verifies name + size on the drive, deletes and reports mismatches, and removes partial files on cancel. `service/BackupService.kt` is the foreground service that orchestrates a run: starts the engine, writes history entries, updates the notification, and rebuilds the group's manifest afterwards. Progress is a `StateFlow<BackupProgress>` on the `BackupService` companion (`service/BackupProgress.kt` is the immutable snapshot); Compose collects it with `collectAsState()` — there are no volatile fields and no polling.

**Phone files** are enumerated through MediaStore (`util/MediaStoreUtils.kt`) and read with `ContentResolver.openInputStream` — never `java.io.File`. `util/MediaStoreCompat.kt` papers over MediaStore differences on API 26–28 (no `RELATIVE_PATH`, per-media-type URIs). On Android 11+ only images/video/audio are visible to the app; other document types are not backed up.

**USB drive access:** SAF. The user picks a folder with `OpenDocumentTree`; the URI is persisted in SharedPreferences (`"sackup"` prefs, key `"drive_uri"`) with a persistable permission. All drive I/O goes through `DocumentsContract` (child cursors, `createDocument`, `deleteDocument`, `openFileDescriptor` + fsync); `DocumentFile` is used only for the "is the drive still connected?" check. Files land under the chosen folder as `<phoneFolder>/<subpath>/<file>`; there is no per-group drive folder. Files are matched by name + size to skip duplicates.

**Database:** Room, single `sackup.db`, version 3, three tables: `backup_groups` (configured backup sets), `log_entries` (history, pruned after 30 days) and `manifest_entries` (one row per file confirmed on the drive — the engine only writes rows for verified files, so `backupSuccess` is always true for rows it writes; the column is kept for manifests written by older versions. "Free Up Space" and "Check what's backed up" read these). `phoneFolders` is stored as a JSON string array; `decodeFolders`/`encodeFolders`/`folderList()` in `data/BackupGroup.kt` are the only codec — never construct Gson elsewhere. Multi-statement operations (`deleteGroup`, `rebuildManifest`, `removeManifestEntries`) run in `db.withTransaction`; `removeManifestEntries` chunks ids by 900 because of SQLite's 999 bind-variable limit on Android ≤ 11.

**Schema & migrations:** `exportSchema = true`; the JSON lands in `app/schemas/com.sackup.data.AppDatabase/<version>.json` (`room.schemaLocation` in `app/build.gradle.kts`) and must be committed. The builder uses `fallbackToDestructiveMigrationFrom(1, 2)` — only the pre-manifest versions may be wiped. **Never bump the DB version without shipping a `Migration` via `addMigrations(...)`**, and never widen the destructive fallback: it would silently erase users' manifests.

**Navigation:** Jetpack Navigation Compose. Routes in `ui/Navigation.kt`: Home → Setup (new/edit) → Progress → History (`LOGS`) → Check backup (`ANALYZE`) → Free Up Space (`CLEAR_SPACE`).

## Key Design Decisions

- No ViewModel — `MainActivity` owns all state and delegates to `BackupRepository` coroutines directly
- Backup progress is a single `StateFlow<BackupProgress>` snapshot on the service companion, collected by Compose
- Phone folders within a backup group are stored as a Gson JSON array string in Room, not a normalized table
- Default backup groups are Images (DCIM, Pictures), Documents (Documents) and Music (Music). Seeding is gated by the `"defaults_seeded"` flag in the `"sackup"` prefs, not by row count, so deleting all defaults does not resurrect them
- 2 copy workers: USB is a serial link, more workers only add SAF overhead
- Every copied file is fsynced and verified (name + size) before it counts; mismatches are deleted and reported
- History auto-prunes after 30 days
- R8 is off (`isMinifyEnabled = false`); `proguard-rules.pro` already carries the Gson `TypeToken` keep rules so it can be turned on safely
- Signing: `sackup-release.jks` at the repo root (git-ignored) plus `signing.*` from Gradle properties or `local.properties`; without it the build warns and uses the debug keystore
