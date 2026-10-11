package com.boko.roudio.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays a recording's original Ogg Opus: the phone's own copy when it has one, otherwise the
 * server's short-lived link (spec §2, "Player"). Times are offsets into the original file.
 */
class RecordingPlayer(context: Context) {
    private val exo = ExoPlayer.Builder(context).build()
    private var loaded: Uri? = null

    val positionMs: Long get() = exo.currentPosition
    val durationMs: Long get() = exo.duration.takeIf { it > 0 } ?: 0
    val playing: Boolean get() = exo.isPlaying
    var speed: Float = 1f
        private set

    fun load(uri: Uri, startMs: Long) {
        if (loaded == uri) return
        loaded = uri
        exo.setMediaItem(MediaItem.fromUri(uri), startMs)
        exo.prepare()
    }

    fun toggle() {
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun seekTo(ms: Long, play: Boolean = true) {
        exo.seekTo(ms.coerceAtLeast(0))
        if (play) exo.play()
    }

    fun skip(deltaMs: Long) = exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0))

    /** 1× → 1.25× → 1.5× → 2× → 0.75× → 1×. */
    fun nextSpeed(): Float {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        exo.playbackParameters = PlaybackParameters(speed)
        return speed
    }

    fun onEnded(block: () -> Unit) {
        exo.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) block()
                }
            },
        )
    }

    fun release() = exo.release()

    private companion object {
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    }
}
