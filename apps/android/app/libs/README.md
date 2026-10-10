# Plaud SDK

`plaud-sdk.aar` is Plaud's Android device SDK, Apache-2.0, copied unchanged from
github.com/Plaud-AI/plaud-sdk-public (`android/app/libs/plaud-sdk.aar`).

- Version: Plaud SDK 1.0.15 (repo commit `d532d5a4`, 10 Oct 2026)
- SHA-256: `041a6f8814d350dbc8bcd4a515487136529bb8bb4368fc88c9b36c9a386aedce`

The aar declares none of its library dependencies; `app/build.gradle.kts` lists
them under "Plaud SDK". Only `sdk.PlaudDeviceAgent`, `sdk.PlaudDeviceAgentListener`,
`BleDevice` / `BleFile` (plus the few `NiceBuildSdk` calls Plaud's template still
needs) are public API. Everything else is obfuscated and changes between releases.

To update: replace the file, update the version and checksum above, and re-test
linking on the real recorder.

## Logging

The SDK logs whole HTTP exchanges (the user token, and the RSA private key Plaud returns)
through Timber, to logcat and to `files/plaud_sdk_logs`. Roudio replaces Timber with a silent
copy (`src/main/java/timber/log/Timber.java`) that covers exactly the calls this SDK makes. If a
new SDK version fails with `NoSuchMethodError` in `timber.log`, add the missing call there as a
no-op; never bring the real Timber back. The SDK's Bluetooth logs (`PenBleSDK-*`, plain
`android.util.Log`) carry no secrets.
