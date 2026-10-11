package com.boko.roudio.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * True when the owner turned animations off (Android's "Remove animations": animator duration
 * scale 0). Looping motion (the search radar, spinners) holds still then (milestone 7).
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The app's animations last a fifth of their usual length (the owner's choice, 11 Oct 2026):
 * screen changes, sheets, ripples and scrolling. Multiplied by the phone's own animator scale,
 * so "Remove animations" still removes them.
 */
const val MOTION_FACTOR = 0.2f

/** Stretch a looping animation back to its intended pace (the radar, spinners). */
fun loopMillis(intended: Int): Int = (intended / MOTION_FACTOR).toInt()

private class AppMotion(override val scaleFactor: Float) : MotionDurationScale

/**
 * A recomposer whose effects (where every Compose animation runs) see [MOTION_FACTOR]. It pauses
 * its frame clock while the activity is stopped, like the default window recomposer.
 */
fun ComponentActivity.fastMotionRecomposer(): Recomposer {
    val system = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val clock = PausableMonotonicFrameClock(AndroidUiDispatcher.Main[MonotonicFrameClock]!!)
    val context = AndroidUiDispatcher.Main + clock + AppMotion(MOTION_FACTOR * system)
    val recomposer = Recomposer(context)
    lifecycleScope.launch(context) { recomposer.runRecomposeAndApplyChanges() }
    lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> clock.resume()
                Lifecycle.Event.ON_STOP -> clock.pause()
                Lifecycle.Event.ON_DESTROY -> recomposer.cancel()
                else -> Unit
            }
        },
    )
    return recomposer
}
