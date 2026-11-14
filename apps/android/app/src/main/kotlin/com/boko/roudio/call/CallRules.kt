package com.boko.roudio.call

import com.boko.roudio.recorder.RecordControl
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingNow
import java.time.Duration
import java.time.Instant

/** Where a call came from: the phone app, WhatsApp, or any other app that put the phone in a call. */
enum class CallApp { Phone, WhatsApp, Other }

enum class CallDirection { Incoming, Outgoing, Unknown }

/** The phone app's call state (the PHONE_STATE broadcast; needs the Phone permission). */
sealed interface PhoneSignal {
    data object Idle : PhoneSignal

    /** [name]: the caller from contacts, when "Who's calling" is allowed. */
    data class Ringing(val name: String?) : PhoneSignal

    /** Answered, or dialling out. */
    data object OffHook : PhoneSignal
}

/** WhatsApp's call notification (notification access): ringing in, or a call under way. */
data class WhatsAppSignal(val name: String?, val ringing: Boolean)

/** Everything that says a call is happening, as it stands right now. */
data class CallSignals(
    val phone: PhoneSignal = PhoneSignal.Idle,
    val whatsApp: WhatsAppSignal? = null,
    /** Since when AudioManager has said the phone is in a call or a voice chat; null when it isn't. */
    val audioInCallSince: Instant? = null,
)

/** The call being followed. [promptShown] and [autoStarted] say what Roudio has done about it. */
data class ActiveCall(
    val app: CallApp,
    val direction: CallDirection,
    val name: String?,
    val startedAt: Instant,
    val answeredAt: Instant? = null,
    val promptShown: Boolean = false,
    /** Whether the prompt on screen has its Start button, so it's re-posted when that changes. */
    val promptCanStart: Boolean = false,
    /** Roudio started the recording for this call, so it may stop it when the call ends. */
    val autoStarted: Boolean = false,
    /** Automatic start was tried once already; it isn't tried again for this call. */
    val autoStartTried: Boolean = false,
    /** Since when nothing has said "in a call"; the call ends after [CallRules.END_GRACE]. */
    val quietSince: Instant? = null,
)

/** A finished, answered call, kept on the phone so its recording can be titled later. */
data class FinishedCall(
    val app: CallApp,
    val direction: CallDirection,
    val name: String?,
    val startedAt: Instant,
    val answeredAt: Instant,
    val endedAt: Instant,
    val autoStarted: Boolean,
)

sealed interface CallAction {
    /** Post or update the heads-up: "Call from Ada · place your Plaud on the back of the phone". */
    data class ShowPrompt(val call: ActiveCall, val canStart: Boolean) : CallAction

    data object HidePrompt : CallAction

    data object StartRecording : CallAction

    data object StopRecording : CallAction

    data class Finished(val call: FinishedCall) : CallAction
}

data class CallSettings(val prompts: Boolean, val autoStart: Boolean)

/**
 * Turns the call signals into what Roudio does (next-features.md, milestone 8, "Calls"). Pure: the
 * watcher feeds it the current signals and runs the actions. Rules:
 * - A prompt only when a recorder is linked and isn't recording; its Start button only when the
 *   recorder has said it's not recording (never on a guess).
 * - Automatic start once the call is answered; automatic stop only of a recording Roudio started
 *   for that call, never one the owner started.
 * - Another app's voice chat only counts after [OTHER_APP_MIN], so a short sound doesn't look like a call.
 */
object CallRules {
    val END_GRACE: Duration = Duration.ofSeconds(3)
    val OTHER_APP_MIN: Duration = Duration.ofSeconds(5)

