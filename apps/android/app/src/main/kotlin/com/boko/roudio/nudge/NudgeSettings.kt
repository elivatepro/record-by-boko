package com.boko.roudio.nudge

import android.content.Context
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** When the owner is usually awake. Nudges stay quiet outside it; Today's gap line only looks inside it. */
data class WakingHours(val from: LocalTime = LocalTime.of(7, 0), val until: LocalTime = LocalTime.of(23, 0)) {
    /** True inside the window; a window like 22:00–06:00 runs across midnight. */
    fun contains(time: LocalTime): Boolean =
        if (from < until) !time.isBefore(from) && time.isBefore(until) else !time.isBefore(from) || time.isBefore(until)

    /** The window as second-of-day ranges, split at midnight when it runs across it. */
    fun secondRanges(): List<IntRange> {
        val a = from.toSecondOfDay()
        val b = until.toSecondOfDay()
        return if (a < b) listOf(a until b) else listOf(a until DAY, 0 until b).filterNot { it.isEmpty() }
    }

    private companion object {
        const val DAY = 86_400
    }
}

/** Settings → Notifications (15d). Each nudge can be switched off; calls are milestone 8's last part. */
data class NudgeSettings(
    val waking: WakingHours = WakingHours(),
    /** Needs the calendar permission as well; without it the switch shows "Allow" instead. */
    val meetings: Boolean = true,
    val idle: Boolean = true,
    val idleMinutes: Int = 30,
    val battery: Boolean = true,
    /** When the recorder starts charging, and when it's full. */
    val charging: Boolean = true,
    val storage: Boolean = true,
    val callPrompts: Boolean = true,
    /** Off by default (next-features.md); turning it on the first time shows 15e. */
    val autoStartCalls: Boolean = false,
    val autoStartConsentShown: Boolean = false,
) {
    companion object {
        val IDLE_CHOICES = listOf(15, 30, 60)
    }
}

/** [NudgeSettings] kept in SharedPreferences, as a flow the service and screens watch. */
class NudgePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("nudges", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<NudgeSettings> = _settings.asStateFlow()

    fun update(change: (NudgeSettings) -> NudgeSettings) {
        val next = _settings.updateAndGet(change)
        prefs.edit()
            .putInt(WAKE_FROM, next.waking.from.toSecondOfDay() / 60)
            .putInt(WAKE_UNTIL, next.waking.until.toSecondOfDay() / 60)
            .putBoolean(MEETINGS, next.meetings)
            .putBoolean(IDLE, next.idle)
            .putInt(IDLE_MINUTES, next.idleMinutes)
            .putBoolean(BATTERY, next.battery)
            .putBoolean(CHARGING, next.charging)
            .putBoolean(STORAGE, next.storage)
            .putBoolean(CALL_PROMPTS, next.callPrompts)
            .putBoolean(AUTO_START, next.autoStartCalls)
            .putBoolean(CONSENT_SHOWN, next.autoStartConsentShown)
            .apply()
    }

    private fun load(): NudgeSettings {
        val d = NudgeSettings()
        fun time(key: String, default: LocalTime) =
            prefs.getInt(key, -1).takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) } ?: default
        return NudgeSettings(
            waking = WakingHours(time(WAKE_FROM, d.waking.from), time(WAKE_UNTIL, d.waking.until)),
            meetings = prefs.getBoolean(MEETINGS, d.meetings),
            idle = prefs.getBoolean(IDLE, d.idle),
            idleMinutes = prefs.getInt(IDLE_MINUTES, d.idleMinutes).takeIf { it in NudgeSettings.IDLE_CHOICES } ?: d.idleMinutes,
            battery = prefs.getBoolean(BATTERY, d.battery),
            charging = prefs.getBoolean(CHARGING, d.charging),
            storage = prefs.getBoolean(STORAGE, d.storage),
            callPrompts = prefs.getBoolean(CALL_PROMPTS, d.callPrompts),
            autoStartCalls = prefs.getBoolean(AUTO_START, d.autoStartCalls),
            autoStartConsentShown = prefs.getBoolean(CONSENT_SHOWN, d.autoStartConsentShown),
        )
    }

    private companion object {
        const val WAKE_FROM = "wake_from_minute"
        const val WAKE_UNTIL = "wake_until_minute"
        const val MEETINGS = "meetings"
        const val IDLE = "idle"
        const val IDLE_MINUTES = "idle_minutes"
        const val BATTERY = "battery"
        const val CHARGING = "charging"
        const val STORAGE = "storage"
        const val CALL_PROMPTS = "call_prompts"
        const val AUTO_START = "auto_start_calls"
        const val CONSENT_SHOWN = "auto_start_consent_shown"
    }
}
