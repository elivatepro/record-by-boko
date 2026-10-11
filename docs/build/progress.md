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

Still to do before milestone 1 is done:
- 11f–11i (finding, found, linking, linked) and error screens 12a–12e, each triggered by the
  real condition (30 s with nothing found, Bluetooth off, permission refused for good, linked
  elsewhere / handshake refused, linking failed or no internet).
- Recorder screen 13a/13b (transfer rows wait for milestone 2) and the recorder chip on Today
  (a Today shell; the dial and timeline are milestone 2). Then delete the check screen.
- "Done when" pass on the phone: fresh install links; live battery and recording state;
  Bluetooth off and walking away show the right screens.
- Owner: turn on Autostart and battery "No restrictions" for Roudio (11d shows both off).

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
