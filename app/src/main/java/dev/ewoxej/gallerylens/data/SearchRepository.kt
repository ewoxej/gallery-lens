package dev.ewoxej.gallerylens.data

class SearchRepository(private val dao: PhotoDao) {

    /**
     * With a query -> full-text search (results are inherently "with text").
     * Blank query -> the gallery grid: all photos, or only those with recognised
     * text when [onlyWithText] is on.
     */
    suspend fun results(raw: String, onlyWithText: Boolean): List<PhotoEntity> {
        val fts = toFtsQuery(raw)
            ?: return if (onlyWithText) dao.recentWithText(GALLERY_LIMIT)
            else dao.allByDateDesc(GALLERY_LIMIT)
        return runCatching { dao.search(fts) }.getOrDefault(emptyList())
    }

    private fun toFtsQuery(raw: String): String? {
        val tokens = raw
            .lowercase()
            .replace('ё', 'е')
            .split(Regex("\\s+"))
            .map { it.filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    companion object {
        private const val GALLERY_LIMIT = 5000
    }
}
