package dev.ewoxej.gallerylens.data

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

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

    /** OCR.space free tier: 500 requests/day per IP, and (Engine 3) 2500/month. */
    const val DAILY_LIMIT = 500
    const val MONTHLY_LIMIT = 2500

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(context: Context, value: String) {
        val trimmed = value.trim()
        val changed = trimmed != apiKey(context)
        prefs(context).edit().putString(KEY_API_KEY, trimmed).apply()
        // A new key has its own fresh daily/monthly quota — reset our counters so
        // the displayed usage reflects the key actually in use.
        if (changed) resetOcrCounters(context)
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

    // --- OCR.space request budget (daily resets on the calendar day; monthly on
    // the calendar month). Local estimate — the API returns no usage figures. ---
    private const val KEY_OCR_DAY = "ocr_day"
    private const val KEY_OCR_COUNT = "ocr_count"
    private const val KEY_OCR_MONTH = "ocr_month"
    private const val KEY_OCR_MONTH_COUNT = "ocr_month_count"

    private fun today(): String = LocalDate.now().toString()       // yyyy-MM-dd
    private fun thisMonth(): String = YearMonth.now().toString()   // yyyy-MM

    /** Requests spent today (0 once the calendar day rolls over). */
    @Synchronized
    fun ocrUsedToday(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_OCR_DAY, null) == today()) p.getInt(KEY_OCR_COUNT, 0) else 0
    }

    /** Requests spent this month (0 once the calendar month rolls over). */
    @Synchronized
    fun ocrUsedThisMonth(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_OCR_MONTH, null) == thisMonth()) p.getInt(KEY_OCR_MONTH_COUNT, 0) else 0
    }

    fun ocrRemainingToday(context: Context): Int =
        (DAILY_LIMIT - ocrUsedToday(context)).coerceAtLeast(0)

    fun ocrRemainingThisMonth(context: Context): Int =
        (MONTHLY_LIMIT - ocrUsedThisMonth(context)).coerceAtLeast(0)

    /** How many more requests we may make right now (min of the day/month budgets). */
    fun ocrRemaining(context: Context): Int =
        minOf(ocrRemainingToday(context), ocrRemainingThisMonth(context))

    /** Count one request the API actually processed against the day + month budgets. */
    @Synchronized
    fun recordOcrRequest(context: Context) {
        writeDay(context, ocrUsedToday(context) + 1)
        writeMonth(context, ocrUsedThisMonth(context) + 1)
    }

    /** The API said the daily quota is gone — burn the rest of today's budget. */
    @Synchronized
    fun markQuotaExhausted(context: Context) = writeDay(context, DAILY_LIMIT)

    @Synchronized
    fun resetOcrCounters(context: Context) {
        prefs(context).edit()
            .remove(KEY_OCR_DAY).remove(KEY_OCR_COUNT)
            .remove(KEY_OCR_MONTH).remove(KEY_OCR_MONTH_COUNT)
            .apply()
    }

    private fun writeDay(context: Context, n: Int) {
        prefs(context).edit().putString(KEY_OCR_DAY, today()).putInt(KEY_OCR_COUNT, n).apply()
    }

    private fun writeMonth(context: Context, n: Int) {
        prefs(context).edit().putString(KEY_OCR_MONTH, thisMonth()).putInt(KEY_OCR_MONTH_COUNT, n).apply()
    }
}
