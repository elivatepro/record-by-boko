package com.boko.roudio.recorder

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Fixes the OpusTags header the Plaud Android SDK's Ogg/Opus exporter writes. Its
 * `user_comment_list_length` holds the remaining byte length instead of the comment count, so
 * strict parsers (Android's MediaPlayer, ExoPlayer) reject the file; ffmpeg copes. The repair sets
 * the count to 0 and rewrites that page's CRC. The audio pages aren't touched. Idempotent.
 *
 * Adapted from Plaud's template app (`OpusRepair.kt`, Apache-2.0,
 * github.com/Plaud-AI/plaud-sdk-public). Remove once the SDK writes a valid header.
 */
object OpusRepair {
    /** Repairs [file] in place if its OpusTags comment count is impossible. Returns true if patched. */
    fun repairIfNeeded(file: File): Boolean = try {
        RandomAccessFile(file, "rw").use { raf ->
            val head = ByteArray(minOf(raf.length(), HEADER_BYTES).toInt())
            raf.seek(0)
            raf.readFully(head)
            val patchedLength = patch(head)
            if (patchedLength > 0) {
                raf.seek(0)
                raf.write(head, 0, patchedLength)
                Log.w(TAG, "patched the OpusTags comment count in ${file.name}")
            }
            patchedLength > 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't check ${file.name}", e)
        false
    }

    /**
     * Patches the first two Ogg pages in [head] if needed. Returns how many leading bytes changed
     * (to write back), or 0 when the header is fine or isn't Ogg Opus.
     */
    internal fun patch(head: ByteArray): Int {
        fun isOggS(at: Int) = at + 27 <= head.size &&
            head[at] == 'O'.code.toByte() && head[at + 1] == 'g'.code.toByte() &&
            head[at + 2] == 'g'.code.toByte() && head[at + 3] == 'S'.code.toByte()

        fun pageLength(at: Int): Int {
            val segments = head[at + 26].toInt() and 0xFF
            var body = 0
            for (i in 0 until segments) body += head[at + 27 + i].toInt() and 0xFF
            return 27 + segments + body
        }

        fun readLe32(at: Int): Long {
            var v = 0L
            for (b in 3 downTo 0) v = (v shl 8) or (head[at + b].toLong() and 0xFF)
            return v
        }

        if (!isOggS(0)) return 0
        val page2 = pageLength(0) // page 1 is OpusHead
        if (!isOggS(page2)) return 0
        val page2End = page2 + pageLength(page2)
        if (page2End > head.size) return 0
        val packet = page2 + 27 + (head[page2 + 26].toInt() and 0xFF)
        if (String(head, packet, 8, Charsets.US_ASCII) != "OpusTags") return 0

        val vendorLength = readLe32(packet + 8).toInt()
        val countAt = packet + 12 + vendorLength
        if (vendorLength < 0 || countAt + 4 > page2End) return 0
        // Each comment needs at least 4 bytes for its length, so a larger count can't be real.
        val count = readLe32(countAt)
        val room = (page2End - countAt - 4).toLong()
        if (count * 4 <= room) return 0

        for (i in 0 until 4) head[countAt + i] = 0
        for (i in 22 until 26) head[page2 + i] = 0
        val crc = oggCrc(head, page2, page2End)
        for (i in 0 until 4) head[page2 + 22 + i] = ((crc shr (8 * i)) and 0xFF).toByte()
        return page2End
    }

    /** Ogg's CRC-32: polynomial 0x04C11DB7, initial 0, not reflected. */
    internal fun oggCrc(bytes: ByteArray, from: Int, to: Int): Long {
        var crc = 0L
        for (i in from until to) {
            crc = crc xor ((bytes[i].toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000L != 0L) (crc shl 1) xor 0x04C11DB7L else crc shl 1
                crc = crc and 0xFFFFFFFFL
            }
        }
        return crc
    }

    private const val TAG = "OpusRepair"
    private const val HEADER_BYTES = 8_192L
}
