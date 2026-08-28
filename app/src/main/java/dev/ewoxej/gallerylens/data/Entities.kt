package dev.ewoxej.gallerylens.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PhotoStatus { PENDING, DONE, FAILED, NO_TEXT }

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

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "photo_fts")
data class PhotoFts(
    @PrimaryKey val rowid: Long,
    val text: String,
)
