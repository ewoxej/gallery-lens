package dev.ewoxej.gallerylens.ocr

import android.content.Context
import android.util.Log

/**
 * Bundled word lists used to tell a real transcript from OCR garbage. ML Kit
 * reads Cyrillic glyphs as Latin look-alikes ("привёз" -> "npuBes"); that garbage
 * is not made of real words, so scoring each engine's output against the right
 * dictionary lets us pick the transcript that actually means something and drop
 * the nonsense from the search index.
 *
 * Per-word script decides the language: a word with any Cyrillic letter is checked
 * against the ru+uk set, otherwise against the en+hu set — a single word almost
 * never mixes scripts.
 */
object Lexicon {
    @Volatile private var latin: Set<String>? = null
    @Volatile private var cyr: Set<String>? = null

    val ready: Boolean get() = latin != null && cyr != null

    private val WORD = Regex("[\\p{L}][\\p{L}'’-]*")

    fun ensureLoaded(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            runCatching {
                latin = read(context, "lexicon/latin.txt")
                cyr = read(context, "lexicon/cyrillic.txt")
            }.onFailure { Log.w("Lexicon", "failed to load word lists; validity check off", it) }
        }
    }

    // Stored uncompressed in assets (AGP would gunzip a .gz and drop the
    // extension); the APK's own zip keeps them small.
    private fun read(context: Context, path: String): Set<String> =
        context.assets.open(path).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        }

    /** True if [word] is in the dictionary for its script. */
    fun isReal(word: String): Boolean {
        val lat = latin ?: return false
        val cy = cyr ?: return false
        val w = word.lowercase().replace('ё', 'е')
        if (w.length < 2) return false
        return if (w.any { it in 'Ѐ'..'ӿ' }) cy.contains(w) else lat.contains(w)
    }

    /** Fraction of word-tokens that are real; -1 when the lists aren't loaded. */
    fun realFraction(text: String): Float {
        if (!ready) return -1f
        val toks = WORD.findAll(text).map { it.value }.filter { it.length >= 2 }.toList()
        if (toks.isEmpty()) return 0f
        return toks.count { isReal(it) }.toFloat() / toks.size
    }

    /** The real word-tokens found in [text] (for augmenting the search index). */
    fun realWords(text: String): List<String> {
        if (!ready) return emptyList()
        return WORD.findAll(text).map { it.value }.filter { it.length >= 2 && isReal(it) }.toList()
    }
}
