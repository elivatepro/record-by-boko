# Start development in a new thread

Paste this as the first message of the new thread:

> We're continuing development of Roudio. Read `AGENTS.md`, `README.md`,
> `docs/build/build-plan.md`, `docs/build/progress.md`,
> `docs/architecture/decisions.md`, `docs/architecture/spec.md`,
> `docs/design/README.md` and `apps/android/app/libs/README.md` before doing
> anything.
>
> First finish **Milestone 1**. `progress.md` lists what's left: 11f–11i and
> error screens 12a–12e, the recorder screen 13a/13b and the Today chip, then
> the "done when" check on my phone. Then begin **Milestone 2 (Transfer, local
> store, upload queue)** as described in the build plan.
>
> My Note Pro (…1437) is already linked to Roudio on my Redmi Note 14 Pro+ 5G
> (Android 16), connected over USB. Don't unlink it: relinking needs the Plaud
> app to unbind it while connected. Drive the recorder with the debug adb
> commands in `progress.md`. Don't restart adb, and keep installs few; every
> install asks me to confirm on the phone.
>
> Confirm the plan with me before writing code, then work in small steps,
> testing on the phone. Never commit keys, and never delete anything from the
> recorder.

## Before you paste it

- Phone plugged in, unlocked, USB debugging on, and "Always allow from this
  computer" ticked on the debugging prompt.
- Note Pro charged and next to the phone. The Plaud app closed.
