package dev.ewoxej.gallerylens.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Indexing lifecycle. CLOUD_PENDING/CLOUD_SUBMITTED are the Batch-API cloud stages:
 * local OCR is done (its text is stored + searchable) but the photo is queued for /
 * in a Claude batch that will replace the text. These are just enum name strings in
 * a TEXT column, so adding them needs no DB migration.
 */
enum class PhotoStatus { PENDING, DONE, FAILED, NO_TEXT, CLOUD_PENDING, CLOUD_SUBMITTED }

@Entity(
    tableName = "photos",
    indices = [Index(value = ["mediaId"], unique = true)],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val uri: String,
    val bucketName: String?,
    val dateTakenMs: Long,
    val dateAddedMs: Long,
    val status: PhotoStatus = PhotoStatus.PENDING,
    val ocrText: String? = null,
    val ocrWidth: Int? = null,
    val ocrHeight: Int? = null,
    val blocksJson: String? = null,
    val indexedAtMs: Long? = null,
)

// unicode61 case-folds Cyrillic (the default `simple` tokenizer folds ASCII
// only). remove_diacritics=1 strips accents on BOTH the indexed text and the
// MATCH query, so search is diacritic-insensitive: "orult" finds "őrült/örült",
// "fuszer" finds "fűszer". (=1 not =2: =2 needs SQLite 3.27+, above our minSdk.)
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
)
@Entity(tableName = "photo_fts")
data class PhotoFts(
    @PrimaryKey val rowid: Long,
    val text: String,
)
