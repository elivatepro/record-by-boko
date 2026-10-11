package com.boko.roudio.recorder

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusRepairTest {
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** One Ogg page with a correct CRC holding [packet] (under 255 bytes). */
    private fun page(sequence: Int, packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(0) // version
        out.write(if (sequence == 0) 2 else 0) // header type
        out.write(ByteArray(8)) // granule
        out.write(le32(1)) // serial
        out.write(le32(sequence.toLong()))
        out.write(ByteArray(4)) // CRC, filled below
        out.write(1)
        out.write(packet.size)
        out.write(packet)
        val bytes = out.toByteArray()
        val crc = OpusRepair.oggCrc(bytes, 0, bytes.size)
        for (i in 0 until 4) bytes[22 + i] = ((crc shr (8 * i)) and 0xFF).toByte()
        return bytes
    }

    private fun opusFile(commentCount: Long): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val vendor = "Tinnotech".toByteArray()
        val tags = "OpusTags".toByteArray() + le32(vendor.size.toLong()) + vendor + le32(commentCount) + ByteArray(20)
        return page(0, head) + page(1, tags) + page(2, ByteArray(40) { it.toByte() })
    }

    @Test
    fun `an impossible comment count is set to zero with a valid CRC`() {
        val file = opusFile(commentCount = 0x193)
        val audioBefore = file.copyOfRange(file.size - 68, file.size)
        val changed = OpusRepair.patch(file)
        assertTrue(changed > 0)

        val page2 = 28 + 19
        val countAt = page2 + 28 + 8 + 4 + 9
        assertArrayEquals(le32(0), file.copyOfRange(countAt, countAt + 4))
        val stored = (0 until 4).fold(0L) { acc, i -> acc or ((file[page2 + 22 + i].toLong() and 0xFF) shl (8 * i)) }
        val check = file.copyOf().also { for (i in 22 until 26) it[page2 + i] = 0 }
        assertEquals(OpusRepair.oggCrc(check, page2, changed), stored)
        assertArrayEquals("audio pages untouched", audioBefore, file.copyOfRange(file.size - 68, file.size))
    }

    @Test
    fun `a valid header is left alone, and repairing twice changes nothing`() {
        val good = opusFile(commentCount = 0)
        assertEquals(0, OpusRepair.patch(good.copyOf()))

        val bad = opusFile(commentCount = 0x193)
        OpusRepair.patch(bad)
        val once = bad.copyOf()
        assertEquals(0, OpusRepair.patch(bad))
        assertArrayEquals(once, bad)
    }

    @Test
    fun `files that aren't Ogg Opus are ignored`() {
        assertEquals(0, OpusRepair.patch("ID3 not an ogg file at all, just some bytes".toByteArray()))
    }
}
