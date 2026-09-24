package com.arctechnology.zerofomo.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A "Share to 0 FOMO" submission queued on the device until the inbox
 *  accepts it (docs/GLOBAL_DESIGN.md §3). */
@Entity(tableName = "submissions")
data class SubmissionEntity(
    @PrimaryKey val id: String,
    val market: String,
    val kind: String,            // text | url | image
    val text: String,
    val url: String,
    val imagePath: String?,      // app-private file, deleted after upload
    val sourceHint: String,      // "WhatsApp", "Instagram", package name, ...
    val createdAtEpochMs: Long,
    val status: String,          // QUEUED | SENT | FAILED
    val attempts: Int = 0,
    val remoteId: String? = null,
    val error: String? = null,
) {
    companion object {
        const val QUEUED = "QUEUED"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
    }
}

@Dao
interface SubmissionDao {
    @Insert
    suspend fun insert(s: SubmissionEntity)

    @Query("SELECT * FROM submissions WHERE status = 'QUEUED' ORDER BY createdAtEpochMs")
    suspend fun queued(): List<SubmissionEntity>

    @Query("SELECT * FROM submissions ORDER BY createdAtEpochMs DESC LIMIT 50")
    fun recent(): Flow<List<SubmissionEntity>>

    @Query("SELECT * FROM submissions WHERE id = :id")
    suspend fun byId(id: String): SubmissionEntity?

    @Query("DELETE FROM submissions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""UPDATE submissions SET status = :status, attempts = :attempts,
              remoteId = :remoteId, error = :error WHERE id = :id""")
    suspend fun update(id: String, status: String, attempts: Int, remoteId: String?, error: String?)

    @Query("DELETE FROM submissions WHERE status = 'SENT' AND createdAtEpochMs < :beforeEpochMs")
    suspend fun purgeSentBefore(beforeEpochMs: Long)
}
