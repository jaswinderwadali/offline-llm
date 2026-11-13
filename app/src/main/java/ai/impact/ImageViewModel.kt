package ai.impact

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import ai.impact.image.CatalogImageModel
import ai.impact.image.GeneratedImage
import ai.impact.image.GenerationPhase
import ai.impact.image.GenerationRequest
import ai.impact.image.toDownloadSpec
import ai.impact.models.formatBytes
import ai.impact.image.ImageModelCatalog
import ai.impact.image.ImageModelDefaults
import ai.impact.image.InstalledImageModel
import ai.impact.image.PromptEnhancement
import ai.impact.image.PromptEnhancer
import ai.impact.image.StableDiffusionPipeline
import ai.impact.image.UnsupportedModelException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Why the chat can't make an image right now, phrased for the user. */
class ImageUnavailableException(message: String) : Exception(message)

sealed interface ImageModelState {
    data object NotLoaded : ImageModelState
    data class Loading(val id: String, val name: String) : ImageModelState
    data class Ready(val id: String, val name: String, val fixedSize: Pair<Int, Int>?) : ImageModelState
    /** [lowMemory] means loading was held back because free memory looks too low; it can be retried anyway. */
    data class Error(val message: String, val modelId: String? = null, val lowMemory: Boolean = false) : ImageModelState
}

sealed interface GenerationState {
    data object Idle : GenerationState
    data class Running(
        val request: GenerationRequest,
        val phase: GenerationPhase,
        val preview: Bitmap?,
        val startedAt: Long
    ) : GenerationState {
        /** Overall progress, with prompt encoding and decoding counted as a small share each. */
        val progress: Float
            get() = when (phase) {
                GenerationPhase.EnhancingPrompt -> 0.01f
                GenerationPhase.EncodingPrompt -> 0.02f
                is GenerationPhase.Denoising -> 0.05f + 0.85f * phase.step / phase.total.coerceAtLeast(1)
                GenerationPhase.Decoding -> 0.92f
            }
    }
    data class Done(val image: GeneratedImage, val bitmap: Bitmap) : GenerationState
    data class Failed(val message: String) : GenerationState
}

enum class AspectRatio(val label: String, val width: Int, val height: Int) {
    Square("1:1", 512, 512),
    Portrait("3:4", 384, 512),
    Landscape("4:3", 512, 384)
}

data class ImageSettings(
    val negativePrompt: String = "",
    val steps: Int = 20,
    val maxSteps: Int = 40,
    val guidance: Float = 7f,
    val usesGuidance: Boolean = true,
    val aspect: AspectRatio = AspectRatio.Square,
    /** Fixed seed, or null for a new random one each time. */
    val seed: Long? = null,
    /** Let the chat model rewrite the prompt into a detailed one before the image model runs. */
    val enhancePrompt: Boolean = false
)

class ImageViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ImageViewModel"
        private const val PREFS = "image_studio"
        private const val KEY_SELECTED = "selected_model"
        private const val KEY_ASPECT = "aspect"
        private const val KEY_ENHANCE = "enhance_prompt"
        // A phone CPU writes a ~50-word prompt in well under this; past it, give up and use the original.
        private const val ENHANCE_TIMEOUT_MS = 60_000L
        // Largest model, as a share of total device RAM, that we'll try to load.
        const val MAX_MODEL_RAM_FRACTION = 0.6
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val store = (application as ImpactApp).imageModels
    private val gallery = (application as ImpactApp).gallery

    val catalog: List<CatalogImageModel> = ImageModelCatalog.models
    val installed: StateFlow<List<InstalledImageModel>> = store.installed
    val downloads = store.downloads
    val import = store.import
    val images: StateFlow<List<GeneratedImage>> = gallery.images
    val deviceRamBytes: Long = ActivityManager.MemoryInfo().also {
        application.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem

    private val _modelState = MutableStateFlow<ImageModelState>(ImageModelState.NotLoaded)
    val modelState: StateFlow<ImageModelState> = _modelState.asStateFlow()

    private val _selectedModelId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

    private val _generation = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generation: StateFlow<GenerationState> = _generation.asStateFlow()

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val _settings = MutableStateFlow(
        ImageSettings(
            aspect = AspectRatio.entries.getOrNull(prefs.getInt(KEY_ASPECT, 0)) ?: AspectRatio.Square,
            enhancePrompt = prefs.getBoolean(KEY_ENHANCE, false)
        )
    )
    val settings: StateFlow<ImageSettings> = _settings.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short confirmations and errors for a snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Set by the activity: rewrites prompts with the chat model. */
    var promptEnhancer: PromptEnhancer? = null


    private var pipeline: StableDiffusionPipeline? = null
    private var loadJob: Job? = null
    private var generationJob: Job? = null
    private val engineLock = Mutex()

    init {
        viewModelScope.launch(Dispatchers.IO) { gallery.refresh() }
        store.interruptedLoad?.let { name ->
            store.setLoading(null)
            _modelState.value = ImageModelState.Error(
                "The app closed while loading \"$name\". It probably needs more memory than this device has free. " +
                    "Close other apps, unload the chat model, or try a smaller model."
            )
        }
        viewModelScope.launch {
            // Keep the generation settings matched to the selected model.
            installed.collect { list ->
                val selected = list.firstOrNull { it.id == _selectedModelId.value }
                if (selected != null) {
                    applyDefaults(selected.defaults, onlyIfUnset = true)
                } else if (list.isNotEmpty()) {
                    // Nothing chosen yet, or the chosen model was deleted.
                    select(list.first())
                } else if (_selectedModelId.value != null) {
                    _selectedModelId.value = null
                    prefs.edit().remove(KEY_SELECTED).apply()
                    defaultsAppliedFor = null
                }
            }
        }
    }

    private var defaultsAppliedFor: ImageModelDefaults? = null

    private fun applyDefaults(defaults: ImageModelDefaults, onlyIfUnset: Boolean) {
        if (onlyIfUnset && defaultsAppliedFor != null) return
        defaultsAppliedFor = defaults
        _settings.value = _settings.value.copy(
            steps = defaults.steps,
            maxSteps = defaults.maxSteps,
            guidance = if (defaults.usesGuidance) defaults.guidance else 0f,
            usesGuidance = defaults.usesGuidance,
            negativePrompt = defaults.negativePrompt
        )
    }

    private fun select(model: InstalledImageModel) {
        _selectedModelId.value = model.id
        prefs.edit().putString(KEY_SELECTED, model.id).apply()
        applyDefaults(model.defaults, onlyIfUnset = false)
    }

    // region Models

    /** Loads the selected model when the studio opens, so it's ready by the time a prompt is typed. */
    fun onStudioOpened() {
        if (_modelState.value != ImageModelState.NotLoaded) return
        val id = _selectedModelId.value ?: installed.value.firstOrNull()?.id ?: return
        loadModel(id)
    }

    fun loadModel(id: String, ignoreLowMemory: Boolean = false) {
        val model = installed.value.firstOrNull { it.id == id } ?: return
        val currentId = when (val current = _modelState.value) {
            is ImageModelState.Ready -> current.id
            is ImageModelState.Loading -> current.id
            else -> null
        }
        if (currentId == id) return
        if (model.id != _selectedModelId.value) select(model)

        val context = getApplication<Application>()
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (model.sizeBytes > memory.totalMem * MAX_MODEL_RAM_FRACTION) {
            _modelState.value = ImageModelState.Error(
                "${model.name} needs about ${formatBytes(model.sizeBytes)} of memory, too much for this device's " +
                    "${formatBytes(memory.totalMem)}. Try a smaller model.",
                modelId = id
            )
            return
        }
        if (!ignoreLowMemory && model.sizeBytes > memory.availMem) {
            _modelState.value = ImageModelState.Error(
                "Only ${formatBytes(memory.availMem)} of memory is free and ${model.name} needs about " +
                    "${formatBytes(model.sizeBytes)}. Unload the chat model or close other apps first.",
                modelId = id,
                lowMemory = true
            )
            return
        }

        _modelState.value = ImageModelState.Loading(id, model.name)
        val previous = loadJob
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            generationJob?.let { pipeline?.cancel(); it.cancelAndJoin() }
            // A chat image may still hold the engine; stop it and wait before closing.
            pipeline?.cancel()
            engineLock.withLock { pipeline?.close() }
            pipeline = null
            store.setLoading(model.name)
            try {
                val loaded = StableDiffusionPipeline.load(store.dirFor(id), model.defaults)
                store.setLoading(null)
                pipeline = loaded
                _modelState.value = ImageModelState.Ready(id, model.name, loaded.fixedSize)
            } catch (e: CancellationException) {
                store.setLoading(null)
                throw e
            } catch (e: Throwable) {
                store.setLoading(null)
                Log.e(TAG, "Couldn't load ${model.name}", e)
                val message = when (e) {
                    is UnsupportedModelException -> e.message ?: "This model isn't supported."
                    is OutOfMemoryError -> "There isn't enough memory to load ${model.name}."
                    else -> "Couldn't load ${model.name}. The files may be incomplete or not a supported model."
                }
                _modelState.value = ImageModelState.Error(message, modelId = id)
            }
        }
    }

    fun unloadModel() {
        val running = generationJob
        val loaded = pipeline
        pipeline = null
        loaded?.cancel()
        loadJob?.cancel()
        _modelState.value = ImageModelState.NotLoaded
        viewModelScope.launch(Dispatchers.IO) {
            running?.cancelAndJoin()
            engineLock.withLock { loaded?.close() }
        }
    }

    fun dismissModelError() {
        if (_modelState.value is ImageModelState.Error) _modelState.value = ImageModelState.NotLoaded
    }

    fun startDownload(model: CatalogImageModel) {
        store.startDownload(model.toDownloadSpec())?.let { _messages.tryEmit(it) }
    }



    fun cancelDownload(id: String) = store.cancelDownload(id)

    fun dismissDownloadError(id: String) = store.dismissDownloadError(id)

    fun deleteModel(id: String) {
        if ((_modelState.value as? ImageModelState.Ready)?.id == id || (_modelState.value as? ImageModelState.Loading)?.id == id) {
            unloadModel()
        }
        // Refreshing the installed list re-selects another model if this one was selected.
        viewModelScope.launch(Dispatchers.IO) { store.delete(id) }
    }

    fun importModel(treeUri: Uri) {
        store.startImport(treeUri) { error ->
            _messages.tryEmit(error ?: "Model imported")
        }
    }

    fun cancelImport() = store.cancelImport()

    // endregion

    // region Generation

    fun setPrompt(value: String) {
        _prompt.value = value
    }

    fun updateSettings(transform: (ImageSettings) -> ImageSettings) {
        _settings.value = transform(_settings.value)
        prefs.edit()
            .putInt(KEY_ASPECT, _settings.value.aspect.ordinal)
            .putBoolean(KEY_ENHANCE, _settings.value.enhancePrompt)
            .apply()
    }

    fun resetSettingsToModel() {
        installed.value.firstOrNull { it.id == _selectedModelId.value }?.let { applyDefaults(it.defaults, onlyIfUnset = false) }
    }

    /** The request the current settings describe for [prompt]. */
    private fun requestFor(prompt: String, ready: ImageModelState.Ready): GenerationRequest {
        val settings = _settings.value
        val (width, height) = ready.fixedSize ?: (settings.aspect.width to settings.aspect.height)
        return GenerationRequest(
            prompt = prompt,
            negativePrompt = if (settings.usesGuidance) settings.negativePrompt.trim() else "",
            steps = settings.steps,
            guidance = if (settings.usesGuidance) settings.guidance else 0f,
            width = width,
            height = height,
            seed = settings.seed ?: (kotlin.random.Random.nextLong() and 0xFFFFFFFFL)
        )
    }

    /**
     * Enhances (if enabled), paints and saves one image, reporting each step through [onState].
     * The engine runs one image at a time, so the studio and the chat take turns via [engineLock].
     */
    private suspend fun runGeneration(
        engine: StableDiffusionPipeline,
        ready: ImageModelState.Ready,
        initial: GenerationRequest,
        startedAt: Long,
        onState: (GenerationState.Running) -> Unit
    ): GenerationState.Done = engineLock.withLock {
        withContext(Dispatchers.Default) {
            val enhancer = promptEnhancer.takeIf { _settings.value.enhancePrompt }
            var request = initial
            if (enhancer != null) {
                onState(GenerationState.Running(request, GenerationPhase.EnhancingPrompt, null, startedAt))
                request = enhance(request, enhancer, ready.name)
            }
            onState(GenerationState.Running(request, GenerationPhase.EncodingPrompt, null, startedAt))
            // Cancelling the coroutine has to reach the native engine, which is blocking this thread.
            // A child coroutine is cancelled at once even while the parent is busy, so it relays the stop.
            var finished = false
            val stopEngine = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished) engine.cancel()
                }
            }
            try {
                var preview: Bitmap? = null
                val bitmap = engine.generate(request) { phase ->
                    // A cancelled run can still report a phase before the engine notices.
                    if (!isActive) return@generate
                    (phase as? GenerationPhase.Denoising)?.preview?.let { preview = it }
                    onState(GenerationState.Running(request, phase, preview, startedAt))
                }
                // Upload to the GPU now, off the main thread, so the first frame showing it doesn't stutter.
                bitmap.prepareToDraw()
                val took = System.currentTimeMillis() - startedAt
                val saved = withContext(Dispatchers.IO) { gallery.save(bitmap, request, ready.name, took) }
                GenerationState.Done(saved, bitmap)
            } finally {
                finished = true
                stopEngine.cancel()
            }
        }
    }

    fun generate() {
        val engine = pipeline ?: return
        val ready = _modelState.value as? ImageModelState.Ready ?: return
        val text = _prompt.value.trim()
        if (text.isEmpty() || _generation.value is GenerationState.Running) return

        val request = requestFor(text, ready)
        val startedAt = System.currentTimeMillis()
        _generation.value = GenerationState.Running(request, GenerationPhase.EncodingPrompt, null, startedAt)

        generationJob = viewModelScope.launch {
            try {
                _generation.value = runGeneration(engine, ready, request, startedAt) { running ->
                    _generation.value = running
                }
            } catch (e: CancellationException) {
                if ((_generation.value as? GenerationState.Running)?.startedAt == startedAt) _generation.value = GenerationState.Idle
            } catch (e: Throwable) {
                Log.e(TAG, "Generation failed", e)
                _generation.value = GenerationState.Failed(failureMessage(e))
            }
        }
    }

    private fun failureMessage(e: Throwable): String =
        if (e is OutOfMemoryError) "Ran out of memory. Try a smaller image size or close other apps."
        else "Something went wrong while generating: ${e.message ?: "unknown error"}"

    /** The request with the text model's rewrite as its prompt, or unchanged if that isn't possible. */
    private suspend fun enhance(request: GenerationRequest, enhancer: PromptEnhancer, modelName: String): GenerationRequest {
        var timedOut = true
        val improved = withTimeoutOrNull(ENHANCE_TIMEOUT_MS) {
            enhancer.enhance(request.prompt, PromptEnhancement.styleHintFor(modelName)).also { timedOut = false }
        }
        if (improved == null) {
            _messages.tryEmit(
                if (timedOut) "Enhancing took too long, so your prompt was used as written."
                else "Enhancing needs a chat model that isn't busy, so your prompt was used as written."
            )
            return request
        }
        if (improved.equals(request.prompt, ignoreCase = true)) return request
        return request.copy(prompt = improved, originalPrompt = request.prompt)
    }

    fun cancelGeneration() {
        pipeline?.cancel()
        generationJob?.cancel()
        _generation.value = GenerationState.Idle
    }

    // region Chat

    /** Whether an image model is installed, so the chat can offer image mode. */
    val hasImageModel: Boolean get() = installed.value.isNotEmpty()

    /**
     * Paints [prompt] for the chat with the studio's current settings, loading the selected
     * image model first if needed. Throws [ImageUnavailableException] with a user-facing reason
     * when there's no usable model; cancelling the caller stops the engine.
     */
    suspend fun generateForChat(prompt: String, onState: (GenerationState.Running) -> Unit): GenerationState.Done {
        val ready = ensureModelReady()
        val engine = pipeline ?: throw ImageUnavailableException("The image model isn't loaded.")
        val startedAt = System.currentTimeMillis()
        try {
            return runGeneration(engine, ready, requestFor(prompt.trim(), ready), startedAt, onState)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageUnavailableException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Chat image failed", e)
            throw ImageUnavailableException(failureMessage(e))
        }
    }

    private suspend fun ensureModelReady(): ImageModelState.Ready {
        (_modelState.value as? ImageModelState.Ready)?.let { return it }
        if (_modelState.value !is ImageModelState.Loading) {
            val id = _selectedModelId.value?.takeIf { id -> installed.value.any { it.id == id } }
                ?: installed.value.firstOrNull()?.id
                ?: throw ImageUnavailableException("No image model yet. Download one from Models to create images.")
            loadModel(id)
        }
        return when (val state = _modelState.first { it !is ImageModelState.Loading }) {
            is ImageModelState.Ready -> state
            is ImageModelState.Error -> throw ImageUnavailableException(state.message)
            else -> throw ImageUnavailableException("The image model isn't loaded.")
        }
    }

    // endregion

    fun clearResult() {
        if (_generation.value !is GenerationState.Running) _generation.value = GenerationState.Idle
    }

    /** Puts a past image's prompt and settings back into the studio, keeping its seed. */
    fun remix(image: GeneratedImage) {
        _prompt.value = image.prompt
        _settings.value = _settings.value.copy(
            negativePrompt = image.negativePrompt.ifBlank { _settings.value.negativePrompt },
            steps = image.steps.coerceIn(1, _settings.value.maxSteps),
            guidance = if (_settings.value.usesGuidance && image.guidance > 1f) image.guidance else _settings.value.guidance,
            aspect = AspectRatio.entries.firstOrNull { it.width == image.width && it.height == image.height } ?: _settings.value.aspect,
            seed = image.seed
        )
        clearResult()
    }

    // endregion

    // region Gallery

    fun decode(image: GeneratedImage, maxSide: Int = 0): Bitmap? = gallery.decode(image, maxSide)

    fun shareIntent(image: GeneratedImage): Intent = gallery.shareIntent(image)

    fun saveToPhotos(image: GeneratedImage) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                gallery.saveToPhotos(image)
                "Saved to Photos"
            } catch (e: Exception) {
                Log.e(TAG, "Save failed", e)
                "Couldn't save the image"
            }
            _messages.tryEmit(message)
        }
    }

    fun deleteImage(id: String) {
        val done = _generation.value as? GenerationState.Done
        if (done?.image?.id == id) _generation.value = GenerationState.Idle
        viewModelScope.launch(Dispatchers.IO) { gallery.delete(id) }
    }

    fun deleteAllImages() {
        clearResult()
        viewModelScope.launch(Dispatchers.IO) { gallery.deleteAll() }
    }

    // endregion

    override fun onCleared() {
        super.onCleared()
        val loaded = pipeline ?: return
        loaded.cancel()
        val running = generationJob
        // Close only once any in-flight native call has returned.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            running?.join()
            engineLock.withLock { loaded.close() }
        }
    }
}
