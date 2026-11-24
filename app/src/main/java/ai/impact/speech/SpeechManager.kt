package ai.impact.speech

import ai.impact.models.AuxModelStore
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listening and speaking for the whole app. Each side uses either the phone's own offline engine
 * ([SYSTEM], the default) or a downloaded model, chosen in the Models tab.
 */
class SpeechManager(context: Context, private val scope: CoroutineScope) {

    companion object {
        const val SYSTEM = "system"
        private const val FILE = "speech"
        private const val KEY_LISTEN = "listen_engine"
        private const val KEY_SPEAK = "speak_engine"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    val models = AuxModelStore(appContext, scope, prefsName = "speech_models", relativeRoot = "speech_models", catalog = SpeechCatalog.models)

    private val _listenEngine = MutableStateFlow(prefs.getString(KEY_LISTEN, SYSTEM) ?: SYSTEM)
    val listenEngine: StateFlow<String> = _listenEngine.asStateFlow()
    private val _speakEngine = MutableStateFlow(prefs.getString(KEY_SPEAK, SYSTEM) ?: SYSTEM)
    val speakEngine: StateFlow<String> = _speakEngine.asStateFlow()

    /** Whether a downloaded model is in memory (the phone's engines are always ready). */
    private val _listenLoaded = MutableStateFlow(false)
    val listenLoaded: StateFlow<Boolean> = _listenLoaded.asStateFlow()
    private val _speakLoaded = MutableStateFlow(false)
    val speakLoaded: StateFlow<Boolean> = _speakLoaded.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    val systemListenAvailable: Boolean get() = SystemListener.isAvailable(appContext)

    private var listener: Listener? = null
    private var listenerId: String? = null
    private var speaker: Speaker? = null
    private var speakerId: String? = null

    fun selectListen(id: String) {
        if (id == _listenEngine.value) return
        unloadListen()
        prefs.edit().putString(KEY_LISTEN, id).apply()
        _listenEngine.value = id
        if (id != SYSTEM) loadListen()
    }

    fun selectSpeak(id: String) {
        if (id == _speakEngine.value) return
        unloadSpeak()
        prefs.edit().putString(KEY_SPEAK, id).apply()
        _speakEngine.value = id
        if (id != SYSTEM) loadSpeak()
    }

    /** Loads the chosen listening model now, so the first words aren't kept waiting. */
    fun loadListen() {
        scope.launch(Dispatchers.Default) {
            runCatching { (currentListener() as? WhisperListener)?.load() }
            _listenLoaded.value = (listener as? WhisperListener)?.isLoaded == true
        }
    }

    fun loadSpeak() {
        scope.launch(Dispatchers.Default) {
            runCatching { (currentSpeaker() as? MmsSpeaker)?.load() }
            _speakLoaded.value = (speaker as? MmsSpeaker)?.isLoaded == true
        }
    }

    fun unloadListen() {
        listener?.release()
        listener = null
        listenerId = null
        _listenLoaded.value = false
    }

    fun unloadSpeak() {
        speaker?.release()
        speaker = null
        speakerId = null
        _speakLoaded.value = false
    }

    /** What the user says next, or "" if they say nothing. */
    suspend fun listen(onLevel: (Float) -> Unit, onPartial: (String) -> Unit, onFinished: () -> Unit = {}): String {
        val l = currentListener()
        return try {
            l.listen(onLevel, onPartial, onFinished)
        } finally {
            _listenLoaded.value = (l as? WhisperListener)?.isLoaded == true
        }
    }

    /** Ends listening now instead of waiting for a pause. */
    fun finishListening() {
        listener?.finish()
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val s = currentSpeaker()
        _speaking.value = true
        try {
            s.speak(text)
        } finally {
            _speaking.value = false
            _speakLoaded.value = (s as? MmsSpeaker)?.isLoaded == true
        }
    }

    fun stopSpeaking() {
        speaker?.stop()
    }

    @Synchronized
    private fun currentListener(): Listener {
        val id = _listenEngine.value
        listener?.takeIf { listenerId == id }?.let { return it }
        listener?.release()
        val created = if (id == SYSTEM) SystemListener(appContext) else {
            val model = models.find(id) ?: return SystemListener(appContext).also { fallBackListen() }
            WhisperListener(appContext, model.dir)
        }
        listener = created
        listenerId = id
        return created
    }

    @Synchronized
    private fun currentSpeaker(): Speaker {
        val id = _speakEngine.value
        speaker?.takeIf { speakerId == id }?.let { return it }
        speaker?.release()
        val created = if (id == SYSTEM) SystemSpeaker(appContext) else {
            val model = models.find(id) ?: return SystemSpeaker(appContext).also { fallBackSpeak() }
            MmsSpeaker(model.dir)
        }
        speaker = created
        speakerId = id
        return created
    }

    // A chosen model that's been deleted falls back to the phone's own engine.
    private fun fallBackListen() {
        prefs.edit().putString(KEY_LISTEN, SYSTEM).apply()
        _listenEngine.value = SYSTEM
    }

    private fun fallBackSpeak() {
        prefs.edit().putString(KEY_SPEAK, SYSTEM).apply()
        _speakEngine.value = SYSTEM
    }

    /** Deletes a downloaded model, switching back to the phone's engine if it was in use. */
    fun delete(id: String) {
        if (_listenEngine.value == id) { unloadListen(); fallBackListen() }
        if (_speakEngine.value == id) { unloadSpeak(); fallBackSpeak() }
        scope.launch { withContext(Dispatchers.IO) { models.delete(id) } }
    }
}
