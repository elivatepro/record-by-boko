package com.boko.roudio.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings → Light and dark (15). System follows the phone. */
enum class ThemeChoice { System, Light, Dark }

/** The owner's theme choice, kept on this phone. */
class ThemePreference(context: Context) {
    private val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        prefs.getString(KEY, null)?.let { saved -> ThemeChoice.entries.firstOrNull { it.name == saved } } ?: ThemeChoice.System,
    )
    val choice: StateFlow<ThemeChoice> = state.asStateFlow()

    fun set(choice: ThemeChoice) {
        prefs.edit().putString(KEY, choice.name).apply()
        state.value = choice
    }

    private companion object {
        const val KEY = "theme"
    }
}
