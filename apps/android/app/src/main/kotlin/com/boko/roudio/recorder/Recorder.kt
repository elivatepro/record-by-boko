package com.boko.roudio.recorder

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The app's view of the recorder: folds [RecorderSource] events into one [RecorderState],
 * runs the search and link time limits, and saves what should survive a restart.
 */
class Recorder(
    private val source: RecorderSource,
    private val store: RecorderStore,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(store.load().copy(bluetoothOn = source.isBluetoothOn()))
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var searchTimer: Job? = null
    private var linkTimer: Job? = null

    init {
        scope.launch {
            source.events.collect { event ->
                update { it.reduce(event, now()) }
                if (event is RecorderEvent.Connected) {
                    linkTimer?.cancel()
                    // Battery, storage and recording state come in after this.
                    source.refresh()
                }
            }
        }
    }

    fun search() {
        searchTimer?.cancel()
        linkTimer?.cancel()
        // A connected recorder doesn't show up in a scan.
        if (_state.value.isConnected) source.disconnect()
        update { it.searching() }
        source.startSearch()
        searchTimer = scope.launch {
            delay(RecorderState.SEARCH_TIME.toMillis())
            source.stopSearch()
            update { it.searchTimedOut() }
        }
    }

    fun stopSearch() {
        searchTimer?.cancel()
        source.stopSearch()
        update { if (it.link is LinkPhase.Searching) it.linkIdle() else it }
    }

    fun link(recorder: FoundRecorder) {
        searchTimer?.cancel()
        source.stopSearch()
        val alreadyOurs = _state.value.linked?.serial == recorder.serial
        update { it.linking(recorder) }
        source.link(recorder.serial, alreadyOurs)
        linkTimer?.cancel()
        linkTimer = scope.launch {
            delay(LINK_TIME.toMillis())
            if (_state.value.link is LinkPhase.Linking) {
                source.disconnect()
                update { it.reduce(RecorderEvent.LinkFailed(recorder.serial, LinkFailure.Dropped), now()) }
            }
        }
    }

    /** 11g: the owner tapped a recorder that's linked to another app. */
    fun showLinkedElsewhere(recorder: FoundRecorder) {
        searchTimer?.cancel()
        source.stopSearch()
        update { it.linkedElsewhere(recorder) }
    }

    /** "Cancel" on 11h: drop the half-made connection. */
    fun cancelLink() {
        linkTimer?.cancel()
        if (_state.value.link is LinkPhase.Linking) source.disconnect()
        update { it.linkIdle() }
    }

    /** Leave an error screen or the found list; nothing is left running. */
    fun clearLink() {
        if (_state.value.link is LinkPhase.Searching) source.stopSearch()
        searchTimer?.cancel()
        linkTimer?.cancel()
        update { it.linkIdle() }
    }

    /** Reconnect to the linked recorder, if there is one and it isn't connected. */
    fun reconnect() {
        val linked = _state.value.linked ?: return
        if (_state.value.isConnected || !_state.value.bluetoothOn) return
        source.connect(linked.serial)
    }

    fun refresh() {
        if (_state.value.isConnected) source.refresh()
    }

    suspend fun unlink() {
        val linked = _state.value.linked ?: return
        source.unlink(linked.serial)
        update { it.unlinked() }
    }

    private fun update(change: (RecorderState) -> RecorderState) {
        val before = _state.value
        val after = _state.updateAndGet(change)
        if (after.linked != before.linked || after.lastSeen != before.lastSeen) store.save(after)
    }

    companion object {
        /** Linking includes a round trip to Plaud's servers; give it a minute (as Plaud's template). */
        val LINK_TIME: Duration = Duration.ofSeconds(60)
    }
}
