package dev.ewoxej.gallerylens.work

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Writes the recognized text into a photo's EXIF metadata so other gallery apps
 * can read it. Stamps two widely-read description fields: ImageDescription and
 * UserComment.
 *
 * Editing photos the app doesn't own requires "All files access"
 * (MANAGE_EXTERNAL_STORAGE) under scoped storage, so this only does anything once
 * the user has turned the feature on and granted that permission.
 */
object MetadataWriter {

    private const val TAG = "MetadataWriter"

    /** True once the user has granted the broad file access the writes need. */
    fun canWrite(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true // API 26-29: covered by WRITE_EXTERNAL_STORAGE granted at runtime

    sealed interface Result {
        object Written : Result
        /** Nothing more to try for this photo (unsupported format, missing file). */
        object Skip : Result
    }

    /**
     * Stamp [text] into [uriString]'s EXIF. Returns [Result.Written] on success and
     * [Result.Skip] for files we can't stamp (so the caller marks them done either
     * way and doesn't loop forever).
     */
    fun write(context: Context, uriString: String, text: String): Result {
        val path = resolvePath(context, uriString)
        if (path == null) {
            Log.w(TAG, "no file path for $uriString")
            return Result.Skip
        }
        val file = File(path)
        if (!file.exists() || !file.canWrite()) {
            Log.w(TAG, "not writable: $path")
            return Result.Skip
        }
        return try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, text)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, text)
            exif.saveAttributes()
            // Let MediaStore and other apps pick up the refreshed metadata.
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(path), null, null) }
            Result.Written
        } catch (e: Exception) {
            // ExifInterface throws for formats it can't rewrite (e.g. some PNGs/HEICs).
            Log.w(TAG, "exif write failed for $path", e)
            Result.Skip
        }
    }

    /** Resolve a MediaStore content:// uri to its on-disk path (the DATA column). */
    private fun resolvePath(context: Context, uriString: String): String? =
        try {
            context.contentResolver.query(
                Uri.parse(uriString),
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "path lookup failed for $uriString", e)
            null
        }
}
