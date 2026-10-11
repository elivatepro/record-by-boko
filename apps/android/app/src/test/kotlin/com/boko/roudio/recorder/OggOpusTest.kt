package com.boko.roudio.recorder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OggOpusTest {
    @get:Rule val folder = TemporaryFolder()

    private fun page(granule: Long, body: ByteArray): ByteArray {
        val header = ByteArray(27)
        "OggS".toByteArray().copyInto(header)
        for (i in 0 until 8) header[6 + i] = (granule shr (8 * i)).toByte()
        header[26] = 1
        return header + byteArrayOf(body.size.toByte()) + body
    }

    @Test
    fun `duration is the last granule minus the pre-skip at 48 kHz`() {
        // OpusHead with a pre-skip of 312 samples.
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 0x01, 0x80.toByte(), 0x3E, 0, 0, 0, 0, 0)
        val file = File(folder.root, "a.opus")
        file.writeBytes(page(0, head) + page(0, "OpusTags".toByteArray()) + page(48_000L * 7 + 312, ByteArray(10)))
        assertEquals(7_000L, OggOpus.durationMs(file))
    }

    @Test
    fun `a file that isn't Ogg has no duration`() {
        val file = File(folder.root, "b.mp3").apply { writeText("ID3 not ogg") }
        assertNull(OggOpus.durationMs(file))
    }
}
