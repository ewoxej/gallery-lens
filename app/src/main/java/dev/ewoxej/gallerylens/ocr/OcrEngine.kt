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
import java.io.File
import java.io.IOException
import kotlin.math.max

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
            val visionText: Text? = runCatching {
                latin.process(InputImage.fromBitmap(bitmap, 0)).await()
            }.getOrNull()
            val latinText = visionText?.text?.trim().orEmpty()

            val (tessText, tessBlocks) = withContext(Dispatchers.Default) {
                runCatching {
                    tesseract()?.let { api ->
                        api.setImage(bitmap)
                        val t = api.getUTF8Text()?.trim().orEmpty()
                        t to (if (t.isNotEmpty()) tessLineBlocks(api) else emptyList())
                    } ?: ("" to emptyList<OcrBlock>())
                }.getOrDefault("" to emptyList())
            }

            val useTess = preferTess(latinText, tessText)
            val text = if (useTess) tessText else latinText
            val blocks = if (useTess) tessBlocks else mlkitLineBlocks(visionText)
            OcrResult(text, w, h, blocks)
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed for $uri", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun preferTess(latinText: String, tessText: String): Boolean {
        if (tessText.isEmpty()) return false
        if (latinText.isEmpty()) return true
        // Whole Cyrillic block (U+0400–U+04FF) so Ukrainian-only letters
        // (і ї є ґ) count too, not just the Russian а–я range.
        val cyr = tessText.count { it in 'Ѐ'..'ӿ' }
        val lat = latinText.count { it.isLetter() }
        return cyr >= 2 && cyr >= lat / 3
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
        private const val MAX_DIM = 1600
    }
}

object Tessdata {
    private val LANGS = listOf("eng", "rus", "ukr")

    fun ensureInstalled(context: Context): File? {
        val base = File(context.filesDir, "tess")
        val tessdata = File(base, "tessdata").apply { mkdirs() }
        return try {
            for (lang in LANGS) {
                val out = File(tessdata, "$lang.traineddata")
                if (out.exists() && out.length() > 0) continue
                context.assets.open("tessdata/$lang.traineddata").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            base
        } catch (e: IOException) {
            Log.e("Tessdata", "install failed", e)
            null
        }
    }
}
