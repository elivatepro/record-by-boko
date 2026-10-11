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

## Linking (learned on the owner's Note Pro, firmware V67328, 11 Oct 2026)

- A recorder that was used with the Plaud app keeps that app's RSA public key and owner. Our
  normal handshake then fails: the SDK can't decrypt the recorder's first reply
  (`PKCS_DECODING_ERROR` / `DATA_TOO_LARGE_FOR_MODULUS`), and later the recorder stays silent
  after `first_handshake` until the SDK's fixed 10 s connect timeout (`TIME_OUT`).
- First links therefore use the SDK's force-clear pre-handshake (`recoveryConnectBleDevice`
  with our own user id), which makes the recorder drop the old key and take ours.
- The old *owner* only goes away when the Plaud app unbinds the recorder **while connected to
  it**. The scan shows it: `bindInfo=1` still bound, `bindInfo=0` free. Unbinding in the app
  while the recorder is away only updates Plaud's cloud.
- Reconnects use the normal handshake. The SDK's 10 s timeout includes opening the Bluetooth
  connection (1.5–7 s seen), so slow connections can time out; KeepConnected retries.
- `bleStorage` reports bytes; `bleRecordStateRefreshed` gives session 0 when idle.

## Exporting (learned on the owner's Note Pro, 11 Oct 2026)

- `getFileList()` answers through `bleFileList`; each `BleFile` gives only `sessionId` (the start in
  Unix seconds) and `fileSize` publicly.
- `exportAudio(sessionId, dir, OPUS, 1, callback)` writes Ogg Opus into `dir`: mono, 16 kHz input
  rate, about 33 kbps, so the recorder's own stream wrapped rather than re-encoded. Progress 0–100
  is the download; then a short `TRANSCODING` stage; then `onComplete(file)`. A 7 s test file took
  3.4 s over Bluetooth, most of it setting up.
- Plaud's template notes that the SDK's Opus exporter writes a corrupt OpusTags comment count;
  1.0.15 wrote a valid one in our test. `OpusRepair` checks every export anyway.
- The template deletes each file from the recorder after export. Roudio never does: deleting is
  the clean-up gate's job (spec §8, milestone 4).
