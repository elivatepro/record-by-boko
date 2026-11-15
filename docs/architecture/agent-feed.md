# Agent feed: Root reads Roudio and writes notes back

Status: **agreed with Root and built** (15 Nov 2026). The code is
`services/backend/src/roudio/api/agent.py` and `src/roudio/agent/`; "As built" at the end
says where the build pins down what this left open.

Root is the owner's Hermes agent on the VPS (`srv1931183`). She classifies each
conversation and keeps people, projects and tasks up to date in Notion, ClickUp
(project work) and TickTick (personal tasks). Roudio gives her a feed to read, and a
small set of fields she can write back to.

## Decisions

- **Pull, not push.** Root polls the public Railway API every 5 minutes. Nothing
  calls into the VPS, and her Hermes API stays loopback-only.
- **One unit: the conversation.** A conversation only shows up in the feed once its
  recording has finished processing (`processing_state = done`), so she never sees
  a half-transcribed one.
- **Summary first.** The feed carries the summary, items, speakers and note, with no
  transcript. She fetches the transcript only when she needs it.
- **Her writes live apart from Roudio's results.** They go into new `agent_*`
  tables, so reprocessing never wipes them. Speaker names are the exception: they
  use the existing rename, which already survives reprocessing.
- **Every write is logged** (`agent_action`): what changed, the old and new value,
  her confidence, and her reason.
- **Real names are allowed.** The owner said Root may use other people's real names.

## Reprocessing and stable IDs

Reprocessing a recording deletes its conversations and creates new ones with new
IDs (`pipeline/process.py`). To keep things tidy for Root:

- Each new conversation lists `replaces`: the old conversation IDs it overlaps in
  time.
- When the old ones go, the feed also emits `conversation.removed` for them.
- Her annotations, links and corrections move to the new conversation that overlaps
  the old one most, the same way Review decisions already carry over.
- **Item sync states carry over in three steps:**
  1. An exact match on the normalised text (trimmed, lower-case, punctuation
     dropped).
  2. If nothing matches, the same kind of item with text similarity ≥ 0.8 and
     `at_ms` within 2 minutes.
  3. If still nothing matches, the feed emits `item.sync_lost` with the old text,
     system and `external_id`, so Root can re-link the new item at once instead of
     the owner seeing a task that's already in ClickUp suggested as new.
- **Corrections are re-checked.** A correction that moved to a new conversation
  comes back with `annotation.recheck = true`, because the summary under it may
  have changed. Root confirms it by sending it again, or drops it.
- **Speakers get new IDs on every reprocess.** Roudio deletes and recreates them.
  Names carry over by diarisation label (`speaker_1`), but reprocessing transcribes
  the audio again, so a label isn't guaranteed to be the same person afterwards.
  Treat speaker IDs as belonging to one `results_version`. Each speaker in the feed
  has `name_source` (`owner`, `root` or `carried`). Re-check `carried` names after
  a `replaces`.
- **No in-place re-summarising.** `results_version` is per recording, and every
  bump is a full rewrite that makes new conversation IDs. Edits made while the ID
  stays the same (the owner renaming a conversation, notes, speaker names, Review
  states, transcript edits) come through as `conversation.updated`.

## Noise

Noise never becomes a conversation:

- The speech detector cuts silence and music before transcription.
- The summariser is told to skip background talk, TV and noise.

A recording with nothing worth noting has **no conversations**, so there's no
`kind` to force-fit. `voice_note` means one person speaking on purpose, such as a
memo. Root still gets `recording.empty`, with the recording ID and its speech
minutes, so she knows it was looked at.

## Access

- A new table `agent_key` holds the key's name, its SHA-256 hash, its scopes,
  `created_at`, `last_used_at` and `revoked_at`.
- Keys are made with a CLI: `uv run roudio-agent-key create root --scopes read,annotate`.
  It prints the key once, and only the hash is stored.
- Requests send `Authorization: Bearer rdo_agent_…`.
- An agent key works only on `/agent/v1/*`. App sessions don't work there, and agent
  keys don't work on the app's routes.
- Scopes:
  - `read`: the feed, transcripts and digests.
  - `annotate`: the write endpoints below.
- There is no audio and no access to delete, reprocess or change settings.
- The rate limit is 60 requests a minute.

## Endpoints

