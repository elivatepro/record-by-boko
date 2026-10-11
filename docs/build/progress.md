# Build progress

What's done, what was learned, and what's still open. Newest milestone last.

## Milestone 0: Foundations (done 10 Oct 2026)

Done when "you can sign in on the phone and on the web as yourself, any other
Google account is refused, and CI is green". All three were checked by the
owner on 10 Oct 2026: phone (Redmi Note 14 Pro+ 5G, Android 16) and web
(production) sign-in worked, another account was refused, and CI passed.

What exists:
- Monorepo as in the build plan; CI on GitHub Actions (backend, web,
  Android). The repo is public, so Actions minutes are free.
- `packages/design-tokens`: Paper tokens plus the dark mapping. Tokens
  added to Paper during the build: warning tint/rim, night sheet/rim,
  night card tile, night disabled, night alert tile, night warning tint/rim,
  alert tint, hero muted, `--radius-sheet`.
- Backend: FastAPI api + worker, Postgres job queue, Alembic, OpenAPI.
  Owner-only Google sign-in (Google account pinned on first sign-in), Roudio
  sessions with rotating refresh tokens, encrypted Drive grant,
  `POST /plaud/token`.
- Web: W1 sign-in (one Google popup covers sign-in and Drive) and a
  placeholder signed-in page.
- Android: 11b sign-in and a temporary check screen (account, Drive, Plaud
  token). Replace it with the setup flow in milestone 1.
- Railway: project `roudio` (Quality Performance workspace), production and
  staging. See README "Deploying".

Learned:
- **Plaud token exchange works** with real credentials. The partner-token
  call (`POST /oauth/partner/access-token`, Basic auth) accepts
  `grant_type=client_credentials`, which Plaud doesn't document. The user
  token is a 24 h JWT for `platform-us.plaud.ai`. Its claims are `aud`,
  `client_id`, `exp`, `iat`, `sub`, `type`, `user_id`.
- The Plaud Android SDK is a public `.aar` at
  github.com/Plaud-AI/plaud-sdk-public (`sdk/android/plaud-sdk.aar`), with
  minSdk 21 and arm64/armeabi-v7a only, so it needs a real phone. Known SDK
  bug: call `NiceBuildSdk.getPartnerApiManager().updateBaseUrl(...)` before
  `initSDK`, or US tokens fail with `CLIENT_USER_NOT_FOUND`.
- Railway's `railway.json` config is deprecated (Railway stops reading it on
  1 Dec 2026). Infrastructure is in `.railway/railway.ts`.
- TypeScript 7 has no JS API yet, so `openapi-typescript` and
  `typescript-eslint` need TypeScript 6 (pinned in `apps/web`).
- The repo path contains spaces, so the OpenAPI generator gets a `file://`
  URI.
- Xiaomi blocks `adb install` until "Install via USB" is on, and the
  on-screen prompt must be accepted.

Still open:
- **Rotate the Google client secret.** It was pasted in chat. Add a new
  secret in Google Cloud, put it in `services/backend/.env`, copy it to
  Railway's shared `GOOGLE_CLIENT_SECRET` in both environments, check it,
  then disable the old one. Don't disable the old one first.
- `railway config plan` answers "Unauthorized" with the staging project
  token, so staging is a copy of production and IaC changes are applied to
  production only. Staging service variables were set by API.
- The Android debug build points at the production API
  (`apps/android/local.properties`). Switch it to staging or local when
  that's more useful.
- A detached volume (`postgres-volume-6yrH`) is pending deletion on Railway.
- Ask Plaud support whether the SDK needs Plaud's servers on every
  connection or only when binding, and test transfers offline in
  milestone 1.
- Release builds need their own Android OAuth client (release SHA-1).

## Milestone 1: Linking the recorder (in progress)

Done so far (11 Oct 2026):
- Plaud SDK 1.0.15 in `apps/android/app/libs` (Apache-2.0, committed with its checksum).
- `RecorderSource` with `PlaudRecorderSource` and a fake; `Recorder` holds the state rules
  (recording state is "unknown" unless reported on the current connection; search gives up
  after 30 s, linking after 60 s). Unit-tested.
- `RecorderService` (connected-device foreground service) keeps the recorder connected, shows
  its state in an ongoing notification, restarts after boot or an update. `KeepConnected`
  reconnects at once when Bluetooth returns, then after 30 s, 1, 2 and every 5 min.
- Linked to the owner's Note Pro (…1437) and read live battery, storage and recording state.
- Debug builds only: adb commands drive the recorder
  (`adb shell am broadcast -n com.boko.roudio/.debug.RecorderCommands --es cmd search|link|state|…`).
- Setup screens 11a (welcome), 11c (permissions), 11d (keep running: reads Xiaomi Autostart and
  the battery exemption) and 11e (unlink from the Plaud app), built from Paper with Navigation 3.
- The recorder check screen still stands in after 11e and once a recorder is linked.

Done since (11 Oct 2026):
- 11f–11i: real 30 s search, found list (a recorder bound to an app that isn't ours is greyed and
  opens 12d), link steps from SDK events, linked with live readings. Linking the recorder Roudio
  already has uses the ordinary handshake, not force-clear (tested on the phone: relinked fine).
- 12a–12e, each from its real condition: search timed out; Bluetooth off (any step); Nearby
  devices refused for good (from 11c or 11f); handshake refused / HTTP 403 / bound in the scan;
  dropped, no internet or other (with "Show details").
