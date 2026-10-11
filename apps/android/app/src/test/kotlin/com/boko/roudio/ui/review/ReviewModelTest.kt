package com.boko.roudio.ui.review

import com.boko.roudio.api.models.ConversationSummary
import com.boko.roudio.api.models.ReviewChange
import com.boko.roudio.api.models.ReviewView
import com.boko.roudio.api.models.ReviewView.State
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewModelTest {
    private val lagos = ZoneOffset.ofHours(1)
    private val today = LocalDate.of(2026, 10, 11)

    private fun item(id: Long, state: State, updated: OffsetDateTime = OffsetDateTime.of(2026, 10, 11, 12, 0, 0, 0, lagos)) = ReviewView(
        id = id,
        state = state,
        text = "Task $id",
        suggestedText = "Task $id",
        conversation = ConversationSummary(
            id = UUID(0, 1),
            recordingId = UUID(0, 2),
            day = today,
            kind = "conversation",
            title = "Studio call",
            summary = "",
            startedAt = OffsetDateTime.of(2026, 10, 11, 9, 30, 0, 0, lagos),
            endedAt = OffsetDateTime.of(2026, 10, 11, 10, 30, 0, 0, lagos),
            startMs = 0,
            endMs = 3_600_000,
        ),
        atMs = 2_285_000,
        sources = listOf(4),
        updatedAt = updated,
    )

    @Test
    fun `split keeps order, leaves dismissed out, and keeps just-ticked tasks on Accepted`() {
        val items = listOf(item(1, State.WAITING), item(2, State.ACCEPTED), item(3, State.DONE), item(4, State.DISMISSED), item(5, State.WAITING))
        val lists = ReviewModel.split(items)
        assertEquals(listOf(1L, 5L), lists.waiting.map { it.id })
        assertEquals(listOf(2L), lists.accepted.map { it.id })
        assertEquals(listOf(3L), lists.done.map { it.id })
        assertEquals(listOf(2L, 3L), ReviewModel.split(items, justDone = setOf(3L)).accepted.map { it.id })
    }

    @Test
    fun `change sets the state and the owner's wording`() {
        val items = listOf(item(1, State.WAITING), item(2, State.WAITING))
        val accepted = ReviewModel.change(items, 1, State.ACCEPTED, "  Export 5 recordings  ")
        assertEquals(State.ACCEPTED, accepted[0].state)
        assertEquals("Export 5 recordings", accepted[0].text)
        assertTrue(ReviewModel.edited(accepted[0]))
        assertEquals(items[1], accepted[1])

        val reset = ReviewModel.change(accepted, 1, null, " ")
        assertEquals(State.ACCEPTED, reset[0].state)
        assertEquals("Task 1", reset[0].text)
        assertFalse(ReviewModel.edited(reset[0]))
    }

    @Test
    fun `tally counts only today's decisions, done counting as accepted`() {
        val yesterday = OffsetDateTime.of(2026, 10, 10, 23, 30, 0, 0, lagos)
        val items = listOf(
            item(1, State.ACCEPTED),
            item(2, State.DONE),
            item(3, State.DISMISSED),
            item(4, State.WAITING),
            item(5, State.ACCEPTED, updated = yesterday),
        )
        val tally = ReviewModel.tallyToday(items, today, lagos)
        assertEquals(Tally(accepted = 2, dismissed = 1), tally)
        assertEquals(3, tally.total)
    }

    @Test
    fun `states map to the change request`() {
        assertEquals(ReviewChange.State.DISMISSED, ReviewModel.toChange(State.DISMISSED))
        assertEquals(ReviewChange.State.DONE, ReviewModel.toChange(State.DONE))
    }
}
