package ai.impact

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import ai.impact.data.BuiltInPersonas
import ai.impact.data.Persona
import ai.impact.data.ResponseParser
import ai.impact.data.history.StoredMessage
import ai.impact.data.memory.MemoryCategory
import ai.impact.data.memory.MemoryEntity
import ai.impact.data.memory.MemoryRepository
import ai.impact.data.memory.MemorySource
import ai.impact.image.PromptEnhancement
import ai.impact.models.CatalogTextModel
import ai.impact.models.toDownloadSpec
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class ChatMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** How long the model spent in its <think> block, once that block has closed. */
    val thinkingMillis: Long? = null,
    /** Gallery id of an image the assistant made for this message. */
    val imageId: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    /** Live progress while that image is being painted. */
    val imageProgress: GenerationState.Running? = null
) {
    val isImage: Boolean get() = imageId != null || imageProgress != null
}

/** What sending a message in the chat produces. */
enum class ChatMode { Text, Image }

/** Paints images for the chat; implemented by the image studio's engine. */
interface ChatImageGenerator {
    /** Whether any image model is installed (it's loaded on demand). */
    val hasModel: Boolean
    suspend fun generate(prompt: String, onState: (GenerationState.Running) -> Unit): GenerationState.Done
}

sealed interface ModelState {
    data object NotLoaded : ModelState
    data class Loading(val name: String, val uri: String? = null) : ModelState
    data class Ready(val name: String, val uri: String? = null) : ModelState
    data class Error(val message: String) : ModelState
}

private enum class PromptFormat { Llama3, Gemma, ChatML }

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val CONTEXT_LENGTH = 2048
        private const val CHARS_PER_TOKEN = 3.5
        // Largest model file, as a share of total device RAM, that we'll try to load.
        private const val MAX_MODEL_RAM_FRACTION = 0.6

        // Markers that mean the model has finished its turn and started hallucinating the next one.
        private val STOP_MARKERS = listOf(
            "<|eot_id|>", "<|im_end|>", "<|im_start|>", "<|start_header_id|>",
            "<end_of_turn>", "<start_of_turn>", "<|end_of_text|>", "</s>"
        )
    }

    private val app = application as ImpactApp
    val preferences = app.preferences
    private val history = app.chatHistory
    private val memory = app.memory
    private val memoryExtractor = app.memoryExtractor
    private val textModels = app.textModels

    /** Downloaded chat models and in-progress downloads, for the Models screen. */
    val installedTextModels = textModels.installed
    val textDownloads = textModels.downloads

    private val _chatMode = MutableStateFlow(ChatMode.entries.getOrNull(preferences.chatMode) ?: ChatMode.Text)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    /** Set by the activity: makes images when the chat is in image mode. */
    var imageGenerator: ChatImageGenerator? = null
    private var imageJob: Job? = null

    /** All saved conversations, newest first, for the history screen. */
    val conversations = history.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything remembered about the user, for the memory screen and the prompt. */
    val memories: StateFlow<List<MemoryEntity>> = memory.memories
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _memoryEvents = MutableSharedFlow<MemoryEntity>(extraBufferCapacity = 8)
    /** Emits each memory learned from chat, so the chat can briefly confirm it. */
    val memoryEvents: SharedFlow<MemoryEntity> = _memoryEvents.asSharedFlow()

    val allPersonas: StateFlow<List<Persona>> = preferences.customPersonas
        .map { BuiltInPersonas.all + it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BuiltInPersonas.all + preferences.customPersonas.value)

    val persona: StateFlow<Persona> = combine(preferences.selectedPersonaId, allPersonas) { id, list ->
        list.firstOrNull { it.id == id } ?: BuiltInPersonas.all.first()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, resolvePersona(preferences.selectedPersonaId.value))

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState = _modelState.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    /**
     * A conversation on screen. [dbId] stays null until its first message is saved, so empty
     * "new chats" never clutter the history.
     */
    private class Session(val personaId: String, var dbId: Long? = null, var messages: List<ChatMessage> = emptyList())

    // The open conversation for each persona, so switching personas doesn't lose your place.
    private val sessions = mutableMapOf<String, Session>()
    private var active: Session = Session(persona.value.id).also { sessions[it.personaId] = it }
    private var generating: Session? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    /** Database id of the conversation on screen (null for a new, unsaved chat). */
    val activeConversationId = _activeConversationId.asStateFlow()

    // Database writes run strictly in order (create conversation, then insert messages) on one consumer.
    private val dbQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var nextMessageId = 0L
    private var promptFormat = PromptFormat.ChatML

    private val _llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 256)

    /** A single prompt run outside any chat (e.g. enhancing an image prompt); its tokens never reach the chat. */
    private class OneShot(val maxChars: Int) {
        val text = StringBuilder()
        val result = CompletableDeferred<String>()
    }

    @Volatile
    private var oneShot: OneShot? = null

    // Background scope for LlamaHelper so native work never blocks the UI thread.
    // The handler keeps engine failures that aren't Exceptions (e.g. UnsatisfiedLinkError) from crashing the app.
    private val backgroundScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Engine failure", e)
            if (_modelState.value is ModelState.Loading) {
                onModelLoadFailed("Couldn't load the model: ${e.message ?: "unknown error"}")
            }
        }
    )
    private val llamaHelper = LlamaHelper(application.contentResolver, backgroundScope, _llmFlow)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (job in dbQueue) {
                try { job() } catch (e: Exception) { Log.e(TAG, "History write failed", e) }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            _llmFlow.collect(::handleEvent)
        }
        restoreLatest(active)
        viewModelScope.launch {
            persona.collect { switchTo(it.id) }
        }
        restoreSavedModel()
    }


    private fun persist(block: suspend () -> Unit) {
        dbQueue.trySend(block)
    }

    private fun resolvePersona(id: String): Persona =
        (BuiltInPersonas.all + preferences.customPersonas.value).firstOrNull { it.id == id }
            ?: BuiltInPersonas.all.first()

    private fun handleEvent(event: LlamaHelper.LLMEvent) {
        val loading = _modelState.value as? ModelState.Loading
        if (event is LlamaHelper.LLMEvent.Error && loading != null) {
            Log.e(TAG, "Model load failed: ${event.message}")
            onModelLoadFailed("Couldn't load \"${loading.name}\". The file may be incomplete or not a supported model.")
            return
        }
        oneShot?.let { shot ->
            handleOneShot(shot, event)
            return
        }
        val session = generating ?: return
        when (event) {
            is LlamaHelper.LLMEvent.Ongoing -> {
                var hitStopMarker = false
                updateSession(session) { current ->
                    val last = current.lastOrNull()
                    if (last == null || last.isUser || !last.isStreaming) return@updateSession current
                    var text = last.text + event.word
                    val markerIndex = STOP_MARKERS.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
                    if (markerIndex != null) {
                        text = text.substring(0, markerIndex)
                        hitStopMarker = true
                    }
                    val thinkingMillis = last.thinkingMillis
                        ?: if (ResponseParser.hasFinishedThinking(text)) System.currentTimeMillis() - last.createdAt else null
                    current.dropLast(1) + last.copy(text = text, thinkingMillis = thinkingMillis)
                }
                if (hitStopMarker) {
                    llamaHelper.stopPrediction()
                    finishGeneration(session)
                }
            }
            is LlamaHelper.LLMEvent.Done -> finishGeneration(session)
            is LlamaHelper.LLMEvent.Error -> {
                Log.e(TAG, "LLM Error: ${event.message}")
                updateSession(session) { current ->
                    val withoutEmpty = current.filterNot { !it.isUser && it.isStreaming && it.text.isBlank() }
                    withoutEmpty.map { if (it.isStreaming) it.copy(isStreaming = false) else it } +
                        newMessage("Something went wrong: ${event.message}", isUser = false, isError = true)
                }
                generating = null
                _isGenerating.value = false
            }
            else -> Log.d(TAG, "LLMEvent: $event")
        }
    }

    private fun handleOneShot(shot: OneShot, event: LlamaHelper.LLMEvent) {
        when (event) {
            is LlamaHelper.LLMEvent.Ongoing -> {
                shot.text.append(event.word)
                val text = shot.text.toString()
                val marker = STOP_MARKERS.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
                if (marker != null || text.length >= shot.maxChars) {
                    runCatching { llamaHelper.stopPrediction() }
                    shot.result.complete(if (marker != null) text.substring(0, marker) else text)
                }
            }
            is LlamaHelper.LLMEvent.Done -> shot.result.complete(shot.text.toString())
            is LlamaHelper.LLMEvent.Error -> {
                Log.w(TAG, "One-shot prompt failed: ${event.message}")
                shot.result.complete("")
            }
            else -> Unit
        }
    }

    private fun finishGeneration(session: Session) {
        if (generating !== session) return
        var reply: ChatMessage? = null
        updateSession(session) { current ->
            current.map {
                if (it.isStreaming) it.copy(text = it.text.trim().ifEmpty { "…" }, isStreaming = false).also { done -> reply = done }
                else it
            }
        }
        generating = null
        _isGenerating.value = false

        reply?.takeIf { it.text != "…" }?.let { message ->
            persist {
                val id = session.dbId ?: return@persist
                history.addMessage(id, StoredMessage(false, message.text, message.createdAt, message.thinkingMillis))
            }
        }
    }

    // region Model loading

    private fun restoreSavedModel() {
        preferences.interruptedModelLoad?.let { name ->
            // The app died while loading this model last time, so say why instead of failing silently.
            preferences.setModelLoading(null)
            _modelState.value = ModelState.Error(
                "The app closed while loading \"$name\". It probably needs more memory than this device has. Try a smaller model."
            )
            return
        }
        val saved = preferences.modelUri ?: return
        val uri = Uri.parse(saved)
        // Downloaded models are plain files; picked ones need their read permission to have survived.
        val usable = if (uri.scheme == "file") {
            File(uri.path.orEmpty()).isFile
        } else {
            getApplication<Application>().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }
        if (usable) {
            loadModel(uri, persist = false, label = preferences.modelName)
        } else {
            preferences.setModel(null, null)
        }
    }

    fun onModelPicked(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist URI permission", e)
        }
        loadModel(uri, persist = true)
    }

    /** Loads a downloaded chat model from the Models screen. */
    fun loadTextModel(id: String) {
        val model = textModels.find(id) ?: return
        loadModel(Uri.fromFile(model.file), persist = true, label = model.name)
    }

    fun deleteTextModel(id: String) {
        val model = textModels.find(id)
        if (model != null && currentModelUri() == Uri.fromFile(model.file).toString()) unloadModel()
        viewModelScope.launch(Dispatchers.IO) { textModels.delete(id) }
    }

    /** Returns an error to show, or null once queued. */
    fun startTextDownload(model: CatalogTextModel): String? = textModels.startDownload(model.toDownloadSpec())

    fun cancelTextDownload(id: String) = textModels.cancelDownload(id)

    fun dismissTextDownloadError(id: String) = textModels.dismissDownloadError(id)

    private fun currentModelUri(): String? = when (val state = _modelState.value) {
        is ModelState.Ready -> state.uri
        is ModelState.Loading -> state.uri
        else -> null
    }

    private fun loadModel(uri: Uri, persist: Boolean, label: String? = null) {
        val fileName = displayName(uri)
        // The file name says which chat template the model expects; the label is just for display.
        val name = label?.takeIf { it.isNotBlank() } ?: fileName
        val lower = fileName.lowercase()
        promptFormat = when {
            "llama" in lower -> PromptFormat.Llama3
            "gemma" in lower -> PromptFormat.Gemma
            else -> PromptFormat.ChatML
        }
        Log.d(TAG, "Loading $name with format $promptFormat")

        if (!lower.endsWith(".gguf")) {
            _modelState.value = ModelState.Error("\"$fileName\" isn't a .gguf model file. Please pick a GGUF model.")
            return
        }

        tooLargeMessage(uri, name)?.let {
            _modelState.value = ModelState.Error(it)
            return
        }

        stopGeneration()
        _modelState.value = ModelState.Loading(name, uri.toString())

        viewModelScope.launch(Dispatchers.IO) {
            // Only a first-time load is marked: a model that has loaded before is known to fit.
            if (persist) preferences.setModelLoading(name)
            try {
                // Drop the previous model ourselves so LlamaHelper never keeps a stale context id.
                try {
                    llamaHelper.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Release failed", e)
                }
                llamaHelper.load(
                    path = uri.toString(),
                    contextLength = CONTEXT_LENGTH,
                    loaded = {
                        Log.d(TAG, "Model loaded")
                        preferences.setModelLoading(null)
                        // Saved only once it works, so a model that crashes the app is never auto-loaded at launch.
                        if (persist) preferences.setModel(uri.toString(), name)
                        _modelState.value = ModelState.Ready(name, uri.toString())
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                onModelLoadFailed("Couldn't load the model: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun onModelLoadFailed(message: String) {
        preferences.setModelLoading(null)
        _modelState.value = ModelState.Error(message)
    }

    /**
     * The engine reads the whole file into RAM rather than memory-mapping it, so a model that
     * doesn't fit gets the app killed by the system instead of failing with an error.
     */
    private fun tooLargeMessage(uri: Uri, name: String): String? {
        val context = getApplication<Application>()
        val size = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        } catch (e: Exception) {
            return null
        }
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if (size <= memory.totalMem * MAX_MODEL_RAM_FRACTION) return null
        return "\"$name\" is ${Formatter.formatShortFileSize(context, size)}, too large for this device's " +
            "${Formatter.formatShortFileSize(context, memory.totalMem)} of memory. Try a smaller model."
    }

    fun unloadModel() {
        stopGeneration()
        try {
            llamaHelper.release()
        } catch (e: Exception) {
            Log.w(TAG, "Release failed", e)
        }
        preferences.setModel(null, null)
        _modelState.value = ModelState.NotLoaded
    }

    private fun displayName(uri: Uri): String {
        return try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
    }

    // endregion

    // region Chat

    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
        preferences.setChatMode(mode.ordinal)
    }

    /** Sends [userText] as a chat message or an image request, depending on the mode. */
    fun send(userText: String) {
        if (_chatMode.value == ChatMode.Image) requestImage(userText) else sendMessage(userText)
    }

    private fun requestImage(userText: String) {
        val text = userText.trim()
        val generator = imageGenerator
        if (text.isEmpty() || _isGenerating.value || generator == null) return

        val session = active
        val userMessage = newMessage(text, isUser = true)
        val placeholder = newMessage("", isUser = false, isStreaming = true)
        updateSession(session) { it + userMessage + placeholder }
        generating = session
        _isGenerating.value = true
        persist {
            val id = session.dbId ?: history.createConversation(session.personaId, text).also { newId ->
                session.dbId = newId
                if (session === active) _activeConversationId.value = newId
            }
            history.addMessage(id, StoredMessage(true, text, userMessage.createdAt, null))
        }

        fun replacePlaceholder(transform: (ChatMessage) -> ChatMessage?) = updateSession(session) { current ->
            current.mapNotNull { if (it.id == placeholder.id) transform(it) else it }
        }

        imageJob = viewModelScope.launch {
            try {
                val done = generator.generate(text) { running ->
                    replacePlaceholder { it.copy(imageProgress = running) }
                }
                val image = done.image
                // The caption is the enhanced prompt, when the text model rewrote it.
                val caption = image.enhancedPrompt.orEmpty()
                replacePlaceholder {
                    it.copy(
                        text = caption, isStreaming = false, imageProgress = null,
                        imageId = image.id, imageWidth = image.width, imageHeight = image.height
                    )
                }
                persist {
                    val id = session.dbId ?: return@persist
                    history.addMessage(
                        id,
                        StoredMessage(false, caption.ifEmpty { text }, placeholder.createdAt, null, image.id, image.width, image.height)
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                replacePlaceholder { null }
            } catch (e: Exception) {
                Log.w(TAG, "Chat image failed", e)
                replacePlaceholder {
                    newMessage(e.message ?: "Couldn't create the image.", isUser = false, isError = true)
                }
            } finally {
                if (generating === session) generating = null
                imageJob = null
                _isGenerating.value = false
            }
        }
    }

    fun sendMessage(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || _modelState.value !is ModelState.Ready || _isGenerating.value || oneShot != null) return

        val session = active
        val userMessage = newMessage(text, isUser = true)
        updateSession(session) { it + userMessage }
        val prompt = buildPrompt(persona.value, session.messages)
        updateSession(session) { it + newMessage("", isUser = false, isStreaming = true) }

        generating = session
        _isGenerating.value = true

        persist {
            val id = session.dbId ?: history.createConversation(session.personaId, text).also { newId ->
                session.dbId = newId
                if (session === active) _activeConversationId.value = newId
            }
            history.addMessage(id, StoredMessage(true, text, userMessage.createdAt, null))
        }
        if (preferences.autoMemory.value) learnFrom(text)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                llamaHelper.predict(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "predict() failed", e)
                handleEvent(LlamaHelper.LLMEvent.Error(e.message ?: "prediction failed"))
            }
        }
    }

    fun stopGeneration() {
        imageJob?.let {
            it.cancel()
            return
        }
        val session = generating ?: return
        try {
            llamaHelper.stopPrediction()
        } catch (e: Exception) {
            Log.w(TAG, "stopPrediction failed", e)
        }
        finishGeneration(session)
    }

    /** Starts a fresh conversation with the current persona. The previous one stays in history. */
    fun newChat() {
        stopGeneration()
        show(Session(active.personaId).also { sessions[it.personaId] = it })
    }

    /** Opens a saved conversation, switching to its persona. */
    fun openConversation(conversationId: Long) {
        viewModelScope.launch {
            val conversation = history.conversation(conversationId) ?: return@launch
            val existing = sessions[conversation.personaId]?.takeIf { it.dbId == conversationId }
            val session = existing ?: run {
                stopGeneration()
                Session(conversation.personaId, conversationId, history.messages(conversationId).map(::toChatMessage))
            }
            sessions[conversation.personaId] = session
            show(session)
            preferences.selectPersona(conversation.personaId)
        }
    }

    fun deleteConversation(conversationId: Long) {
        sessions.values.filter { it.dbId == conversationId }.toList().forEach { session ->
            if (generating === session) stopGeneration()
            val fresh = Session(session.personaId)
            sessions[session.personaId] = fresh
            if (active === session) show(fresh)
        }
        persist { history.delete(conversationId) }
    }

    fun clearHistory() {
        stopGeneration()
        sessions.clear()
        show(Session(active.personaId).also { sessions[it.personaId] = it })
        persist { history.deleteAll() }
    }

    fun selectPersona(id: String) {
        preferences.selectPersona(id)
    }

    fun deletePersona(id: String) {
        if (generating?.personaId == id) stopGeneration()
        sessions.remove(id)
        preferences.deleteCustomPersona(id)
        persist { history.deleteForPersona(id) }
    }

    private fun switchTo(personaId: String) {
        if (active.personaId == personaId) return
        show(sessions.getOrPut(personaId) { Session(personaId).also(::restoreLatest) })
    }

    /** Resumes the persona's most recent saved conversation, so context survives app restarts. */
    private fun restoreLatest(session: Session) {
        viewModelScope.launch {
            val latest = history.latestConversation(session.personaId) ?: return@launch
            val stored = history.messages(latest.id)
            if (session.dbId != null || session.messages.isNotEmpty()) return@launch // user already started chatting
            session.dbId = latest.id
            updateSession(session) { stored.map(::toChatMessage) }
            if (session === active) _activeConversationId.value = latest.id
        }
    }

    private fun show(session: Session) {
        active = session
        _messages.value = session.messages
        _activeConversationId.value = session.dbId
    }

    private fun updateSession(session: Session, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        synchronized(session) {
            session.messages = transform(session.messages)
            if (session === active) _messages.value = session.messages
        }
    }

    private fun toChatMessage(stored: StoredMessage) = ChatMessage(
        nextMessageId++,
        stored.text,
        stored.isUser,
        createdAt = stored.createdAt,
        thinkingMillis = stored.thinkingMillis,
        imageId = stored.imageId,
        imageWidth = stored.imageWidth,
        imageHeight = stored.imageHeight
    )

    // endregion

    // region One-shot prompts

    /**
     * Runs one system + user prompt through the loaded model, outside any conversation. Returns
     * null if no model is loaded or it's busy answering a chat, so callers can carry on without it.
     */
    suspend fun completeOnce(system: String, user: String, maxChars: Int = 2_000): String? {
        val chatStreaming = _isGenerating.value && imageJob == null
        if (_modelState.value !is ModelState.Ready || chatStreaming || oneShot != null) return null
        val shot = OneShot(maxChars)
        oneShot = shot
        try {
            withContext(Dispatchers.IO) { llamaHelper.predict(chatTemplate(system, listOf(true to user))) }
            return shot.result.await()
        } catch (e: CancellationException) {
            runCatching { llamaHelper.stopPrediction() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "One-shot prompt failed", e)
            runCatching { llamaHelper.stopPrediction() }
            return null
        } finally {
            oneShot = null
        }
    }

    /** Turns a short idea into a detailed Stable Diffusion prompt, or null if the chat model can't. */
    suspend fun enhanceImagePrompt(idea: String, styleHint: String): String? {
        val raw = completeOnce(PromptEnhancement.systemPrompt(styleHint), idea) ?: return null
        return PromptEnhancement.clean(raw)
    }

    // endregion

    // region Memory

    private fun learnFrom(userText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryExtractor.extract(userText).forEach { found ->
                memory.remember(found.content, found.category, MemorySource.Auto)?.let { _memoryEvents.tryEmit(it) }
            }
        }
    }

    /** Explicit "Remember this" on a chat message. */
    fun rememberMessage(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val extracted = memoryExtractor.extract(text)
            if (extracted.isEmpty()) {
                memory.remember(text.trim().take(140), MemoryCategory.Other, MemorySource.Manual)?.let { _memoryEvents.tryEmit(it) }
            } else {
                extracted.forEach { found ->
                    memory.remember(found.content, found.category, MemorySource.Manual)?.let { _memoryEvents.tryEmit(it) }
                }
            }
        }
    }

    fun addMemory(content: String, category: MemoryCategory) {
        viewModelScope.launch(Dispatchers.IO) { memory.remember(content, category, MemorySource.Manual) }
    }

    fun updateMemory(updated: MemoryEntity) {
        viewModelScope.launch(Dispatchers.IO) { memory.update(updated) }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { memory.delete(id) }
    }

    fun clearMemory() {
        viewModelScope.launch(Dispatchers.IO) { memory.deleteAll() }
    }

    // endregion

    // region Prompt

    private fun newMessage(text: String, isUser: Boolean, isStreaming: Boolean = false, isError: Boolean = false) =
        ChatMessage(nextMessageId++, text, isUser, isStreaming, isError)

    /**
     * Builds a multi-turn prompt in the model's native chat template, keeping as much recent
     * history as fits in the context window after reserving room for the reply.
     */
    private fun buildPrompt(persona: Persona, history: List<ChatMessage>): String {
        val wordLimit = preferences.wordLimit.value
        val memoryContext = if (preferences.useMemory.value) MemoryRepository.formatForPrompt(memories.value) else null
        val system = buildString {
            append(persona.fullSystemPrompt(preferences.userName.value, wordLimit))
            if (memoryContext != null) {
                append("\n\nWhat you remember about the user from past conversations ")
                append("(use it naturally when relevant; never recite the list):\n")
                append(memoryContext)
            }
        }
        val replyTokens = (wordLimit * 1.5).toInt() + 32
        val budgetChars = ((CONTEXT_LENGTH - replyTokens) * CHARS_PER_TOKEN).toInt() - system.length

        val turns = ArrayDeque<ChatMessage>()
        var used = 0
        // Image requests and the images they produced aren't conversation the text model can follow.
        val imageTurns = history.indices.filter { i ->
            history[i].isImage || (history[i].isUser && history.getOrNull(i + 1)?.isImage == true)
        }.map { history[it].id }.toSet()
        for (message in history.asReversed()) {
            if (message.isError || message.text.isBlank() || message.id in imageTurns) continue
            // Feed back only the visible answer; the model's reasoning would just eat the context window.
            val text = if (message.isUser) message.text else ResponseParser.parse(message.text).answer
            if (text.isBlank()) continue
            used += text.length + 32
            if (used > budgetChars && turns.isNotEmpty()) break
            turns.addFirst(message.copy(text = text))
        }
        // Templates expect the conversation to start with a user turn.
        while (turns.isNotEmpty() && !turns.first().isUser) turns.removeFirst()

        return chatTemplate(system, turns.map { it.isUser to it.text })
    }

    /** Formats a system prompt and alternating turns (isUser to text) in the loaded model's chat template. */
    private fun chatTemplate(system: String, turns: List<Pair<Boolean, String>>): String {
        return when (promptFormat) {
            PromptFormat.Llama3 -> buildString {
                append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|>")
                turns.forEach { (isUser, text) ->
                    val role = if (isUser) "user" else "assistant"
                    append("<|start_header_id|>$role<|end_header_id|>\n\n$text<|eot_id|>")
                }
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            PromptFormat.Gemma -> buildString {
                turns.forEachIndexed { index, (isUser, text) ->
                    val role = if (isUser) "user" else "model"
                    val body = if (index == 0) "$system\n\n$text" else text
                    append("<start_of_turn>$role\n$body<end_of_turn>\n")
                }
                append("<start_of_turn>model\n")
            }
            PromptFormat.ChatML -> buildString {
                append("<|im_start|>system\n$system<|im_end|>\n")
                turns.forEach { (isUser, text) ->
                    val role = if (isUser) "user" else "assistant"
                    append("<|im_start|>$role\n$text<|im_end|>\n")
                }
                append("<|im_start|>assistant\n")
            }
        }
    }

    // endregion

    override fun onCleared() {
        super.onCleared()
        try {
            llamaHelper.release()
        } catch (e: Exception) {
            Log.w(TAG, "Release failed", e)
        }
        backgroundScope.cancel()
    }
}
