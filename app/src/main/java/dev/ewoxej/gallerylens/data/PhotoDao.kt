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

    // All queue/gallery/count queries are scoped to included = 1: photos in
    // de-selected albums stay in the table (OCR preserved) but are invisible to
    // indexing, the gallery, search, and the stats.
    @Query("SELECT * FROM photos WHERE status = 'PENDING' AND included = 1 ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun nextPending(limit: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE included = 1")
    fun countAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE status = 'PENDING' AND included = 1")
    fun countPending(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE status = 'DONE' AND included = 1")
    fun countDone(): Flow<Int>

    @Query("SELECT MAX(mediaId) FROM photos")
    suspend fun maxMediaId(): Long?

    /** Store an OCR result: DONE with text (+ boxes), or NO_TEXT when it's blank. */
    @Transaction
    suspend fun applyOcrResult(
        id: Long,
        text: String,
        searchText: String,
        blocksJson: String?,
        w: Int,
        h: Int,
        atMs: Long,
    ) {
        deleteFts(id)
        val display = text.trim()
        val search = searchText.trim()
        val hasText = display.isNotEmpty() || search.isNotEmpty()
        if (!hasText) {
            setResult(id, PhotoStatus.NO_TEXT, null, null, null, null, atMs)
        } else {
            setResult(id, PhotoStatus.DONE, display.ifEmpty { null }, w, h, blocksJson, atMs)
            val ftsText = search.ifEmpty { display }
            insertFts(PhotoFts(rowid = id, text = ftsText.replace('ё', 'е').replace('Ё', 'Е')))
        }
    }

    // --- Album filter ---

    @Query("UPDATE photos SET included = 1")
    suspend fun includeAllAlbums()

    @Query("UPDATE photos SET included = 0")
    suspend fun excludeAllAlbums()

    @Query("UPDATE photos SET included = 1 WHERE COALESCE(bucketName, '') IN (:keys)")
    suspend fun includeBuckets(keys: List<String>)

    /**
     * Reconcile every photo's [PhotoEntity.included] flag to the selection.
     * null = all albums (clear the filter). Photos keep their status/OCR, so
     * re-including an album shows its already-recognised text instantly.
     */
    @Transaction
    suspend fun applyAlbumFilter(keys: Set<String>?) {
        if (keys == null) {
            includeAllAlbums()
        } else {
            excludeAllAlbums()
            if (keys.isNotEmpty()) includeBuckets(keys.toList())
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
        WHERE photo_fts MATCH :ftsQuery AND p.included = 1
        ORDER BY p.dateTakenMs DESC
        """
    )
    suspend fun search(ftsQuery: String): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun byId(id: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE status = 'DONE' AND included = 1 ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun recentWithText(limit: Int): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE included = 1 ORDER BY dateTakenMs DESC LIMIT :limit")
    suspend fun allByDateDesc(limit: Int): List<PhotoEntity>

    @Query("SELECT COUNT(*) FROM photos WHERE status = :status AND included = 1")
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
