package com.boko.roudio.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Instant

/**
 * An answered call the phone noticed (milestone 8). Kept so the recording that covers it can be
 * titled "Call with Ada · WhatsApp · 12 min". No phone numbers are stored, only the name a contact
 * or WhatsApp showed, and only when "Who's calling" or WhatsApp access is allowed.
 */
@Entity(tableName = "calls", indices = [Index("startedAt")])
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "Phone", "WhatsApp" or "Other" (see `CallApp`). */
    val app: String,
    /** "Incoming", "Outgoing" or "Unknown". */
    val direction: String,
    val name: String?,
    val startedAt: Instant,
    val answeredAt: Instant,
    val endedAt: Instant,
    /** Roudio started the recording for it (automatic start). */
    val autoStarted: Boolean,
)

@Dao
interface CallDao {
    @Insert
    suspend fun insert(call: CallEntity): Long

    @Query("DELETE FROM calls WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM calls WHERE endedAt >= :since ORDER BY startedAt")
    suspend fun since(since: Instant): List<CallEntity>
}
