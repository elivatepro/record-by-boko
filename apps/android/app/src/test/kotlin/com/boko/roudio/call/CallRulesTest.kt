package com.boko.roudio.call

import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecordCommand
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingReading
import com.boko.roudio.recorder.Seen
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRulesTest {
    private val t0 = Instant.parse("2026-11-14T11:00:00Z")
    private val linked = RecorderState(linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro))
    private val prompts = CallSettings(prompts = true, autoStart = false)
    private val auto = CallSettings(prompts = true, autoStart = true)

    private fun idle(at: Instant = t0) = linked.copy(connectedSince = at.minusSeconds(3600), recording = Seen(RecordingReading(false, null), at))
    private fun recording(at: Instant = t0) = linked.copy(connectedSince = at.minusSeconds(3600), recording = Seen(RecordingReading(true, at), at))
    private fun away() = linked

    /** Feeds the rules one step at a time, like the watcher does, and returns each step's actions. */
    private class Run(var call: ActiveCall? = null) {
        fun step(signals: CallSignals, recorder: RecorderState, settings: CallSettings, at: Instant): List<CallAction> {
            val (next, actions) = CallRules.evaluate(call, signals, recorder, settings, at)
            call = next
            return actions
        }
    }

    private fun Instant.s(seconds: Long): Instant = plusSeconds(seconds)

    @Test
    fun `an incoming phone call prompts while ringing, then hides the prompt when it ends`() {
        val r = Run()
        val ring = r.step(CallSignals(phone = PhoneSignal.Ringing("Ada")), idle(), prompts, t0)
        val prompt = ring.single() as CallAction.ShowPrompt
        assertEquals("Ada", prompt.call.name)
        assertEquals(CallApp.Phone, prompt.call.app)
        assertEquals(CallDirection.Incoming, prompt.call.direction)
        assertTrue(prompt.canStart)

        assertTrue(r.step(CallSignals(phone = PhoneSignal.OffHook), idle(), prompts, t0.s(5)).isEmpty())
        // Quiet for less than the grace: still the same call.
        assertTrue(r.step(CallSignals(), idle(), prompts, t0.s(600)).isEmpty())
        val end = r.step(CallSignals(), idle(), prompts, t0.s(604))
        assertEquals(CallAction.HidePrompt, end[0])
        val done = (end[1] as CallAction.Finished).call
        assertEquals(FinishedCall(CallApp.Phone, CallDirection.Incoming, "Ada", t0, t0.s(5), t0.s(604), autoStarted = false), done)
        assertNull(r.call)
    }

    @Test
    fun `a missed call isn't kept`() {
        val r = Run()
        r.step(CallSignals(phone = PhoneSignal.Ringing(null)), idle(), prompts, t0)
        r.step(CallSignals(), idle(), prompts, t0.s(20))
        val end = r.step(CallSignals(), idle(), prompts, t0.s(30))
        assertEquals(listOf(CallAction.HidePrompt), end)
    }

    @Test
    fun `no prompt while already recording, with no recorder, or switched off`() {
        assertTrue(Run().step(CallSignals(phone = PhoneSignal.Ringing("Ada")), recording(), prompts, t0).isEmpty())
        assertTrue(Run().step(CallSignals(phone = PhoneSignal.Ringing("Ada")), RecorderState(), prompts, t0).isEmpty())
        assertTrue(Run().step(CallSignals(phone = PhoneSignal.Ringing("Ada")), idle(), CallSettings(false, false), t0).isEmpty())
    }

    @Test
    fun `out of reach the prompt has no Start button, and gains it when the recorder answers`() {
        val r = Run()
        val first = r.step(CallSignals(phone = PhoneSignal.Ringing("Ada")), away(), prompts, t0).single() as CallAction.ShowPrompt
        assertEquals(false, first.canStart)
        assertTrue(r.step(CallSignals(phone = PhoneSignal.Ringing("Ada")), away(), prompts, t0.s(1)).isEmpty())
        val again = r.step(CallSignals(phone = PhoneSignal.Ringing("Ada")), idle(t0.s(2)), prompts, t0.s(2)).single() as CallAction.ShowPrompt
        assertTrue(again.canStart)
    }

    @Test
    fun `the prompt goes once recording starts`() {
        val r = Run()
        r.step(CallSignals(phone = PhoneSignal.Ringing("Ada")), idle(), prompts, t0)
        val starting = idle().asked(RecordCommand.Start, t0.s(3))
        assertEquals(listOf(CallAction.HidePrompt), r.step(CallSignals(phone = PhoneSignal.OffHook), starting, prompts, t0.s(3)))
    }

    @Test
    fun `automatic start when answered, and stop when the call ends`() {
        val r = Run()
        val ring = r.step(CallSignals(phone = PhoneSignal.Ringing("Ada")), idle(), auto, t0)
        assertTrue(ring.single() is CallAction.ShowPrompt)
        val answer = r.step(CallSignals(phone = PhoneSignal.OffHook), idle(), auto, t0.s(4))
        assertEquals(listOf(CallAction.StartRecording, CallAction.HidePrompt), answer)
        // Not started twice, and no prompt while recording.
        assertTrue(r.step(CallSignals(phone = PhoneSignal.OffHook), recording(t0.s(6)), auto, t0.s(6)).isEmpty())
        r.step(CallSignals(), recording(t0.s(60)), auto, t0.s(60))
        val end = r.step(CallSignals(), recording(t0.s(63)), auto, t0.s(63))
        assertEquals(CallAction.StopRecording, end[0])
        assertTrue((end[1] as CallAction.Finished).call.autoStarted)
    }

    @Test
    fun `a recording the owner started before the call is never stopped`() {
        val r = Run()
        r.step(CallSignals(phone = PhoneSignal.OffHook), recording(), auto, t0)
        r.step(CallSignals(), recording(), auto, t0.s(60))
        val end = r.step(CallSignals(), recording(), auto, t0.s(63))
        assertTrue(end.none { it == CallAction.StopRecording })
        assertEquals(CallDirection.Outgoing, (end.single() as CallAction.Finished).call.direction)
    }

    @Test
    fun `automatic start waits for the recorder, then starts once`() {
        val r = Run()
        assertTrue(r.step(CallSignals(phone = PhoneSignal.OffHook), away(), auto, t0).single() is CallAction.ShowPrompt)
        val ready = r.step(CallSignals(phone = PhoneSignal.OffHook), idle(t0.s(10)), auto, t0.s(10))
        assertEquals(listOf(CallAction.StartRecording, CallAction.HidePrompt), ready)
        // The start timed out and the recorder is idle again: not tried a second time.
        val again = r.step(CallSignals(phone = PhoneSignal.OffHook), idle(t0.s(30)), auto, t0.s(30))
        assertTrue(again.single() is CallAction.ShowPrompt)
    }

    @Test
    fun `a WhatsApp call rings, is answered and ends`() {
        val r = Run()
        val ring = r.step(CallSignals(whatsApp = WhatsAppSignal("Ada", ringing = true)), idle(), prompts, t0).single() as CallAction.ShowPrompt
        assertEquals(CallApp.WhatsApp, ring.call.app)
        assertEquals(CallDirection.Incoming, ring.call.direction)
        assertNull(r.call!!.answeredAt)
        r.step(CallSignals(whatsApp = WhatsAppSignal("Ada", ringing = false), audioInCallSince = t0.s(8)), idle(), prompts, t0.s(8))
        assertEquals(t0.s(8), r.call!!.answeredAt)
        r.step(CallSignals(), idle(), prompts, t0.s(100))
        val end = r.step(CallSignals(), idle(), prompts, t0.s(103))
        assertEquals(CallApp.WhatsApp, (end.last() as CallAction.Finished).call.app)
    }

    @Test
    fun `another app's voice chat counts only after a few seconds, then becomes WhatsApp if its notification shows`() {
        val r = Run()
        assertTrue(r.step(CallSignals(audioInCallSince = t0), idle(), prompts, t0.s(2)).isEmpty())
        assertNull(r.call)
        val prompt = r.step(CallSignals(audioInCallSince = t0), idle(), prompts, t0.s(5)).single() as CallAction.ShowPrompt
        assertEquals(CallApp.Other, prompt.call.app)
        val named = r.step(CallSignals(whatsApp = WhatsAppSignal("Ada", ringing = false), audioInCallSince = t0), idle(), prompts, t0.s(6)).single() as CallAction.ShowPrompt
        assertEquals(CallApp.WhatsApp, named.call.app)
        assertEquals("Ada", named.call.name)
    }

    @Test
    fun `a brief gap in the signals doesn't end the call`() {
        val r = Run()
        r.step(CallSignals(phone = PhoneSignal.OffHook), idle(), prompts, t0)
        r.step(CallSignals(), idle(), prompts, t0.s(10))
        assertTrue(r.step(CallSignals(phone = PhoneSignal.OffHook), idle(), prompts, t0.s(11)).isEmpty())
        assertNull(r.call!!.quietSince)
    }
}
