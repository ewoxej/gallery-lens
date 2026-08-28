package dev.ewoxej.gallerylens.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(photos: List<PhotoEntity>): List<Long>

    @Query("SELECT * FROM photos WHERE status = 'PENDING' ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun nextPending(limit: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos")
    fun countAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE status = 'PENDING'")
    fun countPending(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE status = 'DONE'")
    fun countDone(): Flow<Int>

    @Query("SELECT MAX(mediaId) FROM photos")
    suspend fun maxMediaId(): Long?

    @Transaction
    suspend fun applyOcrResult(id: Long, text: String, blocksJson: String?, w: Int, h: Int, atMs: Long) {
        deleteFts(id)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            setResult(id, PhotoStatus.NO_TEXT, null, null, null, null, atMs)
        } else {
            setResult(id, PhotoStatus.DONE, trimmed, w, h, blocksJson, atMs)
            insertFts(PhotoFts(rowid = id, text = trimmed.replace('ё', 'е').replace('Ё', 'Е')))
        }
    }

    @Query("UPDATE photos SET status = :status, ocrText = :text, indexedAtMs = :atMs WHERE id = :id")
    suspend fun setStatus(id: Long, status: PhotoStatus, text: String?, atMs: Long?)

    @Query(
        """
        UPDATE photos SET status = :status, ocrText = :text,
            ocrWidth = :w, ocrHeight = :h, blocksJson = :blocksJson, indexedAtMs = :atMs
        WHERE id = :id
        """
    )
    suspend fun setResult(
        id: Long, status: PhotoStatus, text: String?,
        w: Int?, h: Int?, blocksJson: String?, atMs: Long?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(row: PhotoFts)

    @Query("DELETE FROM photo_fts WHERE rowid = :id")
    suspend fun deleteFts(id: Long)

    @Query(
        """
        SELECT p.* FROM photos p
        JOIN photo_fts f ON p.id = f.rowid
        WHERE photo_fts MATCH :ftsQuery
        ORDER BY p.dateTakenMs DESC
        """
    )
    suspend fun search(ftsQuery: String): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun byId(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE status = 'DONE' ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun recentWithText(limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun allByDateDesc(limit: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE status = :status")
    fun countByStatus(status: PhotoStatus): Flow<Int>

    @Query("DELETE FROM photo_fts")
    suspend fun clearFts()

    @Query(
        """
        UPDATE photos SET status = 'PENDING', ocrText = NULL,
            ocrWidth = NULL, ocrHeight = NULL, blocksJson = NULL, indexedAtMs = NULL
        """
    )
    suspend fun resetAllToPending()

    @Transaction
    suspend fun reindexAll() {
        clearFts()
        resetAllToPending()
    }
}
