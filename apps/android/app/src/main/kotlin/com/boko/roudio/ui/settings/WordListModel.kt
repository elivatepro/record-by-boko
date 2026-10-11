package com.boko.roudio.ui.settings

import com.boko.roudio.api.models.WordView

/** 15b's two groups: learned words waiting for a yes or no, and the list sent to Scribe. */
data class WordLists(val toConfirm: List<WordView>, val list: List<WordView>) {
    val learned: Int get() = list.count { it.source == WordView.Source.LEARNED }
}

/** Word-list logic, apart from the UI. */
object WordListModel {
    /** The server's limit on a term. */
    const val MAX_LENGTH = 49
    private val SPACES = Regex("\\s+")

    /** Learned and unconfirmed words wait for the owner; everything confirmed is the list, A to Z. */
    fun partition(words: List<WordView>): WordLists = WordLists(
        toConfirm = words.filter { !it.confirmed },
        list = words.filter { it.confirmed }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.term }),
    )

    /** What the field would add: trimmed, single-spaced; null when empty or too long. */
    fun clean(input: String): String? {
        val term = input.trim().replace(SPACES, " ")
        return term.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }
}
