package dev.ewoxej.gallerylens.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/** Share one or more images via the system chooser. */
fun shareImages(context: Context, uris: List<String>) {
    if (uris.isEmpty()) return
    val parsed = ArrayList(uris.map { Uri.parse(it) })
    val intent = if (parsed.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, parsed[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, parsed)
    }
    intent.type = "image/*"
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

/** Put one or more image URIs on the clipboard (apps that accept images can paste). */
fun copyImages(context: Context, uris: List<String>) {
    if (uris.isEmpty()) return
    val clip = ClipData.newUri(context.contentResolver, "image", Uri.parse(uris[0]))
    for (i in 1 until uris.size) clip.addItem(ClipData.Item(Uri.parse(uris[i])))
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** Best-effort real file path for a MediaStore image (DATA, else RELATIVE_PATH/name). */
fun filePath(context: Context, uri: String): String {
    val projection = arrayOf(
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME,
    )
    runCatching {
        context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                fun col(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }
                val data = col(MediaStore.MediaColumns.DATA)
                if (!data.isNullOrBlank()) return data
                val joined = (col(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty() +
                    col(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()).trim()
                if (joined.isNotEmpty()) return joined
            }
        }
    }
    return uri
}
