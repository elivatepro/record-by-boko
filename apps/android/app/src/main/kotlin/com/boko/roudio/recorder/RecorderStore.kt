package com.boko.roudio.recorder

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Keeps the linked recorder and its last-seen values across restarts (13b, the Today chip). */
interface RecorderStore {
    fun load(): RecorderState

    fun save(state: RecorderState)
}

class PrefsRecorderStore(context: Context) : RecorderStore {
    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)

    override fun load(): RecorderState {
        val saved = prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString<Saved>(it) }.getOrNull() }
            ?: return RecorderState()
        return saved.toState()
    }

    override fun save(state: RecorderState) {
        val saved = Saved.from(state)
        prefs.edit {
            if (saved == null) remove(KEY) else putString(KEY, json.encodeToString(saved))
        }
    }

    /** Only what's still true after a restart. Connection and recording state never are. */
    @Serializable
    private data class Saved(
        val serial: String,
        val lastSeenMs: Long? = null,
        val batteryPercent: Int? = null,
        val batteryCharging: Boolean? = null,
        val batteryAtMs: Long? = null,
        val storageTotal: Long? = null,
        val storageFree: Long? = null,
        val storageAtMs: Long? = null,
    ) {
        fun toState() = RecorderState(
            linked = LinkedRecorder(serial, RecorderModel.fromSerial(serial)),
            lastSeen = lastSeenMs?.let(Instant::ofEpochMilli),
            battery = if (batteryPercent != null && batteryAtMs != null) {
                Seen(BatteryReading(batteryPercent, batteryCharging), Instant.ofEpochMilli(batteryAtMs))
            } else {
                null
            },
            storage = if (storageTotal != null && storageFree != null && storageAtMs != null) {
                Seen(StorageReading(storageTotal, storageFree), Instant.ofEpochMilli(storageAtMs))
            } else {
                null
            },
        )

        companion object {
            fun from(state: RecorderState): Saved? {
                val linked = state.linked ?: return null
                return Saved(
                    serial = linked.serial,
                    lastSeenMs = state.lastSeen?.toEpochMilli(),
                    batteryPercent = state.battery?.value?.percent,
                    batteryCharging = state.battery?.value?.charging,
                    batteryAtMs = state.battery?.at?.toEpochMilli(),
                    storageTotal = state.storage?.value?.totalBytes,
                    storageFree = state.storage?.value?.freeBytes,
                    storageAtMs = state.storage?.at?.toEpochMilli(),
                )
            }
        }
    }

    private companion object {
        const val KEY = "linked_recorder"
        val json = Json { ignoreUnknownKeys = true }
    }
}
