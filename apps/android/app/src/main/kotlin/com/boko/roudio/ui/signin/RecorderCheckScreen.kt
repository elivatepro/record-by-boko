package com.boko.roudio.ui.signin

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boko.roudio.recorder.FoundRecorder
import com.boko.roudio.recorder.LinkPhase
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.serialEnding
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.RoudioTypeScale
import com.boko.roudio.ui.theme.roudioText

/**
 * Temporary, milestone 1 step 3: drives the real recorder before the setup screens exist.
 * Replaced by the setup flow (11a–11i) and the recorder screen (13a/13b).
 */
@Composable
fun RecorderCheckScreen(
    state: RecorderState,
    onSearch: () -> Unit,
    onLink: (FoundRecorder) -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUnlink: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = RoudioTheme.colors
    val body = roudioText(RoudioTypeScale.BodySize, RoudioTypeScale.BodyLineHeight)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Column(
        Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Bluetooth: ${if (state.bluetoothOn) "on" else "off"}", style = body, color = c.ink)
        Text("Linked: ${state.linked?.let { "${it.model.displayName} · ${it.serial.serialEnding}" } ?: "none"}", style = body, color = c.ink)
        Text("Connected since: ${state.connectedSince ?: "-"} · last seen ${state.lastSeen ?: "-"}", style = body, color = c.ink)
        Text("Link: ${state.link}", style = body, color = c.ink)
        Text("Battery: ${state.battery}", style = body, color = c.ink)
        Text("Storage: ${state.storage}", style = body, color = c.ink)
        Text("Recording now: ${state.recordingNow}", style = body, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                permissions.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                )
            }) { Text("Permissions", style = body) }
            OutlinedButton(onClick = onSearch) { Text("Search", style = body) }
            OutlinedButton(onClick = onRefresh) { Text("Refresh", style = body) }
            OutlinedButton(onClick = onReconnect) { Text("Reconnect", style = body) }
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect", style = body) }
            OutlinedButton(onClick = onUnlink) { Text("Unlink", style = body) }
            OutlinedButton(onClick = onSignOut) { Text("Sign out", style = body) }
        }
        (state.link as? LinkPhase.Searching)?.found?.forEach { recorder ->
            OutlinedButton(onClick = { onLink(recorder) }) {
                Text("Link ${recorder.model.displayName} · ${recorder.serial.serialEnding} (${recorder.signal} dBm)", style = body)
            }
        }
    }
}
