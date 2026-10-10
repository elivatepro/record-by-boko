# What the static designs can't prove

These need checking in a real build, on the real phone.

## Hardware and services (unverified)

- [ ] Exact phone model, Android version, screen size and density.
- [x] Whether the Plaud Note Pro can be accessed by a third-party app at
      all. Yes, through the official Plaud device SDK (see
      `../architecture/decisions.md`).
- [ ] Bluetooth transfer: speed, reliability, resuming a stopped transfer.
- [ ] Wi-Fi transfer support. The SDK offers Wi-Fi Fast Transfer; still to
      test on the device. (Shown as "not available yet".)
- [x] Deleting files from the recorder. Works through the SDK (checked by
      the owner). The designs still say "not confirmed" and need updating.
- [ ] Reading battery, free space and "is recording" from the device. The
      SDK reports these as events; still to test. (Recording state is shown
      as unknown.)
- [ ] Recording around the clock, charging behaviour, and Android background
      limits (battery optimisation, background restrictions).
- [ ] Transcription quality versus the target, speaker labels, and
      mixed-language handling. (ElevenLabs Scribe v2 chosen; still to check on
      the owner's own audio.)
- [ ] Verifying a Drive backup (checksums or re-download) and being able to
      play from Drive after local deletion.

## Accessibility

- [ ] Contrast was checked from the palette values. Text on glass must be
      re-checked over real content at runtime.
- [ ] Screen-reader labels: every icon button has a name in the designs
      (layer names); confirm the announced labels and the reading order.
- [ ] Font scaling to 200%: the week strip, nav labels, status pairs and
      player controls must reflow, not clip.
- [ ] Touch targets of 48dp or more in the real layout, including chips and
      timestamp buttons.
- [ ] Reduce motion and reduce transparency are followed (system setting
      plus the in-app toggle).
- [ ] Keyboard insets, camera cut-out, 3-button navigation mode as well as
      gesture navigation.
- [ ] Back behaviour matches the flow board (sheets → underlying screen;
      recording tabs don't add history).

## Visual and engineering

- [ ] Background blur cost on the device, with an opaque fallback when it
      isn't supported or in battery saver.
- [ ] Choose the app technology, then decide whether the gradient effects
      are worth porting. The current visuals need no shader.
- [ ] Logo/identity decision. The wordmark is a working draft.
