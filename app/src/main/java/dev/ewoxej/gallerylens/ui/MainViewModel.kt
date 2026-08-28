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

    val results: StateFlow<List<PhotoEntity>> =
        _query
            .debounce(250)
            .flatMapLatest { q -> kotlinx.coroutines.flow.flow { emit(repo.search(q)) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /** Drop all OCR output and re-queue every photo for re-recognition. */
    fun reindexAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.reindexAll() }
            IndexingWorker.enqueue(getApplication())
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
