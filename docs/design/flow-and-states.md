# Flow and states — Tide Clock

## Navigation

```
First run: Welcome → Sign in (Google) → Permissions → Keep running
           → Unlink from Plaud app → Finding → Found → Linking → Linked → Today
  Finding ─(30 s, nothing)→ Not found ─► Look again | Unlink help
  Any step ─► Bluetooth off | Permission refused (→ Android settings)
  Found (linked elsewhere) / Linking ─► Linked to another app ─► Try again
  Linking ─(drop / no internet)→ Linking failed ─► Try again

Tabs (floating clear-glass pill): Today · Search · Review · Settings

Today
 ├─ Bell ──────────────► Updates (sheet) ─► Recording | Backups & clean-up
 ├─ Calendar button ───► Date picker (sheet) ─► chosen day | Today
 ├─ ‹ previous day ────► that day (shows a "Today" return)
 ├─ Recorder chip ─────► Recorder & transfers (connected | disconnected)
 ├─ Digest card ───────► Daily digest ─► Recording (at a moment) | Review
 └─ Timeline row ──────► Recording: Notes ⇄ Transcript ⇄ Ask

Search result / match time ─► Recording (Transcript at that time)
Ask / Notes source chip ────► Transcript at that time, playing
Review source time ─────────► Transcript at that time
Notes follow-up / Open Review ► Review (Waiting)
Settings ─► Recorder & transfers · Backups & clean-up · theme · services · accounts
```

## Back behaviour (Android)

- A sheet (Updates, Date picker) closes back to the screen underneath.
- Recording tabs don't add history. Back leaves the recording and returns to
  whatever opened it.
- On a tab other than Today, Back goes to Today. On Today, Back exits.
- A past day isn't a new screen; Back (or "Today") returns to today.
- With the keyboard open, Back closes the keyboard first.

## State coverage so far

| Screen | Normal | Empty | In progress | Offline | Error / recovery |
| --- | --- | --- | --- | --- | --- |
| Today | 01 | — | 01 (writing up, still on Plaud) | — | — |
| Updates | 03 | — | — | — | 03 backup failed + retry, partial clean-up |
| Date picker | 04 | 04 empty day | — | — | 04 future days disabled |
| Search | 05, 05b | — | — | — | — |
| Recording | 06, 06b | — | — | — | 06 backup failed + retry |
| Transcript | 07 | — | — | — | — |
| Ask | 08 | — | — | — | 08 not-enough answer |
| Digest | 09 updating | — | 09 | — | — |
| Review | 10 | — | — | — | — |

Missing states are listed in `screen-inventory.md` under "Still to design".

## Added 10 Oct (light only so far)

| Screen | States |
| --- | --- |
| Setup (11a–11i) | Every step, plus permission allowed / to allow / optional and a background setting still to do |
| Connection errors (12a–12e) | Not found, Bluetooth off, permission refused, linked to another app, linking failed |
| Recorder (13a–13b) | Connected and transferring; disconnected with a stopped transfer and recording “Unknown” |
| Backups (14) | Failed backup + retry; clean-up gate cleared / blocked / waiting |
| Settings (15) | Background setting switched off again |
| States page (S1–S8) | Today disconnected and offline; transcript writing up; search no results and offline; Review all done; Ask offline; writing up failed |
| Web (W1–W5) | Sign in + not-the-owner error; Today; recording workspace; search with preview; Review |
