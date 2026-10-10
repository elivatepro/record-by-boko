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
