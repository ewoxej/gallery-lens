package dev.ewoxej.gallerylens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import dev.ewoxej.gallerylens.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val CLOUD_WEAK = 0.6f

/**
 * Whether a local transcript should be re-read in the cloud. "always" mode sends
 * every photo; otherwise only photos whose local text is mostly non-words (garbage),
 * and never plain textless photos (they'd cost calls for nothing).
 */
fun cloudWanted(context: Context, localText: String): Boolean {
    if (!Settings.cloudReady(context)) return false
    if (Settings.cloudAlways(context)) return true
    val real = Lexicon.realFraction(localText)
    return localText.isNotBlank() && real in 0f..CLOUD_WEAK
}

data class CloudItem(val id: Long, val base64: String)

enum class BatchState { PROCESSING, ENDED, FAILED }

/**
 * Claude cloud OCR via the Anthropic **Batch API** (50% cheaper than per-request).
 * The worker submits photos as a batch (custom_id = photo id), polls, then applies
 * the transcripts. The API returns no box coordinates, so cloud-read photos have no
 * detail-screen overlay — only text/search.
 */
object CloudBatch {
    private const val TAG = "CloudBatch"
    private const val BASE = "https://api.anthropic.com/v1/messages/batches"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MODEL = "claude-haiku-4-5"
    private const val MAX_DIM = 1280
    private const val JPEG_QUALITY = 82
    private const val PROMPT =
        "Transcribe all text visible in this image exactly as written, keeping the " +
            "original languages and line order. Do not translate, explain, or add " +
            "anything. If there is no text, output nothing. Output only the text."

    private val client by lazy {
        OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
    }
    private val JSON = "application/json".toMediaType()

    /** Submit a batch; returns its id, or null on failure. */
    suspend fun submit(apiKey: String, items: List<CloudItem>): String? = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext null
        runCatching {
            val requests = JSONArray()
            for (item in items) {
                val content = JSONArray()
                    .put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject().put("type", "base64")
                                .put("media_type", "image/jpeg").put("data", item.base64),
                        ),
                    )
                    .put(JSONObject().put("type", "text").put("text", PROMPT))
                val params = JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 1024)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", content)),
                    )
                requests.put(JSONObject().put("custom_id", item.id.toString()).put("params", params))
            }
            val body = JSONObject().put("requests", requests).toString()
            client.newCall(post(apiKey, BASE, body)).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "submit HTTP ${resp.code}: ${s.take(400)}"); return@use null
                }
                JSONObject(s).optString("id").ifBlank { null }
            }
        }.onFailure { Log.w(TAG, "submit failed", it) }.getOrNull()
    }

    suspend fun pollStatus(apiKey: String, batchId: String): BatchState = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(get(apiKey, "$BASE/$batchId")).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "poll HTTP ${resp.code}: ${s.take(200)}")
                    // 404 (deleted/expired) is terminal; other codes are transient.
                    return@use if (resp.code == 404) BatchState.FAILED else BatchState.PROCESSING
                }
                if (JSONObject(s).optString("processing_status") == "ended") BatchState.ENDED
                else BatchState.PROCESSING
            }
        }.getOrDefault(BatchState.PROCESSING) // network hiccup -> retry later
    }

    /** photo id -> transcript (null when that request errored or had no text). */
    suspend fun fetchResults(apiKey: String, batchId: String): Map<Long, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val resultsUrl = client.newCall(get(apiKey, "$BASE/$batchId")).execute().use { r ->
                JSONObject(r.body?.string().orEmpty()).optString("results_url").ifBlank { null }
            } ?: return@withContext emptyMap<Long, String?>()

            val out = HashMap<Long, String?>()
            client.newCall(get(apiKey, resultsUrl)).execute().use { resp ->
                for (line in resp.body?.string().orEmpty().lineSequence()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    val obj = runCatching { JSONObject(t) }.getOrNull() ?: continue
                    val id = obj.optString("custom_id").toLongOrNull() ?: continue
                    val result = obj.optJSONObject("result")
                    out[id] = if (result?.optString("type") == "succeeded")
                        extractText(result.optJSONObject("message")) else null
                }
            }
            out
        }.onFailure { Log.w(TAG, "fetchResults failed", it) }.getOrDefault(emptyMap())
    }

    private fun extractText(message: JSONObject?): String? {
        val content = message?.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(b.optString("text"))
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** Decode a gallery image to a downscaled JPEG base64 for the batch request. */
    fun encode(context: Context, uri: String): String? = runCatching {
        val src = decode(context, Uri.parse(uri)) ?: return null
        val out = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        src.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val longest = max(info.size.width, info.size.height)
                val sample = if (longest > MAX_DIM) longest / MAX_DIM else 1
                if (sample > 1) d.setTargetSampleSize(sample)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            val full = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            val longest = max(full.width, full.height)
            if (longest <= MAX_DIM) full
            else Bitmap.createScaledBitmap(
                full,
                full.width * MAX_DIM / longest,
                full.height * MAX_DIM / longest,
                true,
            ).also { if (it !== full) full.recycle() }
        }

    private fun post(apiKey: String, url: String, body: String) = Request.Builder().url(url)
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", ANTHROPIC_VERSION)
        .addHeader("content-type", "application/json")
        .post(body.toRequestBody(JSON))
        .build()

    private fun get(apiKey: String, url: String) = Request.Builder().url(url)
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", ANTHROPIC_VERSION)
        .get()
        .build()
}
