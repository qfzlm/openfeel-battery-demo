# Earbud Battery (Android)

[![Android CI](https://img.shields.io/github/actions/workflow/status/qfzlm/py/android-ci.yml?branch=main&label=Android%20CI)](https://github.com/qfzlm/py/actions/workflows/android-ci.yml)
[![Release](https://img.shields.io/github/v/release/qfzlm/py?label=Release)](https://github.com/qfzlm/py/releases)
[![License](https://img.shields.io/badge/License-Not%20specified-lightgrey)](https://github.com/qfzlm/py)

A lightweight Android app for fast, reliable earbud battery checks.

> Personal daily-use tool: stable connection, quick refresh, minimal UI.

## Features

- One-tap refresh for both total battery and split battery.
- Stable total battery fallback via standard `180F / 2A19`.
- Split battery pipeline from validated private frame:
  - `DD ?? 04 0C XX YY ZZ AA`
  - Left: `XX & 0x7F`
  - Right: `YY & 0x7F`
  - Case: `ZZ & 0x7F`
- Keeps last successful values after temporary disconnects.
- Export logs to `Download` (MediaStore).

## Supported Device Scope (Current)

- Primary target: `41:42:D3:16:6F:68`
- Matching signals:
  - Manufacturer ID `0x0A0B`
  - MAC prefix `41:42`

This project is intentionally scoped for a validated personal target path, not generic multi-device support.

## UI (Production)

Home screen keeps only essential info:

- Total battery
- Last update time
- Connection status
- Left / Right / Case battery
- Refresh button
- Export logs button

No debug panels, no candidate list, no GATT raw detail shown to users.

## Build

### Requirements

- JDK 17
- Android SDK (`android-35` + matching build-tools)

### Build command (Windows)

```powershell
gradlew.bat -p E:\py\erji\TwsBatteryDemo :app:assembleDebug --no-daemon
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Runtime Permissions

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- Android 11 and below: location permission is required for BLE scan

## Cache Rules

- Total and split battery cache keep the latest successful values.
- Failed refresh does not clear previous valid values.
- Temporary disconnect does not clear previous valid values.
- `N/A` / `--` appears only when there has never been a successful read.

## Known Limits

- Single target path by design (no multi-device session management).
- Charging-state semantics are intentionally not exposed as formal UI yet.
- No unknown private write commands are sent.

## Changelog / Releases

- See GitHub Releases and `RELEASE_NOTES_v0.2.0.md`.

## Disclaimer

This is a personal utility project based on practical validation and reverse-engineering clues.
Compatibility is not guaranteed for all firmware or hardware variants.
