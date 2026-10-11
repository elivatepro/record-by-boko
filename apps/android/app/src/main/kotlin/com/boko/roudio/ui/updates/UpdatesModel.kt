package com.boko.roudio.ui.updates

import com.boko.roudio.api.models.UpdateView
import java.time.LocalDate
import java.time.ZoneId

/** What a group of updates is headed with on 03. */
sealed interface UpdateSection {
    data object NeedsYou : UpdateSection

    data object Today : UpdateSection

    data object Yesterday : UpdateSection

    data class Day(val day: LocalDate) : UpdateSection
}

/** A button under an update. */
enum class UpdateAction { RetryBackup, SeeCleanup, OpenRecording }

/** Updates' logic, apart from the UI: what needs the owner, sections, actions and the safe line. */
object UpdatesModel {
    /** Kinds the backend raises when something stopped and the owner can help (spec §10). */
    private val NEEDS_YOU = setOf("backup_failed", "processing_failed", "cleanup_partial")
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    /** An unread failure. Once read it moves down into the day it happened. */
    fun needsYou(update: UpdateView): Boolean = update.kind in NEEDS_YOU && !update.read

    /** Unread failures first, then everything else by day, newest first (as the server sends them). */
    fun sections(updates: List<UpdateView>, today: LocalDate, zone: ZoneId): List<Pair<UpdateSection, List<UpdateView>>> {
        val (urgent, rest) = updates.partition(::needsYou)
        val byDay = rest.groupBy { it.createdAt.atZoneSameInstant(zone).toLocalDate() }.map { (day, list) ->
            val section = when (day) {
                today -> UpdateSection.Today
                today.minusDays(1) -> UpdateSection.Yesterday
                else -> UpdateSection.Day(day)
            }
            section to list
        }
        return (if (urgent.isEmpty()) emptyList() else listOf(UpdateSection.NeedsYou to urgent)) + byDay
    }

    /**
     * Splits off the sentence that says what's still safe, so it gets its shield line (03):
     * "Drive timed out. It's safe on your phone…" → ("Drive timed out.", "It's safe on your phone…").
     * The message stays whole when it's only one sentence or says nothing about safety.
     */
    fun splitSafe(body: String): Pair<String, String?> {
        val sentences = body.trim().split(SENTENCE_END)
        val at = sentences.indexOfFirst { it.contains("safe", ignoreCase = true) }
        if (at <= 0) return body.trim() to null
        return sentences.take(at).joinToString(" ") to sentences.drop(at).joinToString(" ")
    }

    /** The buttons an update offers. Opening needs a conversation to open; [canOpen] says if there is one. */
    fun actions(update: UpdateView, canOpen: Boolean): List<UpdateAction> = buildList {
        if (update.action == "retry_backup" && update.recordingId != null) add(UpdateAction.RetryBackup)
        if (canOpen) add(UpdateAction.OpenRecording)
        if (update.kind == "cleanup_partial") add(UpdateAction.SeeCleanup)
    }
}
