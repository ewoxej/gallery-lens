package dev.ewoxej.gallerylens.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max

/**
 * Light, LSTM-friendly preprocessing for the Tesseract path only (ML Kit does
 * its own and prefers the natural image). Converts to grayscale and stretches
 * contrast between the 2nd/98th luminance percentiles so dim / low-contrast /
 * unevenly-lit photos read better. Deliberately NOT hard-binarised: tessdata
 * LSTM models are trained on grayscale and degrade on 1-bit input.
 *
 * Keeps the same dimensions as the source, so OCR box coordinates still map to
 * the original image.
 */
object ImagePrep {

    fun forTesseract(src: Bitmap): Bitmap {
        val (lo, hi) = lumaPercentiles(src)
        val range = max(1, hi - lo)
        val scale = 255f / range
        val offset = -lo * scale

        val matrix = ColorMatrix().apply { setSaturation(0f) } // grayscale
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, offset,
                    0f, scale, 0f, 0f, offset,
                    0f, 0f, scale, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src, 0f, 0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(matrix); isFilterBitmap = true },
        )
        return out
    }

    /** Low/high luminance cut points (2nd/98th percentile), sampled for speed. */
    private fun lumaPercentiles(src: Bitmap): Pair<Int, Int> {
        val stepX = max(1, src.width / 240)
        val stepY = max(1, src.height / 240)
        val hist = IntArray(256)
        var count = 0
        val row = IntArray(src.width)
        var y = 0
        while (y < src.height) {
            src.getPixels(row, 0, src.width, 0, y, src.width, 1)
            var x = 0
            while (x < src.width) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                hist[luma]++
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 0 to 255
        val loTarget = (count * 0.02f).toInt()
        val hiTarget = (count * 0.98f).toInt()
        var acc = 0
        var lo = 0
        for (i in 0..255) { acc += hist[i]; if (acc >= loTarget) { lo = i; break } }
        acc = 0
        var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= hiTarget) { hi = i; break } }
        if (hi <= lo) return 0 to 255
        return lo to hi
    }
}