- 13a/13b recorder screen (card, last-seen values, "Unknown" recording, reconnect / turn on
  Bluetooth, unlink behind a confirmation) and a Today shell with the recorder chip (01 / S1).
  The check screen is gone. Sign-out comes back with Settings (milestone 5).
- Fix: a finished search left on screen used to stop KeepConnected reconnecting.
- Checked on the phone: Today chip, 13a live, 13b with Bluetooth off, 12b → 11f → 11g → 11h → 11i.

Still to do before milestone 1 is done (owner chose to do these later, 11 Oct 2026):
- "Done when" pass: start/stop a recording (13a shows it live), walk away with the recorder
  (Today chip, 13b "out of range", 12a after 30 s), come back (reconnects by itself).
- Not yet seen on the phone: 12a, 12c, 12d, 12e. A true fresh install (re-link with force-clear
  as our own user) is untested on purpose; if it fails, relinking needs the Plaud app.
- Owner: turn on Autostart and battery "No restrictions" for Roudio (11d shows both off).
- 11i's "On the recorder" row waits for the file list (milestone 2).

Learned:
- **The SDK logs secrets.** Its own logger writes whole HTTP exchanges (user token, the RSA
  private key Plaud returns) to logcat and app files. Roudio replaces Timber with a silent copy
  (`src/main/java/timber`). See `apps/android/app/libs/README.md`.
- **Linking a recorder that was used with the Plaud app** needs the Plaud app to unbind it
  while connected (scan shows `bindInfo=0` afterwards), plus our force-clear first handshake.
  Details in `apps/android/app/libs/README.md`.
- Scanning works without the location permission on Android 12+.
- Xiaomi: adb can't tap or grant permissions unless "USB debugging (Security settings)" is on
  and the phone has been restarted; every adb restart or install asks for confirmation.

## Milestone 2: Transfer, local store, upload queue (in progress)

Done so far (11 Oct 2026):
- `RecorderSource.listFiles()` / `export()`: Ogg Opus exports (see `apps/android/app/libs/README.md`,
  "Exporting"). A transfer that goes quiet for 60 s, a disconnect or cancelling stops it; the partial
  file is thrown away (decision 14). `OpusRepair` fixes the SDK's corrupt OpusTags count if it
  appears, before any checksum. `RecorderSource` has no delete at all.
- Room database (`store/`, schemas in `app/schemas`): a row per recording with UTC start + the
  phone's offset, the four track states, file, size, duration, SHA-256, and upload progress.
- `Transfers` (runs in `RecorderService`): lists the recorder on every connection, when a
  recording starts or stops, and every 10 min; exports new files oldest first; never the file
  being recorded, and nothing until the recorder has said whether it's recording.
- Backend: `recording` table, `PUT /recordings/{serial}/{session_id}` (start or resume),
  `PUT /recordings/{id}/parts/{n}` (8 MiB parts into an S3 multipart upload), `POST …/complete`
  (reads the file back; "uploaded" only if the SHA-256 matches). Bucket credentials reach api and
  worker as Railway references (`AUDIO_*`); staging was set through the API.
- `Uploader` + WorkManager queue: resumes from the server's next part, retries with back-off.
- Checked on the phone: two short test recordings came across and uploaded to production by
  themselves, checksums matching at every step.

Learned:
- Exporting the same recording twice gives byte-identical files.
- The SDK seems to cache a download: a second export of the same file skipped straight to
  `TRANSCODING`. Where it keeps that cache (and how big it gets) is still to check.
- Xiaomi doesn't restart Roudio after an update while Autostart is off; the service waits until the
  app is opened.
- `generateApiClient` only saw the spec's URI, so a changed spec didn't regenerate the Kotlin
  client locally (CI was fine). The file is now a declared input.

Also done (11 Oct 2026):
- Today built on real data (`TodayModel`, unit-tested): 24-hour dial, "Not written up yet" /
  "Still on Plaud" legend (Paper 01c), chip with "N on device", S2 no-internet banner, timeline
  rows with separate transfer/processing and backup words, past days with "Back to today".
- Recorder screen: Transfers section (live progress and time left, stopped-at-N% with what's
  safe, recording now, transferred today).
- Firmware gate, read-only: Plaud's latest version is checked after each connection (the
  template's `version/latest` call; the SDK's own check needs an endpoint the developer platform
  lacks). The 13a row says Waiting until everything on the recorder is uploaded and it's idle,
  then Ready. There is no install code yet: updating erases the recorder.
- The SDK keeps no download cache on disk; it writes a Bluetooth log (`files/ble_logs/ble.log`).

Still to do (needs the owner with the phone and recorder):
- Recovery: a 10+ minute recording, then pull the plug or turn Bluetooth off mid-transfer (13b
  should show "Stopped at N%", and it should restart from the beginning by itself); no internet
  for a while (S2, then uploads resume).
- Wi-Fi fast transfer: the recorder opens a hotspot the phone has to join. Android may ask the
  owner to approve each join, which would rule out automatic Wi-Fi transfers. Try it, then
  measure Bluetooth vs Wi-Fi speed and a day's battery cost (`docs/build/measurements.md`).
- Watch `ble.log` size over a full day.
- Firmware install (behind the gate and the owner's confirmation), when an update exists.
