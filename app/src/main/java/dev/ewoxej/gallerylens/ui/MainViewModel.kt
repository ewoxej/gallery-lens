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

    // Bumped to force the one-shot gallery query to re-run (e.g. after the album
    // selection changes, which the DB-count flows can't signal on their own).
    private val _refresh = MutableStateFlow(0)

    val results: StateFlow<List<PhotoEntity>> =
        combine(_query.debounce(250), _onlyWithText, _refresh) { q, only, _ -> q to only }
            .flatMapLatest { (q, only) ->
                kotlinx.coroutines.flow.flow { emit(repo.results(q, only)) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() { _refresh.value++ }

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
            dev.ewoxej.gallerylens.data.Settings.setPendingBatchId(getApplication(), null)
            IndexingWorker.enqueue(getApplication())
        }
    }

    /**
     * Manually queue the already-indexed library for a cloud (Claude batch) re-read,
     * respecting the current mode: "all photos" queues everything, otherwise only
     * photos whose local text looks like garbage. Kicks the worker to run the batch.
     */
    fun sendToCloud() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!dev.ewoxej.gallerylens.data.Settings.cloudReady(ctx)) return@launch
            val queued = withContext(Dispatchers.IO) {
                dev.ewoxej.gallerylens.ocr.Lexicon.ensureLoaded(ctx)
                val ids = dao.locallyFinished()
                    .filter { dev.ewoxej.gallerylens.ocr.cloudWanted(ctx, it.ocrText.orEmpty()) }
                    .map { it.id }
                ids.chunked(400).forEach { dao.markCloudPending(it) }
                ids.size
            }
            if (queued > 0) IndexingWorker.enqueue(ctx)
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
