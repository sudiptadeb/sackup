# SackUp

Back up your phone to a USB drive — simple enough for your parents.

## What is SackUp?

SackUp is an Android app that copies your phone's photos, videos, and files to a USB drive (SSD/flash drive) connected via a USB-OTG cable. It's built for people who aren't tech-savvy — your parents, grandparents, or anyone who just wants their files safe on a physical drive without fussing with cloud storage.

## How It Works

### First-Time Setup (done by you, the tech-savvy one)

1. Plug the USB drive into the phone with an OTG cable
2. Open SackUp and pick the folder on the drive where backups should go
3. The app comes with 3 backup groups ready to go: **Images** (DCIM, Pictures), **Documents** (Documents) and **Music** (Music)
4. For each group you pick the **phone folders** files come from. You can edit the defaults, delete them, or add your own group for any folder you choose
5. There is no per-group drive folder. Every group writes into the drive folder you picked in step 2, mirroring the phone's folder layout as `<phoneFolder>/<subpath>/<file>`:

```
<drive folder you picked>/
├── DCIM/
│   └── Camera/
│       ├── IMG_001.jpg
│       └── IMG_002.jpg
├── Pictures/
│   └── Screenshots/
│       └── Screenshot_01.png
└── Documents/
    └── tax-2025.pdf
```

### Everyday Use (what your parents do)

1. Plug in the USB drive
2. Open SackUp
3. Tap a backup group — that's it

Only new files are copied. When it's done: **"12 files are now safely on your USB drive (4.2 GB)"**.

### The menu on each backup group

Tap the three-dot menu on a backup group to get:

- **Edit** — change the name or the phone folders
- **Check what's backed up** — see which files are on the drive and which are not. Works from the app's own records, so it also works without the drive plugged in
- **Free Up Space** — see how much space you'd get back, then confirm. Only files that were confirmed on the drive during a successful backup are removed from the phone
- **Delete** — remove the backup group (asks for confirmation first; nothing on the drive or the phone is touched)

### History

A built-in history screen shows everything that happened — current session and previous sessions. Entries are copyable so you can paste them into a message if you need help troubleshooting. History older than 30 days is pruned automatically.

## Backup Engine

- **Fast enough for USB.** Two copy workers run in parallel with a 4 MB read buffer. USB is a serial link, so more workers would only add overhead; the phone is plugged in and doing one job — copy as fast as the USB connection allows.
- **Runs in foreground and background.** Backup keeps going if the user switches apps, locks the screen, or gets a phone call. A persistent notification shows progress the entire time.
- **Progress is always visible.** The notification shows which file is being copied and overall percentage. Inside the app, a full progress screen shows file count, current file name, and bytes transferred in real time.
- **Only copies what's new.** Before copying, the app scans the drive folder. Files already there (matched by name and size) are skipped. No wasted time re-copying.
- **Picks up where it left off.** If backup is interrupted (cable pulled, phone dies), just run it again. Already-copied files are skipped, and it resumes from where it stopped.
- **Verifies every file.** Every copy is flushed all the way to the drive (fsync), then its name and size on the drive are checked against the source. A mismatch is deleted from the drive and reported — no silent corruption.
- **Cancellable anytime.** Cancel button in the notification and in the app. Stops mid-file; the partially written file is removed from the drive.
- **Errors don't stop the job.** If one file fails, it's skipped and reported at the end. The rest of the backup continues. The summary shows exactly what failed and why, in plain language.

## Key Points

- Nothing gets deleted from the phone unless it was confirmed on the drive
- Works offline — no internet, no cloud, no accounts
- Big buttons, simple words — designed for people who find most apps overwhelming

## Limitations

- **Android 11 and newer:** the app can only see photos, videos and audio through the system media library. Other document types (PDFs, ZIPs, APKs, …) are not visible to it and will not be backed up. Android 8–10 do not have this restriction.
- One USB drive folder at a time. Changing it re-scans the drive on the next backup.

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Phone files:** MediaStore for enumeration, `ContentResolver` streams for reading
- **USB drive:** Android Storage Access Framework (SAF) via `DocumentsContract`
- **Database:** Room — three tables: `backup_groups`, `log_entries`, `manifest_entries` (what is confirmed on the drive)
- **Background work:** Foreground Service with progress notification
- **Min SDK:** 26 (Android 8.0+)
- **Target SDK:** 35

## Building

Requires JDK 17+ and an Android SDK (`ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew testDebugUnitTest      # run the JVM unit tests
./gradlew lint                   # Android lint (report in app/build/reports/)
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` also copies it to `release/app-debug.apk`, which is tracked in git by the maintainer's choice so the latest build can be installed straight from the repository.

CI (`.github/workflows/android.yml`) runs lint, the unit tests and `assembleDebug` on every push and pull request, uploads the reports and APK as workflow artifacts, and attaches the APK to a GitHub release when a `v*` tag is pushed. If a `RELEASE_KEYSTORE_B64` secret (plus `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) is configured the APK is signed with the release key; otherwise the debug key is used.

## Project Structure

```
app/src/main/java/com/sackup/
├── SackUpApp.kt              # Application class: creates the repository, seeds defaults, prunes history
├── MainActivity.kt           # Single activity, owns UI state, hosts Compose navigation
├── data/
│   ├── AppDatabase.kt        # Room database (schema exported to app/schemas/)
│   ├── BackupGroup.kt        # Backup group entity + DAO + phone-folder JSON codec
│   ├── LogEntry.kt           # History entry entity + DAO
│   ├── ManifestEntry.kt      # Per-file record of what is on the drive + DAO
│   └── BackupRepository.kt   # Data access layer (transactions, seeding, pruning)
├── service/
│   ├── BackupService.kt      # Foreground service: orchestrates a run, logs, notifies, rebuilds manifest
│   ├── BackupEngine.kt       # Scans phone + drive, diffs, copies with 2 workers, verifies
│   ├── BackupProgress.kt     # Immutable progress snapshot exposed as a StateFlow
│   └── Diff.kt               # Phone-vs-drive comparison (what to copy, what is already there)
├── ui/
│   ├── Navigation.kt         # App navigation graph
│   ├── HomeScreen.kt         # Main screen — list of backup groups + group menu
│   ├── SetupScreen.kt        # Configure a backup group
│   ├── ProgressScreen.kt     # Live backup progress and results
│   ├── LogScreen.kt          # History (copyable)
│   ├── AnalyzeScreen.kt      # "Check what's backed up"
│   ├── ClearSpaceScreen.kt   # "Free Up Space"
│   └── theme/
│       └── Theme.kt          # App theme
└── util/
    ├── FileUtils.kt          # Byte/duration formatting
    ├── MediaStoreUtils.kt    # Enumerates phone files through MediaStore
    └── MediaStoreCompat.kt   # MediaStore column/URI differences on Android 8–9 (API 26–28)

app/src/test/java/com/sackup/   # JUnit 4 unit tests (data codec, formatting)
app/schemas/                    # Exported Room schema, one JSON per DB version
fastlane/metadata/              # Store listing text and changelogs
```

## License

MIT
