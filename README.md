# SackUp

Back up your phone to a USB drive — simple enough for your parents.

## What is SackUp?

SackUp is an Android app that copies your phone's photos, videos, and files to a USB drive (SSD/flash drive) connected via a USB-OTG cable. It's built for people who aren't tech-savvy — your parents, grandparents, or anyone who just wants their files safe on a physical drive without fussing with cloud storage.

## How It Works

### First-Time Setup (done by you, the tech-savvy one)

1. Plug the USB drive into the phone with an OTG cable
2. Open SackUp and grant it access to the drive
3. The app comes with 3 backup groups ready to go: **Camera**, **Downloads**, and **WhatsApp**
4. For each group, you pick:
   - **Phone folders** — where files come from (e.g., DCIM, Pictures)
   - **Drive folder** — where files go (e.g., Camera-Backup)
5. Phone folder names are created inside the drive folder automatically:

```
Camera-Backup/
├── DCIM/
│   ├── IMG_001.jpg
│   └── IMG_002.jpg
└── Pictures/
    ├── Screenshot_01.png
    └── Screenshot_02.png
```

### Everyday Use (what your parents do)

1. Plug in the USB drive
2. Open SackUp
3. Tap a backup group — that's it

Only new files are copied. When it's done: **"12 files saved, 4.2 GB"**.

### Freeing Up Phone Space

1. Tap the three-dot menu on a backup group
2. Tap "Free Up Space"
3. See how much space you'll get back
4. Confirm — only files safely on the drive get removed

### Checking What's Backed Up

Tap "View Backed Up" on any backup group — works even without the drive plugged in.

### Logs

A built-in log screen shows everything that happened — current session and previous sessions. Logs are copyable so you can paste them into a message if you need help troubleshooting.

## Backup Engine

- **Fast.** Uses maximum CPU and I/O with large buffer sizes. No throttling. The phone is plugged in and doing one job — copy as fast as the USB connection allows.
- **Runs in foreground and background.** Backup keeps going if the user switches apps, locks the screen, or gets a phone call. A persistent notification shows progress the entire time.
- **Progress is always visible.** The notification shows which file is being copied and overall percentage. Inside the app, a full progress screen shows file count, current file name, and bytes transferred in real time.
- **Only copies what's new.** Before copying, the app scans the drive folder. Files already there (matched by name and size) are skipped. No wasted time re-copying.
- **Picks up where it left off.** If backup is interrupted (cable pulled, phone dies), just run it again. Already-copied files are skipped, and it resumes from where it stopped.
- **Verifies every file.** After copying, the app checks the file size on the drive matches the source. If it doesn't, the file is deleted and flagged — no silent corruption.
- **Cancellable anytime.** Cancel button in the notification and in the app. Stops after the current file. Partially copied files are cleaned up.
- **Errors don't stop the job.** If one file fails, it's skipped and reported at the end. The rest of the backup continues. The summary shows exactly what failed and why, in plain language.

## Key Points

- Nothing gets deleted unless it's confirmed on the drive
- Works offline — no internet, no cloud, no accounts
- Big buttons, simple words — designed for people who find most apps overwhelming
- Fast — uses all available CPU to copy as quickly as the USB connection allows

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Storage:** Android Storage Access Framework (SAF) for USB drive access
- **Database:** Room (backup groups, backup history, logs)
- **Background work:** Foreground Service with progress notification
- **Min SDK:** 26 (Android 8.0+)
- **Target SDK:** 35

## Building

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Project Structure

```
app/src/main/java/com/sackup/
├── SackUpApp.kt              # Application class
├── MainActivity.kt           # Single activity, hosts Compose navigation
├── data/
│   ├── AppDatabase.kt        # Room database
│   ├── BackupGroup.kt        # Backup group entity + DAO
│   ├── LogEntry.kt           # Log entry entity + DAO
│   └── BackupRepository.kt   # Data access layer
├── service/
│   └── BackupService.kt      # Foreground service — the backup engine
├── ui/
│   ├── Navigation.kt         # App navigation graph
│   ├── HomeScreen.kt         # Main screen — list of backup groups
│   ├── SetupScreen.kt        # Configure a backup group
│   ├── ProgressScreen.kt     # Live backup progress
│   ├── LogScreen.kt          # View logs (copyable)
│   └── theme/
│       └── Theme.kt          # App theme
└── util/
    └── FileUtils.kt          # File size formatting, etc.
```

## License

MIT
