package com.boko.roudio.ui.search

import android.content.Context
import androidx.core.content.edit

/** The last few searches (05 "Recent"), kept on this phone only. */
class RecentSearches(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<String> = prefs.getString(KEY, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    fun add(query: String): List<String> = save(SearchModel.addRecent(load(), query))

    fun clear(): List<String> = save(emptyList())

    private fun save(list: List<String>): List<String> {
        prefs.edit { putString(KEY, list.joinToString(SEPARATOR)) }
        return list
    }

    private companion object {
        const val PREFS = "search"
        const val KEY = "recent"
        const val SEPARATOR = "\n"
    }
}
