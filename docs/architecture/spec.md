# Roudio — technical spec, draft 2 (10 Oct 2026)

The first pass at how Roudio works under the hood. The decisions behind it
are in `decisions.md`. Anything marked **[open]** is not decided yet; see
the last section.

## 1. Scope of version 1

One user, one Plaud Note Pro, one Android phone (Redmi Note Pro+ 5G; exact
model and Android version still to check), plus a web version for reading
and working with recordings on a computer (see 2, "Web version").

Build order: Android and the backend first. The web version comes after the
Android core works. The API is designed for both from the start.

Version 1 does everything in the brief's "What it must do" list:
- transfer from the recorder
- automatic transcription and notes
- offline queue
- conversations and events
- Today timeline and daily digest
- playback, transcript and notes
- search
- Ask a recording
- Review list
- Drive backup
- safe clean-up

Not in version 1: other users, iOS, on-phone transcription, live captions.

## 2. The parts

### On the phone (Kotlin + Jetpack Compose)

| Part | Job |
| --- | --- |
| `RecorderSource` | The only code that touches the Plaud SDK. Pair and bind, connection, battery, storage, charging, recording-state events, list files, export, delete. |
| Transfer service | An Android foreground service (connected-device type) that pulls new recordings off the recorder and resumes after interruptions. |
| Local store | Audio files in app storage. A Room (SQLite) database for recordings, steps, a cache of processed results, and the user's edits. |
| Upload worker | WorkManager jobs that upload audio to the backend when there's internet, and retry with back-off. |
| Sync | Pulls processed results (transcripts, notes, digest, Review items) down. Pushes the user's edits up. |
| Offline search | Full-text search over cached transcripts, notes and summaries (SQLite FTS), so search works without internet. |
| Player | Plays the local audio. The phone keeps every recording until the owner deletes it. If a file was deleted by hand, it streams from the backend or Drive instead. |
| UI | The Tide Clock screens in `../design/`. |

### Web version (after Android)

A website the owner signs in to from any computer. It shows the same
recordings as the phone: Today, digest, transcript, notes, search, Ask,
Review and backups.
- **It cannot talk to the recorder.** Transfer stays on the phone. Plaud's
  web SDK isn't released, and browser Bluetooth is too limited for this.
- **Same API:** it reads and writes through the same backend API as the
  phone, so both always agree.
- **Tech:** React + TypeScript single-page app built with Vite. It's served
  as static files from Railway, with TanStack Router and TanStack Query, and
  an API client generated from the backend's OpenAPI description
  (decision 15).
- **Screens:** the Paper page "07 Web" has the layouts. A left rail for
  navigation. Recordings open as a workspace: the transcript and the
  Notes/Ask panel side by side, with a docked player.

### Backend (ours, on Railway)

| Part | Job |
| --- | --- |
| API | Takes uploads and sync, serves results and audio to the phone and the web version, and gives the app its Plaud user token (holds the Plaud `client_secret`). |
| Object storage (Railway bucket) | Original audio. Kept so the web version can play it; see section 8. |
| Database (Postgres) | Recordings, segments, transcripts, notes, conversations, digest, Review items, and step history. Full-text search index. |
| Job queue + workers | Run the processing steps below. Each step is retryable and safe to run twice. |
| Drive backup | Uploads audio, transcripts and summaries to the owner's Google Drive and verifies them. |

### Outside services

| Service | Use |
| --- | --- |
| Plaud (via SDK) | Device binding and the user token. No transcription. |
| ElevenLabs Scribe v2 | Transcription with speaker labels, word timestamps and keyterms. |
| OpenAI GPT-6.1 Sol | Cleanup pass, grouping, notes, decisions and tasks, digest, Ask. |
| Google Drive | Backup. |

## 3. Life of a recording

A recording is one file from the recorder (up to about 5 hours). It moves
along **four independent tracks**. These match the separate "processing",
"backed up" and "clean-up" words in the designs. Every step is saved, so
nothing is lost if the app or phone restarts.