All of these live under `/agent/v1`. Times are ISO 8601 UTC, and `at_ms` and the
other millisecond fields count from the start of the recording.

### `GET /feed?cursor=<opaque>&limit=50` (read)

The cursor is opaque, a monotonic change sequence. Leave it out to start from the
beginning. `has_more` means to call again straight away.

```json
{
  "events": [
    {
      "seq": 1042,
      "type": "conversation.ready",
      "conversation": {
        "id": "uuid",
        "recording_id": "uuid",
        "results_version": 3,
        "replaces": ["old-uuid"],
        "day": "2026-11-15",
        "started_at": "2026-11-15T09:12:00Z",
        "ended_at": "2026-11-15T09:40:12Z",
        "kind": "conversation",
        "title": "Oryx sprint check-in",
        "summary": "…",
        "language": "en",
        "speakers": [{ "id": 77, "label": "speaker_1", "name": "Andy", "name_source": "owner" }],
        "items": [
          {
            "id": 901, "kind": "task", "text": "Send Andy the build",
            "at_ms": 812000, "review_state": "waiting", "sync": null
          }
        ],
        "note": null,
        "annotation": null,
        "annotation_version": 0
      }
    },
    { "seq": 1043, "type": "conversation.updated", "conversation": { "…": "same shape" } },
    { "seq": 1044, "type": "conversation.removed", "id": "old-uuid", "reason": "reprocessed" },
    { "seq": 1045, "type": "digest.ready", "day": "2026-11-14", "updated_at": "…" },
    { "seq": 1046, "type": "recording.empty", "recording_id": "uuid", "speech_ms": 41000 },
    { "seq": 1047, "type": "item.sync_lost", "conversation_id": "uuid", "old_text": "Send Andy the build", "system": "clickup", "external_id": "86c…" }
  ],
  "next_cursor": "opaque",
  "has_more": false
}
```

- `conversation.updated` fires when the owner renames a conversation, edits its
  note, a speaker name or a Review state, or changes a transcript segment.
- `removed` reasons:
  - `reprocessed`: `replaces` on the new conversation points back to it.
  - `deleted`: the owner deleted it. She then drops anything she copied from it.

### `GET /conversations/{id}` (read)

The same conversation object as in the feed, for re-reads.

### `GET /conversations/{id}/transcript` (read)

```json
{
  "conversation_id": "uuid",
  "segments": [
    {
      "idx": 12, "speaker": "speaker_1", "speaker_name": "Andy",
      "start_ms": 801000, "end_ms": 809500, "text": "…", "edited": false
    }
  ]
}
```

### `GET /days/{day}/digest` (read)

The day's digest as the app shows it. It returns 404 until the digest is ready.

### `PUT /conversations/{id}/annotation` (annotate)

This is Root's own layer, and it replaces whatever she set before.

- Send `If-Match: <annotation_version>` with the version from your last read.
- If it has changed since, you get a 409 `stale_annotation` with the current
  annotation, so re-merge and send it again.
- Every successful write adds 1 to `annotation_version`.
- The stored annotation also carries `updated_at`, `recheck` and the active
  corrections.

```json
{
  "category": "client_work",
  "tags": ["oryx", "sprint"],
  "links": [
    { "system": "notion", "kind": "project", "external_id": "…", "url": "https://…", "label": "Oryx App" },
    { "system": "clickup", "kind": "task", "external_id": "86c…", "url": "https://…", "label": "Send build" },
    { "system": "ticktick", "kind": "task", "external_id": "…", "url": null, "label": "Call mum" }
  ],
  "needs_review": [{ "what": "speaker_2 may be Sam or Samuel", "options": ["Sam", "Samuel"] }],
  "confidence": 0.86,
  "reason": "Andy named; matches Oryx client in Notion"
}
```

- `category` and `tags` are free text for now. If her set of categories settles, we
  can pin it down later.
- `system` is one of `notion`, `clickup`, `ticktick`.
- The app can show the links, plus a "check this" badge when `needs_review` isn't
  empty.

### `POST /conversations/{id}/corrections` (annotate)

An override of Roudio's own fields, for when she knows something the summariser
couldn't. Only `title` and `kind` can be changed.

```json
{ "field": "title", "value": "Oryx sprint check-in (existing client)", "confidence": 0.95, "reason": "Andy is an existing Oryx client, not a first meeting" }
```

