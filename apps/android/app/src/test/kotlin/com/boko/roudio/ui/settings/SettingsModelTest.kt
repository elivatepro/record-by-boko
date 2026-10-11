package com.boko.roudio.ui.settings

import com.boko.roudio.api.models.WordView
import com.boko.roudio.api.models.WordView.Source
import com.boko.roudio.system.PhoneSettings.Autostart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsModelTest {
    @Test
    fun `battery warning comes first, autostart only when it's known to be off`() {
        assertEquals(BackgroundWarning.Battery, SettingsModel.backgroundWarning(batteryUnrestricted = false, autostart = Autostart.Off))
        assertEquals(BackgroundWarning.Autostart, SettingsModel.backgroundWarning(batteryUnrestricted = true, autostart = Autostart.Off))
        assertNull(SettingsModel.backgroundWarning(batteryUnrestricted = true, autostart = Autostart.Unknown))
        assertNull(SettingsModel.backgroundWarning(batteryUnrestricted = true, autostart = Autostart.On))
    }

    @Test
    fun `words split into those to confirm and the list, A to Z`() {
        val words = listOf(
            WordView(1, "kumasi", Source.MANUAL, true),
            WordView(2, "Osei", Source.LEARNED, false),
            WordView(3, "Efua", Source.LEARNED, true),
            WordView(4, "abeg", Source.MANUAL, true),
        )
        val lists = WordListModel.partition(words)
        assertEquals(listOf("Osei"), lists.toConfirm.map { it.term })
        assertEquals(listOf("abeg", "Efua", "kumasi"), lists.list.map { it.term })
        assertEquals(1, lists.learned)
    }

    @Test
    fun `terms are trimmed, single-spaced and within the server's limit`() {
        assertEquals("Lekki Phase 1", WordListModel.clean("  Lekki   Phase 1 "))
        assertNull(WordListModel.clean("   "))
        assertNull(WordListModel.clean("x".repeat(50)))
        assertEquals("x".repeat(49), WordListModel.clean("x".repeat(49)))
    }
}
