# Roudio — design brief (plain-language version)

This is the original Roudio design request rewritten without jargon. Nothing
has been dropped; only the wording is simpler.

## What we are doing now

- Design the screens for **Roudio**, my personal Android app for recording
  audio and making sense of it. Start fresh — ignore the earlier project and
  its layouts.
- Draw the designs in **Paper** (the design app). Do **not** start building
  the real app yet.

## What Roudio is

- The name means **Root + Audio**.
- I own a **Plaud Note Pro** voice recorder. I want my own app that pulls
  recordings off it over Bluetooth and Wi-Fi, the way Plaud's own app does.
- It is just for me for now. I may share the code publicly later.
- My phone is a **Redmi Note Pro+ 5G** (reportedly 12 GB memory, 500 GB
  storage). The exact model and Android version still need checking.
- I want to record **around the clock**, including while I sleep. This is a
  goal. Nobody has yet confirmed that all-day recording, transferring from the
  recorder, charging behaviour, or Plaud's developer tools actually allow it.
- It should do the useful things Plaud's app does, plus my own extras.

## What it must do

- Pair with the recorder and pull recordings off it.
- Send recordings off to be turned into text and summaries automatically.
- Hold work in a queue when there is no internet, and carry on later.
- English first, plus whatever languages the chosen services support.
- Text accuracy as good as ChatGPT's dictation feels to me. This is a target,
  not a promise.
- Pick the speech-to-text and the "thinking" services by testing them. None is
  chosen yet.
- Don't worry about cost for now.
- Group recordings into understandable conversations and events.
- Keep the original times and links back to the original recordings.
- The home screen is **today's timeline**.
- A **daily summary** of important moments, decisions and suggested tasks.
- Playback, a written record that shows who said what, editable notes, and
  times that link back to the audio.
- Search across recordings, written records and notes.
- Ask questions about one recording, with answers that point to the exact
  moments they came from.
- A **review list** for suggested tasks and decisions.
- Back up original audio, written records and summaries to **Google Drive**.
- Only delete finished audio from the phone and the recorder **after** the
  backup has been checked and the app can still reach what it needs.
- Whether the recorder can be told to delete files (and other Plaud developer
  features) is still unconfirmed.
- Always make it clear where each recording is: transferring, being
  processed, backed up, or cleaned up.
- Give ways to recover when a transfer stops, processing fails, an upload
  fails, or a clean-up fails.

## How it should look and feel

- A high-quality, modern, simple, clever, easy, accessible Android app.
- Use the **Lexend** font for everything in the app. Leave the phone's own
  top and bottom bars in the phone's normal font.
- Design a **light** and a **dark** version on purpose, not as an
  afterthought.
- Use a **frosted-glass look**, especially for the navigation bar and for
  panels that float above other content. The glass must look real: you
  should see colour through it, with a controlled tint, light catching the
  edges, soft shadows, and clear separation from what's behind it. Not just
  see-through rounded boxes on a plain background.
- Keep anything you read — written records, labels, icons, buttons — crisp
  and undistorted.
- Pay close attention to: layout and what stands out first; text size,
  weight, line spacing and wrapping; spacing and alignment; icons that match
  each other; surfaces and depth; how things look when selected, unread,
  turned off, loading, or broken; big-enough tap areas and room for the
  phone's own bars; content never hidden behind the floating navigation; and
  a clear job for every screen.
- Don't make every screen the same title-then-cards layout. Each screen should
  be shaped around its job but still feel like one family.
- No mascot for now.
- The old plain sound-wave logo was rejected. Make a distinctive Roudio look,
  or a carefully made name-only logo for now. Don't present an unapproved logo
  as final.

## Visual effects

- Paper offers an effects library (`@paper-design/shaders-react`), with
  MeshGradient, StaticMeshGradient and FlutedGlass as possible picks.
- Use effects sparingly and only where they help — for example the daily
  summary card or a decorative glass area. Reading screens stay calm.
- That library is for web pages. Don't assume it works in an Android app. The
  technology for the real app hasn't been chosen, so keep look-and-feel
  experiments separate from that decision.
- FlutedGlass distorts a picture you give it. It does not bend whatever is
  live behind it.
- Be clear about which effects are really running and which are just drawn to
  look like them.
- Provide plain, solid versions for people who turn off motion or
  see-through effects, for phones that can't draw them, and for when speed
  matters.

## Screens to design (with their main situations)

Make them connect into one flow, not a set of unrelated pictures.

1. **Today (home)** — clear day and date; an obvious date button with a
   well-made calendar icon; go back to earlier days and jump back to today;
   recorder connection and battery; a prominent way into the daily summary;
   recordings grouped into conversations and events with times; title,
   length and useful details; separate "processing" and "backed up" status;
   notifications button with an unread mark or count; clear way to Search,
   Review and Settings. Also: an empty day, recorder disconnected, waiting
   for internet, and still processing. Never suggest recording is working
   when we don't know the recorder's state.
2. **Main navigation bar** — frosted glass that suits Android; clear icons and
   names; the current section is unmistakable; same-sized tap areas and
   aligned icons; respects the phone's gesture bar; enough space at the
   bottom of every list so nothing hides behind it.