```
Transfer:     on recorder → transferring → on phone (checksum saved)
                → uploading → uploaded (backend checksum matches)

Processing:   waiting → finding speech → transcribing → tidying
                → understanding (notes, decisions, tasks, grouping) → done

Backup:       waiting → uploading to Drive → checking → backed up

Clean-up:     blocked → can clean up → deleting from recorder → cleared from recorder
              (the phone copy is never deleted automatically)
```

- Processing starts only once the recording is uploaded.
- Backup can run alongside processing. Transcripts and summaries are backed
  up again when they change.
- Every step can fail. A failure records what went wrong, what is still safe,
  and the retry action. Retries are automatic with back-off, plus a manual
  "Try again" in the UI.
- "Done processing" never implies "backed up". They are separate fields
  in the data, not just in the UI.

## 4. Transfer

- Bluetooth by default. Wi-Fi Fast Transfer is used when the SDK reports it's
  available. Speed is to be measured; it only affects tuning, not this design.
- **Export format: Opus.** This is decided; see decision 12. It's the
  device's own format, so there's no quality loss and it's the smallest.
  Keep it as the original everywhere. Scribe accepts Opus directly
  (confirmed by the owner), so no conversion is needed.
  - Never MP3: that re-encodes already-compressed audio and loses quality.
  - Never WAV for storage: it's about 8× bigger, with no quality gain.
- After export: compute a SHA-256 of the file, save it, and mark the
  recording "on phone".
- **Interrupted transfer:** the SDK docs show no way to resume a stopped
  transfer partway. `exportAudio` has no offset parameter, and Plaud
  recommends that apps build their own resume behaviour. So Roudio:
  - throws away the half-written file and restarts that file's export from
    the beginning once reconnected
  - never deletes from the recorder until all checks pass, so nothing is
    lost, only time
  - prefers Wi-Fi Fast Transfer for large files, so a restart costs less
  - this restart approach is decided. A question has gone to Plaud support
    (decision 14). If resume turns out to exist, it only makes restarts
    faster; nothing else in the design changes
- **Firmware updates wipe the recorder.** Roudio only offers a firmware
  update when every recording on the recorder has reached "uploaded". It
  says so plainly before starting.
- **Times:** each recording stores its start time in UTC plus the phone's
  time-zone offset at capture, so days, the dial and digests stay right
  across time zones and midnight.
- **24/7 recording:** recordings arrive as many files of up to about 5
  hours. The app treats them as one continuous day. Gaps between files show
  as gaps on the dial, never as "quiet".
- **Recorder state:** "is recording" comes only from SDK events. With no
  fresh event, the UI says "unknown", as the brief requires.

## 5. Processing

1. **Find speech.** FireRedVAD (decision 11) runs on the original audio and
   produces speech segments, each with its offset in the original file.
   Silence and sleep are never sent to Scribe.
   - It's tuned to miss as little speech as possible: low threshold,
     padding around each segment, short gaps merged.
   - Music is flagged, not dropped. TV and radio talk still counts as
     speech; filtering it is a possible later improvement.
   - Each day, a small random sample of the dropped audio is kept, so missed
     speech can be spot-checked.
   - **Tuning is built in from the start.** The first backend milestone
     includes a tuning tool: run the detector on a real day of audio, list
     what it kept and dropped with timestamps, and let the owner listen to
     dropped samples. Thresholds are settings, not code, so they can change
     without a release.
2. **Transcribe.** Speech segments go to Scribe v2, joined into
   sensibly-sized requests. Speaker labels are on, keyterms come from the
   personal word list, and the language is auto-detected. Word timestamps
   are mapped back to the original file's timeline.
3. **Tidy.** An AI pass fixes obvious mishearings using context and the word
   list. Every change is stored as a flagged edit next to the original
   words. Nothing is silently rewritten.
4. **Understand.**
   - Split the day into conversations and events, using silence gaps,
     speaker changes and topic.
   - Write each one's title, summary, decisions and suggested tasks.
   - Every generated statement stores the transcript segment(s) it came
     from. Those links become the tappable times in the UI.
5. **Daily digest.** It's rebuilt as each day's recordings finish
   ("updating"), and marked finished once the day is over and nothing from
   it is still waiting.
6. **Learning from edits.** When the user corrects a word or renames a
   speaker, the correction can be added to the personal word list and the
   speaker names for future recordings.

