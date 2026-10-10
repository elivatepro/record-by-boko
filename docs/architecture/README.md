# Roudio — architecture (October 2026)

How Roudio is built. Code layout and commands: `../../README.md`.

- `decisions.md`: what has been decided, when, and why.
- `spec.md`: the first technical spec. It covers the parts, the life of a
  recording, the data, the rules for deleting, and the open questions.

Related: the product brief (`../roudio-brief.md`) and the design
(`../design/README.md`). Hardware and design questions that only a real build
can answer are in `../design/implementation-checks.md`.

## At a glance

```
Plaud Note Pro ──Bluetooth / Wi-Fi──► Roudio (Android phone)
  (hardware only)   via Plaud SDK       Kotlin + Jetpack Compose
                                        local queue, cache, offline search
                                              │ upload original audio
                                              ▼
                                        Roudio backend (ours, on Railway) ◄── Web version (later)
                                        storage · job queue · database
                                        ├─ silence trimming
                                        ├─ ElevenLabs Scribe v2 (transcripts)
                                        ├─ GPT-6.1 Sol (tidying, notes, digest, Ask)
                                        └─ Google Drive backup + checks
```

The only part of Plaud we use is the device SDK. It handles pairing,
binding and transfer, and it needs a user token that our backend gets from
Plaud. Everything after the audio leaves the recorder is ours.
