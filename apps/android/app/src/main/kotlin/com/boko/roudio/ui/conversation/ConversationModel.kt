package com.boko.roudio.ui.conversation

import com.boko.roudio.api.models.ConversationDetail
import com.boko.roudio.api.models.ItemView
import com.boko.roudio.api.models.SegmentView

/** A chapter chip on the transcript: a key moment's time and a few of its words. */
data class Chapter(val atMs: Long, val label: String)

/** The rules behind the Recording screen, kept out of the UI so they can be tested. */
object ConversationModel {
    /** Key moments become chapters, in time order, labelled with their first three words. */
    fun chapters(detail: ConversationDetail): List<Chapter> =
        detail.items
            .filter { it.kind == ItemView.Kind.KEY_MOMENT }
            .sortedBy { it.atMs }
            .map { Chapter(it.atMs, it.text.split(' ').take(3).joinToString(" ").trimEnd(',', '.', ':')) }

    /** The owner's name for a voice, else "Speaker 1", "Speaker 2"… in the order they speak. */
    fun speakerNames(detail: ConversationDetail): Map<String, String> {
        val order = detail.segments.map { it.speaker }.distinct()
        val named = detail.speakers.associate { it.label to it.name }
        return order.mapIndexed { i, label -> label to (named[label] ?: "Speaker ${i + 1}") }.toMap()
    }

    /** The segment being played at [positionMs] (an offset into the original file). */
    fun playing(segments: List<SegmentView>, positionMs: Long): SegmentView? =
        segments.lastOrNull { it.startMs <= positionMs }?.takeIf { positionMs <= it.endMs + 1500 }

    /** The chapter at [positionMs], for the player's "of 44:12 · Providers". */
    fun chapterAt(chapters: List<Chapter>, positionMs: Long): Chapter? = chapters.lastOrNull { it.atMs <= positionMs }

    /** Times on screen count from the start of the conversation: "9:42", "1:04:05". */
    fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The start time of the segment with index [idx], if it's in this conversation. */
    fun sourceTime(detail: ConversationDetail, idx: Int): Long? = detail.segments.firstOrNull { it.idx == idx }?.startMs
}
