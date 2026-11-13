package com.boko.roudio.recorder

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.boko.roudio.MainActivity
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile: "Recording", "Not recording" or "Not connected". Tapping starts or
 * stops (only when the recorder has said what it's doing); when it can't, tapping opens the
 * recorder screen. Long-press opens the recorder screen too (ACTION_QS_TILE_PREFERENCES in
 * MainActivity).
 */
class RecorderTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private val recorder get() = (application as RoudioApp).recorder

    override fun onStartListening() {
        super.onStartListening()
        watcher?.cancel()
        watcher = scope.launch {
            recorder.state.map { it.recordControl }.distinctUntilChanged().collect(::show)
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (recorder.state.value.recordControl) {
            RecordControl.Start -> recorder.startRecording()
            RecordControl.Stop -> recorder.stopRecording()
            RecordControl.Starting, RecordControl.Stopping -> Unit
            RecordControl.Unavailable -> openRecorder()
        }
    }

    private fun show(control: RecordControl) {
        val tile = qsTile ?: return
        val (state, subtitle) = when (control) {
            RecordControl.Stop -> Tile.STATE_ACTIVE to R.string.qs_tile_recording
            RecordControl.Stopping -> Tile.STATE_ACTIVE to R.string.qs_tile_stopping
            RecordControl.Starting -> Tile.STATE_ACTIVE to R.string.qs_tile_starting
            RecordControl.Start -> Tile.STATE_INACTIVE to R.string.qs_tile_not_recording
            // Still clickable (it opens the recorder screen), so not STATE_UNAVAILABLE.
            RecordControl.Unavailable -> Tile.STATE_INACTIVE to R.string.qs_tile_not_connected
        }
        tile.state = state
        tile.label = getString(R.string.qs_tile_label)
        tile.subtitle = getString(subtitle)
        tile.updateTile()
    }

    private fun openRecorder() {
        val intent = MainActivity.recorderIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            // Below Android 14 the Intent form is the only one there is.
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
