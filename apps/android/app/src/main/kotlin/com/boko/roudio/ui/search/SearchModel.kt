package com.boko.roudio.ui.search

import com.boko.roudio.api.models.SearchHit
import com.boko.roudio.api.models.SearchResult
import com.boko.roudio.store.ConversationEntity
import com.boko.roudio.store.SearchEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Where a match was found (05b labels). */
enum class MatchKind { Summary, Note, Transcript }

/** One match. [snippet] wraps the matched words in `[[ ]]`, as the server does. */
data class Hit(val kind: MatchKind, val snippet: String, val atMs: Long)

/** The matches in one conversation (a 05b card). [startMs] is where it starts in its recording. */
data class ResultGroup(
    val conversationId: String,
    val title: String,
    val startedAt: Instant,
    val startMs: Long,
    val hits: List<Hit>,
)

/** The time chip on 05. Tapping it moves to the next one. */
enum class TimeFilter {
    AnyTime, ThisWeek, Today;

    fun next(): TimeFilter = entries[(ordinal + 1) % entries.size]
}

/** A piece of a snippet: plain text, or a match to highlight. */
data class SnippetSpan(val text: String, val match: Boolean)

/**
 * Search's logic, kept out of the UI so it can be tested: the offline FTS query, snippets and
 * highlights, grouping and the filters (spec §7, milestone 5).
 */
object SearchModel {
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
    private val MARK = Regex("\\[\\[(.*?)]]")
    private const val MAX_TERMS = 8
    private const val HITS_PER_GROUP = 5
    private const val LEAD_WORDS = 6

    /** The words searched for: letters and digits only, lower case, no repeats. */
    fun terms(input: String): List<String> =
        WORD.findAll(input.lowercase()).map { it.value }.distinct().take(MAX_TERMS).toList()

    /**
     * An FTS4 MATCH expression for the phone's index: every word must appear, each as a prefix
     * (`"provid*" "short*"`). Only letters and digits get through, so the user can't write FTS
     * syntax by accident. Null when there's nothing to search for.
     */
    fun ftsQuery(input: String): String? =
        terms(input).takeIf { it.isNotEmpty() }?.joinToString(" ") { "\"$it*\"" }

    /** Splits a `[[ ]]` snippet into spans; matches separated only by spaces become one highlight. */
    fun spans(snippet: String): List<SnippetSpan> {
        val raw = mutableListOf<SnippetSpan>()
        var at = 0
        for (m in MARK.findAll(snippet)) {
            if (m.range.first > at) raw += SnippetSpan(snippet.substring(at, m.range.first), false)
            raw += SnippetSpan(m.groupValues[1], true)
            at = m.range.last + 1
        }
        if (at < snippet.length) raw += SnippetSpan(snippet.substring(at), false)
        val merged = mutableListOf<SnippetSpan>()
        raw.forEachIndexed { i, span ->
            val last = merged.lastOrNull()
            val joinsMatches = !span.match && span.text.isBlank() && raw.getOrNull(i + 1)?.match == true
            if (last?.match == true && (span.match || joinsMatches)) {
                merged[merged.lastIndex] = SnippetSpan(last.text + span.text, true)
            } else {
                merged += span
            }
        }
        return merged
    }

    /**
     * A snippet for an offline match, like the server's: the words around the first match,
     * up to [maxWords], with every word that starts with a search term wrapped in `[[ ]]`.
     */
    fun localSnippet(text: String, terms: List<String>, maxWords: Int = 22): String {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val marked = words.map { word ->
            WORD.replace(word) { m -> if (terms.any { m.value.lowercase().startsWith(it) }) "[[${m.value}]]" else m.value }
        }
        val first = marked.indexOfFirst { it.contains("[[") }.coerceAtLeast(0)
        val start = (first - LEAD_WORDS).coerceIn(0, maxOf(0, words.size - maxWords))
        val end = minOf(words.size, start + maxWords)
        return buildString {
            if (start > 0) append("… ")
            append(marked.subList(start, end).joinToString(" "))
            if (end < words.size) append(" …")
        }
    }

    /** The server's results, as cards. It already groups and sorts them (newest first). */
    fun fromServer(results: List<SearchResult>): List<ResultGroup> = results.map { result ->
        ResultGroup(
            conversationId = result.conversation.id.toString(),
            title = result.conversation.title,
            startedAt = result.conversation.startedAt.toInstant(),
            startMs = result.conversation.startMs,
            hits = result.hits.map { Hit(kindOf(it.where), it.snippet, it.atMs) },
        )
    }

    /**
     * Offline matches from the phone's index, grouped by conversation, newest first. Summary
     * and note matches come before transcript lines, which stay in order; at most five a card.
     * Matches in conversations no longer cached are dropped.
     */
    fun fromLocal(entries: List<SearchEntry>, conversations: Map<String, ConversationEntity>, terms: List<String>): List<ResultGroup> =
        entries.groupBy { it.conversationId }.mapNotNull { (id, matches) ->
            val conversation = conversations[id] ?: return@mapNotNull null
            ResultGroup(
                conversationId = id,
                title = conversation.title,
                startedAt = conversation.startedAt,
                startMs = conversation.startMs,
                hits = matches
                    .map { Hit(localKind(it.kind), localSnippet(it.text, terms), it.atMs) }
                    .sortedWith(compareBy({ it.kind.ordinal }, { it.atMs }))
                    .take(HITS_PER_GROUP),
            )
        }.sortedByDescending { it.startedAt }

    /**
     * The chips on 05: [kinds] empty means every kind; a card stays only if it still has a match.
     * "This week" starts on Monday (design-decisions.md).
     */
    fun filter(groups: List<ResultGroup>, kinds: Set<MatchKind>, time: TimeFilter, now: Instant, zone: ZoneId): List<ResultGroup> {
        val today = now.atZone(zone).toLocalDate()
        val since = when (time) {
            TimeFilter.AnyTime -> null
            TimeFilter.ThisWeek -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant()
            TimeFilter.Today -> today.atStartOfDay(zone).toInstant()
        }
        return groups.mapNotNull { group ->
            if (since != null && group.startedAt.isBefore(since)) return@mapNotNull null
            val hits = if (kinds.isEmpty()) group.hits else group.hits.filter { it.kind in kinds }
            if (hits.isEmpty()) null else group.copy(hits = hits)
        }
    }

    /** S4 "Search “catering” instead": the query without its last word; null for a single word. */
    fun fewerWords(query: String): String? {
        val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        return if (words.size < 2) null else words.dropLast(1).joinToString(" ")
    }

    /** Recent searches with [query] moved to the front, case-insensitively unique, at most [max]. */
    fun addRecent(recent: List<String>, query: String, max: Int = 5): List<String> {
        val clean = query.trim()
        if (clean.isEmpty()) return recent
        return (listOf(clean) + recent.filterNot { it.equals(clean, ignoreCase = true) }).take(max)
    }

    private fun kindOf(where: SearchHit.Where): MatchKind = when (where) {
        SearchHit.Where.TRANSCRIPT -> MatchKind.Transcript
        SearchHit.Where.SUMMARY -> MatchKind.Summary
        SearchHit.Where.NOTE -> MatchKind.Note
    }

    private fun localKind(kind: String): MatchKind = when (kind) {
        "summary" -> MatchKind.Summary
        "note" -> MatchKind.Note
        else -> MatchKind.Transcript
    }
}

/** A position in a recording, as the source chips show it: "9:42", "1:02:05". */
fun positionText(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
