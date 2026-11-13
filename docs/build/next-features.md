# Next features: record more, keep what matters

The owner chose these on 13 Nov 2026, built in this order. The goal is for the
recorder to be running more of the day, and for the important details of the
day to be easy to find again. Each milestone gets its own development thread
(see "Starting a thread" at the end).

New screens and states get designed in Paper first, matching the existing
look (see `docs/design/README.md`). The exception is a small control that
fits a pattern we already have.

## Milestone 8: Never miss a moment

Run the recorder from the phone, and get nudged when it should be running but isn't.

Controls:
- `RecorderSource.startRecording()` / `stopRecording()` using the SDK's
  `PlaudDeviceAgent.startRecord()` / `stopRecord()` (public, unused so far).
  Pause and resume exist too, but leave them out unless the owner asks. Add
  fakes and unit tests like the other source calls.
- **Notification:** the recorder notification gets a Start / Stop recording
  button. It only appears while connected and the recording state is known,
  never on a guess.
- **Quick Settings tile:** shows "Recording", "Not recording" or "Not
  connected". Tapping it starts or stops. Long-press opens the recorder
  screen.
- **Home-screen widget** (Glance): the recorder's state, battery and today's
  captured hours, with a start/stop button.
- **Debug receiver:** `RecorderCommands` gets `start` / `stop`, so these can
  be tested over adb.

Nudges (each can be switched off in Settings → Notifications; quiet hours are
respected):
- **Calendar:** a calendar event starts while the recorder isn't recording.
  The nudge reads "Meeting with Ada started — start recording?" and includes
  a Start button. It uses the phone's calendar (`CalendarContract`, which
  needs the `READ_CALENDAR` permission, asked for with a plain reason). There
  is no server work.
- **Not recording:** the recorder has been idle for a set time during waking
  hours. The default is 30 min. Waking hours are a setting.
- **Battery:** the recorder battery is below 20 %. In the evening, a
  reminder to charge it overnight if it's under 50 %.
- **Storage:** recorder storage is nearly full and recordings are still
  waiting to transfer. The nudge says what's waiting.

Calls (phone and WhatsApp):
- Know when a call happens, even with Roudio in the background:
  - **Phone calls:** `TelephonyCallback` for ringing, answered and ended
    (needs the Phone permission). The caller's name needs call-log access.
  - **WhatsApp calls while ringing:** the "Incoming voice call" notification,
    read through a `NotificationListenerService` (needs notification access).
  - **Any call app, once answered:** `AudioManager` reports the phone is in
    a call (`MODE_IN_COMMUNICATION`, which needs no permission). This also
    catches calls the notification check missed.
  All three permissions are optional and asked for with a plain reason.
- **The prompt:** a heads-up notification, "Call from Ada: place your Plaud
  on the back of the phone", with a Start recording button.
- **Automatic start (a setting, off by default):** start recording when the
  call is answered and stop when it ends.
- **Afterwards:** the recording is titled "Call with Ada · WhatsApp · 12 min"
  and shows as a call on Today.
- **Hardware call mode (needs checking).** The SDK tags every recording
  with a `scene` (`BleFile.getScene()`, and in `bleRecordStart`). Also,
  `PlaudDeviceAgent.startRecord(int)` takes a number that defaults to `1`.
  That looks like a call mode the app can choose. To confirm:
  1. Log the scene for each file.
  2. Make one ordinary recording and one call recording with the Plaud on
     the back of the phone, and compare their scenes.
  3. Try `startRecord` with the call-scene value.
  If it works, recordings started for a call use call mode. Until it's
  confirmed, say nothing about modes in the app.
- **Consent:** the first time automatic start is switched on, a one-line
  reminder that call-recording rules differ by country and that telling the
  other person is the safe habit.

On Today:
- Show "Captured 9 h 20 m today" next to the dial, with a gentle line about
  the biggest gap during waking hours ("Nothing from 2–5 pm"). Count from
  recording spans, not uploads.

