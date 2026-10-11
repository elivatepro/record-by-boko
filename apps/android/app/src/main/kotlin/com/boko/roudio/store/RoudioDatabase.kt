package com.boko.roudio.store

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [Recording::class, ConversationEntity::class, DigestEntity::class, SearchEntry::class],
    version = 3,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // upload fields
        AutoMigration(from = 2, to = 3), // server states, conversations, digests, search
    ],
)
@TypeConverters(Converters::class)
abstract class RoudioDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

    abstract fun library(): LibraryDao

    companion object {
        fun open(context: Context): RoudioDatabase =
            Room.databaseBuilder(context, RoudioDatabase::class.java, "roudio.db").build()
    }
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAt")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE transfer = :state ORDER BY startedAt")
    suspend fun inTransfer(state: TransferState): List<Recording>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(recordings: List<Recording>)

    @Update
    suspend fun update(recording: Recording)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): Recording?

    @Query("SELECT * FROM recordings WHERE serial = :serial AND sessionId = :sessionId")
    suspend fun bySession(serial: String, sessionId: Long): Recording?
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM conversations ORDER BY startedAt")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE serverRecordingId = :recordingId")
    suspend fun conversationsOf(recordingId: String): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putConversations(conversations: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE serverRecordingId = :recordingId AND id NOT IN (:keep)")
    suspend fun dropConversationsExcept(recordingId: String, keep: List<String>)

    @Query("UPDATE conversations SET detail = :detail WHERE id = :id")
    suspend fun putDetail(id: String, detail: String)

    @Query("SELECT * FROM digests")
    fun observeDigests(): Flow<List<DigestEntity>>

    @Query("SELECT * FROM digests WHERE day = :day")
    fun observeDigest(day: String): Flow<DigestEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDigest(digest: DigestEntity)

    @Query("DELETE FROM search_index WHERE conversationId = :conversationId")
    suspend fun clearSearch(conversationId: String)

    @Insert
    suspend fun putSearch(entries: List<SearchEntry>)

    /** Offline search; [query] is an FTS4 MATCH expression. */
    @Query("SELECT * FROM search_index WHERE search_index MATCH :query LIMIT 300")
    suspend fun search(query: String): List<SearchEntry>
}

class Converters {
    @TypeConverter fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
