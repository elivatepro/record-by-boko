# Architecture decisions

Newest last. Each entry says what was decided, why, and what it costs us.

## 1. Talk to the recorder through the official Plaud SDK. Use Plaud for hardware only.

*Decided 10 Oct 2026.*

- **What:** Roudio uses the Plaud Embedded device SDK (Android) to pair,
  bind, list, export and delete recordings. We don't use the Plaud app,
  Plaud's cloud transcription or summaries, or a Plaud subscription.
- **Why:** the recorder's storage is encrypted, and it only talks to the app
  it's bound to. The SDK is the supported way in. A fully custom Bluetooth
  protocol (as in OpenPlod) needs device keys that can't be obtained
  in-app, and it would break whenever the firmware changes.
- **Verified by the owner:** the developer account exists. Exported audio can
  go to a non-Plaud transcription service. Bind, list, export and delete
  work.
- **Costs and limits:**
  - The SDK is closed source.
  - A device binds to one app only, so the Note Pro is unbound from the
    Plaud app.
  - Our backend must exchange Plaud `client_id`/`client_secret` for a user
    token. The secret never ships in the app.
  - **Firmware updates wipe the recordings on the device.**
  - Each recording file tops out at about 5 hours.
- **Not needed:** importing any Plaud cloud history.
- **Escape hatch:** all SDK use sits behind one `RecorderSource` interface
  (see `spec.md`), so another transfer method could replace it later.

## 2. Native Android: Kotlin + Jetpack Compose

*Decided 10 Oct 2026.*

- **Why:** the hard part of Roudio is reliable all-day background work:
  Bluetooth, transfers, queued uploads and Android's battery limits. The
  Plaud SDK is a native Android library. Kotlin uses both directly, with no
  bridge.
- **Rejected:**
  - **Flutter.** Strong for the glass visuals, but the SDK and background
    work would still be Kotlin behind a bridge.
  - **React Native.** Has an official Plaud SDK, but it's the weakest at
    long-running background work.
  - **Kotlin Multiplatform.** Only useful if iOS is planned. It isn't.
- **Glass:** real background blur is available on Android 12 and later,
  with the solid fallbacks the design already specifies.

## 3. Process in the cloud, not on the phone

*Decided 10 Oct 2026.*

- **What:** the phone transfers, stores, queues and uploads. Our backend
  does the rest: silence trimming, transcription, AI, Drive backup and
  checks.
- **Why:** a full day of audio is too much battery and heat for the phone.
  The best transcription for the owner's speech is a cloud service anyway.
  And a backend has to exist for the Plaud token exchange.
- **Rejected for now:** on-phone transcription (Parakeet, Moonshine). They
  were trained mostly on Western English, have no Pidgin, and Parakeet
  doesn't label speakers. They could come back later as an optional offline
  extra.

## 4. Offline queue on the phone

*Decided 10 Oct 2026.*

Every recording moves through explicit, saved steps that survive restarts.
Each step can be retried and is shown in the UI. See "Life of a recording"
in `spec.md`.

## 5. Transcription: ElevenLabs Scribe v2

*Decided 10 Oct 2026.*

- **Why:**
  - It's the strongest general model on independent benchmarks.
  - Speaker labels and timestamps are built in.
  - It was second-best (after Intron Sahara) on Nigerian Pidgin in the
    AfriSwitch benchmark.
  - It's a simple public API.
- **Considered:**
  - **Intron Sahara.** Best on African and code-switched speech, but no
    public developer API, pricing or speaker labels found.
  - **Plaud's Transcription API.** Free tier, but no evidence on African
    speech.
  - **VibeVoice-ASR.** Self-hosted, but untested on African speech.
  - **Whisper large-v3.** Much worse on African-accented English, and it
    invents text in silence.
- **Price (Oct 2026):** $0.22 per audio hour, plus $0.05/h for keyterm
  prompting (the personal word list). Start on Creator ($22/month, about
  100 h). Move to Pro ($99, about 450 h) once real daily speaking hours are
  known. Check what happens past a plan's included hours before relying on
  it.
- **Accepted trade-off:** speech audio is sent to ElevenLabs.
- **Check:** before building on it, run about 10 minutes of the owner's
  real Note Pro audio through it and hand-check it: clear speech, fast or
  mumbled speech, Pidgin/mixing, noise. This confirms the choice; it isn't a
  new selection.