    fun evaluate(
        current: ActiveCall?,
        signals: CallSignals,
        recorder: RecorderState,
        settings: CallSettings,
        now: Instant,
    ): Pair<ActiveCall?, List<CallAction>> {
        val actions = mutableListOf<CallAction>()
        val audio = signals.audioInCallSince?.takeIf { since ->
            // The phone and WhatsApp say so themselves; another app has to keep it up a moment.
            current != null || signals.phone != PhoneSignal.Idle || signals.whatsApp != null ||
                !since.plus(OTHER_APP_MIN).isAfter(now)
        }
        val inCall = signals.phone != PhoneSignal.Idle || signals.whatsApp != null || audio != null

        var call = current
        if (call == null) {
            if (!inCall) return null to emptyList()
            call = start(signals, now)
        }

        if (!inCall) {
            val quiet = call.quietSince ?: now
            if (Duration.between(quiet, now) < END_GRACE) return call.copy(quietSince = quiet) to emptyList()
            if (call.promptShown) actions += CallAction.HidePrompt
            if (call.autoStarted && recorder.recordControl == RecordControl.Stop) actions += CallAction.StopRecording
            call.answeredAt?.let {
                actions += CallAction.Finished(
                    FinishedCall(call.app, call.direction, call.name, call.startedAt, it, now, call.autoStarted),
                )
            }
            return null to actions
        }

        call = update(call.copy(quietSince = null), signals, now)

        val recording = recorder.recordingNow is RecordingNow.Recording ||
            recorder.recordControl == RecordControl.Starting || recorder.recordControl == RecordControl.Stop
        val canStart = recorder.recordControl == RecordControl.Start

        if (settings.autoStart && call.answeredAt != null && !call.autoStartTried && canStart) {
            actions += CallAction.StartRecording
            call = call.copy(autoStarted = true, autoStartTried = true)
        }

        val starting = actions.contains(CallAction.StartRecording)
        val wantPrompt = settings.prompts && recorder.linked != null && !recording && !starting
        if (wantPrompt) {
            val shown = current?.takeIf { it.promptShown }
            // Post once, and again only when there's something new to say (the name, the button).
            if (shown == null || shown.name != call.name || shown.app != call.app || shown.promptCanStart != canStart) {
                actions += CallAction.ShowPrompt(call, canStart)
            }
            call = call.copy(promptShown = true, promptCanStart = canStart)
        } else if (call.promptShown) {
            actions += CallAction.HidePrompt
            call = call.copy(promptShown = false, promptCanStart = false)
        }
        return call to actions
    }

    private fun start(signals: CallSignals, now: Instant): ActiveCall {
        val phone = signals.phone
        val wa = signals.whatsApp
        return when {
            phone is PhoneSignal.Ringing -> ActiveCall(CallApp.Phone, CallDirection.Incoming, phone.name, now)
            phone is PhoneSignal.OffHook -> ActiveCall(CallApp.Phone, CallDirection.Outgoing, null, now)
            wa != null -> ActiveCall(CallApp.WhatsApp, if (wa.ringing) CallDirection.Incoming else CallDirection.Outgoing, wa.name, now)
            else -> ActiveCall(CallApp.Other, CallDirection.Unknown, null, now)
        }
    }

    private fun update(call: ActiveCall, signals: CallSignals, now: Instant): ActiveCall {
        val phone = signals.phone
        val wa = signals.whatsApp
        var c = call
        // A call first seen through the audio mode turns out to be WhatsApp once its notification shows.
        if (c.app == CallApp.Other && wa != null) c = c.copy(app = CallApp.WhatsApp)
        if (c.app == CallApp.Other && phone != PhoneSignal.Idle) c = c.copy(app = CallApp.Phone)
        val name = when (c.app) {
            CallApp.Phone -> (phone as? PhoneSignal.Ringing)?.name
            CallApp.WhatsApp -> wa?.name
            CallApp.Other -> null
        }
        if (c.name == null && name != null) c = c.copy(name = name)
        val answered = when (c.app) {
            CallApp.Phone -> phone == PhoneSignal.OffHook
            CallApp.WhatsApp -> (wa != null && !wa.ringing) || (signals.audioInCallSince != null && wa?.ringing != true)
            CallApp.Other -> signals.audioInCallSince != null
        }
        if (answered && c.answeredAt == null) c = c.copy(answeredAt = now)
        return c
    }
}
