# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`. The `JAVA_HOME` override points to Android Studio's bundled JDK.

No tests exist yet. No linter or formatter is configured.

## What This App Does

SackUp backs up phone files (photos, videos, downloads) to a USB drive via OTG cable. It targets non-technical users — the UI uses large buttons and simple language. There are no cloud services, accounts, or internet requirements.

## Architecture

Single-activity Android app. Kotlin, Jetpack Compose (Material 3), Room database, no DI framework.

**Data flow:** `SackUpApp` (Application subclass) creates a `BackupRepository` singleton. `MainActivity` gets the repo from the Application instance and passes it through Compose screens via lambdas. There is no ViewModel layer — state lives in `MainActivity` as `mutableStateOf`/`mutableStateListOf` properties.

**Backup engine:** `BackupService` is a foreground service that does the actual file copying. It exposes progress via `@Volatile` companion object fields that UI reads directly (not Flow/LiveData). The service uses SAF (`DocumentFile`) to write to USB and `java.io.File` to read from phone storage. Files are matched by name+size to skip duplicates. A 1MB buffer is used for throughput.

**USB drive access:** Uses SAF `OpenDocumentTree` picker. The URI is persisted in SharedPreferences (`"sackup"` prefs, key `"drive_uri"`). Permission is taken persistably so it survives app restarts.

**Database:** Room, single `sackup.db` with two tables: `backup_groups` (configured backup sets) and `log_entries` (backup session logs). `phoneFolders` is stored as a JSON string array, serialized with Gson.

**Navigation:** Jetpack Navigation Compose. Routes defined in `ui/Navigation.kt`. Screens: Home → Setup (new/edit) → Progress → Logs.

## Key Design Decisions

- No ViewModel — `MainActivity` owns all state and delegates to `BackupRepository` coroutines directly
- `BackupService` progress is shared via volatile static fields, not LiveData/Flow — UI polls these in Compose recomposition
- Phone folders within a backup group are stored as a Gson JSON array string in Room, not a normalized table
- Three default backup groups (Camera, Downloads, WhatsApp) are seeded on first launch
- Logs auto-prune after 30 days
