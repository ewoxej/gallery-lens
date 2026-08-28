package dev.ewoxej.gallerylens.ocr

import org.json.JSONArray
import org.json.JSONObject

data class OcrBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class OcrResult(
    val text: String,
    val width: Int,
    val height: Int,
    val blocks: List<OcrBlock>,
)

object OcrLayout {
    fun toJson(blocks: List<OcrBlock>): String {
        val arr = JSONArray()
        for (b in blocks) {
            arr.put(
                JSONObject()
                    .put("t", b.text)
                    .put("l", b.left)
                    .put("tp", b.top)
                    .put("r", b.right)
                    .put("b", b.bottom),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String?): List<OcrBlock> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OcrBlock(
                    text = o.optString("t"),
                    left = o.optInt("l"),
                    top = o.optInt("tp"),
                    right = o.optInt("r"),
                    bottom = o.optInt("b"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