## 6. Data (main records)

| Record | Holds |
| --- | --- |
| Recording | Device file id, start (UTC + offset), length, checksums, the four track states, failure details, local/backend/Drive locations |
| Speech segment | Recording, start/end offset |
| Transcript segment | Recording, speaker, start/end, words with times, language, original text, edited text, flagged AI corrections |
| Speaker | Label from Scribe, the user's name for them |
| Conversation / event | Day, time span, the recordings and segments it covers, title, summary |
| Generated item | Type (decision, task suggestion, key moment), text, source segment(s) |
| Review item | Generated item, state (waiting / accepted / done / dismissed), the user's edited text |
| Note | The user's own notes on a recording |
| Day digest | Date, state (updating / finished), overview, moments, links |
| Word list entry | Term, where it came from (manual / learned from an edit) |
| Update | The notifications feed: type, recording, read/unread, action |

The backend is the source of truth for processed results. The phone keeps a
cache for offline use. User edits are saved on the phone first and synced
up. One user, so conflicts are resolved per field: the latest edit wins.

## 7. Search and Ask

- **Search:** full-text search over transcripts, notes and summaries. On the
  backend when online, on the phone's cache when offline. Results are grouped
  by recording and say where the match is (transcript / note / summary).
  Meaning-based search (embeddings) is a possible later addition.
- **Ask:** limited to one recording.
  - The AI model gets that recording's transcript segments and must cite
    segment ids for every claim.
  - The backend checks that each cited segment exists and belongs to that
    recording. Claims without a valid citation are removed.
  - If nothing valid remains, the answer is "not enough in this recording".

## 8. Backup and clean-up rules

- **Drive backup:** original audio, transcript (text + JSON with times), and
  notes/summary. One folder per day.
- **Verified means:** Drive's MD5 checksum for the uploaded file matches the
  local one, and the file can be read back.

**Delete from the recorder only when all of these are true:**
1. The phone copy's checksum matches.
2. The backend copy's checksum matches.
3. The Drive backup is verified.

**The phone never deletes recordings by itself.** The owner deletes them by
hand when they want to (decision 13). Deleting by hand still warns if the
recording isn't backed up or processed yet.

**The backend copy is kept**, so the web version can play audio. Opus is
small, so the storage cost is low. The owner can clear it by hand later.

- Clean-up is per recording and can be partial. If deleting from the
  recorder fails, the recording stays "on recorder" and is retried. Nothing
  else is undone.
- Before deletion is enabled for real, Settings lets the owner keep the
  safety checks but turn off actual deletion.

## 9. Security and privacy

- The Plaud `client_secret`, the ElevenLabs key and the AI key live only on
  the backend. The app only holds its own login and short-lived tokens.
- **Login:** "Sign in with Google" on both the phone and the web,
  limited to the owner's Google account (decision 10). The same Google
  connection also grants Drive access. Every other account is refused.
- Silence is never sent to outside services. Only speech segments go to
  Scribe and the AI model.
- No keys, tokens or real recordings in the repository, logs or example
  data.

## 10. Failures and recovery

| Failure | What's still safe | Recovery |
| --- | --- | --- |
| Recorder out of range / transfer stops | The original is still on the recorder | Retry when reconnected; restart the export |
| No internet | Audio on the phone | Queue; upload when online |
| Upload fails or checksum mismatch | Phone copy | Re-upload |
| Scribe / AI error | Audio on phone and backend | Retry the step with back-off; show "processing failed" plus a retry |
| Drive upload or check fails | Phone and backend copies; clean-up stays blocked | Retry; reconnect Drive if access was lost |
| Delete from recorder fails | Backed up; nothing lost | Retry; shown as partial clean-up |
| Firmware update pending | Blocked until everything is uploaded | Explain, then allow |

## 11. Open decisions

Settled since draft 1: hosting, AI model, background limits, web version,
login, speech detector, export format, retention, web tech, backend
language (Python). See
`decisions.md` 7–16.

1. **Speech-detector thresholds:** found with the tuning tool in the first
   backend milestone (section 5).
2. **Plaud's answer on resume:** nice to have, not blocking (decision 14).
