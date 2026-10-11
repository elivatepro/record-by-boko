package com.boko.roudio.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * One file from the recorder and where it stands on each of the four independent tracks
 * (spec §3). "Done processing" never implies "backed up": they're separate fields.
 */
@Entity(tableName = "recordings", indices = [Index(value = ["serial", "sessionId"], unique = true)])
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The recorder it came from, and Plaud's id for it there (its start in Unix seconds). */
    val serial: String,
    val sessionId: Long,
    /** Start in UTC, plus the phone's UTC offset at that moment, so days stay right across zones. */
    val startedAt: Instant,
    val zoneOffsetSeconds: Int,
    /** Size as the recorder reported it. The exported file can differ slightly. */
    val sizeOnRecorder: Long,

    /** Live progress isn't saved; see `Transfers.progress`. */
    val transfer: TransferState = TransferState.OnRecorder,
    /** Exports tried and failed; each one restarts from the beginning (decision 14). */
    val transferAttempts: Int = 0,
    /** What went wrong last, in plain words; null once it worked. */
    val transferError: String? = null,

    /** The Ogg Opus file in app storage, relative to the recordings folder. Set from [TransferState.OnPhone]. */
    val fileName: String? = null,
    val fileBytes: Long? = null,
    val sha256: String? = null,
    val durationMs: Long? = null,

    val processing: ProcessingState = ProcessingState.Waiting,
    val backup: BackupState = BackupState.Waiting,
    val cleanup: CleanupState = CleanupState.Blocked,

    val firstSeenAt: Instant,
    val updatedAt: Instant,
)

/** on recorder → transferring → on phone (checksum saved) → uploading → uploaded. */
enum class TransferState { OnRecorder, Transferring, OnPhone, Uploading, Uploaded }

enum class ProcessingState { Waiting, FindingSpeech, Transcribing, Tidying, Understanding, Done }

enum class BackupState { Waiting, Uploading, Checking, BackedUp }

/** The phone copy is never deleted automatically; this is only about the recorder (spec §8). */
enum class CleanupState { Blocked, CanCleanUp, Deleting, Cleared }
