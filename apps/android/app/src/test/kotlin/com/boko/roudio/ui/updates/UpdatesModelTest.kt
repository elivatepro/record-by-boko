package com.boko.roudio.ui.updates

import com.boko.roudio.api.models.UpdateView
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesModelTest {
    private val lagos = ZoneOffset.ofHours(1)
    private val today = LocalDate.of(2026, 10, 11)

    private fun update(
        id: Long,
        kind: String,
        read: Boolean = false,
        at: OffsetDateTime = OffsetDateTime.of(2026, 10, 11, 10, 0, 0, 0, lagos),
        action: String? = null,
        recording: UUID? = UUID(0, id),
    ) = UpdateView(id, kind, "Title", "Body.", action, recording, null, read, at)

    @Test
    fun `only unread failures need the owner`() {
        assertTrue(UpdatesModel.needsYou(update(1, "backup_failed")))
        assertTrue(UpdatesModel.needsYou(update(2, "cleanup_partial")))
        assertFalse(UpdatesModel.needsYou(update(3, "backup_failed", read = true)))
        assertFalse(UpdatesModel.needsYou(update(4, "written_up")))
    }

    @Test
    fun `sections put unread failures first, then each day`() {
        val updates = listOf(
            update(1, "written_up"),
            update(2, "backup_failed"),
            update(3, "processing_failed", read = true, at = OffsetDateTime.of(2026, 10, 10, 9, 0, 0, 0, lagos)),
            // 23:30 UTC on the 9th is already the 10th in Lagos.
            update(4, "written_up", read = true, at = OffsetDateTime.of(2026, 10, 9, 23, 30, 0, 0, ZoneOffset.UTC)),
            update(5, "written_up", read = true, at = OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, lagos)),
        )
        val sections = UpdatesModel.sections(updates, today, lagos)
        assertEquals(
            listOf(UpdateSection.NeedsYou, UpdateSection.Today, UpdateSection.Yesterday, UpdateSection.Day(LocalDate.of(2026, 10, 1))),
            sections.map { it.first },
        )
        assertEquals(listOf(2L), sections[0].second.map { it.id })
        assertEquals(listOf(3L, 4L), sections[2].second.map { it.id })
        assertTrue(UpdatesModel.sections(emptyList(), today, lagos).isEmpty())
    }

    @Test
    fun `the safe sentence is split off`() {
        assertEquals(
            "Drive timed out." to "It's safe on your phone and the server, and on the recorder.",
            UpdatesModel.splitSafe("Drive timed out. It's safe on your phone and the server, and on the recorder."),
        )
        assertEquals(
            "Scribe failed. Try later." to "The audio is safe on your phone and the server.",
            UpdatesModel.splitSafe("Scribe failed. Try later. The audio is safe on your phone and the server."),
        )
        val (body, safe) = UpdatesModel.splitSafe("It's backed up and safe. Roudio will try again.")
        assertEquals("It's backed up and safe. Roudio will try again.", body)
        assertNull(safe)
        assertNull(UpdatesModel.splitSafe("Ready to read. Not backed up yet.").second)
    }

    @Test
    fun `actions follow the kind and what can be opened`() {
        assertEquals(
            listOf(UpdateAction.RetryBackup, UpdateAction.OpenRecording),
            UpdatesModel.actions(update(1, "backup_failed", action = "retry_backup"), canOpen = true),
        )
        assertEquals(emptyList<UpdateAction>(), UpdatesModel.actions(update(2, "backup_failed", action = "retry_backup", recording = null), canOpen = false))
        assertEquals(listOf(UpdateAction.SeeCleanup), UpdatesModel.actions(update(3, "cleanup_partial"), canOpen = false))
        // Writing up has no retry endpoint yet, so its "retry" only offers the recording.
        assertEquals(listOf(UpdateAction.OpenRecording), UpdatesModel.actions(update(4, "processing_failed", action = "retry"), canOpen = true))
    }
}