- The correction is applied straight away, and the old value is kept in
  `agent_action`.
- It shows in the owner's Updates list with a one-tap undo.
- It moves to the new conversation when the recording is reprocessed.

### `PATCH /speakers/{id}` (annotate)

```json
{ "name": "Andy", "confidence": 0.9, "reason": "addressed by name at 13:20" }
```

- This sets the speaker's name for its whole recording, like the owner's rename.
- It refuses to overwrite a name the owner set themselves (409 `owner_set`).

### `PUT /items/{id}/sync` (annotate)

```json
{ "state": "synced", "system": "clickup", "external_id": "86c…", "url": "https://…" }
```

- `state` is one of `synced` or `dismissed`.
- `synced` also marks the Review item as accepted, so it stops asking the owner.
- ClickUp is for project tasks, TickTick for personal ones.

### `GET /actions?cursor=<opaque>&limit=100` (read)

Her own audit log. Each entry has the time, endpoint, target, old and new values,
confidence and reason. It pages with `next_cursor` and `has_more`, like the feed.

## What the owner sees

Root's writes show in the app's Updates list:

- "Root named speaker 2 Andy"
- "Root renamed … (undo)"
- links and `needs_review` items on the conversation

For a question she can't settle, she asks the owner directly on WhatsApp.

## Not in v1

- A push "something's ready" ping.
- Pinning `category` to a fixed list.
- Write access to summaries, items or transcript text.
- Root's own cost cap. She runs that on her side.

## As built (15 Nov 2026)

Details the build settled, without changing the contract above:

- **Errors** look like the app's: `{"detail": {"code": "…", "message": "…"}}`. Codes: `bad_key`
  (401), `missing_scope` (403), `not_found` / `not_ready` (404), `stale_annotation`,
  `owner_set` (409), `if_match_required` (428), `rate_limited` (429, with `Retry-After`),
  `bad_cursor` (400).
- **`If-Match` is required** on the annotation PUT: send `0` before her first one. Quoted
  values (`"3"`) work too.
- **Her own writes don't echo.** Annotations, corrections, speaker names and item sync don't
  produce `conversation.updated`; only the owner's changes (and the owner's undo) do.
- **Dropping a correction:** send the correction again with its `original_value`. Sending the
  same value again confirms it and clears its `recheck`. A new annotation PUT clears the
  annotation's own `recheck`. `annotation.recheck` is true while either needs a look.
- **The owner wins.** The owner renaming a conversation removes her title correction. Undo in
  Updates puts Roudio's value back. Both show in `/actions` with `actor: "owner"`.
- **`item.sync_lost`** also carries `old_item_id` and `state` (`dismissed` items can be lost
  too). Its `conversation_id` is the new conversation the old one moved to, or the old ID
  when nothing replaced it.
- **`results_version`** also goes up on the owner's edits (renames, transcript fixes). IDs
  only change when a recording is processed again, and that always comes with `replaces`.
- **`removed` with `deleted`** is in the contract, but Roudio has no way to delete a
  conversation yet, so only `reprocessed` happens today.
- **The digest read** answers `{day, updated_at, digest}`, where `digest` is the app's
  `DigestView`. "Ready" means `finished` or `quiet`.
- **Limits:** `/feed` takes `limit` up to 200 (default 50), and `/actions` up to 500
  (default 100).
- **Starting point:** the deploy put every conversation that was already written up, and
  every finished digest, into the feed, oldest first. A first pull without a cursor sees
  everything.
- **The owner's app** shows her links and questions on a conversation (`root` on
  `GET /conversations/{id}`), her notes in Updates (`agent_speaker`, `agent_correction`), and
  an Undo button on corrections (`POST /updates/{id}/undo`).
- **Keys:** `uv run roudio-agent-key create root --scopes read,annotate [--key-file PATH]`,
  `… list`, `… revoke root`. `--key-file` writes the key to a new mode-600 file instead of
  printing it.
- **Keys without database access:** the worker variable `AGENT_KEY_HASHES`
  (`name:scopes:sha256`, `;`-separated) adds those keys when the worker starts. It stores only
  the hash, and a key that's already there (even revoked) is left alone. Make the key and
  its hash somewhere private, and set only the hash on Railway.
