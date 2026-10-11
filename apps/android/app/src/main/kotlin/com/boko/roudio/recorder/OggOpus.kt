package com.boko.roudio.recorder

import java.io.File
import java.io.RandomAccessFile

/** Facts read straight from an Ogg Opus file, with no decoder. */
object OggOpus {
    /**
     * Length of the audio: the last page's granule position minus the pre-skip, at Opus's fixed
     * 48 kHz granule rate. Null if the file isn't Ogg Opus.
     */
    fun durationMs(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(raf.length(), 64L).toInt()).also { raf.readFully(it) }
            if (!head.startsWith("OggS", 0)) return null
            val opusHead = String(head, Charsets.ISO_8859_1).indexOf("OpusHead")
            if (opusHead < 0) return null
            val preSkip = (head[opusHead + 10].toInt() and 0xFF) or ((head[opusHead + 11].toInt() and 0xFF) shl 8)

            val tailLength = minOf(raf.length(), TAIL_BYTES).toInt()
            val tail = ByteArray(tailLength)
            raf.seek(raf.length() - tailLength)
            raf.readFully(tail)
            val last = (tailLength - 27 downTo 0).firstOrNull { tail.startsWith("OggS", it) } ?: return null
            var granule = 0L
            for (b in 7 downTo 0) granule = (granule shl 8) or (tail[last + 6 + b].toLong() and 0xFF)
            if (granule <= preSkip) null else (granule - preSkip) / 48
        }
    }.getOrNull()

    private fun ByteArray.startsWith(text: String, at: Int): Boolean =
        at + text.length <= size && text.indices.all { this[at + it] == text[it].code.toByte() }

    private const val TAIL_BYTES = 65_536L
}
