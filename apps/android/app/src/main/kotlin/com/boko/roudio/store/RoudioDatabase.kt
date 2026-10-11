package com.boko.roudio.store

import android.content.Context
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

@Database(entities = [Recording::class], version = 1)
@TypeConverters(Converters::class)
abstract class RoudioDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

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
}

class Converters {
    @TypeConverter fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
