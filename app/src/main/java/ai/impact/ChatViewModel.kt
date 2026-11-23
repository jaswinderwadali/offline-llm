package ai.impact

import kotlinx.coroutines.delay
import ai.impact.speech.VoiceGuard
import ai.impact.speech.SpeakableText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import ai.impact.data.BuiltInPersonas
import ai.impact.data.Persona
import ai.impact.data.ReplyLimiter
import ai.impact.data.ResponseParser
import ai.impact.data.history.StoredMessage
import ai.impact.data.memory.MemoryCategory
import ai.impact.data.memory.MemoryEntity
import ai.impact.data.memory.MemoryRepository
import ai.impact.data.memory.MemorySource
import ai.impact.data.documents.DocumentContext
import ai.impact.data.documents.DocumentSource
import ai.impact.data.documents.SourcesTag
import ai.impact.data.skills.BuiltInSkills
import ai.impact.data.skills.QrCodes
import ai.impact.data.skills.Skill
import ai.impact.data.skills.SkillAction
import ai.impact.data.skills.SkillTag
import ai.impact.data.skills.PhoneNumbers
import ai.impact.data.skills.SkillRouter
import ai.impact.data.skills.ShareTarget
import ai.impact.data.skills.TimeParser
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
    val imageProgress: GenerationState.Running? = null,
    /** The skill this message was sent with, or that produced this reply. */
    val skillId: String? = null,
    /** Skill-specific detail: a reminder's time, a WhatsApp chat's phone number. */
    val skillArg: String? = null,
    /** The user's documents this reply drew on, shown as chips under it. */
    val sources: List<DocumentSource> = emptyList()
) {
    val isImage: Boolean get() = imageId != null || imageProgress != null
    val skill: Skill? get() = BuiltInSkills.byId(skillId)
    /** The user didn't pick [skill]; it was chosen from what they wrote. */
    val isAutoSkill: Boolean get() = isUser && skillId != null && skillArg == ChatViewModel.AUTO_SKILL
}

enum class VoicePhase { Listening, Thinking, Speaking, Paused }

/** What voice mode is doing, for its screen. */
data class VoiceState(
    val phase: VoicePhase,
    /** Microphone loudness, 0 to 1, while listening. */
    val level: Float = 0f,
    /** What the user said (or what's recognised so far). */
    val heard: String = "",
    /** What's being read aloud. */
    val reply: String = "",
    /** Why it paused, when it did. */
    val notice: String? = null,
    /** When this phase began, for the timer on screen. Level and caption updates keep it. */
    val since: Long = System.currentTimeMillis()
)

