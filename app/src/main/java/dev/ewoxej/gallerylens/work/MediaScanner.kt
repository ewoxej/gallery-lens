package dev.ewoxej.gallerylens.work

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import dev.ewoxej.gallerylens.data.PhotoDao
import dev.ewoxej.gallerylens.data.PhotoEntity

class MediaScanner(private val context: Context, private val dao: PhotoDao) {

    suspend fun scan(): Int {
        val sinceId = dao.maxMediaId() ?: 0L

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
                // DATE_TAKEN is ms; DATE_ADDED is seconds. Fall back to added when
                // taken is missing (0), which happens for non-camera images.
                val takenMs = c.getLong(takenCol).takeIf { it > 0 }
                    ?: (c.getLong(addedCol) * 1000L)
                found += PhotoEntity(
                    mediaId = mediaId,
                    uri = uri.toString(),
                    bucketName = c.getString(bucketCol),
                    dateTakenMs = takenMs,
                    dateAddedMs = c.getLong(addedCol) * 1000L,
                )
            }
        }

        if (found.isEmpty()) return 0
        val inserted = dao.insertIgnore(found)
        return inserted.count { it != -1L }
    }
}
