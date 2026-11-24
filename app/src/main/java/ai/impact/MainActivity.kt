package ai.impact

import ai.impact.models.AuxKind
import ai.impact.models.AuxModelStore
import ai.impact.image.AuxOption
import ai.impact.ui.models.AuxModelsUi
import ai.impact.ui.models.BuiltInEngine
import ai.impact.speech.SpeechManager
import ai.impact.speech.SpeechCatalog
import ai.impact.ui.models.CatalogOption
import ai.impact.ui.home.HomeScreen
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import ai.impact.ui.models.ModelManagerScreen
import ai.impact.ui.models.ModelSlot
import ai.impact.ui.models.ModelSlotKind
import ai.impact.ui.models.ManagedModel
import ai.impact.ui.models.SlotStatus
import ai.impact.data.documents.DocumentStatus
import android.net.Uri
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import ai.impact.data.checkins.CheckIns
import ai.impact.data.documents.DocumentKind
import ai.impact.data.documents.SearchModelStore
import ai.impact.ui.documents.DocumentsScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import ai.impact.data.ThemeMode
import ai.impact.image.ModelKind
import ai.impact.image.PromptEnhancer
import ai.impact.image.RepoRef
import ai.impact.models.ModelDownload
import ai.impact.models.TextModelCatalog
import ai.impact.ui.chat.ChatImageActions
import ai.impact.ui.chat.ChatScreen
import ai.impact.ui.history.HistoryScreen
import ai.impact.ui.image.HuggingFaceScreen
import ai.impact.ui.image.ImageGalleryScreen
import ai.impact.ui.image.ImageStudioScreen
import ai.impact.ui.image.ImageViewerScreen
import ai.impact.ui.image.rememberSaveToPhotos
import ai.impact.ui.image.shareImage
import ai.impact.ui.memory.MemoryScreen
import ai.impact.ui.models.ChatModelsUi
import ai.impact.ui.models.ImageModelsUi
import ai.impact.ui.models.ModelsScreen
import ai.impact.ui.models.ModelsTab
import ai.impact.ui.onboarding.OnboardingScreen
import ai.impact.ui.personas.PersonaEditorScreen
import ai.impact.ui.personas.PersonaPickerScreen
import ai.impact.ui.settings.SettingsScreen
import ai.impact.ui.theme.ImpactTheme
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.merge

