# Developer Quickstart

This document is for developers who want to build and contribute quickly.

## Project location

- Repo root: `E:\py`
- Android project: `erji/TwsBatteryDemo`

## Requirements

- JDK 17
- Android SDK (platform + build-tools matching the project)

## Build

From `erji/TwsBatteryDemo`:

```powershell
gradlew.bat :app:assembleDebug --no-daemon
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Lint

```powershell
gradlew.bat :app:lintDebug --no-daemon
```

Lint HTML report:

```text
app/build/reports/lint-results-debug.html
```

## What to keep stable

1. Target-device matching and refresh flow.
2. Total battery path (`180F / 2A19`).
3. Split battery path (`04 0C`).
4. Refresh anti-reentry behavior.
5. Log export to Download (MediaStore).

## Typical contribution flow

1. Create a branch from `main`.
2. Make focused changes.
3. Run `assembleDebug` and `lintDebug`.
4. Open a PR with clear validation notes.
