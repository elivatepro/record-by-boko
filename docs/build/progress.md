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

## Milestone 3: Processing pipeline (built 11 Oct 2026; waiting on the AI keys)

Done (`services/backend/src/roudio/pipeline`, phone and web screens):
- Worker job `process_recording`: download from the bucket → FireRedVAD (pinned weights in the
  Docker image, checked by SHA-256) → speech-only audio with a time map back to the original →
  Scribe v2 (speaker labels, keyterms from the word list, language detection) → tidy pass as
  flagged edits → conversations, titles, summaries, decisions, tasks and key moments, each tied
  to source segments. Claims without a valid source are dropped server-side. Every step logs its
  cost (`cost_entry`), never transcript text.
- Daily digest (`build_digest`): updating while recordings are processing, finished after the
  day ends, quiet and failed states. The hourly tick queues missing processing and backups.
- Ask a recording: answers cite segments, checked by the server; "not enough in this recording"
  when there isn't.
- `roudio-reprocess` writes everything up again; `POST /recordings/{id}/process` does one
  ("Try again now" on the phone's S8 and the web).
- Tuning tool: `uv run roudio-vad-report <files> [--threshold …] --out <dir>` lists kept and
  dropped stretches and cuts dropped clips to listen to. Thresholds are settings (`VAD_*`).
- Phone: sync down (every 15 min, on resume, after uploads) into Room with an FTS index;
  Today shows conversations and quiet on the dial; Recording screen (Notes, Transcript, Ask,
  player, key moments), S3 still writing up / S8 failed, Digest screen (09, 09c, 09d).
  A written-up recording with no conversation keeps its row ("No conversation") and plays in S3.
- Production runs with `PIPELINE_FAKES=1` (fake Scribe and OpenAI) until the keys exist, so the
  whole path can be checked end to end. One real recording went through it on 11 Oct 2026.

Still to do:
- Owner: add `ELEVENLABS_API_KEY` and `OPENAI_API_KEY` on Railway (api and worker), remove
  `PIPELINE_FAKES`, then run `roudio-reprocess`.
- Run the tuning tool on a real day and settle the thresholds before trusting what's dropped.
- "Done when": a real conversation written up within about 15 minutes of upload, every summary
  point jumping to the right moment, Ask refusing questions from outside the recording.

## Milestone 4: Drive backup and clean-up (built 11 Oct 2026)

Done:
- `backup_recording` job: per-day Drive folder with the audio, transcript (text and JSON) and
  notes, checked by MD5 and by reading back; uses the stored `drive.file` grant. A failure raises
  an Update with how to recover; retries hourly and on "Try again now".
- Clean-up gate on the phone (`transfer/CleanupGate.kt`): deletes from the recorder only the
  candidates the server lists (phone, server and Drive all match) and only when the local SHA-256
  matches too. Deleting is off by default (Settings switch). The phone copy is never deleted by
  the gate.
- Screens: Updates (03, 03b), Backups & clean-up (14), Recording More menu (06c: rename, share
  notes, share audio, write up again), delete from this phone with where every copy is (06d),
  only on the server with Download (06e).

Still to do:
- Owner: watch a week of recordings clear only after their Drive copy is checked, then decide
  whether to turn deleting on. Check that a Drive failure blocks clean-up and says what's safe.

## Milestone 5: Search, Review, edits, word list (built 11 Oct 2026)

Done:
- Search: Postgres full text on the server, SQLite FTS on the phone when offline (05, 05b, S4, S5).
- Review: one suggestion at a time, accepted tasks and done, all caught up (10, 10b, 10c, S6).
- Transcript line edits and speaker renames (latest edit wins); conversation rename.
- Word list (15b) with words learned from repeated fixes to confirm; sent to Scribe as keyterms.
  Languages (15c). Settings (15) with sign-out and the background-exemption warning.

Still to do:
- "Done when" needs real recordings: find a phrase from the last week in under two seconds,
  offline too; corrections show up in new recordings.

## Milestone 6: Web app (built 11 Oct 2026, on the shared API)

Done (`apps/web`):
- Routes and deep links: `/` (today), `/day/<date>`, `/day/<date>/digest`, `/c/<conversation>`
  with `?t=<seconds into the recording>&play=true&tab=ask|mine`, `/search?q=`, `/review?state=`,
  `/settings?section=`, `/settings/backups`. Left rail (icons only below 1024 px, a bottom bar
  below 640 px) with Review and unread Updates counts; recorder status is worded "seen by your
  phone" and only says what the phone has uploaded.
- W2 Today (dial, digest card, separate Processing / Drive backup lanes, past days, date input),
  W3/W3b workspace (TanStack Virtual transcript that follows playback, Ctrl F find, line and
  speaker edits, Notes / Ask / My notes, docked glass player; every chip seeks and plays),
  W4 Search with preview (↑ ↓, Enter), W5 Review (A / E / D, U, undo), W6 Digest (updating,
  quiet, failed), W7 Updates drawer, W8 Backups, W9 Settings (theme, glass/solid, word list,
  account, sign out), W10 narrow layouts. Light and dark from the tokens; solid fallbacks for
  glass (no backdrop-filter, reduced transparency, or the Settings switch).
- Vitest for the pure logic (`src/lib`: dial geometry, day spans, timeline rows, status words,
  snippet and find highlighting, playing segment): `pnpm --filter @roudio/web test`.

API gaps found while building (the web works around each one):
- No retry endpoints (backup, writing up, digest). Since added for writing up and backups
  ("Try again now"); the digest still rebuilds by itself.
- Nothing from the phone about the recorder (battery, recordings still on it, the phone's last
  sync). The rail can't show "Plaud linked · 64% · 2 still on recorder", and Today can't show
  "Waiting on your Plaud" rows.
- No processing progress (the designs' "Writing up 62%"); the web names the step instead.
- `ConversationSummary` has no speakers or decision count (Today's "Daniel, Priya, you · 2
  decisions"), no chapters (key moments stand in), and segments have no word timings (no
  current-word highlight).
- `ReviewView` has no item kind (only tasks reach Review today, so decisions never appear
  there); `RecordingSummary` has no size or `backed_up_at` (Backups shows counts, not "checked
  today"); updates can only be marked read all at once; there's no per-month day summary for a
  ring date picker (a native date input is used).
- Search has no date or type filters on the server; the web filters the results it gets.

Still to do: move the refresh token to an httpOnly cookie (needs the backend), and check the
screens against real recordings once processed data exists in production.
