package com.boko.roudio.ui.review

import com.boko.roudio.api.models.ReviewChange
import com.boko.roudio.api.models.ReviewView
import java.time.LocalDate
import java.time.ZoneId

/** The three Review tabs. Accepted also keeps tasks ticked off on this visit, so Undo can bring them back. */
data class ReviewLists(val waiting: List<ReviewView>, val accepted: List<ReviewView>, val done: List<ReviewView>)

/** What happened to today's suggestions (S6 "5 accepted, 2 dismissed"). */
data class Tally(val accepted: Int, val dismissed: Int) {
    val total: Int get() = accepted + dismissed
}

/** Review's logic, apart from the UI: splitting by state, local changes and the S6 tally. */
object ReviewModel {
    /** Keeps the server's order (newest recording first, then by time in it). */
    fun split(items: List<ReviewView>, justDone: Set<Long> = emptySet()): ReviewLists = ReviewLists(
        waiting = items.filter { it.state == ReviewView.State.WAITING },
        accepted = items.filter { it.state == ReviewView.State.ACCEPTED || (it.state == ReviewView.State.DONE && it.id in justDone) },
        done = items.filter { it.state == ReviewView.State.DONE },
    )

    /**
     * [items] with one changed, before the server answers. A blank [text] goes back to the
     * suggestion's own wording, as the server does.
     */
    fun change(items: List<ReviewView>, id: Long, state: ReviewView.State?, text: String? = null): List<ReviewView> = items.map { item ->
        if (item.id != id) return@map item
        item.copy(
            state = state ?: item.state,
            text = when {
                text == null -> item.text
                text.isBlank() -> item.suggestedText
                else -> text.trim()
            },
        )
    }

    /** Suggestions dealt with today: accepted (or already done) and dismissed. */
    fun tallyToday(items: List<ReviewView>, today: LocalDate, zone: ZoneId): Tally {
        val todays = items.filter { it.updatedAt.atZoneSameInstant(zone).toLocalDate() == today }
        return Tally(
            accepted = todays.count { it.state == ReviewView.State.ACCEPTED || it.state == ReviewView.State.DONE },
            dismissed = todays.count { it.state == ReviewView.State.DISMISSED },
        )
    }

    /** True when the owner reworded it (10b "edited by you"). */
    fun edited(item: ReviewView): Boolean = item.text != item.suggestedText

    fun toChange(state: ReviewView.State): ReviewChange.State = when (state) {
        ReviewView.State.WAITING -> ReviewChange.State.WAITING
        ReviewView.State.ACCEPTED -> ReviewChange.State.ACCEPTED
        ReviewView.State.DONE -> ReviewChange.State.DONE
        ReviewView.State.DISMISSED -> ReviewChange.State.DISMISSED
    }
}