## 6. Trim silence before transcription

*Decided 10 Oct 2026.*

- **What:** the backend detects speech (voice activity detection) and sends
  only speech to Scribe. Every piece keeps its offset in the original file,
  so all times still point to the original audio.
- **Why:** it likely cuts the transcription bill by 60–80%. Less of the
  owner's life goes to a third party. And long silences are where models
  invent text.
- **Original audio is never cut.** Trimming only decides what gets
  transcribed.

## 7. Backend on Railway

*Decided 10 Oct 2026.*

- **What:** the backend runs on Railway. That means an API service, worker
  service(s) for the job queue, Postgres, and a storage bucket for original
  audio.
- **Why:** processing runs for a long time (transcribing hours of audio,
  Drive uploads and checks). Railway runs ordinary long-lived workers. Edge
  functions have short time limits.

## 8. AI model: OpenAI GPT-6.1 Sol

*Decided 10 Oct 2026.*

- **What:** `gpt-6.1-sol` through the OpenAI API. It does the tidying pass,
  grouping into conversations and events, notes, decisions and tasks, the
  daily digest, and Ask.
- **Why:** the owner's choice. It's close to OpenAI's flagship model on
  professional work, at about a fifth of the price.
- **Price (Oct 2026, as reported):** $2 per million input tokens, $10 per
  million output tokens. Reported context window: 1M tokens.
- **Accepted trade-off:** transcript text (not audio) is sent to OpenAI.
- **Keep it swappable:** prompts and output formats live behind one
  interface, so the model can change without touching the pipeline.

## 9. Phone exempted from background limits

*Decided 10 Oct 2026.*

- **What:** the owner will exempt Roudio from the phone's battery and
  background limits, so it can run without interruption.
- **On Xiaomi phones (MIUI/HyperOS)** this usually means:
  - turning on Autostart
  - setting Battery saver to "No restrictions"
  - allowing background activity
  - optionally, locking the app in Recents
- **For setup:** the setup flow walks through each setting with a plain
  reason. Settings later checks whether the exemptions are still in place,
  where Android lets it, because system updates can reset them.

## 10. A web version, and Google sign-in

*Decided 10 Oct 2026.*

- **What:** a web version for reading and working with recordings on a
  computer, built after the Android core. It can't talk to the recorder;
  transfer stays on the phone. Both the phone and the web sign in with
  Google, limited to the owner's account.
- **Why the backend is still needed** (it's what makes a web version
  possible at all):
  - The Plaud secret, the ElevenLabs key and the OpenAI key can't live in a
    phone app or a browser.
  - Long processing jobs need a server.
  - Phone and web need one shared place that holds the transcripts, notes
    and Review list. Without a backend, everything would live only on the
    phone, and the web version would have nothing to show.
- **Why Google:** the owner already connects Google for Drive, it means no
  passwords to manage, and limiting it to one account is simple.

## 11. Speech detector: FireRedVAD

*Decided 10 Oct 2026.*

- **Needs** (batch work on the backend, not live):
  - Never miss real speech.
  - Don't send hours of noise to Scribe.
  - Cope with music, TV and Nigerian/mixed-language speech.
  - Be cheap enough for 24 hours of audio a day on a CPU.
  - Speed and latency matter little.
- **Options considered:**
  - **WebRTC VAD:** misses about 64% of speech in the FireRedVAD benchmark.
  - **Silero VAD:** the popular default, but more false alarms.
  - **TEN VAD:** fewest misses, but about 15% false alarms. Built for live
    voice agents.
  - **pyannote segmentation:** heavier, and built for speaker turns, which
    Scribe already handles.
  - **NVIDIA MarbleNet:** needs the heavy NeMo toolkit, with no clear gain.
  - **Picovoice Cobra:** commercial, and built for live on-device use.
  - **"Foreground" VAD research** that ignores distant or TV speech: not
    production-ready.
- **Why FireRedVAD:**
  - Best overall score in its benchmark: F1 97.6 vs Silero 96.0 and TEN
    95.2.
  - Low false alarms (2.7%) with few misses (3.6%).
  - Covers 100+ languages.
  - Tiny (about 0.6M parameters), so it runs fine on a CPU.
  - Apache-2.0 licence.
  - Can also tell speech from singing and music.
- **Caveat:** that benchmark is the vendor's own and uses read speech. Check
  on a real day of the owner's audio. Fallback: Silero.

