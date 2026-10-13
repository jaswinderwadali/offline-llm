package ai.impact

import android.os.Bundle
import ai.impact.data.ThemeMode
import ai.impact.ui.chat.ChatScreen
import ai.impact.ui.history.HistoryScreen
import ai.impact.ui.memory.MemoryScreen
import ai.impact.ui.models.ModelCenterScreen
import ai.impact.ui.onboarding.OnboardingScreen
import ai.impact.ui.personas.PersonaEditorScreen
import ai.impact.ui.personas.PersonaPickerScreen
import ai.impact.ui.settings.SettingsScreen
import ai.impact.ui.theme.ImpactTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private object Routes {
    const val CHAT = "chat"
    const val PERSONAS = "personas"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val HISTORY = "history"
    const val MEMORY = "memory"
}

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onModelPicked)
    }

    private fun pickModel() {
        // MIME filters are unreliable for .gguf, so let the user pick any file and validate the name.
        modelPicker.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.preferences.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            ImpactTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppContent()
                }
            }
        }
    }

    @Composable
    private fun AppContent() {
        val prefs = viewModel.preferences
        val onboarded by prefs.onboardingComplete.collectAsStateWithLifecycle()
        val personas by viewModel.allPersonas.collectAsStateWithLifecycle()
        val persona by viewModel.persona.collectAsStateWithLifecycle()
        val userName by prefs.userName.collectAsStateWithLifecycle()

        if (!onboarded) {
            OnboardingScreen(
                personas = personas,
                initialPersonaId = persona.id,
                onSaveCustomPersona = prefs::saveCustomPersona,
                onFinish = { name, personaId ->
                    prefs.setUserName(name)
                    viewModel.selectPersona(personaId)
                    prefs.completeOnboarding()
                }
            )
            return
        }

        var route by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var editorReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var editingPersonaId by rememberSaveable { mutableStateOf<String?>(null) }
        var historyReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var memoryReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }

        fun openEditor(personaId: String?, from: String) {
            editingPersonaId = personaId
            editorReturnRoute = from
            route = Routes.EDITOR
        }

        AnimatedContent(
            targetState = route,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "nav"
        ) { current ->
            when (current) {
                Routes.PERSONAS -> PersonaPickerScreen(
                    personas = personas,
                    selectedId = persona.id,
                    onSelect = {
                        viewModel.selectPersona(it)
                        route = Routes.CHAT
                    },
                    onCreate = { openEditor(null, Routes.PERSONAS) },
                    onEdit = { openEditor(it.id, Routes.PERSONAS) },
                    onDelete = { viewModel.deletePersona(it.id) },
                    onBack = { route = Routes.CHAT }
                )

                Routes.EDITOR -> PersonaEditorScreen(
                    existing = personas.firstOrNull { it.id == editingPersonaId && it.isCustom },
                    onBack = { route = editorReturnRoute },
                    onSave = { saved ->
                        prefs.saveCustomPersona(saved)
                        viewModel.selectPersona(saved.id)
                        route = Routes.CHAT
                    }
                )

                Routes.HISTORY -> {
                    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
                    val activeId by viewModel.activeConversationId.collectAsStateWithLifecycle()
                    HistoryScreen(
                        conversations = conversations,
                        personas = personas,
                        activeConversationId = activeId,
                        onOpen = {
                            viewModel.openConversation(it)
                            route = Routes.CHAT
                        },
                        onDelete = viewModel::deleteConversation,
                        onClearAll = viewModel::clearHistory,
                        onBack = { route = historyReturnRoute }
                    )
                }

                Routes.MEMORY -> {
                    val memories by viewModel.memories.collectAsStateWithLifecycle()
                    val useMemory by prefs.useMemory.collectAsStateWithLifecycle()
                    val autoMemory by prefs.autoMemory.collectAsStateWithLifecycle()
                    MemoryScreen(
                        memories = memories,
                        useMemory = useMemory,
                        autoMemory = autoMemory,
                        onUseMemoryChange = prefs::setUseMemory,
                        onAutoMemoryChange = prefs::setAutoMemory,
                        onAdd = viewModel::addMemory,
                        onUpdate = viewModel::updateMemory,
                        onDelete = viewModel::deleteMemory,
                        onClearAll = viewModel::clearMemory,
                        onBack = { route = memoryReturnRoute }
                    )
                }

                Routes.SETTINGS -> {
                    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
                    val memories by viewModel.memories.collectAsStateWithLifecycle()
                    val wordLimit by prefs.wordLimit.collectAsStateWithLifecycle()
                    val themeMode by prefs.themeMode.collectAsStateWithLifecycle()
                    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
                    SettingsScreen(
                        persona = persona,
                        userName = userName,
                        wordLimit = wordLimit,
                        themeMode = themeMode,
                        modelState = modelState,
                        appVersion = BuildConfig.VERSION_NAME,
                        onBack = { route = Routes.CHAT },
                        onUserNameChange = prefs::setUserName,
                        onWordLimitChange = prefs::setWordLimit,
                        onThemeModeChange = prefs::setThemeMode,
                        onOpenPersonas = { route = Routes.PERSONAS },
                        onCreatePersona = { openEditor(null, Routes.SETTINGS) },
                        onPickModel = ::pickModel,
                        onUnloadModel = viewModel::unloadModel,
                        onOpenModelCenter = { route = Routes.MODELS },
                        onOpenHistory = { historyReturnRoute = Routes.SETTINGS; route = Routes.HISTORY },
                        onOpenMemory = { memoryReturnRoute = Routes.SETTINGS; route = Routes.MEMORY },
                        conversationCount = conversations.size,
                        memoryCount = memories.size,
                        onResetApp = {
                            viewModel.unloadModel()
                            viewModel.clearHistory()
                            viewModel.clearMemory()
                            prefs.resetAll()
                            route = Routes.CHAT
                        }
                    )
                }

                Routes.MODELS -> ModelCenterScreen(
                    onBack = { route = Routes.CHAT },
                    onPickModel = {
                        pickModel()
                        route = Routes.CHAT
                    }
                )

                else -> {
                    val messages by viewModel.messages.collectAsStateWithLifecycle()
                    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
                    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
                    ChatScreen(
                        persona = persona,
                        personas = personas,
                        messages = messages,
                        modelState = modelState,
                        isGenerating = isGenerating,
                        userName = userName,
                        onSend = viewModel::sendMessage,
                        onStop = viewModel::stopGeneration,
                        onNewChat = viewModel::newChat,
                        onSelectPersona = viewModel::selectPersona,
                        onBrowsePersonas = { route = Routes.PERSONAS },
                        onCreatePersona = { openEditor(null, Routes.CHAT) },
                        onPickModel = ::pickModel,
                        onOpenModelCenter = { route = Routes.MODELS },
                        onOpenSettings = { route = Routes.SETTINGS },
                        onOpenHistory = { historyReturnRoute = Routes.CHAT; route = Routes.HISTORY },
                        onOpenMemory = { memoryReturnRoute = Routes.CHAT; route = Routes.MEMORY },
                        onRememberMessage = viewModel::rememberMessage,
                        memoryEvents = viewModel.memoryEvents
                    )
                }
            }
        }
    }
}