private object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val PERSONAS = "personas"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val MODEL_MANAGER = "model_manager"
    const val HUB = "hub"
    const val HISTORY = "history"
    const val MEMORY = "memory"
    const val DOCUMENTS = "documents"
    const val IMAGINE = "imagine"
    const val IMAGE_GALLERY = "image_gallery"
    const val IMAGE_VIEWER = "image_viewer"
}

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private val imageViewModel: ImageViewModel by viewModels()
    private val hubViewModel: HubViewModel by viewModels()

    private val imageModelFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(imageViewModel::importModel)
    }

    /** Which kind of search or speech model the open folder picker is for. */
    private var pendingImportKind: AuxKind? = null
    private val auxMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val auxModelFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val kind = pendingImportKind ?: return@registerForActivityResult
        pendingImportKind = null
        if (uri != null) auxStore(kind).startImport(uri, kind) { error -> auxMessages.tryEmit(error ?: "Model imported") }
    }

    private fun importAuxModel(kind: AuxKind) {
        pendingImportKind = kind
        auxModelFolderPicker.launch(null)
    }

    private fun auxStore(kind: AuxKind): AuxModelStore = if (kind == AuxKind.Search) searchModel.store else speech.models

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The picker can outlive a rotation; remember what it was opened for.
        pendingImportKind?.let { outState.putString(KEY_IMPORT_KIND, it.name) }
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onModelPicked)
    }

    private val checkIns get() = (application as ImpactApp).checkIns
    private val speech get() = (application as ImpactApp).speech
    private val documents get() = (application as ImpactApp).documents
    private val searchModel get() = (application as ImpactApp).searchModel

    /** Things to tell the user on the Documents screen, like why a file couldn't be added. */
    private val documentMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { documents.add(uri)?.let(documentMessages::tryEmit) }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Asks for notification permission (Android 13+) if it's missing, at most once for check-ins. */
    private fun requestNotificationPermission(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || checkIns.notificationsAllowed) return
        if (checkIns.askedPermission && !force) return
        checkIns.askedPermission = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        checkIns.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        // Rotating the screen isn't leaving the app.
        if (!isChangingConfigurations) {
            checkIns.onAppBackground()
            viewModel.stopVoice()
            viewModel.stopReadAloud()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCheckIn(intent)
    }

    /** A tapped check-in opens its conversation with the follow-up prompt typed in. */
    private fun handleCheckIn(intent: Intent?) {
        if (intent?.hasExtra(CheckIns.EXTRA_DRAFT) != true) return
        val conversationId = intent.getLongExtra(CheckIns.EXTRA_CONVERSATION_ID, 0L)
        val draft = intent.getStringExtra(CheckIns.EXTRA_DRAFT)
        // Handled once: a rotation recreates the activity with the same intent.
        intent.removeExtra(CheckIns.EXTRA_DRAFT)
        intent.removeExtra(CheckIns.EXTRA_CONVERSATION_ID)
        viewModel.openFromCheckIn(conversationId, draft)
    }

    private fun pickModel() {
        // MIME filters are unreliable for .gguf, so let the user pick any file and validate the name.
        modelPicker.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(KEY_IMPORT_KIND)?.let { name -> pendingImportKind = AuxKind.entries.firstOrNull { it.name == name } }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleCheckIn(intent)
        // The chat and the image studio lend each other their models. Both view models outlive the
        // activity, and these hooks only capture the view models, so nothing leaks on rotation.
        imageViewModel.promptEnhancer = PromptEnhancer { idea, hint -> viewModel.enhanceImagePrompt(idea, hint) }
        viewModel.imageGenerator = object : ChatImageGenerator {
            override val hasModel: Boolean get() = imageViewModel.hasImageModel
            override suspend fun generate(prompt: String, onState: (GenerationState.Running) -> Unit) =
                imageViewModel.generateForChat(prompt, onState)
        }

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

        var route by rememberSaveable { mutableStateOf(Routes.HOME) }
        var personasReturnRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        // A tapped check-in always lands in the chat, whichever screen was open.
        val pendingDraft by viewModel.draft.collectAsStateWithLifecycle()
        LaunchedEffect(pendingDraft) { if (pendingDraft != null) route = Routes.CHAT }
        var editorReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var editingPersonaId by rememberSaveable { mutableStateOf<String?>(null) }
        var historyReturnRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        var memoryReturnRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        var documentsReturnRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        var imagineReturnRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        var modelsReturnRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        var managerReturnRoute by rememberSaveable { mutableStateOf(Routes.HOME) }
        var modelsTab by rememberSaveable { mutableStateOf(ModelsTab.Chat) }
        var galleryReturnRoute by rememberSaveable { mutableStateOf(Routes.IMAGINE) }
        var viewerReturnRoute by rememberSaveable { mutableStateOf(Routes.IMAGE_GALLERY) }
        var viewerImageId by rememberSaveable { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        val saveToPhotos = rememberSaveToPhotos(imageViewModel::saveToPhotos)
        val share = { image: ai.impact.image.GeneratedImage -> context.shareImage(imageViewModel.shareIntent(image)) }

        // Screens inside the image area return to wherever the studio was first opened from.
        val imageRoutes = setOf(Routes.IMAGINE, Routes.IMAGE_GALLERY, Routes.IMAGE_VIEWER)
        fun openImagine(from: String) {
            if (from !in imageRoutes) imagineReturnRoute = from
            route = Routes.IMAGINE
        }
        fun openModels(tab: ModelsTab, from: String) {
            modelsTab = tab
            if (from != Routes.HUB) modelsReturnRoute = from
            route = Routes.MODELS
        }
        fun openGallery(from: String) {
            galleryReturnRoute = from
            route = Routes.IMAGE_GALLERY
        }
        fun openViewer(id: String, from: String) {
            viewerImageId = id
            viewerReturnRoute = from
            route = Routes.IMAGE_VIEWER
        }
        fun openEditor(personaId: String?, from: String) {
            editingPersonaId = personaId
            editorReturnRoute = from
            route = Routes.EDITOR
        }

        val tabRoutes = setOf(Routes.HOME, Routes.MODEL_MANAGER, Routes.DOCUMENTS, Routes.SETTINGS)
        Scaffold(
            bottomBar = {
                if (route in tabRoutes) {
                    val chatState by viewModel.modelState.collectAsStateWithLifecycle()
                    val indexing by documents.progress.collectAsStateWithLifecycle()
                    MainNavigationBar(
                        current = route,
                        modelMissing = chatState is ModelState.NotLoaded || chatState is ModelState.Error,
                        indexingCount = indexing.size,
                        onSelect = { tab ->
                            // From the tab bar, each tab is a root with no back arrow.
                            when (tab) {
                                Routes.MODEL_MANAGER -> managerReturnRoute = Routes.HOME
                                Routes.DOCUMENTS -> documentsReturnRoute = Routes.HOME
                            }
                            route = tab
                        }
                    )
                }
            },
            // Screens handle the status bar themselves; this only makes room for the tab bar.
            contentWindowInsets = WindowInsets(0)
        ) { inner ->
            AnimatedContent(
                targetState = route,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "nav",
                modifier = Modifier.padding(inner).consumeWindowInsets(inner)
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
                        onBack = { route = personasReturnRoute }
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

                    Routes.HOME -> {
                        val conversations by viewModel.conversations.collectAsStateWithLifecycle()
                        val chatState by viewModel.modelState.collectAsStateWithLifecycle()
                        val docs by documents.documents.collectAsStateWithLifecycle()
                        HomeScreen(
                            userName = userName,
                            personas = personas,
                            selectedPersonaId = persona.id,
                            conversations = conversations,
                            modelState = chatState,
                            documentCount = docs.count { it.status == DocumentStatus.READY },
                            onOpenPersona = {
                                viewModel.selectPersona(it.id)
                                route = Routes.CHAT
                            },
                            onCreatePersona = { openEditor(null, Routes.HOME) },
                            onOpenConversation = {
                                viewModel.openConversation(it)
                                route = Routes.CHAT
                            },
                            onDeleteConversation = viewModel::deleteConversation,
                            onRenameConversation = viewModel::renameConversation,
                            onSeeAllHistory = { historyReturnRoute = Routes.HOME; route = Routes.HISTORY },
                            onOpenModels = { managerReturnRoute = Routes.HOME; route = Routes.MODEL_MANAGER },
                            onCreateImage = { openImagine(Routes.HOME) },
                            onOpenDocuments = { documentsReturnRoute = Routes.HOME; route = Routes.DOCUMENTS }
                        )
                    }

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
                            onRename = viewModel::renameConversation,
                            onClearAll = viewModel::clearHistory,
                            onBack = { route = historyReturnRoute }
                        )
                    }

                    Routes.DOCUMENTS -> {
                        val docs by documents.documents.collectAsStateWithLifecycle()
                        val progress by documents.progress.collectAsStateWithLifecycle()
                        val modelInstalled by searchModel.installed.collectAsStateWithLifecycle()
                        val downloads by searchModel.download.collectAsStateWithLifecycle()
                        DocumentsScreen(
                            documents = docs,
                            progress = progress,
                            modelInstalled = modelInstalled,
                            modelDownload = downloads[SearchModelStore.ID],
                            modelSizeBytes = SearchModelStore.spec.sizeBytes,
                            messages = documentMessages,
                            onDownloadModel = {
                                searchModel.dismissError()
                                searchModel.startDownload()?.let(documentMessages::tryEmit)
                            },
                            onCancelDownload = searchModel::cancelDownload,
                            onAdd = { documentPicker.launch(DocumentKind.pickerTypes) },
                            onRetry = documents::retry,
                            onDelete = documents::delete,
                            onBack = { route = documentsReturnRoute },
                            showBack = documentsReturnRoute != Routes.HOME
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
                        val useMemory by prefs.useMemory.collectAsStateWithLifecycle()
                        val wordLimit by prefs.wordLimit.collectAsStateWithLifecycle()
                        val themeMode by prefs.themeMode.collectAsStateWithLifecycle()
                        val checkInsEnabled by checkIns.enabled.collectAsStateWithLifecycle()
                        SettingsScreen(
                            userName = userName,
                            wordLimit = wordLimit,
                            themeMode = themeMode,
                            appVersion = BuildConfig.VERSION_NAME,
                            memoryCount = memories.size,
                            useMemory = useMemory,
                            conversationCount = conversations.size,
                            checkInsEnabled = checkInsEnabled,
                            onUserNameChange = prefs::setUserName,
                            onReplyLengthChange = prefs::setReplyLength,
                            onThemeModeChange = prefs::setThemeMode,
                            onOpenMemory = { memoryReturnRoute = Routes.SETTINGS; route = Routes.MEMORY },
                            onCheckInsChange = { enabled ->
                                checkIns.setEnabled(enabled)
                                if (enabled) requestNotificationPermission(force = true)
                            },
                            onDeleteAllChats = viewModel::clearHistory,
                            onResetApp = {
                                checkIns.reset()
                                documents.deleteAll()
                                viewModel.unloadModel()
                                viewModel.clearHistory()
                                viewModel.clearMemory()
                                imageViewModel.deleteAllImages()
                                prefs.resetAll()
                                route = Routes.HOME
                            },
                            onBack = { route = Routes.HOME }
                        )
                    }

                    Routes.MODEL_MANAGER -> {
                        val chatState by viewModel.modelState.collectAsStateWithLifecycle()
                        val textInstalled by viewModel.installedTextModels.collectAsStateWithLifecycle()
                        val imageState by imageViewModel.modelState.collectAsStateWithLifecycle()
                        val imageInstalled by imageViewModel.installed.collectAsStateWithLifecycle()
                        val selectedImageId by imageViewModel.selectedModelId.collectAsStateWithLifecycle()
                        val searchModels by searchModel.models.collectAsStateWithLifecycle()
                        val activeSearch by searchModel.active.collectAsStateWithLifecycle()
                        val searchLoaded by documents.searchModelLoaded.collectAsStateWithLifecycle()
                        val searchDownloads by searchModel.download.collectAsStateWithLifecycle()
                        val docs by documents.documents.collectAsStateWithLifecycle()

                        // Chat: the loaded model is matched to a downloaded one by its file.
                        val chatUri = (chatState as? ModelState.Ready)?.uri ?: (chatState as? ModelState.Loading)?.uri
                        val activeText = textInstalled.firstOrNull { Uri.fromFile(it.file).toString() == chatUri }
                        val chatSlot = ModelSlot(
                            kind = ModelSlotKind.Chat,
                            status = when (chatState) {
                                is ModelState.Ready -> SlotStatus.Loaded
                                is ModelState.Loading -> SlotStatus.Loading
                                is ModelState.Error -> SlotStatus.Error
                                ModelState.NotLoaded -> SlotStatus.NotLoaded
                            },
                            activeName = (chatState as? ModelState.Ready)?.name ?: (chatState as? ModelState.Loading)?.name
                                ?: textInstalled.singleOrNull()?.name,
                            error = (chatState as? ModelState.Error)?.message,
                            installed = textInstalled.map { ManagedModel(it.id, it.name, it.sizeBytes, it === activeText) },
                            loadedBytes = if (chatState is ModelState.Ready) activeText?.sizeBytes ?: 0L else 0L,
                            onLoad = textInstalled.singleOrNull()?.let { only -> { viewModel.loadTextModel(only.id) } },
                            onUse = { viewModel.loadTextModel(it.id) },
                            onUnload = viewModel::unloadModel,
                            moreLabel = "Download or delete chat models",
                            onMore = { openModels(ModelsTab.Chat, Routes.MODEL_MANAGER) }
                        )

                        val imageActiveId = (imageState as? ImageModelState.Ready)?.id ?: (imageState as? ImageModelState.Loading)?.id
                        val rememberedImage = imageInstalled.firstOrNull { it.id == (imageActiveId ?: selectedImageId) }
                            ?: imageInstalled.singleOrNull()
                        val imageSlot = ModelSlot(
                            kind = ModelSlotKind.Image,
                            status = when (imageState) {
                                is ImageModelState.Ready -> SlotStatus.Loaded
                                is ImageModelState.Loading -> SlotStatus.Loading
                                is ImageModelState.Error -> SlotStatus.Error
                                ImageModelState.NotLoaded -> SlotStatus.NotLoaded
                            },
                            activeName = rememberedImage?.name,
                            error = (imageState as? ImageModelState.Error)?.message,
                            installed = imageInstalled.map { ManagedModel(it.id, it.name, it.sizeBytes, it.id == imageActiveId) },
                            loadedBytes = if (imageState is ImageModelState.Ready) rememberedImage?.sizeBytes ?: 0L else 0L,
                            onLoad = rememberedImage?.let { model -> { imageViewModel.loadModel(model.id) } },
                            onUse = { imageViewModel.loadModel(it.id) },
                            onUnload = imageViewModel::unloadModel,
                            note = "It also loads by itself when you create an image.",
                            moreLabel = "Download or delete image models",
                            onMore = { openModels(ModelsTab.Image, Routes.MODEL_MANAGER) }
                        )

                        val readyDocs = docs.count { it.status == DocumentStatus.READY }
                        val searchSlot = ModelSlot(
                            kind = ModelSlotKind.Search,
                            status = if (searchLoaded) SlotStatus.Loaded else SlotStatus.NotLoaded,
                            activeName = activeSearch?.name,
                            installed = searchModels.map { ManagedModel(it.id, it.name, it.sizeBytes, it.id == activeSearch?.id) },
                            loadedBytes = if (searchLoaded) activeSearch?.sizeBytes ?: 0L else 0L,
                            onLoad = if (activeSearch != null) documents::loadSearchModel else null,
                            onUse = { documents.selectSearchModel(it.id) },
                            onUnload = documents::unloadSearchModel,
                            note = when {
                                activeSearch == null -> "Needed to ask about your documents in chat."
                                readyDocs == 0 -> "Add documents to search them in chat."
                                readyDocs == 1 -> "It loads by itself when chat searches your document."
                                else -> "It loads by itself when chat searches your $readyDocs documents."
                            },
                            moreLabel = "Get more search models",
                            onMore = { openModels(ModelsTab.Search, Routes.MODEL_MANAGER) },
                            onDelete = { documents.deleteSearchModel(it.id) },
                            download = searchDownloads[SearchModelStore.ID],
                            onDownload = {
                                searchModel.dismissError()
                                searchModel.startDownload()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            },
                            downloadSizeBytes = searchModel.sizeBytes
                        )
                        // Listening and speaking: the phone's own engine, or a downloaded model.
                        val speechInstalled by speech.models.installed.collectAsStateWithLifecycle()
                        val speechDownloads by speech.models.downloads.collectAsStateWithLifecycle()
                        val listenEngine by speech.listenEngine.collectAsStateWithLifecycle()
                        val speakEngine by speech.speakEngine.collectAsStateWithLifecycle()
                        val listenLoaded by speech.listenLoaded.collectAsStateWithLifecycle()
                        val speakLoaded by speech.speakLoaded.collectAsStateWithLifecycle()
                        fun speechSlot(
                            kind: AuxKind,
                            slotKind: ModelSlotKind,
                            engine: String,
                            loaded: Boolean,
                            builtInName: String,
                            builtInReady: Boolean,
                            builtInNote: String,
                            onSelect: (String) -> Unit,
                            onLoad: () -> Unit,
                            onUnload: () -> Unit
                        ): ModelSlot {
                            val downloaded = speechInstalled.filter { it.kind == kind }
                            val active = downloaded.firstOrNull { it.id == engine }
                            val builtIn = engine == SpeechManager.SYSTEM
                            return ModelSlot(
                                kind = slotKind,
                                status = when {
                                    builtIn && builtInReady -> SlotStatus.Loaded
                                    builtIn -> SlotStatus.Error
                                    loaded -> SlotStatus.Loaded
                                    else -> SlotStatus.NotLoaded
                                },
                                activeName = active?.name ?: builtInName,
                                error = if (builtIn && !builtInReady) "This phone has no offline speech recognizer. Download Whisper below." else null,
                                installed = listOf(ManagedModel(SpeechManager.SYSTEM, builtInName, 0L, builtIn)) +
                                    downloaded.map { ManagedModel(it.id, it.name, it.sizeBytes, it.id == engine) },
                                loadedBytes = if (!builtIn && loaded) active?.sizeBytes ?: 0L else 0L,
                                onLoad = if (!builtIn && !loaded) onLoad else null,
                                onUse = { onSelect(it.id) },
                                onUnload = onUnload,
                                canUnload = !builtIn,
                                note = if (builtIn) builtInNote else "Loads by itself when needed. Everything stays on this phone.",
                                moreLabel = "More models, Hugging Face or import",
                                onMore = { openModels(if (kind == AuxKind.Listen) ModelsTab.Listen else ModelsTab.Speak, Routes.MODEL_MANAGER) },
                                // Only the recommended pick here; the full list lives on the Models screen.
                                catalog = SpeechCatalog.models.filter { m -> m.kind == kind && m.recommended && downloaded.none { it.id == m.id } }.map { m ->
                                    CatalogOption(
                                        id = m.id, name = m.name, description = m.description, license = m.license, sizeBytes = m.sizeBytes,
                                        download = speechDownloads[m.id],
                                        onDownload = {
                                            speech.models.dismissError(m.id)
                                            speech.models.startDownload(m)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                        },
                                        onCancel = { speech.models.cancelDownload(m.id) }
                                    )
                                },
                                onDelete = { speech.delete(it.id) }
                            )
                        }
                        val listenSlot = speechSlot(
                            AuxKind.Listen, ModelSlotKind.Listen, listenEngine, listenLoaded,
                            builtInName = "Phone's offline recognizer",
                            builtInReady = speech.systemListenAvailable,
                            builtInNote = "Uses your phone's own offline speech recognition. Nothing is sent online.",
                            onSelect = speech::selectListen, onLoad = speech::loadListen, onUnload = speech::unloadListen
                        )
                        val speakSlot = speechSlot(
                            AuxKind.Speak, ModelSlotKind.Speak, speakEngine, speakLoaded,
                            builtInName = "Phone's voice",
                            builtInReady = true,
                            builtInNote = "Uses your phone's offline voice. Change its voice and speed in your phone's text-to-speech settings.",
                            onSelect = speech::selectSpeak, onLoad = speech::loadSpeak, onUnload = speech::unloadSpeak
                        )


                        ModelManagerScreen(
                            slots = listOf(chatSlot, imageSlot, searchSlot, listenSlot, speakSlot),
                            deviceRamBytes = imageViewModel.deviceRamBytes,
                            onUnloadAll = {
                                viewModel.unloadModel()
                                imageViewModel.unloadModel()
                                documents.unloadSearchModel()
                                speech.unloadListen()
                                speech.unloadSpeak()
                            },
                            onBack = { route = managerReturnRoute },
                            showBack = managerReturnRoute != Routes.HOME
                        )
                    }

                    Routes.MODELS -> {
                        val chatState by viewModel.modelState.collectAsStateWithLifecycle()
                        val textInstalled by viewModel.installedTextModels.collectAsStateWithLifecycle()
                        val textDownloads by viewModel.textDownloads.collectAsStateWithLifecycle()
                        val imageState by imageViewModel.modelState.collectAsStateWithLifecycle()
                        val imageInstalled by imageViewModel.installed.collectAsStateWithLifecycle()
                        val imageDownloads by imageViewModel.downloads.collectAsStateWithLifecycle()
                        val import by imageViewModel.import.collectAsStateWithLifecycle()
                        val selectedImageId by imageViewModel.selectedModelId.collectAsStateWithLifecycle()
                        val messages = remember { merge(imageViewModel.messages, hubViewModel.messages, auxMessages) }

                        // Document search: one model at a time, chosen from those on the phone.
                        val searchModels by searchModel.models.collectAsStateWithLifecycle()
                        val activeSearch by searchModel.active.collectAsStateWithLifecycle()
                        val searchLoaded by documents.searchModelLoaded.collectAsStateWithLifecycle()
                        val searchDownloads by searchModel.download.collectAsStateWithLifecycle()
                        val searchImport by searchModel.store.import.collectAsStateWithLifecycle()
                        val docs by documents.documents.collectAsStateWithLifecycle()
                        val docProgress by documents.progress.collectAsStateWithLifecycle()
                        val readyDocs = docs.count { it.status == DocumentStatus.READY }
                        fun toast(message: String?) { message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
                        val searchUi = AuxModelsUi(
                            kind = AuxKind.Search,
                            installed = searchModels,
                            activeId = activeSearch?.id,
                            loaded = searchLoaded,
                            builtIn = null,
                            catalog = SearchModelStore.catalog,
                            downloads = searchDownloads,
                            import = searchImport,
                            note = when {
                                docProgress.isNotEmpty() && activeSearch != null ->
                                    "Updating ${docProgress.size} document${if (docProgress.size == 1) "" else "s"} for ${activeSearch!!.name}…"
                                readyDocs > 0 -> "Switching models re-scores your $readyDocs document${if (readyDocs == 1) "" else "s"} on the phone. Nothing is uploaded."
                                else -> null
                            },
                            onDownload = { m -> searchModel.store.dismissError(m.id); toast(searchModel.store.startDownload(m)) },
                            onCancelDownload = searchModel.store::cancelDownload,
                            onDismissDownloadError = searchModel.store::dismissError,
                            onUse = documents::selectSearchModel,
                            onLoad = documents::loadSearchModel,
                            onUnload = documents::unloadSearchModel,
                            onDelete = { documents.deleteSearchModel(it.id) },
                            onImport = { importAuxModel(AuxKind.Search) },
                            onCancelImport = searchModel.store::cancelImport
                        )

                        // Speech: the phone's own engine, or a downloaded model.
                        val speechInstalled by speech.models.installed.collectAsStateWithLifecycle()
                        val speechDownloads by speech.models.downloads.collectAsStateWithLifecycle()
                        val speechImport by speech.models.import.collectAsStateWithLifecycle()
                        val listenEngine by speech.listenEngine.collectAsStateWithLifecycle()
                        val speakEngine by speech.speakEngine.collectAsStateWithLifecycle()
                        val listenLoaded by speech.listenLoaded.collectAsStateWithLifecycle()
                        val speakLoaded by speech.speakLoaded.collectAsStateWithLifecycle()
                        fun speechUi(kind: AuxKind, engine: String, loaded: Boolean, builtIn: BuiltInEngine, onSelect: (String) -> Unit, onLoad: () -> Unit, onUnload: () -> Unit): AuxModelsUi {
                            val catalog = SpeechCatalog.models.filter { it.kind == kind }
                            return AuxModelsUi(
                                kind = kind,
                                installed = speechInstalled.filter { it.kind == kind },
                                activeId = engine,
                                loaded = loaded,
                                builtIn = builtIn,
                                catalog = catalog,
                                // Both kinds share one store; keep each tab to its own downloads.
                                downloads = speechDownloads.filterKeys { id -> catalog.any { it.id == id } || id.startsWith(AuxOption.idPrefix(kind)) },
                                import = speechImport,
                                note = null,
                                onDownload = { m -> speech.models.dismissError(m.id); toast(speech.models.startDownload(m)) },
                                onCancelDownload = speech.models::cancelDownload,
                                onDismissDownloadError = speech.models::dismissError,
                                onUse = onSelect,
                                onLoad = onLoad,
                                onUnload = onUnload,
                                onDelete = { speech.delete(it.id) },
                                onImport = { importAuxModel(kind) },
                                onCancelImport = speech.models::cancelImport
                            )
                        }
                        val listenUi = speechUi(
                            AuxKind.Listen, listenEngine, listenLoaded,
                            BuiltInEngine(
                                SpeechManager.SYSTEM, "Phone's offline recognizer", speech.systemListenAvailable,
                                unavailableReason = "This phone has no offline speech recognizer. Download a Whisper model below.",
                                note = "Built into your phone · nothing is sent online"
                            ),
                            speech::selectListen, speech::loadListen, speech::unloadListen
                        )
                        val speakUi = speechUi(
                            AuxKind.Speak, speakEngine, speakLoaded,
                            BuiltInEngine(
                                SpeechManager.SYSTEM, "Phone's voice", true,
                                unavailableReason = "",
                                note = "Built into your phone · change it in text-to-speech settings"
                            ),
                            speech::selectSpeak, speech::loadSpeak, speech::unloadSpeak
                        )

                        ModelsScreen(
                            tab = modelsTab,
                            onTabChange = { modelsTab = it },
                            deviceRamBytes = imageViewModel.deviceRamBytes,
                            chat = ChatModelsUi(
                                state = chatState,
                                installed = textInstalled,
                                catalog = TextModelCatalog.models,
                                downloads = textDownloads,
                                onDownload = { model ->
                                    viewModel.startTextDownload(model)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                                },
                                onCancelDownload = viewModel::cancelTextDownload,
                                onDismissDownloadError = viewModel::dismissTextDownloadError,
                                onUse = { viewModel.loadTextModel(it.id) },
                                onUnload = viewModel::unloadModel,
                                onDelete = { viewModel.deleteTextModel(it.id) },
                                onPickFile = ::pickModel
                            ),
                            image = ImageModelsUi(
                                state = imageState,
                                installed = imageInstalled,
                                catalog = imageViewModel.catalog,
                                downloads = imageDownloads,
                                import = import,
                                selectedId = selectedImageId,
                                onDownload = imageViewModel::startDownload,
                                onCancelDownload = imageViewModel::cancelDownload,
                                onDismissDownloadError = imageViewModel::dismissDownloadError,
                                onUse = { imageViewModel.loadModel(it.id) },
                                onUnload = imageViewModel::unloadModel,
                                onDelete = { imageViewModel.deleteModel(it.id) },
                                onImport = { imageModelFolderPicker.launch(null) },
                                onCancelImport = imageViewModel::cancelImport
                            ),
                            search = searchUi,
                            listen = listenUi,
                            speak = speakUi,
                            messages = messages,
                            onOpenHub = { kind ->
                                hubViewModel.open(kind)
                                route = Routes.HUB
                            },
                            onBack = { route = modelsReturnRoute }
                        )
                    }

                    Routes.HUB -> {
                        val kind by hubViewModel.kind.collectAsStateWithLifecycle()
                        val search by hubViewModel.search.collectAsStateWithLifecycle()
                        val resolve by hubViewModel.resolve.collectAsStateWithLifecycle()
                        val textInstalled by viewModel.installedTextModels.collectAsStateWithLifecycle()
                        val textDownloads by viewModel.textDownloads.collectAsStateWithLifecycle()
                        val imageInstalled by imageViewModel.installed.collectAsStateWithLifecycle()
                        val imageDownloads by imageViewModel.downloads.collectAsStateWithLifecycle()
                        val searchModels by searchModel.models.collectAsStateWithLifecycle()
                        val searchDownloads by searchModel.download.collectAsStateWithLifecycle()
                        val speechInstalled by speech.models.installed.collectAsStateWithLifecycle()
                        val speechDownloads by speech.models.downloads.collectAsStateWithLifecycle()
                        // Opened straight from a restored back stack: make sure suggestions load.
                        LaunchedEffect(Unit) { hubViewModel.open(kind) }
                        val (installed, downloads) = when (kind) {
                            ModelKind.Chat -> textInstalled.map { it.id } to textDownloads
                            ModelKind.Image -> imageInstalled.map { it.id } to imageDownloads
                            ModelKind.Search -> searchModels.map { it.id } to searchDownloads
                            ModelKind.Listen, ModelKind.Speak -> speechInstalled.map { it.id } to speechDownloads
                        }
                        // Each kind has a Models tab of the same name.
                        fun backToModels() = openModels(ModelsTab.valueOf(kind.name), Routes.HUB)
                        HuggingFaceScreen(
                            kind = kind,
                            search = search,
                            resolve = resolve,
                            installedIds = installed.toSet(),
                            downloadingIds = downloads.filterValues { it is ModelDownload.Running }.keys,
                            messages = hubViewModel.messages,
                            fitsInMemory = hubViewModel::fitsInMemory,
                            onBack = {
                                hubViewModel.dismissResolve()
                                backToModels()
                            },
                            onSearch = hubViewModel::search,
                            onOpenModel = { hubViewModel.resolve(RepoRef(it.id)) },
                            onRetryResolve = hubViewModel::resolve,
                            onDismissResolve = hubViewModel::dismissResolve,
                            // Progress shows on the Models screen, next to the other downloads.
                            onDownloadImage = { if (hubViewModel.download(it)) backToModels() },
                            onDownloadChat = { if (hubViewModel.download(it)) backToModels() },
                            onDownloadAux = { if (hubViewModel.download(it)) backToModels() }
                        )
                    }

                    Routes.IMAGINE -> {
                        val modelState by imageViewModel.modelState.collectAsStateWithLifecycle()
                        val installed by imageViewModel.installed.collectAsStateWithLifecycle()
                        val selectedId by imageViewModel.selectedModelId.collectAsStateWithLifecycle()
                        val generation by imageViewModel.generation.collectAsStateWithLifecycle()
                        val prompt by imageViewModel.prompt.collectAsStateWithLifecycle()
                        val settings by imageViewModel.settings.collectAsStateWithLifecycle()
                        val images by imageViewModel.images.collectAsStateWithLifecycle()
                        val chatModelState by viewModel.modelState.collectAsStateWithLifecycle()
                        ImageStudioScreen(
                            modelState = modelState,
                            installed = installed,
                            selectedModelId = selectedId,
                            generation = generation,
                            prompt = prompt,
                            settings = settings,
                            recent = images,
                            chatModelLoaded = chatModelState is ModelState.Ready,
                            chatModelName = (chatModelState as? ModelState.Ready)?.name,
                            messages = imageViewModel.messages,
                            decode = imageViewModel::decode,
                            onOpened = imageViewModel::onStudioOpened,
                            onBack = { route = imagineReturnRoute },
                            onPromptChange = imageViewModel::setPrompt,
                            onGenerate = imageViewModel::generate,
                            onCancel = imageViewModel::cancelGeneration,
                            onUpdateSettings = imageViewModel::updateSettings,
                            onResetSettings = imageViewModel::resetSettingsToModel,
                            onOpenModels = { openModels(ModelsTab.Image, Routes.IMAGINE) },
                            onOpenGallery = { openGallery(Routes.IMAGINE) },
                            onOpenImage = { openViewer(it.id, Routes.IMAGINE) },
                            onLoadModel = imageViewModel::loadModel,
                            onFreeChatModelAndRetry = { id ->
                                viewModel.unloadModel()
                                imageViewModel.loadModel(id, ignoreLowMemory = true)
                            },
                            onDismissModelError = imageViewModel::dismissModelError,
                            onShare = share,
                            onSave = saveToPhotos,
                            onChooseChatModel = { openModels(ModelsTab.Chat, Routes.IMAGINE) }
                        )
                    }

                    Routes.IMAGE_GALLERY -> {
                        val images by imageViewModel.images.collectAsStateWithLifecycle()
                        ImageGalleryScreen(
                            images = images,
                            decode = imageViewModel::decode,
                            onBack = { route = galleryReturnRoute },
                            onOpen = { openViewer(it.id, Routes.IMAGE_GALLERY) },
                            onCreate = { openImagine(Routes.IMAGE_GALLERY) },
                            onDeleteAll = imageViewModel::deleteAllImages
                        )
                    }

                    Routes.IMAGE_VIEWER -> {
                        val images by imageViewModel.images.collectAsStateWithLifecycle()
                        ImageViewerScreen(
                            images = images,
                            initialImageId = viewerImageId.orEmpty(),
                            decode = imageViewModel::decode,
                            onBack = { route = viewerReturnRoute },
                            onShare = share,
                            onSave = saveToPhotos,
                            onRemix = {
                                imageViewModel.remix(it)
                                openImagine(Routes.IMAGE_VIEWER)
                            },
                            onDelete = { imageViewModel.deleteImage(it.id) }
                        )
                    }

                    else -> {
                        val messages by viewModel.messages.collectAsStateWithLifecycle()
                        val draft by viewModel.draft.collectAsStateWithLifecycle()
                        val voiceState by viewModel.voice.collectAsStateWithLifecycle()
                        val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
                        val documentsSearchable by viewModel.documentsSearchable.collectAsStateWithLifecycle()
                        val allDocuments by documents.documents.collectAsStateWithLifecycle()
                        val readyDocuments = allDocuments.filter { it.status == DocumentStatus.READY }
                        val excludedDocuments by viewModel.excludedDocuments.collectAsStateWithLifecycle()
                        val useDocuments by viewModel.useDocuments.collectAsStateWithLifecycle()
                        // Ask for notifications once the user is clearly chatting, not on first launch.
                        val userTurns = messages.count { it.isUser }
                        LaunchedEffect(userTurns >= 2) {
                            if (userTurns >= 2 && !checkIns.askedPermission) requestNotificationPermission()
                        }
                        val modelState by viewModel.modelState.collectAsStateWithLifecycle()
                        val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
                        val imageModels by imageViewModel.installed.collectAsStateWithLifecycle()
                        val gallery by imageViewModel.images.collectAsStateWithLifecycle()
                        val enabledSkillIds by prefs.enabledSkillIds.collectAsStateWithLifecycle()
                        val autoSkills by prefs.autoSkills.collectAsStateWithLifecycle()
                        val scheduledNotifications by viewModel.reminders.scheduled.collectAsStateWithLifecycle()
                        val imageActions = remember(gallery) {
                            val byId = gallery.associateBy { it.id }
                            ChatImageActions(
                                find = byId::get,
                                decode = imageViewModel::decode,
                                onOpen = { openViewer(it.id, Routes.CHAT) },
                                onShare = share,
                                onSave = saveToPhotos
                            )
                        }
                        ChatScreen(
                            persona = persona,
                            personas = personas,
                            messages = messages,
                            modelState = modelState,
                            isGenerating = isGenerating,
                            userName = userName,
                            onSend = viewModel::send,
                            onStop = viewModel::stopGeneration,
                            onNewChat = viewModel::newChat,
                            onSelectPersona = viewModel::selectPersona,
                            onBrowsePersonas = { personasReturnRoute = Routes.CHAT; route = Routes.PERSONAS },
                            onCreatePersona = { openEditor(null, Routes.CHAT) },
                            onPickModel = ::pickModel,
                            onOpenModelCenter = { openModels(ModelsTab.Chat, Routes.CHAT) },
                            onOpenSettings = { route = Routes.SETTINGS },
                            onBack = { viewModel.stopVoice(); viewModel.stopReadAloud(); route = Routes.HOME },
                            onOpenHistory = { historyReturnRoute = Routes.CHAT; route = Routes.HISTORY },
                            onOpenMemory = { memoryReturnRoute = Routes.CHAT; route = Routes.MEMORY },
                            onOpenImageStudio = { openImagine(Routes.CHAT) },
                            onRememberMessage = viewModel::rememberMessage,
                            memoryEvents = viewModel.memoryEvents,
                            hasImageModel = imageModels.isNotEmpty(),
                            onGetImageModel = { openModels(ModelsTab.Image, Routes.CHAT) },
                            images = imageActions,
                            enabledSkillIds = enabledSkillIds,
                            onToggleSkill = viewModel::setSkillEnabled,
                            skillEffects = viewModel.skillEffects,
                            scheduledNotifications = scheduledNotifications,
                            onCancelNotification = viewModel::cancelNotification,
                            autoSkills = autoSkills,
                            onAutoSkillsChange = prefs::setAutoSkills,
                            draft = draft,
                            onDraftConsumed = viewModel::consumeDraft,
                            documentsAvailable = documentsSearchable,
                            documents = readyDocuments,
                            excludedDocuments = excludedDocuments,
                            onToggleDocument = { id -> viewModel.toggleDocument(id, readyDocuments.map { it.id }.toSet()) },
                            useDocuments = useDocuments,
                            onUseDocumentsChange = viewModel::setUseDocuments,
                            onOpenDocuments = { documentsReturnRoute = Routes.CHAT; route = Routes.DOCUMENTS },
                            voice = voiceState,
                            onStartVoice = viewModel::startVoice,
                            onVoiceTap = viewModel::voiceTap,
                            onStopVoice = viewModel::stopVoice,
                            speakingMessageId = speakingMessageId,
                            onReadAloud = viewModel::toggleReadAloud
                        )
                    }
                }
            }
        }
    }
}

private const val KEY_IMPORT_KIND = "import_kind"

/** The four tabs. Chat opens on top of Home, full screen. */
@Composable
private fun MainNavigationBar(current: String, modelMissing: Boolean, indexingCount: Int, onSelect: (String) -> Unit) {
    data class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector)
    val tabs = listOf(
        Tab(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
        Tab(Routes.MODEL_MANAGER, "Models", Icons.Filled.Memory, Icons.Outlined.Memory),
        Tab(Routes.DOCUMENTS, "Documents", Icons.Filled.Description, Icons.Outlined.Description),
        Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    )
    NavigationBar {
        tabs.forEach { tab ->
            val selected = current == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab.route) },
                label = { Text(tab.label) },
                icon = {
                    BadgedBox(
                        badge = {
                            when {
                                // No chat model loaded: the Models tab is where to fix that.
                                tab.route == Routes.MODEL_MANAGER && modelMissing -> Badge()
                                tab.route == Routes.DOCUMENTS && indexingCount > 0 -> Badge { Text("$indexingCount") }
                            }
                        }
                    ) {
                        Icon(if (selected) tab.selected else tab.unselected, contentDescription = null)
                    }
                }
            )
        }
    }
}
