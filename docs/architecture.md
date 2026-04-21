# Architecture Overview

This app is intentionally optimized for a single validated personal-use battery flow.

## Main pipeline

1. `TargetDeviceMatcher`
   - Identifies the primary target device.
2. `BleScannerManager`
   - Handles permissions/checks and delegates refresh to the GATT session.
3. `OpenFeelGattSession`
   - Connection lifecycle
   - Service discovery
   - Read total battery via `180F/2A19`
   - Enable notify
   - Send validated split-battery trigger commands
   - Collect notify frames
4. `OpenFeelBatteryParser`
   - Parses total battery and split battery frame payloads.
5. `MainViewModel` + `MainScreen`
   - Stable UI state and minimal product-facing UI.

## Data rules

- Total battery and split battery are cached separately.
- Failed refresh does not clear previous successful values.
- Temporary disconnect does not clear previous successful values.
