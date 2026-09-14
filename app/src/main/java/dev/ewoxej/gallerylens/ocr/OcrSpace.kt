package dev.ewoxej.gallerylens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Result of one OCR.space call. */
sealed interface OcrOutcome {
    /** Got a response — [result] carries the text (empty text ⇒ the photo has none). */
    data class Ok(val result: OcrResult) : OcrOutcome
    /** Rate-limited (1-at-a-time E552, or 60/hour E553) — wait [retryAfterSec] and retry. */
    data class Throttled(val retryAfterSec: Int) : OcrOutcome
    /** Daily 500/IP cap reached — stop for today, resume tomorrow. */
    data object DailyLimit : OcrOutcome
    /** Network/server hiccup — leave the photo pending and retry on the next run. */
    data object Transient : OcrOutcome
    /** The image itself couldn't be decoded — give up on this one photo. */
    data object Undecodable : OcrOutcome
}

/**
 * OCR via the free OCR.space HTTP API (https://ocr.space/OCRAPI). One image per
 * request; the key goes in the `apikey` header. Engine 3 with `language=auto`
 * handles the mixed Cyrillic/Latin content per image. Images are downscaled and
 * JPEG-compressed to stay under the free tier's 1 MB file limit.
 */
object OcrSpace {
    private const val TAG = "OcrSpace"
    private const val ENDPOINT = "https://api.ocr.space/parse/image"
    private const val MAX_DIM = 2000         // downscale longest side to this
    private const val TARGET_BYTES = 950_000 // stay safely under the 1 MB cap
    private const val START_QUALITY = 88
    private const val MIN_QUALITY = 40
    private const val OCR_ENGINE = "3"      // 200+ languages + per-image auto-detect
    private const val LANGUAGE = "auto"     // Engine 3 detects the language itself
    // Free-plan Engine 3 throttles are surfaced as HTTP 429 ("E552" = 1-at-a-time,
    // "E553" = 60/hour, with a `retryAfter`). Bounds for how long we back off.
    private const val DEFAULT_RETRY_SEC = 5
    private const val MIN_RETRY_SEC = 3
    private const val MAX_RETRY_SEC = 3600

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS) // uploading up to ~1 MB
            .readTimeout(60, TimeUnit.SECONDS)  // Engine 3 can be slow (default 10s is too short)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    private val JPEG = "image/jpeg".toMediaType()

    suspend fun recognize(context: Context, uri: String, apiKey: String): OcrOutcome =
        withContext(Dispatchers.IO) {
            val img = compress(context, Uri.parse(uri)) ?: return@withContext OcrOutcome.Undecodable
            val req = Request.Builder().url(ENDPOINT)
                .addHeader("apikey", apiKey)
                .post(
                    MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", "photo.jpg", img.bytes.toRequestBody(JPEG))
                        .addFormDataPart("language", LANGUAGE)
                        .addFormDataPart("OCREngine", OCR_ENGINE)
                        .addFormDataPart("isOverlayRequired", "true")
                        .addFormDataPart("scale", "true")
                        .build(),
                )
                .build()
            call(req, img.width, img.height)
        }

    private fun call(req: Request, w: Int, h: Int): OcrOutcome =
        runCatching {
            client.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                when {
                    // Daily 500/IP cap ("upto maximum … 86400 seconds", usually 403).
                    resp.code == 403 || isDailyLimit(s) -> {
                        Log.w(TAG, "daily limit HTTP ${resp.code}: ${s.take(160)}")
                        OcrOutcome.DailyLimit
                    }
                    // Throttled: E552 (1-at-a-time) or E553 (60/hour), carries retryAfter.
                    resp.code == 429 || isThrottle(s) -> {
                        val sec = retryAfterSec(resp, s)
                        Log.w(TAG, "throttled ${sec}s: ${s.take(160)}")
                        OcrOutcome.Throttled(sec)
                    }
                    !resp.isSuccessful -> {
                        Log.w(TAG, "HTTP ${resp.code}: ${s.take(160)}")
                        OcrOutcome.Transient
                    }
                    else -> parse(s, w, h)
                }
            }
        }.getOrElse { Log.w(TAG, "request failed", it); OcrOutcome.Transient }

    private fun isDailyLimit(body: String): Boolean =
        body.contains("upto maximum", true) || body.contains("86400")

    private fun isThrottle(body: String): Boolean =
        body.contains("E552") || body.contains("E553") ||
            body.contains("Rate limit exceeded", true)

    /** Seconds to wait before retrying, from the JSON `retryAfter` or `Retry-After` header. */
    private fun retryAfterSec(resp: Response, body: String): Int {
        val fromBody = runCatching { JSONObject(body).optInt("retryAfter", 0) }.getOrDefault(0)
        val fromHeader = resp.header("Retry-After")?.trim()?.toIntOrNull() ?: 0
        val sec = max(fromBody, fromHeader)
        return if (sec > 0) sec.coerceIn(MIN_RETRY_SEC, MAX_RETRY_SEC) else DEFAULT_RETRY_SEC
    }

    private fun parse(json: String, w: Int, h: Int): OcrOutcome {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return OcrOutcome.Transient
        if (obj.optBoolean("IsErroredOnProcessing")) {
            val msg = obj.optString("ErrorMessage") + " " + obj.optString("ErrorDetails")
            if (isDailyLimit(msg)) {
                Log.w(TAG, "daily limit: ${msg.take(160)}")
                return OcrOutcome.DailyLimit
            }
            // A per-image engine error (e.g. unreadable) — treat as "no text found".
            Log.w(TAG, "processing error: ${msg.take(160)}")
            return OcrOutcome.Ok(OcrResult("", "", w, h, emptyList()))
        }
        val results = obj.optJSONArray("ParsedResults")
        val sb = StringBuilder()
        val blocks = ArrayList<OcrBlock>()
        if (results != null) {
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val text = r.optString("ParsedText").trim()
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }
                val lines = r.optJSONObject("TextOverlay")?.optJSONArray("Lines") ?: continue
                for (j in 0 until lines.length()) {
                    lineBlock(lines.optJSONObject(j) ?: continue)?.let { blocks += it }
                }
            }
        }
        val full = sb.toString().trim()
        return OcrOutcome.Ok(OcrResult(text = full, searchText = full, width = w, height = h, blocks = blocks))
    }

    /** One overlay line → a block: words joined, box = union of the word boxes. */
    private fun lineBlock(line: JSONObject): OcrBlock? {
        val words = line.optJSONArray("Words") ?: return null
        if (words.length() == 0) return null
        val text = StringBuilder()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = 0
        var bottom = 0
        for (k in 0 until words.length()) {
            val wd = words.optJSONObject(k) ?: continue
            val t = wd.optString("WordText")
            if (t.isNotEmpty()) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(t)
            }
            val l = wd.optInt("Left")
            val tp = wd.optInt("Top")
            left = min(left, l)
            top = min(top, tp)
            right = max(right, l + wd.optInt("Width"))
            bottom = max(bottom, tp + wd.optInt("Height"))
        }
        if (text.isEmpty()) return null
        return OcrBlock(text.toString(), left, top, right, bottom)
    }

    // --- Preprocessing: downscale to <=2000px, JPEG, keep under ~1 MB ---
    private class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

    private fun compress(context: Context, uri: Uri): Encoded? = runCatching {
        var bmp = decode(context, uri) ?: return null
        val longest = max(bmp.width, bmp.height)
        if (longest > MAX_DIM) bmp = scaleLongestTo(bmp, MAX_DIM)

        var quality = START_QUALITY
        var out = jpeg(bmp, quality)
        // First trim quality, then shrink dimensions, until under the byte target.
        while (out.size > TARGET_BYTES && quality > MIN_QUALITY) {
            quality -= 8
            out = jpeg(bmp, quality)
        }
        while (out.size > TARGET_BYTES && max(bmp.width, bmp.height) > 800) {
            bmp = scaleLongestTo(bmp, (max(bmp.width, bmp.height) * 0.85f).toInt())
            out = jpeg(bmp, quality)
        }
        val e = Encoded(out, bmp.width, bmp.height)
        bmp.recycle()
        e
    }.getOrElse { Log.w(TAG, "compress failed", it); null }

    private fun scaleLongestTo(bmp: Bitmap, dim: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= dim) return bmp
        val w = bmp.width * dim / longest
        val h = bmp.height * dim / longest
        return Bitmap.createScaledBitmap(bmp, max(1, w), max(1, h), true)
            .also { if (it !== bmp) bmp.recycle() }
    }

    private fun jpeg(bmp: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_DIM) d.setTargetSampleSize(max(1, longest / MAX_DIM))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
}