## 12. Export format: Opus

*Decided 10 Oct 2026.*

- **Why:** the recorder stores Opus, so exporting Opus keeps the original
  quality at the smallest size.
- **Rejected:**
  - **MP3:** re-encodes compressed audio, so quality drops.
  - **WAV:** about 8× bigger, with no quality gain.
- Scribe accepts Opus directly (confirmed by the owner), so no conversion is
  needed.

## 13. Keep recordings on the phone

*Decided 10 Oct 2026.*

- The phone never deletes recordings automatically. The owner deletes them
  by hand.
- The backend also keeps its copy, so the web version can play audio.
- The recorder is still cleared automatically, but only after every check
  passes (spec, section 8). Otherwise its 64 GB would eventually fill up.

## 14. Interrupted transfers restart from the beginning

*Decided 10 Oct 2026.*

- **What:** when a transfer stops partway, Roudio throws away the partial
  file and exports that recording again from the start. The recorder copy
  is only deleted once every check passes, so a restart costs time, never
  audio.
- **Why:** the SDK docs show no way to resume partway.
- **Question sent to Plaud developer support:**

  > We're using the Plaud Android SDK with a Note Pro to transfer long
  > recordings (up to the 5-hour limit). If the BLE connection drops
  > partway through `exportAudio` (or a Wi-Fi Fast Transfer export), can
  > the transfer resume from where it stopped? Is there an offset or
  > resume parameter, or does the SDK keep partial progress? If resume
  > isn't supported, can a session that's still being recorded be
  > exported in parts? And is the export callback's progress safe to use
  > to detect a stalled transfer?

- **If resume exists:** it only makes restarts faster. Nothing else in the
  design changes.

## 15. Web tech: React + TypeScript single-page app (Vite)

*Decided 10 Oct 2026.*

- **What:** a client-only React + TypeScript app built with Vite, served as
  static files from Railway.
  - TanStack Router for screens and deep links (a recording at a time).
  - TanStack Query for data, caching and retries.
  - TanStack Virtual for long transcripts.
  - A typed API client generated from the backend's OpenAPI description.
- **Why it fits Roudio, not just because it's popular:**
  - **Paper exports React.** The designs live in Paper, which turns
    artboards into JSX. That's the shortest path from design to code.
  - **The design system's effects library** (`@paper-design/shaders-react`)
    is React-only.
  - **No server rendering needed.** The site sits behind a single-owner
    login, with nothing for search engines and no SEO. Next.js or
    SvelteKit would add a second server next to our Railway backend for no
    gain.
  - **The browser does real glass well.** CSS `backdrop-filter` gives the
    frosted look natively, with the solid fallbacks from the design.
  - **The jobs this app does are well served by React's tools:** synced
    playback, virtualised long transcripts, and streamed Ask answers.
- **Rejected:**
  - **SvelteKit:** a lovely developer experience, but it loses the Paper
    JSX path and the shaders library, and adds server rendering we don't
    need.
  - **Next.js:** a server framework we'd have to run and secure as well.
  - **Flutter web:** draws the page onto a canvas, so text selection, find
    in page and accessibility suffer. Bad for a reading app.
  - **Compose Multiplatform (web):** could share UI with Android, but it's
    less mature on the web, and the phone and desktop layouts differ
    anyway.

## 16. Backend in Python (FastAPI + a Postgres-backed job queue)

*Decided 10 Oct 2026 as the default for the build. Change it before
milestone 0 if you prefer otherwise.*

- **What:** one Python codebase with two Railway services:
  - `api` (FastAPI)
  - `worker` (the processing jobs)

  Jobs live in Postgres, so there's no extra queue service to run. The API
  publishes an OpenAPI description, and clients are generated from it for
  web (TypeScript) and Android (Kotlin).
- **Why Python:**
  - The heavy backend work is audio: FireRedVAD (a PyTorch model), ffmpeg,
    and splitting and re-timing speech.
  - Python is where those tools live, and the ElevenLabs, OpenAI and Google
    Drive SDKs are first-class there.
  - FastAPI generates the OpenAPI description that both clients are built
    from.
- **Rejected:**
  - **TypeScript (Node):** would share types with the web app, but the
    speech detector would need a second runtime.
  - **Kotlin (Ktor):** would share models with Android, but has the same
    audio-tooling gap.