Done when the owner can start and stop the Plaud from the notification, the
tile and the widget, and gets a calendar nudge for a real meeting. Each nudge
is checked once on the phone.

## Milestone 9: Mark key moments

Flag a moment while it's happening, and have Roudio treat it as important.

- **Spike first (half a day at most).** The Plaud's own marks (button
  presses during a recording) are in the SDK. However, they're only reachable
  through obfuscated internal classes (`proto.o2` holds a session id and a
  list of mark times, and `t3`/`q`/`b4` request it), with no public method.
  Try reading them for one test recording.
  - If it works reliably, wrap it behind `RecorderSource.marks(session)`.
    Pin the SDK version and add a check that fails loudly if the internals
    change.
  - If it doesn't work, use phone-side marks only (below). Ask Plaud support
    to make marks public.
- **Phone-side marks** (built either way): a "Mark this moment" button in the
  recorder notification, the tile's long-press menu and the widget. It stores
  the phone time and the recorder serial in Room. Marks reach the server with
  the recording and are matched to it by time.
- **Spoken marks:** the "understand" step treats "mark this", "remember
  this" or "note to self" in the transcript as a mark. This works with no
  button at all.
- **Server:** a `mark` table (recording, offset, source:
  device/phone/spoken). The understand step gets the marks as hints. Each mark
  becomes a key moment with what was said around it (about 30 s before and 60
  s after) and a short title. Claims still need cited segments.
- **Screens:**
  - Marked moments lead the daily summary and show as pins on the Today dial.
  - The recording screen has a "Marked" filter.
  - Search can be limited to marked moments.
  - Web gets the same.

Done when a button mark, a phone mark and a spoken mark from one real
conversation each show up in that day's summary at the right moment.

## Milestone 10: Look back

Turn the days into something worth rereading.

- **Daily journal:** a few first-person paragraphs written from the day's
  conversations, generated alongside the digest. Every sentence links to the
  moment it came from. It's editable, and the owner's edits are kept when
  the digest rebuilds.
- **Weekly and monthly review:** the main moments, decisions, people talked
  to most, tasks done and still open, and marked moments. Generated on
  Sunday night and on the 1st. Shown as an Update and a card on Today.
- **On this day:** a card on Today showing what happened on this date a week,
  a month and a year ago. It only appears when there's something worth
  showing.
- **Promises:** the understand step separates "I'll do X" (already a task)
  from "they'll do X for me" (new: owed to me, with the person and a due date
  when one is said). Review gets an "Owed to me" list. When a promise is due
  and isn't marked done, a nudge offers the source moment and a ready-to-send
  follow-up message.
- **Server:** new job kinds (`build_week`, `build_month`) on the existing
  queue, costs tagged by purpose like the others, and API and OpenAPI updates
  (then regenerate the clients).

Done when a week of real recordings gives a weekly review the owner finds
worth reading, and one real "owed to me" promise gets a correct follow-up
nudge.

## Not chosen yet

- **Life memory:** Ask across all recordings, People pages, and key details
  pulled out automatically (numbers, places, prices). The owner may pick this
  after these three milestones.

## Starting a thread

Paste this into a new thread, changing the milestone number:

> We're continuing development of Roudio. Read `AGENTS.md`, `README.md`,
> `docs/build/progress.md`, `docs/build/next-features.md`,
> `docs/architecture/decisions.md`, `docs/architecture/spec.md`,
> `docs/design/README.md` and `apps/android/app/libs/README.md`, then build
> **Milestone 8** from `next-features.md`. Confirm the plan with me before
> writing code, design any new screens in Paper first, work in small steps
> and test on my phone (wireless debugging; find it with
> `adb mdns services`, then `adb connect`). Don't unlink the recorder, never
> delete anything from it, and keep installs few. The phone gets the `fast`
> build (`./gradlew :app:assembleFast`).
