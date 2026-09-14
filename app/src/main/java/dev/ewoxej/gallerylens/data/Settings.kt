package dev.ewoxej.gallerylens.data

import android.content.Context
import java.time.LocalDate

/**
 * Small key-value settings store (app-private SharedPreferences). Holds the
 * user-entered OCR.space API key, the album filter, and the per-day request
 * counter that keeps us under the free tier's 500-requests/day limit. The key
 * lives only in this app's private storage and is sent only to api.ocr.space.
 */
object Settings {
    private const val PREFS = "gallery_lens_settings"
    // Kept the legacy key name so an existing key survives the upgrade.
    private const val KEY_API_KEY = "anthropic_api_key"

    /** OCR.space free tier: 500 requests per day per IP. */
    const val DAILY_LIMIT = 500

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API_KEY, value.trim()).apply()
    }

    // Album filter: the set of album (bucket) keys to index/show. null = every
    // album (the default — no filter). An empty set means "none selected".
    private const val KEY_INCLUDED_BUCKETS = "included_buckets"

    fun includedBuckets(context: Context): Set<String>? =
        // getStringSet returns a shared instance; copy it before handing it out.
        prefs(context).getStringSet(KEY_INCLUDED_BUCKETS, null)?.let { HashSet(it) }

    fun setIncludedBuckets(context: Context, buckets: Set<String>?) {
        prefs(context).edit().apply {
            if (buckets == null) remove(KEY_INCLUDED_BUCKETS)
            else putStringSet(KEY_INCLUDED_BUCKETS, buckets)
        }.apply()
    }

    // --- Daily OCR.space request budget (resets on the local calendar day) ---
    private const val KEY_OCR_DAY = "ocr_day"
    private const val KEY_OCR_COUNT = "ocr_count"

    private fun today(): String = LocalDate.now().toString() // yyyy-MM-dd

    /** Requests already spent today (0 once the calendar day rolls over). */
    @Synchronized
    fun ocrUsedToday(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_OCR_DAY, null) == today()) p.getInt(KEY_OCR_COUNT, 0) else 0
    }

    fun ocrRemainingToday(context: Context): Int =
        (DAILY_LIMIT - ocrUsedToday(context)).coerceAtLeast(0)

    /** Atomically claim one request slot for today; false when the day is spent. */
    @Synchronized
    fun tryReserveOcrSlot(context: Context): Boolean {
        val used = ocrUsedToday(context)
        if (used >= DAILY_LIMIT) return false
        writeCount(context, used + 1)
        return true
    }

    /** Give a reserved slot back (e.g. the image couldn't be decoded — no request sent). */
    @Synchronized
    fun refundOcrSlot(context: Context) {
        writeCount(context, (ocrUsedToday(context) - 1).coerceAtLeast(0))
    }

    /** The API said the daily quota is gone — burn the rest of today's budget. */
    @Synchronized
    fun markQuotaExhausted(context: Context) = writeCount(context, DAILY_LIMIT)

    private fun writeCount(context: Context, n: Int) {
        prefs(context).edit().putString(KEY_OCR_DAY, today()).putInt(KEY_OCR_COUNT, n).apply()
    }
}
