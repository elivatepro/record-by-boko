# Screen inventory — Tide Clock

All phone artboards are 412 × 915 dp. Every artboard is labelled "sample
data". Exports: `exports/tide-clock/`.

## Done

| Page | # | Artboard | Light | Dark | Covers |
| --- | --- | --- | --- | --- | --- |
| 00 Foundations | F1 | Brand sheet | ✓ | — | Idea, palette, mark |
| 01 | 01 | Today | ✓ | ✓ | 24h dial + legend, now marker, recorder chip, digest card (updating), timeline rows with separate processing/backup words, unread bell, glass nav |
| 01 | 03 | Updates (sheet) | ✓ | ✓ | Everything / Needs you filter, unread vs read, mark all read, backup failed + what's safe + retry, partial clean-up, still on Plaud, written up ≠ backed up |
| 01 | 04 | Date picker (sheet) | ✓ | ✓ | Month header + jump-to-month affordance, prev / next (next off), Mon-first, per-day recording rings, selected vs today, empty and future days, key, selected-day preview, Today shortcut |
| 01 | 05 | Search | ✓ | ✓ | Empty field, time/type filters, recent, people, "Try" suggestions |
| 01 | 05b | Search results | ✓ | ✓ | Grouped by recording, Summary/Transcript/Note labels, highlights, play-from-time |
| 02 | 06 | Recording · Notes | ✓ | ✓ | Title, separate written-up / backup-failed chips, player card, tabs, summary with sources, decisions |
| 02 | 07 | Transcript | ✓ | ✓ | Compact header, find + edit, chapters, speakers, playing segment + current word, docked glass player (speed, ±10/30, follow-along) |
| 02 | 06b | Notes (scrolled) | ✓ | | Compact header + docked player, last decision, follow-ups: dashed suggestions "waiting in Review" vs solid accepted task, Open Review, editable My notes (only you, last edited, add a note) |
| 02 | 08 | Ask | ✓ | ✓ | Scope notice, grounded answer with citations, "not enough in this recording" answer, suggestions, glass composer |
| 03 | 09 | Daily digest | ✓ | ✓ | Small dial with moment diamonds + counts, overview, moments with sources |
| 03 | 10 | Review | ✓ | ✓ | Waiting / Accepted / Done, one suggestion at a time, Accept / Edit / Dismiss, source time |

| 04 | 11a–11i | Setup flow | ✓ | — | Welcome; sign in with Google (also Drive); permissions with reasons (allowed / to allow / optional); keep running (Xiaomi battery and autostart checklist, with a “continue without it” warning); unlink from the Plaud app; finding; found list (yours vs linked elsewhere); linking progress; linked (battery, storage, recordings, recording now) |
| 04 | 12a–12e | Connection errors | ✓ | — | Recorder not found (checklist); Bluetooth off; Nearby devices permission refused; linked to another app; linking failed. Each says what is safe and how to recover |
| 04 | 13a | Recorder & transfers | ✓ | — | Connected card (battery, space, recording now), transfer with progress, still recording, done today, Wi-Fi fast transfer, recorder update held back until safe, unlink |
| 04 | 13b | Recorder disconnected | ✓ | — | Last seen values, recording “Unknown”, stopped transfer with what’s safe and restart-from-beginning, reconnect |
| 04 | 14 | Backups & clean-up | ✓ | — | Drive status and counts, failed backup with what’s safe and retry, recorder clean-up gate per recording (phone / server / Drive) |
| 04 | 15 | Settings | ✓ | — | Battery-saver warning, theme, glass and motion, languages, word list, services, recorder, backups, phone storage, account |
| 05 | S1–S8 | States | ✓ | — | Today recorder not connected; Today no internet; transcript still writing up; search no results; search offline; Review all caught up; Ask couldn’t answer (offline); recording writing up failed |
| 07 Web | W1–W5 | Web (1440 × 900) | ✓ | — | Sign in (with not-the-owner error); Today (rail, dial, digest, timeline with separate processing and backup columns); recording workspace (transcript + Notes/Ask panel + docked glass player); search with preview; Review list + detail with keyboard shortcuts |

| 01 | 01b | Today · past day | ✓ | — | Yesterday's dial (no "now"), finished digest, a recording whose write-up failed, Back to today |
| 01 | 03b | Updates · nothing needs you | ✓ | — | Needs-you filter empty, all read |
| 02 | 06c | Recording · More menu | ✓ | — | Rename, share transcript and notes, share audio, write up again, delete from this phone |
| 02 | 06d | Recording · delete from phone | ✓ | — | Where the copies are (phone / server / Drive), warning when the Drive backup failed, retry first or delete anyway |
| 02 | 06e | Recording · only on Drive | ✓ | — | You deleted the audio from this phone, it streams from the server, download again |
| 02 | 07b | Transcript · editing | ✓ | — | Edit bar (undo, done), one line in edit mode, speaker chip, "original kept", suggestion to add a word to the word list |
| 02 | 07c | Transcript · rename speaker | ✓ | — | Name field, people named before, rename all lines |
| 03 | 09b | Daily digest · scrolled | ✓ | — | Decisions with sources, follow-ups (suggested vs accepted), not in this digest yet |
| 03 | 09c | Daily digest · quiet day | ✓ | — | No conversations found, open the day's recordings |
| 03 | 09d | Daily digest · failed | ✓ | — | What is fine, automatic retry, try again now |
| 03 | 10b | Review · Accepted | ✓ | — | Your tasks, ticking done, undo |
| 03 | 10c | Review · edit suggestion | ✓ | — | Sheet over the keyboard: text, due date, save and accept |
| 04 | 15b | Settings · word list | ✓ | — | Add, learned words to confirm, the list, cost note |
| 04 | 15c | Settings · languages | ✓ | — | Mostly (one), also spoken (many), accuracy note |
| 07 Web | W3b, W6–W10 | More web | ✓ | — | Ask panel; daily digest; Updates drawer; Backups table (phone / server / Drive / recorder); Settings with section list; narrow window (834) with icon rail |

## Dark mode

Dark boards exist for the original screens (pages 01–03) and as references
for the new work: **W2 Today · Dark** (web) and **13a Recorder · Dark**
(phone). Every other new screen gets its dark version from the token mapping
in `design-decisions.md` ("Dark mode rules"). That mapping is mechanical, so
it's done in code through the theme, not drawn board by board. Check each
screen in dark during the build.

## Still to design (expected to be found during the build)

- Notification settings detail, and the Android system notification for
  transfers.
- Small leftovers that come up while building (empty search history, long
  names, very long recordings).
- Large-font (200%) and 3-button-navigation checks on the real phone.
