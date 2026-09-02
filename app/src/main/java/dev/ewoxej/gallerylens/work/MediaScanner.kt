package dev.ewoxej.gallerylens.work

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import dev.ewoxej.gallerylens.data.PhotoDao
import dev.ewoxej.gallerylens.data.PhotoEntity
import dev.ewoxej.gallerylens.data.Settings

/** An on-device album (MediaStore bucket) with how many photos it holds. */
data class Album(val key: String, val name: String, val count: Int)

class MediaScanner(private val context: Context, private val dao: PhotoDao) {

    suspend fun scan(): Int {
        val sinceId = dao.maxMediaId() ?: 0L
        // Null = all albums (no filter). New photos in de-selected albums are
        // stored but flagged excluded so they're never shown or indexed.
        val included = Settings.includedBuckets(context)

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Images.Media._ID} > ?"
        val args = arrayOf(sinceId.toString())
        val sort = "${MediaStore.Images.Media._ID} ASC"

        val found = ArrayList<PhotoEntity>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val mediaId = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, mediaId)
                val bucket = c.getString(bucketCol)
                // DATE_TAKEN is ms; DATE_ADDED is seconds. Fall back to added when
                // taken is missing (0), which happens for non-camera images.
                val takenMs = c.getLong(takenCol).takeIf { it > 0 }
                    ?: (c.getLong(addedCol) * 1000L)
                found += PhotoEntity(
                    mediaId = mediaId,
                    uri = uri.toString(),
                    bucketName = bucket,
                    dateTakenMs = takenMs,
                    dateAddedMs = c.getLong(addedCol) * 1000L,
                    included = included == null || (bucket ?: "") in included,
                )
            }
        }

        if (found.isEmpty()) return 0
        val inserted = dao.insertIgnore(found)
        return inserted.count { it != -1L }
    }

    /** Every album on the device with its photo count, for the album-filter UI. */
    fun listAlbums(): List<Album> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val counts = LinkedHashMap<String, Int>() // key -> count, insertion order
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val key = c.getString(bucketCol) ?: ""
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries
            .map { Album(key = it.key, name = it.key.ifEmpty { UNKNOWN_ALBUM }, count = it.value) }
            .sortedByDescending { it.count }
    }

    companion object {
        // Display name for photos MediaStore reports with no bucket name.
        const val UNKNOWN_ALBUM = "Unknown"
    }
}
