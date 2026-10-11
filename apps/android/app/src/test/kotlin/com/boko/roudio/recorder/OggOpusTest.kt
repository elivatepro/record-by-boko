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

    private fun streamPage(serial: Int, body: ByteArray): ByteArray = page(0L, body).also {
        for (i in 0 until 4) it[14 + i] = (serial shr (8 * i)).toByte()
    }

    @Test
    fun `playback copy keeps only the Opus stream of a Plaud file`() {
        val opus = streamPage(0x3E9, "OpusHead".toByteArray() + ByteArray(11)) + streamPage(0x3E9, "OpusTags".toByteArray()) +
            streamPage(0x3E9, byteArrayOf(0xB8.toByte(), 1, 2))
        val plaud = streamPage(0x3EA, "PLAUD.AI".toByteArray()) + streamPage(0x3EA, ByteArray(23))
        val source = File(folder.root, "rec.opus").apply {
            writeBytes(opus.copyOfRange(0, 47) + plaud.copyOfRange(0, 36) + opus.copyOfRange(47, opus.size) + plaud.copyOfRange(36, plaud.size))
        }
        val cache = folder.newFolder("cache")
        val played = OggOpus.audioOnly(source, cache)
        assertEquals(opus.toList(), played.readBytes().toList())
    }

    @Test
    fun `a file with one stream plays as it is`() {
        val source = File(folder.root, "one.opus").apply { writeBytes(streamPage(7, "OpusHead".toByteArray() + ByteArray(11))) }
        assertEquals(source, OggOpus.audioOnly(source, folder.newFolder("c")))
    }
}
