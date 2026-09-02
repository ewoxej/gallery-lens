package dev.ewoxej.gallerylens.data

import android.content.Context

/**
 * Small key-value settings store (app-private SharedPreferences). Holds the
 * user-entered Anthropic API key and the cloud-OCR toggle. The key lives only in
 * this app's private storage and is sent only to api.anthropic.com when cloud
 * OCR is enabled.
 */
object Settings {
    private const val PREFS = "gallery_lens_settings"
    private const val KEY_CLOUD_ENABLED = "cloud_ocr_enabled"
    private const val KEY_CLOUD_ALWAYS = "cloud_ocr_always"
    private const val KEY_API_KEY = "anthropic_api_key"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cloudEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLOUD_ENABLED, false)

    fun setCloudEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOUD_ENABLED, value).apply()
    }

    /** When on, every photo is sent to Claude (not just weak local results). */
    fun cloudAlways(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLOUD_ALWAYS, false)

    fun setCloudAlways(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLOUD_ALWAYS, value).apply()
    }

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API_KEY, value.trim()).apply()
    }

    /** Cloud OCR is usable only when it's enabled and a key is present. */
    fun cloudReady(context: Context): Boolean =
        cloudEnabled(context) && apiKey(context).isNotBlank()

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

    // In-flight Claude batch id — persisted so a batch that hasn't finished when
    // the worker stops is resumed (polled) on the next run instead of resubmitted.
    private const val KEY_BATCH_ID = "cloud_pending_batch_id"

    fun pendingBatchId(context: Context): String? =
        prefs(context).getString(KEY_BATCH_ID, null)?.ifBlank { null }

    fun setPendingBatchId(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id.isNullOrBlank()) remove(KEY_BATCH_ID) else putString(KEY_BATCH_ID, id)
        }.apply()
    }
}