3. **Notifications** — a layered panel or its own screen; read and unread;
   updates about transfers, writing up, backups, clean-ups and daily
   summaries; buttons that go straight to the recording or the fix-it screen;
   an "needs attention" filter if useful; mark as read; an empty state.
   Failed backup and clean-up messages must say what is still safe and how to
   fix it. "Finished processing" must not suggest "backed up".
4. **Date picker** — clear month and year; previous/next month; weekdays line
   up correctly; selected day and today look different; days with recordings
   are marked, with a key explaining the marks; days with nothing and future
   days are handled; a "Today" shortcut; a preview of the chosen day and a
   clear way to open it; a faster way to jump through lots of history; big
   date buttons.
5. **Search** — a useful starting screen; search box with a clear button;
   recent searches or suggestions; filters by time and by type; results
   grouped by recording; show whether a match is in the written record, a
   note or a summary; helpful snippets, dates and times; play or open the
   source; no results, loading, offline and error versions; a version with
   the keyboard open; results never hidden by the navigation bar.
6. **Recording details / notes** — title and details; play/pause; a position
   slider with time played and total; jump back/forward and speed; clear
   tabs for Notes, Transcript and Ask; a short summary; decisions and
   suggested tasks; times that show where every generated statement came
   from; edit and review buttons; separate processing and Drive backup
   status; waiting, failed and "audio not available" versions.
7. **Transcript (written record)** — easy to read at length; speaker names
   and times; playback follows along; search inside it; edit text and speaker
   names; language shown; long recordings and mixed languages; processing,
   partly done and failed versions; no showy effects.
8. **Ask a recording** — make it clear answers only come from this one
   recording; suggested questions; question-and-answer layout; tappable
   times showing the source; a comfortable typing box, including with the
   keyboard open; loading and error versions; an honest "not enough in the
   recording to answer" reply. Never present made-up answers as fact from the
   recording.
9. **Daily summary** — the day and whether it's finished or still updating;
   key moments and a short overview; decisions and follow-ups; links to the
   recordings and exact times; add suggested tasks to Review; recordings
   still waiting and processing not finished; empty day and error versions.
10. **Review list** — suggested tasks and decisions waiting for me; which
    recording they came from; accept, edit, dismiss and mark done; obvious
    difference between a suggestion and a task I've accepted; empty and
    all-done versions.
11. **First-time setup and pairing** — explain what the app is for; find and
    pair the recorder; ask for Android permissions with plain reasons; set up
    Bluetooth and Wi-Fi transfer only as far as is confirmed to work;
    success, recorder not found, permission refused and pairing failed. Don't
    promise hardware features that aren't confirmed.
12. **Recorder and transfer status** — connection, battery and storage where
    available; recording and transfer status; the transfer queue and
    progress; stopped transfers and how to recover; offline and disconnected;
    explain limits without technical detail.
13. **Backups (archive)** — Google Drive connection; backup status for audio,
    written records and summaries; checking backups; waiting and failed
    uploads; getting archived recordings back; clean-up status on the phone
    and recorder; deleting is blocked until the backup is checked and
    processing is done; partly finished clean-up and retry.
14. **Settings** — follow the phone's light/dark setting or override it;
    default language and other supported languages; choose services/models
    where it makes sense; recording and transfer preferences; Drive backup and
    how long to keep files; notification choices; accessibility and motion
    choices; clear connection and account controls. Never show real keys or
    private settings in example content.

## Accessibility and Android behaviour

- Tap areas at least 48 × 48.
- Normal text contrast at least 4.5:1; large text at least 3:1.
- Never rely on colour alone to say something.
- Plan screen-reader labels and a sensible reading order.
- Bigger text settings must not push important buttons off screen.
- Respect "reduce motion" and "reduce transparency".
- Allow for the keyboard, the camera cut-out, the phone's top and bottom bars,
  and gesture navigation.
- Back button/gesture works the way Android users expect.
- Don't decorate the phone's own bars so it looks like an iPhone app.
- A static design can't prove accessibility or real Android behaviour; list
  what must be checked in the real app separately.

## Content and honesty

- Use realistic example recordings and label them clearly as example data.
- Keep the example content the same everywhere it appears.
- Don't claim recorder transfer, all-day recording, transcription quality,
  Drive checking or deleting works until it has been tested.

## How to work

1. Look at the project's docs and the Paper setup. Ignore the old app project.
2. Set a fresh look and a small design kit: colours, Lexend text sizes,
   spacing, icons, surfaces, glass, controls.
3. Briefly state key assumptions, then draw. Don't stop at a plan.
4. Start with Today, navigation, Search, Notifications and the date picker —
   they set the look.
5. Build step by step in Paper so I can watch.
6. Take screenshots at key points and look for layout problems, weak
   emphasis, inconsistencies, cut-off content, low contrast, and buttons
   hidden behind floating parts.
7. Fix problems before spreading the look to the other screens.
8. Design light and dark, and write down how the screens connect and which
   situations are covered.
9. Save editable designs, named image exports, a list of screens, and short
   design decisions in the project.
10. Be clear about what's finished versus what still needs variations or
    real-app work.
11. Don't write app code or install packages just to make pictures. A
    separate small effects demo is fine if it really helps judge an effect.

Be careful and thorough: I want a distinctive, finished look with attention
to small details — not another generic app with a fresh coat of paint.
