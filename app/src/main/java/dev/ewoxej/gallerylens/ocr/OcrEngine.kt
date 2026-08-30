package dev.ewoxej.gallerylens.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.math.max

private data class TessResult(val text: String, val blocks: List<OcrBlock>, val confidence: Int)

class OcrEngine(private val context: Context) {

    private val latin: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var tess: TessBaseAPI? = null

    private fun tesseract(): TessBaseAPI? {
        tess?.let { return it }
        val dataDir = Tessdata.ensureInstalled(context) ?: return null
        val api = TessBaseAPI()
        return if (api.init(dataDir.absolutePath, "rus+ukr+eng")) {
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            tess = api
            api
        } else {
            Log.e(TAG, "Tesseract init failed")
            api.recycle()
            null
        }
    }

    suspend fun recognize(uri: Uri): OcrResult? {
        val bitmap = loadDownscaled(uri) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        return try {
            // ML Kit (Latin). Time-boxed so one pathological image can't stall
            // the whole indexing queue (on timeout we just drop this engine's
            // result for this photo).
            val visionText: Text? = withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
                runCatching { latin.process(InputImage.fromBitmap(bitmap, 0)).await() }.getOrNull()
            }
            val latinText = visionText?.text?.trim().orEmpty()

            // Tesseract (Cyrillic). Fed a grayscale/contrast-stretched copy
            // (ImagePrep) — better on dim/low-contrast photos. getUTF8Text() is a
            // blocking native call that ignores coroutine cancellation, so on
            // timeout we (a) ask it to stop() and (b) ABANDON this recognizer
            // (tess = null) rather than reuse one whose native call is still
            // unwinding — reusing it would race/crash. Next photo builds a fresh one.
            val tessOut: TessResult? = withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        tesseract()?.let { api ->
                            val prepped = ImagePrep.forTesseract(bitmap)
                            try {
                                api.setImage(prepped)
                                val t = api.getUTF8Text()?.trim().orEmpty()
                                val conf = runCatching { api.meanConfidence() }.getOrDefault(0)
                                val blocks = if (t.isNotEmpty()) tessLineBlocks(api) else emptyList()
                                TessResult(t, blocks, conf)
                            } finally {
                                prepped.recycle()
                            }
                        } ?: TessResult("", emptyList(), 0)
                    }.getOrDefault(TessResult("", emptyList(), 0))
                }
            }
            if (tessOut == null) {
                Log.w(TAG, "Tesseract timed out for $uri; skipping it for this photo")
                runCatching { tess?.stop() }
                tess = null
            }
            val tessRes = tessOut ?: TessResult("", emptyList(), 0)

            Lexicon.ensureLoaded(context)
            val useTess = chooseUseTess(latinText, tessRes.text, tessRes.confidence)
            val text = if (useTess) tessRes.text else latinText
            val blocks = if (useTess) tessRes.blocks else mlkitLineBlocks(visionText)
            // Index the chosen transcript in full plus the *real* words from the
            // other engine, so a mixed photo is found by either script while the
            // other engine's OCR garbage stays out of the index.
            val other = if (useTess) latinText else tessRes.text
            val searchText = buildSearchText(text, other)
            OcrResult(text, searchText, w, h, blocks)
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed for $uri", e)
            null
        }
        // No bitmap.recycle(): after a timeout the aborted native call may still
        // reference the bitmap briefly. It is already downscaled, so we let GC
        // reclaim it rather than risk recycling one that is still in use.
    }

    /**
     * Pick the transcript to DISPLAY by which one is made of more *real* words
     * (dictionary check). This is what fixes Cyrillic-read-as-Latin: ML Kit's
     * "npuBes BaM yrnA" scores ~0 against the Latin dictionary while Tesseract's
     * "привёз вам угля" scores high against the Cyrillic one, so Tesseract wins.
     * Falls back to the script/confidence heuristic when the lists aren't loaded
     * or the two look equally (un)real.
     */
    private fun chooseUseTess(latinText: String, tessText: String, tessConfidence: Int): Boolean {
        if (tessText.isEmpty()) return false
        if (latinText.isEmpty()) return true
        val mlkitReal = Lexicon.realFraction(latinText)
        val tessReal = Lexicon.realFraction(tessText)
        if (mlkitReal >= 0f && tessReal >= 0f) {
            if (tessReal > mlkitReal + 0.15f) return true
            if (mlkitReal > tessReal + 0.15f) return false
        }
        return preferTess(latinText, tessText, tessConfidence)
    }

    /**
     * Search index = the chosen transcript in full (keeps numbers, names, rare
     * words for recall) plus only the real words from the other engine (adds a
     * mixed photo's other script without letting its OCR garbage into the index).
     * Falls back to a plain union when the dictionaries aren't loaded.
     */
    private fun buildSearchText(chosen: String, other: String): String {
        if (other.isEmpty()) return chosen
        if (!Lexicon.ready) return unionText(chosen, other)
        val lines = LinkedHashSet<String>()
        chosen.lineSequence().forEach { val s = it.trim(); if (s.isNotEmpty()) lines += s }
        val extra = Lexicon.realWords(other).filterNot { chosen.contains(it, ignoreCase = true) }
        if (extra.isNotEmpty()) lines += extra.joinToString(" ")
        return lines.joinToString("\n")
    }

    /**
     * Which engine's transcript to DISPLAY (and take boxes from). ML Kit has no
     * Cyrillic model, so real Cyrillic can only be right if Tesseract produced
     * it; pure-Latin images stay with ML Kit (cleaner). Confidence guards
     * against Tesseract noise inventing a few Cyrillic glyphs on a Latin image.
     */
    private fun preferTess(latinText: String, tessText: String, tessConfidence: Int): Boolean {
        if (tessText.isEmpty()) return false
        if (latinText.isEmpty()) return true
        // Whole Cyrillic block (U+0400–U+04FF) so Ukrainian-only letters
        // (і ї є ґ) count too, not just the Russian а–я range.
        val cyr = tessText.count { it in 'Ѐ'..'ӿ' }
        if (cyr >= 3 && (tessConfidence <= 0 || tessConfidence >= 45)) return true
        // No meaningful Cyrillic -> it's a Latin image -> ML Kit, unless ML Kit
        // found almost nothing while Tesseract did (confidently).
        return latinText.length < 3 && tessConfidence >= 45
    }

    /** Union of both engines' lines for the search index (dedup identical ones). */
    private fun unionText(latinText: String, tessText: String): String {
        if (tessText.isEmpty()) return latinText
        if (latinText.isEmpty()) return tessText
        if (latinText == tessText) return latinText
        val lines = LinkedHashSet<String>()
        latinText.lineSequence().forEach { val s = it.trim(); if (s.isNotEmpty()) lines += s }
        tessText.lineSequence().forEach { val s = it.trim(); if (s.isNotEmpty()) lines += s }
        return lines.joinToString("\n")
    }

    private fun mlkitLineBlocks(visionText: Text?): List<OcrBlock> {
        if (visionText == null) return emptyList()
        val out = ArrayList<OcrBlock>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val r = line.boundingBox ?: continue
                out += OcrBlock(line.text, r.left, r.top, r.right, r.bottom)
            }
        }
        return out
    }

    private fun tessLineBlocks(api: TessBaseAPI): List<OcrBlock> = runCatching {
        val it = api.resultIterator ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        val out = ArrayList<OcrBlock>()
        it.begin()
        do {
            val txt = it.getUTF8Text(level)?.trim().orEmpty()
            val box = it.getBoundingRect(level)
            if (txt.isNotEmpty() && box != null) {
                out += OcrBlock(txt, box.left, box.top, box.right, box.bottom)
            }
        } while (it.next(level))
        it.delete()
        out
    }.getOrDefault(emptyList())

    private fun loadDownscaled(uri: Uri): Bitmap? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    val sample = if (longest > MAX_DIM) longest / MAX_DIM else 1
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: IOException) {
            Log.w(TAG, "decode failed for $uri", e); null
        } catch (e: Exception) {
            Log.w(TAG, "decode error for $uri", e); null
        }

    fun close() {
        tess?.recycle(); tess = null
        latin.close()
    }

    companion object {
        private const val TAG = "OcrEngine"
        // Higher = better on small/dense text (receipts, documents), at the cost
        // of speed/memory. 1600 lost fine print; 2560 keeps much more of it.
        private const val MAX_DIM = 2560
        // Per-engine, per-photo budget. A normal page is well under a second;
        // this only fires on a pathological image so it can be skipped.
        private const val ENGINE_TIMEOUT_MS = 20_000L
    }
}

object Tessdata {
    private val LANGS = listOf("eng", "rus", "ukr")

    // Bump whenever the bundled traineddata changes (e.g. fast -> best) so
    // existing installs re-copy the new models instead of keeping the old ones.
    // v2: rus/ukr upgraded to tessdata_best.
    private const val VERSION = 2

    fun ensureInstalled(context: Context): File? {
        val base = File(context.filesDir, "tess")
        val tessdata = File(base, "tessdata")
        val marker = File(base, ".version")
        return try {
            val installed = runCatching { marker.readText().trim().toInt() }.getOrNull()
            if (installed != VERSION) {
                tessdata.deleteRecursively() // drop stale models
            }
            tessdata.mkdirs()
            for (lang in LANGS) {
                val out = File(tessdata, "$lang.traineddata")
                if (out.exists() && out.length() > 0) continue
                context.assets.open("tessdata/$lang.traineddata").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            marker.writeText(VERSION.toString())
            base
        } catch (e: IOException) {
            Log.e("Tessdata", "install failed", e)
            null
        }
    }
}
