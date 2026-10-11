package com.boko.roudio.recorder

import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the foreground service does all day: keep the linked recorder connected. Reconnects
 * straight away when Bluetooth comes back, otherwise with growing gaps so a recorder that's
 * out of range doesn't drain the phone. Asks for fresh readings now and then while connected.
 */
class KeepConnected(
    private val recorder: Recorder,
    private val scope: CoroutineScope,
    private val retryDelays: List<Duration> = RETRY_DELAYS,
    private val refreshEvery: Duration = REFRESH_EVERY,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            recorder.state
                .map { it.mode() }
                .distinctUntilChanged()
                .collect { mode -> follow(mode) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        worker?.cancel()
        worker = null
    }

    private var worker: Job? = null

    private fun follow(mode: Mode) {
        worker?.cancel()
        worker = when (mode) {
            Mode.Idle -> null
            Mode.Reconnect -> scope.launch {
                var attempt = 0
                while (true) {
                    recorder.reconnect()
                    delay(retryDelays[attempt.coerceAtMost(retryDelays.lastIndex)].toMillis())
                    attempt++
                }
            }
            Mode.Connected -> scope.launch {
                while (true) {
                    delay(refreshEvery.toMillis())
                    recorder.refresh()
                }
            }
        }
    }

    private enum class Mode { Idle, Reconnect, Connected }

    private fun RecorderState.mode(): Mode = when {
        // Nothing linked, Bluetooth off, or the setup flow is busy with the recorder. A finished
        // search or a failed link left on screen doesn't count: reconnecting goes on.
        linked == null || !bluetoothOn || link is LinkPhase.Searching || link is LinkPhase.Linking -> Mode.Idle
        isConnected -> Mode.Connected
        else -> Mode.Reconnect
    }

    companion object {
        /** Gaps between reconnect attempts; the last one repeats. */
        val RETRY_DELAYS: List<Duration> = listOf(30L, 60L, 120L, 300L).map(Duration::ofSeconds)

        /** Battery, storage and recording state also arrive as events; this catches misses. */
        val REFRESH_EVERY: Duration = Duration.ofMinutes(10)
    }
}
