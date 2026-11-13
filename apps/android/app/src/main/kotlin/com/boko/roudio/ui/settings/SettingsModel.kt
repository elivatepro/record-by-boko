package com.boko.roudio.ui.settings

import com.boko.roudio.system.PhoneSettings.Autostart

/** A background exemption that got switched off again (15, milestone 5). */
enum class BackgroundWarning { Battery, Autostart, PopUps }

/** Settings' logic, apart from the UI. */
object SettingsModel {
    /**
     * Which warning Settings shows, if any. Battery first: without it transfers stop with the
     * screen off. Autostart only counts when Android says it's off; "unknown" isn't a warning.
     * Pop-ups last: without them Bluetooth going off is only asked about in the notification.
     */
    fun backgroundWarning(batteryUnrestricted: Boolean, autostart: Autostart, canPopUp: Boolean = true): BackgroundWarning? = when {
        !batteryUnrestricted -> BackgroundWarning.Battery
        autostart == Autostart.Off -> BackgroundWarning.Autostart
        !canPopUp -> BackgroundWarning.PopUps
        else -> null
    }
}
