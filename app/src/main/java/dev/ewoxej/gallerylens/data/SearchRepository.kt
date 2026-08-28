package dev.ewoxej.gallerylens.data

class SearchRepository(private val dao: PhotoDao) {

    suspend fun search(raw: String): List<PhotoEntity> {
        val fts = toFtsQuery(raw) ?: return dao.allByDateDesc(GALLERY_LIMIT)
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
