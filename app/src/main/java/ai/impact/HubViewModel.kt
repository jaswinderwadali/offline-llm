package ai.impact

import android.app.ActivityManager
import android.app.Application
import android.util.Log
import ai.impact.image.GgufFile
import ai.impact.image.GgufResolution
import ai.impact.image.HubCandidate
import ai.impact.image.HubModel
import ai.impact.image.HubParsing
import ai.impact.image.HubResolution
import ai.impact.image.HuggingFaceClient
import ai.impact.image.ModelKind
import ai.impact.image.RepoRef
import ai.impact.image.toDownloadSpec
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class HubSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<HubModel> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false
)

sealed interface HubResolveState {
    data class Checking(val repo: String) : HubResolveState
    data class ImageResult(val resolution: HubResolution) : HubResolveState
    data class ChatResult(val resolution: GgufResolution) : HubResolveState
    data class Failed(val ref: RepoRef, val message: String) : HubResolveState
}

/** Browsing Hugging Face for chat (GGUF) and image (ONNX) models, and queuing their downloads. */
class HubViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "HubViewModel"
        /** First searches shown: small instruct chat models, and quantised SD 1.5 image models. */
        val DEFAULT_QUERY = mapOf(ModelKind.Chat to "instruct", ModelKind.Image to "int8")
        private const val MAX_MODEL_RAM_FRACTION = 0.6
    }

    private val app = application as ImpactApp
    private val client = HuggingFaceClient()

    private val _kind = MutableStateFlow(ModelKind.Image)
    val kind: StateFlow<ModelKind> = _kind.asStateFlow()

    private val searches = ModelKind.entries.associateWith { MutableStateFlow(HubSearchState()) }

    private val _search = MutableStateFlow(HubSearchState())
    /** The search for the current [kind]. */
    val search: StateFlow<HubSearchState> = _search.asStateFlow()

    private val _resolve = MutableStateFlow<HubResolveState?>(null)
    val resolve: StateFlow<HubResolveState?> = _resolve.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val deviceRamBytes: Long = ActivityManager.MemoryInfo().also {
        application.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem

    private var searchJob: Job? = null
    private var resolveJob: Job? = null

    /** Switches to [kind] and shows suggestions the first time. */
    fun open(kind: ModelKind) {
        if (_kind.value != kind) dismissResolve()
        _kind.value = kind
        _search.value = searches.getValue(kind).value
        val state = _search.value
        if (!state.loaded && !state.loading) search(DEFAULT_QUERY.getValue(kind))
    }

    /** Searches Hugging Face, or checks a repo directly when [query] is a repo name or link. */
    fun search(query: String) {
        HubParsing.parseRepoReference(query)?.let {
            resolve(it)
            return
        }
        val kind = _kind.value
        val state = searches.getValue(kind)
        searchJob?.cancel()
        update(kind, state.value.copy(query = query, loading = true, error = null))
        searchJob = viewModelScope.launch {
            val result = try {
                state.value.copy(loading = false, results = client.search(query, kind), loaded = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                state.value.copy(loading = false, error = e.message ?: "Search failed.", loaded = true)
            }
            update(kind, result)
        }
    }

    private fun update(kind: ModelKind, state: HubSearchState) {
        searches.getValue(kind).value = state
        if (_kind.value == kind) _search.value = state
    }

    fun resolve(ref: RepoRef) {
        val kind = _kind.value
        resolveJob?.cancel()
        _resolve.value = HubResolveState.Checking(ref.repo)
        resolveJob = viewModelScope.launch {
            _resolve.value = try {
                when (kind) {
                    ModelKind.Image -> HubResolveState.ImageResult(client.resolve(ref))
                    ModelKind.Chat -> HubResolveState.ChatResult(client.resolveGguf(ref))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't check ${ref.repo}", e)
                HubResolveState.Failed(ref, e.message ?: "Couldn't check that repo.")
            }
        }
    }

    fun dismissResolve() {
        resolveJob?.cancel()
        _resolve.value = null
    }

    /** Each returns true once the download is queued. */
    fun download(candidate: HubCandidate): Boolean = queued(app.imageModels.startDownload(candidate.toDownloadSpec()))

    fun download(file: GgufFile): Boolean = queued(app.textModels.startDownload(file.toDownloadSpec()))

    private fun queued(error: String?): Boolean {
        if (error != null) {
            _messages.tryEmit(error)
            return false
        }
        _resolve.value = null
        return true
    }

    /** Whether a model of [sizeBytes] can be loaded on this device at all. */
    fun fitsInMemory(sizeBytes: Long): Boolean = sizeBytes <= deviceRamBytes * MAX_MODEL_RAM_FRACTION
}
