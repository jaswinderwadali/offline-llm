package ai.impact.data

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.Log
import ai.impact.data.skills.BuiltInSkills
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Small persisted settings store backed by SharedPreferences.
 * Every value is mirrored into a StateFlow so Compose can observe it.
 */
class AppPreferences(context: Context) {

    companion object {
        private const val TAG = "AppPreferences"
        private const val FILE = "impact_prefs"
        private const val KEY_ONBOARDED = "onboarding_complete"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_PERSONA_ID = "selected_persona_id"
        private const val KEY_CUSTOM_PERSONAS = "custom_personas"
        private const val KEY_WORD_LIMIT = "word_limit"
        private const val KEY_MODEL_URI = "model_uri"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_MODEL_LOADING = "model_loading_name"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_USE_MEMORY = "use_memory"
        private const val KEY_AUTO_MEMORY = "auto_memory"
        private const val KEY_ENABLED_SKILLS = "enabled_skills"
        private const val KEY_AUTO_SKILLS = "auto_skills"
        /** Every skill the app has offered so far, so a skill added in an update can start enabled. */
        private const val KEY_KNOWN_SKILLS = "known_skills"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _onboardingComplete = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    private val _userName = MutableStateFlow(prefs.getString(KEY_USER_NAME, "") ?: "")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _selectedPersonaId = MutableStateFlow(
        prefs.getString(KEY_PERSONA_ID, BuiltInPersonas.DEFAULT_ID) ?: BuiltInPersonas.DEFAULT_ID
    )
    val selectedPersonaId: StateFlow<String> = _selectedPersonaId.asStateFlow()

    private val _customPersonas = MutableStateFlow(readCustomPersonas())
    val customPersonas: StateFlow<List<Persona>> = _customPersonas.asStateFlow()

    // Snapped to a preset, so values saved by the old free slider (e.g. 149 or 350) still map cleanly.
    private val _wordLimit = MutableStateFlow(
        ReplyLength.nearest(prefs.getInt(KEY_WORD_LIMIT, ReplyLength.Default.words)).words
    )
    /** Most words a reply may have; one of the [ReplyLength] presets. */
    val wordLimit: StateFlow<Int> = _wordLimit.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.getOrNull(prefs.getInt(KEY_THEME, ThemeMode.System.ordinal)) ?: ThemeMode.System
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _useMemory = MutableStateFlow(prefs.getBoolean(KEY_USE_MEMORY, true))
    /** Whether saved memories are given to the AI as context. */
    val useMemory: StateFlow<Boolean> = _useMemory.asStateFlow()

    private val _autoMemory = MutableStateFlow(prefs.getBoolean(KEY_AUTO_MEMORY, true))
    /** Whether new memories are learned automatically from what the user writes. */
    val autoMemory: StateFlow<Boolean> = _autoMemory.asStateFlow()

    private val _enabledSkillIds = MutableStateFlow(readEnabledSkills())
    /** Skills the user turned on, so they're one tap away in the chat's input field. */
    val enabledSkillIds: StateFlow<Set<String>> = _enabledSkillIds.asStateFlow()

    fun setSkillEnabled(id: String, enabled: Boolean) {
        val updated = if (enabled) _enabledSkillIds.value + id else _enabledSkillIds.value - id
        prefs.edit().putString(KEY_ENABLED_SKILLS, updated.joinToString(",")).apply()
        _enabledSkillIds.value = updated
    }

    private val _autoSkills = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SKILLS, true))
    /** Whether a message that clearly asks for an enabled skill uses it without the user picking it. */
    val autoSkills: StateFlow<Boolean> = _autoSkills.asStateFlow()

    fun setAutoSkills(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SKILLS, enabled).apply()
        _autoSkills.value = enabled
    }

    private fun readEnabledSkills(): Set<String> {
        val allIds = BuiltInSkills.all.map { it.id }.toSet()
        val raw = prefs.getString(KEY_ENABLED_SKILLS, null)
        if (raw == null) {
            prefs.edit().putString(KEY_KNOWN_SKILLS, allIds.joinToString(",")).apply()
            return BuiltInSkills.defaultEnabledIds
        }
        // Skills since removed (like the online Wikipedia lookup) simply drop out.
        val saved = raw.split(',').filter { it in allIds }.toSet()
        // Skills added in an update start in their default state; ones the user has seen keep their choice.
        // Before this list was kept, everything in the first release's catalog had been seen.
        val known = prefs.getString(KEY_KNOWN_SKILLS, null)?.split(',')?.toSet() ?: (allIds - "image")
        val added = BuiltInSkills.defaultEnabledIds - known
        if (allIds != known) {
            prefs.edit()
                .putString(KEY_KNOWN_SKILLS, allIds.joinToString(","))
                .putString(KEY_ENABLED_SKILLS, (saved + added).joinToString(","))
                .apply()
        }
        return saved + added
    }

    val modelUri: String? get() = prefs.getString(KEY_MODEL_URI, null)
    val modelName: String? get() = prefs.getString(KEY_MODEL_NAME, null)

    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        _onboardingComplete.value = true
    }

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
        _userName.value = name.trim()
    }

    fun selectPersona(id: String) {
        prefs.edit().putString(KEY_PERSONA_ID, id).apply()
        _selectedPersonaId.value = id
    }

    fun setReplyLength(length: ReplyLength) {
        prefs.edit().putInt(KEY_WORD_LIMIT, length.words).apply()
        _wordLimit.value = length.words
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME, mode.ordinal).apply()
        _themeMode.value = mode
        applySystemNightMode(mode)
    }

    /**
     * On Android 12+, tell the system about the app's own light/dark choice so the launch splash
     * and system UI match it too (the system persists this across restarts).
     */
    private fun applySystemNightMode(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val uiModeManager = appContext.getSystemService(UiModeManager::class.java) ?: return
        uiModeManager.setApplicationNightMode(
            when (mode) {
                ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
            }
        )
    }

    fun setUseMemory(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_MEMORY, enabled).apply()
        _useMemory.value = enabled
    }

    fun setAutoMemory(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MEMORY, enabled).apply()
        _autoMemory.value = enabled
    }

    fun setModel(uri: String?, name: String?) {
        prefs.edit().putString(KEY_MODEL_URI, uri).putString(KEY_MODEL_NAME, name).apply()
    }

    /** Name of a model whose first load never finished. Still set at launch means loading it killed the app. */
    val interruptedModelLoad: String? get() = prefs.getString(KEY_MODEL_LOADING, null)

    fun setModelLoading(name: String?) {
        // commit(), not apply(): the process can be killed mid-load, before an async write reaches disk.
        prefs.edit().putString(KEY_MODEL_LOADING, name).commit()
    }

    fun saveCustomPersona(persona: Persona) {
        val updated = _customPersonas.value.filterNot { it.id == persona.id } + persona.copy(isCustom = true)
        writeCustomPersonas(updated)
    }

    fun deleteCustomPersona(id: String) {
        writeCustomPersonas(_customPersonas.value.filterNot { it.id == id })
        if (_selectedPersonaId.value == id) selectPersona(BuiltInPersonas.DEFAULT_ID)
    }

    fun resetAll() {
        prefs.edit().clear().apply()
        _onboardingComplete.value = false
        _userName.value = ""
        _selectedPersonaId.value = BuiltInPersonas.DEFAULT_ID
        _customPersonas.value = emptyList()
        _wordLimit.value = ReplyLength.Default.words
        _themeMode.value = ThemeMode.System
        applySystemNightMode(ThemeMode.System)
        _useMemory.value = true
        _autoMemory.value = true
        _enabledSkillIds.value = BuiltInSkills.defaultEnabledIds
        _autoSkills.value = true
    }

    private fun writeCustomPersonas(list: List<Persona>) {
        val json = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_CUSTOM_PERSONAS, json.toString()).apply()
        _customPersonas.value = list
    }

    private fun readCustomPersonas(): List<Persona> {
        val raw = prefs.getString(KEY_CUSTOM_PERSONAS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { Persona.fromJson(array.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse custom personas", e)
            emptyList()
        }
    }
}

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }
