# Design decisions — Tide Clock (chosen 10 Oct 2026)

Direction C, "Tide Clock", was chosen from the three brand explorations
(see `brand-directions.md`). Directions A and B, and the earlier
"tidal / sea-glass" v1 screens, were removed from the Paper file.

## Idea

The day is a 24-hour dial. Roudio records around the clock, so the main
picture of a day is a ring: when you were talking, when it was quiet or you
were asleep, and what is still sitting on the Plaud. Everything else
(digest, timeline, search, review) hangs off that ring.

## Colour

One deep navy, white, and a single buoy-yellow signal. Tokens live in the
Paper file (`--color-*`).

| Role | Light | Dark |
| --- | --- | --- |
| Ground | Paper `#FFFFFF` | Night `#07111D` |
| Raised surface (chips, round buttons, segmented controls) | Fog `#F1F4F8` | `#111F33` |
| Hero card (digest, Notes player) | Navy `#0E2240` | `#16294A` |
| Ink / muted text | `#0E2240` / `#56627A` | `#E8EEF7` / `#9AA8BE` |
| Dividers | `#E3E7EE` | `#1B2A3F` |
| Signal | Buoy `#FFB800` | `#FFC433` |
| Signal as text | `#8A5A00` (≈5.9:1 on white) | buoy itself |
| Failure | `#B3261E` | `#FF8A80` |

**Buoy yellow means only one thing: "now / live / not yet safe".** It marks
the current time on the dial, recordings still on the Plaud, unread updates,
the segment that is playing, and the playhead. It is never decoration, and
it's never used for "selected" (selected is solid navy). Text is never set in
buoy on white; `#8A5A00` is used for words.

Key moments (Digest) use their own mark: a small outlined diamond, on the
dial and in the Moments list. They never borrow the buoy colour.

"Quiet · asleep" is navy at 28% opacity on the dial. The same tone marks
night-time elsewhere (e.g. the "Last night" search suggestion).

### Dark mode rules

- Ground Night `#07111D`; sheets `#0B1726` with a `#22344F` top rim; raised
  `#111F33`; nested cards and the Notes player `#16294A`.
- Primary fills flip: selected tabs, primary buttons, question bubbles and
  checks become light `#E8EEF7` with Night text/icons.
- Buoy `#FFC433`. On a light selected pill, counts use `#8A5A00` instead.
- Tints: playing segment and "not enough" answer `#2A2410` (border
  `#5C4A14`); failure icon tile `#3A1A1F` with `#FF8A80` icon.
- Search and current-word highlights are solid buoy with Night text.
- Future/disabled numbers `#4E5D74` (non-essential, below 4.5:1 on
  purpose); disabled icons are dimmed the same way.

## Type

Lexend throughout (Android status bar and keyboard stay Roboto). Display
34/40 (dial centre), screen titles 26/32, section 20/26, transcript 17/28,
body 15–16, small 13–14, labels 12/16 minimum. Times use tabular figures.

## Signature: the ring

- **Today:** a 300dp dial with recording arcs, a "now" marker, and a
  legend underneath (Conversation / Quiet · asleep / Still on Plaud), so
  the arcs never rely on colour alone.
- **Date picker:** every past day in the month is a small ring filled by
  how much was recorded. Selected = solid navy disc with a white arc;
  today = navy outline; nothing recorded = empty track; future = faded
  number with no ring.
- **Digest:** a smaller dial next to the day's counts.
- **Selected-day preview:** a 64dp mini dial of that day.

## Shapes and surfaces

- Round 48dp fog buttons for header actions; fully rounded pills for
  buttons, chips and segmented controls (selected segment = solid navy).
- Rows sit directly on the ground with hairline dividers. Cards are used
  only for the digest, the Notes player, the selected-day preview, and
  answers that need a boundary.
- Sheets (Updates, Date picker) are **solid** white with a 28dp top
  radius over a dark, slightly blurred scrim. They hold reading content.

## Glass

Clear glass is reserved for chrome that floats over scrolling content:
- the bottom nav pill (tab screens),
- the docked player (Transcript),
- the Ask composer and its suggestion chips.

Light: white 62%, blur 24, saturate 170%, 1px white rim, inset top
highlight, soft shadow. Dark: `#111F33` at 66% with a 12% white rim.
Reduced-transparency fallback: the same shapes in opaque Fog / `#111F33`.

## Status honesty

- Processing and backup are always stated separately ("Written up · not
  backed up"). "Ready" never implies "backed up".
- Failures say what is still safe (shield + sentence) and offer the
  recovery action right there ("Retry backup").
- Recordings still on the Plaud are described as "still on the recorder";
  we don't promise they're safe until they've transferred.
- Ask states its scope first, cites every answer with tappable times, and
  shows a visibly different "Not enough in this recording" reply. Since 11 Oct
  2026 it can also look things up: a separate "Beyond this recording · from the
  web" card with its links. The question appears at once with "Thinking…".

## Navigation

- Floating clear-glass pill, four destinations: Today, Search, Review,
  Settings. The selected tab expands to a navy pill with a label; the
  others are icon-only with accessible names.
- Recording screens have no nav. Notes opens with a large title, status
  chips and an in-flow player card. On Transcript and Ask the header
  collapses to a compact bar, and the player docks (Transcript) or the
  composer docks (Ask) at the bottom.

## Identity

Working mark: a navy ring with a buoy dot "on the clock", plus lowercase
Lexend SemiBold "roudio". **Not a final logo.** Since 11 Oct 2026 it's also the
Android launcher icon (light ring and buoy dot on navy, with a monochrome layer
for themed icons) and the web favicon, so the app no longer shows Android's
default icon. Swap both when a final logo is chosen.

## Assumptions

- Monday-first weeks, 24-hour times (locale-dependent in the real app).
- 412 × 915 dp phone frame; the real Redmi's size is unverified.
- Source chips show the position inside the recording (m:ss).

## Web (October 2026)

- A left rail (240 px) replaces the floating glass pill. Selected item = solid
  navy pill, same rule as the phone. Below 1024 px the rail becomes icons only
  (80 px). See W10.
- Recordings open as a workspace: transcript (reading column, max 760 px) on
  the left, a 440 px side panel with Notes / Ask / My notes, and a glass
  player docked over the transcript.
- Lists that compare status use fixed column lanes (Processing, Drive backup;
  Phone / Server / Drive / Recorder).
- Keyboard: `/` search, `↑ ↓` move, `Enter` open, `A / E / D` in Review,
  `Ctrl F` find in transcript.
- The web can't see the recorder. Its status is always worded "seen by your
  phone" with the phone's last sync time.

## Dark mode mapping used for new screens

| Light | Dark |
| --- | --- |
| `#FFFFFF` ground | `#07111D` |
| `#F1F4F8` raised | `#111F33` |
| `#0E2240` hero card | `#16294A` (tiles inside it `#1F355C`) |
| `#0E2240` selected / primary fill | `#E8EEF7`, with its text and icons `#07111D` |
| `#0E2240` text and icons | `#E8EEF7` |
| `#56627A` muted | `#9AA8BE` |
| `#E3E7EE` hairline / tracks | `#1B2A3F` |
| `#FFB800` buoy, `#8A5A00` buoy text | `#FFC433` |
| `#B3261E` failure | `#FF8A80` (tiles `#3A1A1F`) |
| `#FFF6DB` / `#F2D27A` warning tint | `#2A2410` / `#5C4A14` |
| Quiet arc (navy 28%) | `#E8EEF7` at 30% |
