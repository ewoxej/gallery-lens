package dev.ewoxej.gallerylens.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ewoxej.gallerylens.data.AppDatabase
import dev.ewoxej.gallerylens.data.PhotoEntity
import dev.ewoxej.gallerylens.data.PhotoStatus
import dev.ewoxej.gallerylens.data.SearchRepository
import dev.ewoxej.gallerylens.work.IndexingWorker
import dev.ewoxej.gallerylens.work.MediaWatch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IndexStatus(val total: Int, val done: Int, val pending: Int) {
    val isIndexing get() = pending > 0
}

enum class ViewMode { ALL, ALBUMS }

data class Stats(
    val total: Int = 0,
    val done: Int = 0,
    val pending: Int = 0,
    val noText: Int = 0,
    val failed: Int = 0,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).photoDao()
    private val repo = SearchRepository(dao)

    val status: StateFlow<IndexStatus> =
        combine(dao.countAll(), dao.countDone(), dao.countPending()) { total, done, pending ->
            IndexStatus(total, done, pending)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IndexStatus(0, 0, 0))

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _onlyWithText = MutableStateFlow(false)
    val onlyWithText: StateFlow<Boolean> = _onlyWithText.asStateFlow()

    // Gallery view: All Photos (flat grid) vs Albums (browse by album).
    private val _viewMode = MutableStateFlow(ViewMode.ALL)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    // The album (bucket key) currently open, or null = not inside one. "" is the
    // unnamed-bucket album. When set, the photo grid is scoped to it.
    private val _openAlbum = MutableStateFlow<String?>(null)
    val openAlbum: StateFlow<String?> = _openAlbum.asStateFlow()

    // Bumped to force the one-shot gallery query to re-run (e.g. after the album
    // selection changes, which the DB-count flows can't signal on their own).
    private val _refresh = MutableStateFlow(0)

    val results: StateFlow<List<PhotoEntity>> =
        combine(_query.debounce(250), _onlyWithText, _openAlbum, _refresh) { q, only, bucket, _ ->
            Triple(q, only, bucket)
        }
            .flatMapLatest { (q, only, bucket) ->
                kotlinx.coroutines.flow.flow { emit(repo.results(q, only, bucket)) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Album rows for the "Albums" view (newest first); empty while in All mode. */
    val albums: StateFlow<List<dev.ewoxej.gallerylens.data.AlbumSummary>> =
        combine(_viewMode, _refresh) { mode, _ -> mode }
            .flatMapLatest { mode ->
                kotlinx.coroutines.flow.flow {
                    emit(if (mode == ViewMode.ALBUMS) dao.albumSummaries() else emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() { _refresh.value++ }

    fun showAllPhotos() { _viewMode.value = ViewMode.ALL; _openAlbum.value = null }

    fun showAlbums() { _viewMode.value = ViewMode.ALBUMS; _openAlbum.value = null }

    fun openAlbum(bucketKey: String) { _viewMode.value = ViewMode.ALBUMS; _openAlbum.value = bucketKey }

    /** Toggle a photo's ignore flag (ignored photos are hidden + not indexed). */
    fun setIgnored(id: Long, ignored: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.setIgnored(id, ignored) }
            refresh()
        }
    }

    val stats: StateFlow<Stats> =
        combine(
            dao.countAll(),
            dao.countDone(),
            dao.countPending(),
            dao.countByStatus(PhotoStatus.NO_TEXT),
            dao.countByStatus(PhotoStatus.FAILED),
        ) { total, done, pending, noText, failed ->
            Stats(total, done, pending, noText, failed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Stats())

    fun onQueryChange(q: String) { _query.value = q }

    fun setOnlyWithText(on: Boolean) { _onlyWithText.value = on }

    /** Drop all OCR output and re-queue every photo for re-recognition. */
    fun reindexAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.reindexAll() }
            IndexingWorker.enqueue(getApplication())
        }
    }

    // --- Album filter ---

    /** Every device album with its photo count, for the album-filter screen. */
    suspend fun loadAlbums(): List<dev.ewoxej.gallerylens.work.Album> =
        withContext(Dispatchers.IO) { dev.ewoxej.gallerylens.work.MediaScanner(getApplication(), dao).listAlbums() }

    /** Current selection: null = all albums (no filter). */
    fun currentAlbumSelection(): Set<String>? =
        dev.ewoxej.gallerylens.data.Settings.includedBuckets(getApplication())

    /**
     * Persist an album selection (null = all) and reconcile the DB, then kick the
     * worker to index any photos that just became included.
     */
    fun applyAlbumSelection(selection: Set<String>?) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            dev.ewoxej.gallerylens.data.Settings.setIncludedBuckets(app, selection)
            withContext(Dispatchers.IO) { dao.applyAlbumFilter(selection) }
            refresh()
            IndexingWorker.enqueue(app)
        }
    }

    /** Called once the media permission is granted, and on every cold start. */
    fun startIndexing() {
        val app = getApplication<Application>()
        IndexingWorker.enqueue(app)
        // Register the background watcher so photos added later are indexed
        // automatically, without needing the app to be reopened.
        MediaWatch.arm(app)
    }

    suspend fun photoById(id: Long): PhotoEntity? = dao.byId(id)
}
