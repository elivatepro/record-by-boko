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

    /**
     * A copy of [source] with only its Opus stream, for playback. Plaud's files interleave a second
     * Ogg stream (a "PLAUD.AI" metadata stream) that ffmpeg skips but Android's player feeds to the
     * Opus decoder, which then fails. The stored file is never changed: its checksum is what the
     * clean-up gate compares. Returns [source] itself when it has only the one stream.
     */
    fun audioOnly(source: File, cacheDir: File): File {
        val target = File(cacheDir, "play-${source.name}")
        if (target.exists() && target.lastModified() >= source.lastModified()) return target
        var opusSerial: Int? = null
        var dropped = false
        val partial = File(cacheDir, "play-${source.name}.part")
        source.inputStream().buffered().use { input ->
            partial.outputStream().buffered().use { output ->
                val header = ByteArray(27)
                while (input.readNBytesCompat(header) == 27) {
                    if (!header.startsWith("OggS", 0)) break
                    val segments = header[26].toInt() and 0xFF
                    val table = ByteArray(segments).also { input.readNBytesCompat(it) }
                    val body = ByteArray(table.sumOf { it.toInt() and 0xFF }).also { input.readNBytesCompat(it) }
                    val serial = (header[14].toInt() and 0xFF) or ((header[15].toInt() and 0xFF) shl 8) or
                        ((header[16].toInt() and 0xFF) shl 16) or ((header[17].toInt() and 0xFF) shl 24)
                    if (opusSerial == null && body.startsWith("OpusHead", 0)) opusSerial = serial
                    if (serial == opusSerial) {
                        output.write(header)
                        output.write(table)
                        output.write(body)
                    } else {
                        dropped = true
                    }
                }
            }
        }
        if (!dropped || opusSerial == null) {
            partial.delete()
            return source
        }
        partial.renameTo(target)
        return target
    }

    /** InputStream.readNBytes needs API 33; read until [buffer] is full or the stream ends. */
    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun ByteArray.startsWith(text: String, at: Int): Boolean =
        at + text.length <= size && text.indices.all { this[at + it] == text[it].code.toByte() }

    private const val TAIL_BYTES = 65_536L
}
