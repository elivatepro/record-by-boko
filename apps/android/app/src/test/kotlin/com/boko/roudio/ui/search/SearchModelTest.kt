package com.boko.roudio.ui.search

import com.boko.roudio.api.models.ConversationSummary
import com.boko.roudio.api.models.SearchHit
import com.boko.roudio.api.models.SearchResult
import com.boko.roudio.store.ConversationEntity
import com.boko.roudio.store.SearchEntry
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchModelTest {
    private val lagos = ZoneOffset.ofHours(1)
    private val now = Instant.parse("2026-10-14T13:32:00Z") // Wednesday 14:32 in Lagos

    @Test
    fun `fts query quotes each word as a prefix and drops everything else`() {
        assertEquals("\"provider*\" \"shortlist*\"", SearchModel.ftsQuery("Provider  shortlist"))
        assertEquals("\"o*\" \"shea*\"", SearchModel.ftsQuery("O'Shea"))
        assertEquals("\"near*\" \"kumasi*\" \"or*\"", SearchModel.ftsQuery("\"NEAR\" kumasi* OR -"))
        assertEquals("\"kumasi*\"", SearchModel.ftsQuery("Kumasi kumasi"))
        assertNull(SearchModel.ftsQuery("  \"*()- "))
    }

    @Test
    fun `fts query keeps letters beyond ascii and caps the number of words`() {
        assertEquals("\"ọjà*\"", SearchModel.ftsQuery("Ọjà"))
        assertEquals(8, SearchModel.terms("a b c d e f g h i j").size)
    }

    @Test
    fun `snippet spans split out matches and join neighbouring ones`() {
        assertEquals(
            listOf(
                SnippetSpan("Agreed a ", false),
                SnippetSpan("provider shortlist", true),
                SnippetSpan(" of three.", false),
            ),
            SearchModel.spans("Agreed a [[provider]] [[shortlist]] of three."),
        )
        assertEquals(
            listOf(SnippetSpan("Kumasi", true), SnippetSpan(", then ", false), SnippetSpan("Accra", true)),
            SearchModel.spans("[[Kumasi]], then [[Accra]]"),
        )
        assertEquals(listOf(SnippetSpan("no match here", false)), SearchModel.spans("no match here"))
    }

    @Test
    fun `local snippet marks words starting with a term and keeps the window around the first`() {
        assertEquals(
            "Can we keep the [[provider]] [[shortlist]] to three?",
            SearchModel.localSnippet("Can we keep the provider shortlist to three?", listOf("provid", "short")),
        )
        val long = (1..40).joinToString(" ") { "w$it" } + " Kumasi trip"
        val snippet = SearchModel.localSnippet(long, listOf("kumasi"), maxWords = 10)
        assertEquals("… w33 w34 w35 w36 w37 w38 w39 w40 [[Kumasi]] trip", snippet)
        assertEquals("“[[Book]] it.”", SearchModel.localSnippet("“Book it.”", listOf("book")))
    }

    @Test
    fun `local results group by conversation, newest first, summaries before transcript lines`() {
        val older = conversation("a", "Breakfast planning", "2026-10-14T07:05:00Z")
        val newer = conversation("b", "Studio call", "2026-10-14T08:30:00Z")
        val entries = listOf(
            SearchEntry("a", "transcript", "the provider list", 5_000),
            SearchEntry("b", "transcript", "keep the provider shortlist", 582_000),
            SearchEntry("b", "summary", "Studio call — Agreed a provider shortlist", 0),
            SearchEntry("b", "transcript", "provider again", 100_000),
            SearchEntry("gone", "note", "provider", 0),
        )
        val groups = SearchModel.fromLocal(entries, mapOf("a" to older, "b" to newer), listOf("provider"))
        assertEquals(listOf("b", "a"), groups.map { it.conversationId })
        assertEquals(listOf(MatchKind.Summary, MatchKind.Transcript, MatchKind.Transcript), groups[0].hits.map { it.kind })
        assertEquals(listOf(0L, 100_000L, 582_000L), groups[0].hits.map { it.atMs })
        assertEquals("the [[provider]] list", groups[1].hits.single().snippet)
    }

    @Test
    fun `server results keep their order and map where`() {
        val result = SearchResult(
            conversation = ConversationSummary(
                id = UUID(0, 7),
                recordingId = UUID(0, 1),
                day = LocalDate.of(2026, 10, 14),
                kind = "conversation",
                title = "Studio call",
                summary = "",
                startedAt = OffsetDateTime.of(2026, 10, 14, 9, 30, 0, 0, lagos),
                endedAt = OffsetDateTime.of(2026, 10, 14, 10, 30, 0, 0, lagos),
                startMs = 60_000,
                endMs = 3_660_000,
            ),
            hits = listOf(SearchHit(SearchHit.Where.NOTE, "[[x]]", 60_000), SearchHit(SearchHit.Where.TRANSCRIPT, "[[x]] y", 90_000, 3)),
        )
        val group = SearchModel.fromServer(listOf(result)).single()
        assertEquals(UUID(0, 7).toString(), group.conversationId)
        assertEquals(Instant.parse("2026-10-14T08:30:00Z"), group.startedAt)
        assertEquals(listOf(MatchKind.Note, MatchKind.Transcript), group.hits.map { it.kind })
    }

    @Test
    fun `filters by kind and by time, dropping cards left empty`() {
        val monday = group("mon", "2026-10-12T06:00:00Z", MatchKind.Transcript, MatchKind.Note)
        val sunday = group("sun", "2026-10-11T20:00:00Z", MatchKind.Summary)
        val today = group("today", "2026-10-14T06:00:00Z", MatchKind.Transcript)
        val all = listOf(today, monday, sunday)

        assertEquals(all, SearchModel.filter(all, emptySet(), TimeFilter.AnyTime, now, lagos))
        assertEquals(listOf("today", "mon"), SearchModel.filter(all, emptySet(), TimeFilter.ThisWeek, now, lagos).map { it.conversationId })
        assertEquals(listOf("today"), SearchModel.filter(all, emptySet(), TimeFilter.Today, now, lagos).map { it.conversationId })
        val notes = SearchModel.filter(all, setOf(MatchKind.Note), TimeFilter.AnyTime, now, lagos)
        assertEquals(listOf("mon"), notes.map { it.conversationId })
        assertEquals(listOf(MatchKind.Note), notes.single().hits.map { it.kind })
    }

    @Test
    fun `time filter cycles`() {
        assertEquals(TimeFilter.ThisWeek, TimeFilter.AnyTime.next())
        assertEquals(TimeFilter.Today, TimeFilter.ThisWeek.next())
        assertEquals(TimeFilter.AnyTime, TimeFilter.Today.next())
    }

    @Test
    fun `fewer words drops the last one`() {
        assertEquals("catering", SearchModel.fewerWords("catering quote"))
        assertEquals("early bus to", SearchModel.fewerWords(" early  bus to Kumasi "))
        assertNull(SearchModel.fewerWords("catering"))
    }

    @Test
    fun `recent searches move to the front without repeats`() {
        assertEquals(listOf("Kumasi", "provider"), SearchModel.addRecent(listOf("provider", "kumasi"), " Kumasi "))
        assertEquals(listOf("a", "b"), SearchModel.addRecent(listOf("a", "b"), "  "))
        assertEquals(listOf("f", "a", "b", "c", "d"), SearchModel.addRecent(listOf("a", "b", "c", "d", "e"), "f"))
    }

    @Test
    fun `positions read as m-ss or h-mm-ss`() {
        assertEquals("0:00", positionText(0))
        assertEquals("9:42", positionText(582_000))
        assertEquals("38:05", positionText(2_285_999))
        assertEquals("1:02:05", positionText(3_725_000))
        assertEquals("0:00", positionText(-5))
    }

    private fun conversation(id: String, title: String, start: String) = ConversationEntity(
        id = id,
        serverRecordingId = "r",
        serial = "s",
        sessionId = 1,
        day = "2026-10-14",
        kind = "conversation",
        title = title,
        summary = "",
        startedAt = Instant.parse(start),
        endedAt = Instant.parse(start).plusSeconds(600),
        startMs = 0,
        endMs = 600_000,
        resultsVersion = 1,
    )

    private fun group(id: String, start: String, vararg kinds: MatchKind) =
        ResultGroup(id, id, Instant.parse(start), 0, kinds.map { Hit(it, "[[x]]", 0) })
}
