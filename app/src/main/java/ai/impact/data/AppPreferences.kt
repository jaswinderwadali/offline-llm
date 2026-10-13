package ai.impact.data

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.Log
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
        private const val KEY_THEME = "theme_mode"
        private const val KEY_USE_MEMORY = "use_memory"
        private const val KEY_AUTO_MEMORY = "auto_memory"
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

    private val _wordLimit = MutableStateFlow(prefs.getInt(KEY_WORD_LIMIT, 150))
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

    fun setWordLimit(limit: Int) {
        prefs.edit().putInt(KEY_WORD_LIMIT, limit).apply()
        _wordLimit.value = limit
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
        _wordLimit.value = 150
        _themeMode.value = ThemeMode.System
        applySystemNightMode(ThemeMode.System)
        _useMemory.value = true
        _autoMemory.value = true
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