/** Something a skill needs the screen to do, since only it can start other apps. */
sealed interface SkillEffect {
    data class AddToCalendar(val title: String, val triggerAt: Long) : SkillEffect
    /** Open [target]'s app with [text] ready to send (to [phone]'s chat, for WhatsApp). */
    data class SendToApp(val target: ShareTarget, val text: String, val phone: String?) : SkillEffect
}

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
        /** Marks a user message whose skill was picked automatically (stored as its skill arg). */
        const val AUTO_SKILL = "auto"
        /** Voice mode stops listening after this long in one turn. */
        const val VOICE_LISTEN_LIMIT_MS = 3 * 60_000L
        /** Voice mode gives up on a reply that takes longer than this. */
        const val VOICE_THINK_LIMIT_MS = 5 * 60_000L
        /** A reply with nothing new for this long is treated as stuck. Long, since a slow phone can take a while to start. */
        const val VOICE_STALL_MS = 90_000L
        /** Voice mode pauses after this many problems in a row instead of apologising again. */
        const val VOICE_MAX_TROUBLES = 3
        private const val TAG = "ChatViewModel"
        private const val CONTEXT_LENGTH = 2048
        /** Room for document excerpts: about 750 tokens, a little over a third of the context. */
        private const val MAX_DOCUMENT_CHARS = 2_600
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
    /** Notifications scheduled by the "Schedule a notification" skill. */
    val reminders = app.reminders

    /** Listening and speaking, for voice mode and the read-aloud button. */
    val speech = app.speech
    private val _voice = MutableStateFlow<VoiceState?>(null)
    /** Hands-free voice conversation; null when it's off. */
    val voice: StateFlow<VoiceState?> = _voice.asStateFlow()
    private var voiceJob: Job? = null
    private val _speakingMessageId = MutableStateFlow<Long?>(null)
    /** The reply being read aloud by its speaker button, if any. */
    val speakingMessageId: StateFlow<Long?> = _speakingMessageId.asStateFlow()
    private var readAloudJob: Job? = null

    private val _skillEffects = MutableSharedFlow<SkillEffect>(extraBufferCapacity = 4)
    val skillEffects: SharedFlow<SkillEffect> = _skillEffects.asSharedFlow()

    /** Downloaded chat models and in-progress downloads, for the Models screen. */
    val installedTextModels = textModels.installed
    val textDownloads = textModels.downloads

    private val documents = app.documents
    /** Whether the user has documents chat can search (and the model to search them). */
    val documentsSearchable: StateFlow<Boolean> = documents.searchable
    private val _useDocuments = MutableStateFlow(true)
    /** Whether the open conversation searches the user's documents; on by default. */
    val useDocuments: StateFlow<Boolean> = _useDocuments.asStateFlow()
    private val _excludedDocuments = MutableStateFlow<Set<Long>>(emptySet())
    /** Documents the user unselected for the open conversation. */
    val excludedDocuments: StateFlow<Set<Long>> = _excludedDocuments.asStateFlow()
    // Looking up the user's documents before the model starts answering.
    private var retrievalJob: Job? = null

    /** Set by the activity: makes images when a message asks for one. */
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
    private class Session(
        val personaId: String,
        var dbId: Long? = null,
        var messages: List<ChatMessage> = emptyList(),
        var useDocuments: Boolean = true,
        /** Documents unselected in this chat. Unlisted ones are used, so new documents start selected. */
        var excludedDocuments: Set<Long> = emptySet()
    )

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
                    // The engine can't cap reply length, so stop once the answer runs past the chosen length.
                    ReplyLimiter.clip(text, preferences.wordLimit.value)?.let { clipped ->
                        text = clipped
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

    private fun finishGeneration(session: Session, userStopped: Boolean = false) {
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
            // A finished draft goes straight to its app; the user still reviews it there before sending.
            val target = message.skill?.share
            val answer = ResponseParser.parse(message.text).answer
            if (target != null && !userStopped && answer.isNotBlank()) {
                _skillEffects.tryEmit(SkillEffect.SendToApp(target, answer, message.skillArg))
            }
            persist {
                val id = session.dbId ?: return@persist
                val stored = SourcesTag.encode(message.sources, SkillTag.encode(message.skillId, message.skillArg, message.text))
                history.addMessage(id, StoredMessage(false, stored, message.createdAt, message.thinkingMillis))
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

    fun setSkillEnabled(id: String, enabled: Boolean) = preferences.setSkillEnabled(id, enabled)

    fun cancelNotification(id: Int) = reminders.cancel(id)

    // region Voice

    /**
     * Starts hands-free voice mode: listen, send what was heard, read the reply aloud, and listen
     * again, until [stopVoice]. Two silences in a row pause it until the user taps.
     */
    fun startVoice() {
        stopReadAloud()
        voiceJob?.cancel()
        _voice.value = VoiceState(VoicePhase.Listening)
        voiceJob = viewModelScope.launch { voiceLoop() }
    }

    fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
        speech.finishListening()
        speech.stopSpeaking()
        _voice.value = null
    }

    /** A tap in voice mode: interrupts the voice, finishes listening, or resumes after a pause. */
    fun voiceTap() {
        when (_voice.value?.phase) {
            VoicePhase.Speaking -> speech.stopSpeaking()
            VoicePhase.Listening -> speech.finishListening()
            VoicePhase.Paused -> startVoice()
            else -> Unit
        }
    }

    /** How waiting for a voice reply ended. */
    private sealed interface VoiceReply {
        data class Done(val message: ChatMessage) : VoiceReply
        /** Still going at the time limit. */
        data object TimedOut : VoiceReply
        /** Nothing new for a long while, or no reply ever started. */
        data object Stuck : VoiceReply
    }

    private suspend fun voiceLoop() {
        var silences = 0
        // Problems in a row: misheard, no answer, a jumbled answer, a stuck model.
        var troubles = 0

        /** Says sorry and listens again, or pauses after too many problems in a row. */
        suspend fun recover(heard: String, sorry: String): Boolean {
            troubles++
            if (troubles >= VOICE_MAX_TROUBLES) {
                val notice = "I'm having trouble right now. Tap to try again, or pick another chat model in the Models tab."
                _voice.value = VoiceState(VoicePhase.Speaking, heard = heard, reply = notice)
                speakQuietly(notice)
                _voice.value = VoiceState(VoicePhase.Paused, notice = notice)
                return false
            }
            _voice.value = VoiceState(VoicePhase.Speaking, heard = heard, reply = sorry)
            speakQuietly(sorry)
            return true
        }

        while (true) {
            _voice.value = VoiceState(VoicePhase.Listening)
            val heard = try {
                withTimeoutOrNull(VOICE_LISTEN_LIMIT_MS) {
                    speech.listen(
                        onLevel = { level -> _voice.value = _voice.value?.copy(level = level) },
                        onPartial = { text -> _voice.value = _voice.value?.copy(heard = text) }
                    )
                } ?: run {
                    speech.finishListening()
                    _voice.value = VoiceState(VoicePhase.Paused, notice = "I stopped listening after 3 minutes. Tap to talk again.")
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Listening failed", e)
                _voice.value = VoiceState(VoicePhase.Paused, notice = e.message ?: "Couldn't hear you. Tap to try again.")
                return
            }
            if (heard.isBlank()) {
                silences++
                if (silences >= 2) {
                    _voice.value = VoiceState(VoicePhase.Paused, notice = "Tap when you're ready to talk.")
                    return
                }
                continue
            }
            silences = 0

            // Speech recognition can make words up from noise; don't send those.
            val request = VoiceGuard.cleanTranscript(heard)
            if (request == null) {
                Log.i(TAG, "Voice: ignored an unclear transcript")
                if (!recover(heard, "Sorry, I didn't catch that. Could you say it again?")) return
                continue
            }
            _voice.value = VoiceState(VoicePhase.Thinking, heard = request)

            // Spoken requests use skills just like typed ones: "remind me at 6pm to call mom".
            val routeSkills = preferences.autoSkills.value || persona.value.agentic
            val skill = if (routeSkills) SkillRouter.detect(request, preferences.enabledSkillIds.value) else null
            if (_modelState.value !is ModelState.Ready && (skill == null || skill.needsModel)) {
                val notice = "Load a chat model first, from the Models tab."
                _voice.value = VoiceState(VoicePhase.Speaking, heard = request, reply = notice)
                speakQuietly(notice)
                _voice.value = VoiceState(VoicePhase.Paused, notice = notice)
                return
            }
            val before = active.messages.size
            send(request, skill, auto = skill != null)

            val message = when (val outcome = awaitVoiceReply(before)) {
                is VoiceReply.Done -> outcome.message
                VoiceReply.TimedOut -> {
                    // Still going after 5 minutes: stop it rather than keep the user waiting.
                    stopGeneration()
                    val notice = "Sorry, that took more than 5 minutes, so I stopped. Try a shorter question."
                    _voice.value = VoiceState(VoicePhase.Speaking, heard = request, reply = notice)
                    speakQuietly(notice)
                    _voice.value = VoiceState(VoicePhase.Paused, notice = notice)
                    return
                }
                VoiceReply.Stuck -> {
                    Log.w(TAG, "Voice: the reply stalled")
                    stopGeneration()
                    if (!recover(request, "Sorry, I got stuck on that one. Could you ask it again?")) return
                    continue
                }
            }

            val spoken: String? = when {
                message.isImage -> "Here's your picture."
                message.isError -> null.also { Log.w(TAG, "Voice: reply failed: ${message.text}") }
                message.skill?.isTool == true -> "Done. ${message.text}"
                else -> when (val check = VoiceGuard.checkReply(ResponseParser.parse(message.text).answer)) {
                    is VoiceGuard.Reply.Ok -> SpeakableText.from(check.text).ifBlank { null }
                    VoiceGuard.Reply.Empty -> null.also { Log.w(TAG, "Voice: empty reply") }
                    VoiceGuard.Reply.Garbled -> {
                        Log.w(TAG, "Voice: garbled reply")
                        if (!recover(request, "Sorry, my answer came out jumbled. Could you ask that again?")) return
                        continue
                    }
                }
            }
            if (spoken == null) {
                val sorry = if (message.isError) "Sorry, something went wrong with that answer. Let's try again."
                else "Sorry, I couldn't come up with an answer. Could you ask that another way?"
                if (!recover(request, sorry)) return
                continue
            }
            troubles = 0
            _voice.value = VoiceState(VoicePhase.Speaking, heard = request, reply = spoken)
            try {
                speech.speak(spoken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Speaking failed", e)
                _voice.value = VoiceState(VoicePhase.Paused, notice = e.message ?: "Couldn't speak the reply.")
                return
            }
        }
    }

    /**
     * Waits for the reply to the message just sent, however it's made (chat, skill, picture).
     * Gives up at the time limit, when nothing new appears for a long while, or when no reply
     * starts at all.
     */
    private suspend fun awaitVoiceReply(before: Int): VoiceReply {
        val started = System.currentTimeMillis()
        var lastProgress: Any? = null
        var lastChange = started
        while (true) {
            val messages = _messages.value
            val last = messages.lastOrNull()
            val generating = _isGenerating.value
            // The user's turn and a finished reply after it.
            if (!generating && messages.size >= before + 2 && last != null && !last.isUser && !last.isStreaming && last.imageProgress == null) {
                return VoiceReply.Done(last)
            }
            val now = System.currentTimeMillis()
            // Text growing or a picture painting both count as progress.
            val progress = last?.let { it.text.length to it.imageProgress?.phase?.toString() }
            if (progress != lastProgress) {
                lastProgress = progress
                lastChange = now
            }
            if (now - started > VOICE_THINK_LIMIT_MS) return VoiceReply.TimedOut
            if (generating && now - lastChange > VOICE_STALL_MS) return VoiceReply.Stuck
            // Nothing is being made and no reply came: the message was never answered.
            if (!generating && now - started > 3_000 && messages.size < before + 2) return VoiceReply.Stuck
            delay(250)
        }
    }

    /** Reads a notice aloud; if the voice fails, the notice is still shown on screen. */
    private suspend fun speakQuietly(text: String) {
        try {
            speech.speak(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read a notice aloud", e)
        }
    }

    /** What to say for a reply: its answer without markdown, or a short line for a picture or tool. */
    private fun spokenReply(message: ChatMessage): String = when {
        message.isImage -> "Here's your picture."
        message.isError -> message.text
        message.skill?.isTool == true -> "Done. ${message.text}"
        else -> SpeakableText.from(ResponseParser.parse(message.text).answer).ifBlank { "Sorry, I don't have an answer for that." }
    }

    /** Reads one reply aloud, or stops if it's the one already being read. */
    fun toggleReadAloud(message: ChatMessage) {
        if (_speakingMessageId.value == message.id) {
            stopReadAloud()
            return
        }
        stopReadAloud()
        _speakingMessageId.value = message.id
        readAloudJob = viewModelScope.launch {
            try {
                speech.speak(spokenReply(message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Read aloud failed", e)
            } finally {
                if (_speakingMessageId.value == message.id) _speakingMessageId.value = null
            }
        }
    }

    fun stopReadAloud() {
        readAloudJob?.cancel()
        readAloudJob = null
        speech.stopSpeaking()
        _speakingMessageId.value = null
    }

    // endregion

    /**
     * Sends [userText] as a chat message. With a [skill] (picked by the user or detected from the
     * message, such as "draw a cat"), the skill decides what happens to it instead.
     */
    fun send(userText: String, skill: Skill? = null, auto: Boolean = false) {
        val text = userText.trim().ifEmpty { skill?.emptyPrompt.orEmpty() }
        when (skill?.action) {
            null, SkillAction.Chat ->
                if (skill?.share != null && _modelState.value !is ModelState.Ready) sendWithoutDraft(text, skill, auto)
                else sendMessage(text, skill, auto)
            SkillAction.QrCode -> makeQrCode(text, skill, auto)
            SkillAction.Reminder, SkillAction.Notification -> scheduleReminder(text, skill, auto)
            SkillAction.Image -> requestImage(text, skill, auto)
        }
    }

    /**
     * Adds the user's turn and saves it, creating the conversation on its first message. An
     * [auto] turn is one whose skill was picked for the user, which the chat labels as such.
     */
    private fun addUserTurn(session: Session, text: String, skill: Skill?, auto: Boolean = false): ChatMessage {
        val arg = if (auto && skill != null) AUTO_SKILL else null
        val userMessage = newMessage(text, isUser = true).copy(skillId = skill?.id, skillArg = arg)
        updateSession(session) { it + userMessage }
        persist {
            val id = session.dbId ?: history.createConversation(session.personaId, text).also { newId ->
                session.dbId = newId
                if (session === active) _activeConversationId.value = newId
            }
            history.addMessage(id, StoredMessage(true, SkillTag.encode(skill?.id, arg, text), userMessage.createdAt, null))
        }
        return userMessage
    }

    /** Saves a finished reply to the conversation's history. */
    private fun persistReply(session: Session, reply: ChatMessage) = persist {
        val id = session.dbId ?: return@persist
        history.addMessage(id, StoredMessage(false, SkillTag.encode(reply.skillId, reply.skillArg, reply.text), reply.createdAt, null))
    }

    /** Adds a finished reply no model wrote (a QR code, a scheduled reminder) and saves it. */
    private fun addSkillReply(session: Session, reply: ChatMessage) {
        updateSession(session) { it + reply }
        persistReply(session, reply)
    }

    /** A reply explaining why a skill couldn't run; shown, but not saved. */
    private fun addNotice(session: Session, text: String) = updateSession(session) {
        it + newMessage(text, isUser = false, isError = true)
    }

    private fun endTurn(session: Session) {
        if (generating === session) generating = null
        _isGenerating.value = false
    }

    private fun startPrediction(prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                llamaHelper.predict(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "predict() failed", e)
                handleEvent(LlamaHelper.LLMEvent.Error(e.message ?: "prediction failed"))
            }
        }
    }

    /** With no chat model to draft it, a sharing skill sends the typed text to its app as it is. */
    private fun sendWithoutDraft(text: String, skill: Skill, auto: Boolean = false) {
        val target = skill.share ?: return
        if (text.isEmpty() || _isGenerating.value) return
        val session = active
        addUserTurn(session, text, skill, auto)
        val phone = if (target == ShareTarget.WhatsApp) PhoneNumbers.find(text) else null
        // A request like "send a WhatsApp to mom saying I'm late" sends just "I'm late".
        val body = if (auto) SkillRouter.messageBody(text) else text
        addSkillReply(session, newMessage(body, isUser = false).copy(skillId = skill.id, skillArg = phone))
        _skillEffects.tryEmit(SkillEffect.SendToApp(target, body, phone))
    }

    private fun makeQrCode(text: String, skill: Skill, auto: Boolean = false) {
        if (text.isEmpty() || _isGenerating.value) return
        val session = active
        addUserTurn(session, text, skill, auto)
        // "Make a QR code for example.com" encodes just "example.com".
        val content = if (auto) SkillRouter.qrContent(text) else text
        if (content.length > QrCodes.MAX_LENGTH) {
            addNotice(session, "That's too long for one QR code. Keep it under ${QrCodes.MAX_LENGTH} characters.")
        } else {
            addSkillReply(session, newMessage(content, isUser = false).copy(skillId = skill.id))
        }
    }

    private fun scheduleReminder(text: String, skill: Skill, auto: Boolean = false) {
        if (text.isEmpty() || _isGenerating.value) return
        val session = active
        addUserTurn(session, text, skill, auto)
        val parsed = TimeParser.parse(text)
        if (parsed == null) {
            addNotice(session, "When should I remind you? Add a time, like \"in 20 minutes\", \"at 6pm\" or \"tomorrow morning\".")
            return
        }
        if (skill.action == SkillAction.Notification) {
            val scheduled = reminders.schedule(parsed.title, parsed.triggerAt)
            val reply = newMessage(parsed.title, isUser = false)
            addSkillReply(session, reply.copy(skillId = skill.id, skillArg = "${scheduled.triggerAt};${scheduled.id}"))
        } else {
            val reply = newMessage(parsed.title, isUser = false)
            addSkillReply(session, reply.copy(skillId = skill.id, skillArg = parsed.triggerAt.toString()))
            _skillEffects.tryEmit(SkillEffect.AddToCalendar(parsed.title, parsed.triggerAt))
        }
    }

    /**
     * Paints [userText] with the image model, through the image skill.
     * An [auto] request paints just its subject ("draw a cat in space" → "a cat in space").
     */
    private fun requestImage(userText: String, skill: Skill? = null, auto: Boolean = false) {
        val text = userText.trim()
        val generator = imageGenerator
        if (text.isEmpty() || _isGenerating.value || generator == null) return
        val prompt = if (auto) SkillRouter.imagePrompt(text) else text

        val session = active
        addUserTurn(session, text, skill, auto)
        val placeholder = newMessage("", isUser = false, isStreaming = true)
        updateSession(session) { it + placeholder }
        generating = session
        _isGenerating.value = true

        fun replacePlaceholder(transform: (ChatMessage) -> ChatMessage?) = updateSession(session) { current ->
            current.mapNotNull { if (it.id == placeholder.id) transform(it) else it }
        }

        imageJob = viewModelScope.launch {
            try {
                val done = generator.generate(prompt) { running ->
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

    fun sendMessage(userText: String, skill: Skill? = null, auto: Boolean = false) {
        val text = userText.trim()
        if (text.isEmpty() || _modelState.value !is ModelState.Ready || _isGenerating.value || oneShot != null) return

        val session = active
        addUserTurn(session, text, skill, auto)
        if (skill == null && session.useDocuments && documents.searchable.value) return answerFromDocuments(session, text)
        val prompt = buildPrompt(persona.value, session.messages, skill?.instruction?.takeIf { it.isNotBlank() })
        // For WhatsApp, a number in the request says whose chat the draft should open in.
        val phone = if (skill?.share == ShareTarget.WhatsApp) PhoneNumbers.find(text) else null
        updateSession(session) {
            it + newMessage("", isUser = false, isStreaming = true).copy(skillId = skill?.id, skillArg = phone)
        }

        generating = session
        _isGenerating.value = true

        // A skill request ("write an X post about…") isn't the user telling us about themselves.
        if (skill == null && preferences.autoMemory.value) learnFrom(text)
        startPrediction(prompt)
    }

    /**
     * Looks the message up in the user's documents, then answers with the best passages in the
     * prompt. With no close match it's an ordinary reply; the search adds a fraction of a second.
     */
    private fun answerFromDocuments(session: Session, text: String) {
        val placeholder = newMessage("", isUser = false, isStreaming = true)
        updateSession(session) { it + placeholder }
        generating = session
        _isGenerating.value = true
        if (preferences.autoMemory.value) learnFrom(text)
        // The user turn and the placeholder are the last two; the one before is the previous question.
        val previous = session.messages.dropLast(2).lastOrNull { it.isUser }?.text

        retrievalJob = viewModelScope.launch {
            val hits = try {
                documents.search(DocumentContext.query(text, previous), excluded = session.excludedDocuments)
            } catch (e: CancellationException) {
                updateSession(session) { current -> current.filterNot { it.id == placeholder.id } }
                endTurn(session)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Document search failed; answering without it", e)
                emptyList()
            } finally {
                retrievalJob = null
            }
            if (generating !== session || _modelState.value !is ModelState.Ready) {
                updateSession(session) { current -> current.filterNot { it.id == placeholder.id } }
                endTurn(session)
                return@launch
            }
            val grounding = DocumentContext.format(hits, MAX_DOCUMENT_CHARS)
            if (grounding != null) {
                val sources = DocumentContext.sources(grounding.second)
                updateSession(session) { current -> current.map { if (it.id == placeholder.id) it.copy(sources = sources) else it } }
            }
            startPrediction(buildPrompt(persona.value, session.messages, grounding?.first))
        }
    }

    fun stopGeneration() {
        imageJob?.let {
            it.cancel()
            return
        }
        retrievalJob?.let {
            it.cancel()
            return
        }
        val session = generating ?: return
        try {
            llamaHelper.stopPrediction()
        } catch (e: Exception) {
            Log.w(TAG, "stopPrediction failed", e)
        }
        finishGeneration(session, userStopped = true)
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

    private val _draft = MutableStateFlow<String?>(null)
    /** Text to put in the chat's input field, such as a check-in's follow-up prompt. */
    val draft: StateFlow<String?> = _draft.asStateFlow()

    /** Opened from a check-in: back to the conversation it was about, with its prompt ready to send. */
    fun openFromCheckIn(conversationId: Long, draft: String?) {
        if (conversationId > 0) openConversation(conversationId)
        _draft.value = draft?.takeIf { it.isNotBlank() }
    }

    fun consumeDraft() {
        _draft.value = null
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

    /** The chat-wide switch: on selects every document again, off unselects them all. */
    fun setUseDocuments(enabled: Boolean) {
        active.useDocuments = enabled
        _useDocuments.value = enabled
        if (enabled) {
            active.excludedDocuments = emptySet()
            _excludedDocuments.value = emptySet()
        }
    }

    /** Selects or unselects one document for this chat. */
    fun toggleDocument(id: Long, readyIds: Set<Long>) {
        val session = active
        if (!session.useDocuments) {
            // Picking one document while all are off uses just that one.
            session.useDocuments = true
            session.excludedDocuments = readyIds - id
        } else {
            session.excludedDocuments = if (id in session.excludedDocuments) session.excludedDocuments - id else session.excludedDocuments + id
            // Unselecting the last one is the same as switching documents off.
            if (readyIds.isNotEmpty() && readyIds.all { it in session.excludedDocuments }) {
                session.useDocuments = false
                session.excludedDocuments = emptySet()
            }
        }
        _useDocuments.value = session.useDocuments
        _excludedDocuments.value = session.excludedDocuments
    }

    private fun show(session: Session) {
        active = session
        _useDocuments.value = session.useDocuments
        _excludedDocuments.value = session.excludedDocuments
        _messages.value = session.messages
        _activeConversationId.value = session.dbId
    }

    private fun updateSession(session: Session, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        synchronized(session) {
            session.messages = transform(session.messages)
            if (session === active) _messages.value = session.messages
        }
    }

    private fun toChatMessage(stored: StoredMessage): ChatMessage {
        val withSources = SourcesTag.decode(stored.text)
        val decoded = SkillTag.decode(withSources.text)
        return ChatMessage(
            nextMessageId++,
            decoded.text,
            stored.isUser,
            createdAt = stored.createdAt,
            thinkingMillis = stored.thinkingMillis,
            imageId = stored.imageId,
            imageWidth = stored.imageWidth,
            imageHeight = stored.imageHeight,
            skillId = decoded.skillId,
            skillArg = decoded.arg,
            sources = withSources.sources
        )
    }

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
    private fun buildPrompt(persona: Persona, history: List<ChatMessage>, skillInstruction: String? = null): String {
        val wordLimit = preferences.wordLimit.value
        val memoryContext = if (preferences.useMemory.value) MemoryRepository.formatForPrompt(memories.value) else null
        val system = buildString {
            append(persona.fullSystemPrompt(preferences.userName.value, wordLimit))
            if (persona.agentic && skillInstruction == null) {
                val skills = BuiltInSkills.all.filter { it.id in preferences.enabledSkillIds.value }
                if (skills.isNotEmpty()) {
                    append("\n\nThis app can also act for the user with these skills, which run on their own ")
                    append("when the user asks plainly (for example \"make a QR code for…\" or \"remind me at 6pm to…\"): ")
                    append(skills.joinToString("; ") { "${it.name}: ${it.description}" })
                    append(". When one of these would help, suggest it in one short line with the exact words to ask. ")
                    append("Never pretend you already did an action yourself.")
                }
            }
            if (memoryContext != null) {
                append("\n\nWhat you remember about the user from past conversations ")
                append("(use it naturally when relevant; never recite the list):\n")
                append(memoryContext)
            }
            if (skillInstruction != null) append("\n\nFor this reply only. ").append(skillInstruction)
        }
        val replyTokens = (wordLimit * 1.5).toInt() + 32
        val budgetChars = ((CONTEXT_LENGTH - replyTokens) * CHARS_PER_TOKEN).toInt() - system.length

        val turns = ArrayDeque<ChatMessage>()
        var used = 0
        // Image requests and the images they produced aren't conversation the text model can follow.
        // Nor are QR codes and scheduled reminders, which no model wrote.
        val imageTurns = history.indices.filter { i ->
            history[i].isImage || (history[i].isUser && history.getOrNull(i + 1)?.isImage == true) ||
                history[i].skill?.isTool == true
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
        stopVoice()
        stopReadAloud()
        super.onCleared()
        try {
            llamaHelper.release()
        } catch (e: Exception) {
            Log.w(TAG, "Release failed", e)
        }
        backgroundScope.cancel()
    }
}
