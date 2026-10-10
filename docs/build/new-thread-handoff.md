# Start development in a new thread

Paste this as the first message of the new thread:

> We're continuing development of Roudio. Read `AGENTS.md`, `README.md`,
> `docs/build/build-plan.md`, `docs/build/progress.md`,
> `docs/architecture/decisions.md` (1–16), `docs/architecture/spec.md` and
> `docs/design/README.md` before doing anything. Then begin **Milestone 1
> (Linking the recorder, Android)**, as described in the build plan:
> - the `RecorderSource` interface, with the Plaud SDK implementation behind it
> - setup flow 11a–11i and error screens 12a–12e, built from the Paper file
> - the foreground service that holds the connection and reads battery,
>   storage, charging and recording-state events
> - the recorder screen 13a/13b and the recorder chip on Today
>
> My phone is a Redmi Note 14 Pro+ 5G on Android 16, connected over USB.
> The Note Pro is charged and unbound from the Plaud app. Confirm the plan
> for Milestone 1 with me before writing code, then work in small steps.
> Never commit keys.

## Before you paste it

- Phone plugged in with USB debugging and "Install via USB" on.
- Note Pro charged and **unbound from the Plaud app** (Plaud app → device
  settings → unbind).
