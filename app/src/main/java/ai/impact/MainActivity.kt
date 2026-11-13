package ai.impact

import android.os.Bundle
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
    const val CHAT = "chat"
    const val PERSONAS = "personas"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val HUB = "hub"
    const val HISTORY = "history"
    const val MEMORY = "memory"
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

        var route by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var editorReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var editingPersonaId by rememberSaveable { mutableStateOf<String?>(null) }
        var historyReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var memoryReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var imagineReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
        var modelsReturnRoute by rememberSaveable { mutableStateOf(Routes.CHAT) }
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
                    val installedImageModels by imageViewModel.installed.collectAsStateWithLifecycle()
                    val imageModelState by imageViewModel.modelState.collectAsStateWithLifecycle()
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
                        onUnloadModel = viewModel::unloadModel,
                        onOpenChatModels = { openModels(ModelsTab.Chat, Routes.SETTINGS) },
                        imageModelTitle = (imageModelState as? ImageModelState.Ready)?.name ?: "Image model",
                        imageModelSummary = when (imageModelState) {
                            is ImageModelState.Ready -> "Image model · loaded"
                            is ImageModelState.Loading -> "Image model · loading…"
                            else -> if (installedImageModels.isEmpty()) "None yet · download one"
                            else "${installedImageModels.size} on this device · loads when needed"
                        },
                        onOpenImageStudio = { openImagine(Routes.SETTINGS) },
                        onOpenImageModels = { openModels(ModelsTab.Image, Routes.SETTINGS) },
                        onOpenImageGallery = { openGallery(Routes.SETTINGS) },
                        onOpenHistory = { historyReturnRoute = Routes.SETTINGS; route = Routes.HISTORY },
                        onOpenMemory = { memoryReturnRoute = Routes.SETTINGS; route = Routes.MEMORY },
                        conversationCount = conversations.size,
                        memoryCount = memories.size,
                        onResetApp = {
                            viewModel.unloadModel()
                            viewModel.clearHistory()
                            viewModel.clearMemory()
                            imageViewModel.deleteAllImages()
                            prefs.resetAll()
                            route = Routes.CHAT
                        }
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
                    val messages = remember { merge(imageViewModel.messages, hubViewModel.messages) }
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
                    // Opened straight from a restored back stack: make sure suggestions load.
                    LaunchedEffect(Unit) { hubViewModel.open(kind) }
                    val (installed, downloads) = when (kind) {
                        ModelKind.Chat -> textInstalled.map { it.id } to textDownloads
                        ModelKind.Image -> imageInstalled.map { it.id } to imageDownloads
                    }
                    fun backToModels() = openModels(if (kind == ModelKind.Chat) ModelsTab.Chat else ModelsTab.Image, Routes.HUB)
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
                        onDownloadChat = { if (hubViewModel.download(it)) backToModels() }
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
                    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
                    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
                    val chatMode by viewModel.chatMode.collectAsStateWithLifecycle()
                    val imageModels by imageViewModel.installed.collectAsStateWithLifecycle()
                    val gallery by imageViewModel.images.collectAsStateWithLifecycle()
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
                        onBrowsePersonas = { route = Routes.PERSONAS },
                        onCreatePersona = { openEditor(null, Routes.CHAT) },
                        onPickModel = ::pickModel,
                        onOpenModelCenter = { openModels(ModelsTab.Chat, Routes.CHAT) },
                        onOpenSettings = { route = Routes.SETTINGS },
                        onOpenHistory = { historyReturnRoute = Routes.CHAT; route = Routes.HISTORY },
                        onOpenMemory = { memoryReturnRoute = Routes.CHAT; route = Routes.MEMORY },
                        onOpenImageStudio = { openImagine(Routes.CHAT) },
                        onRememberMessage = viewModel::rememberMessage,
                        memoryEvents = viewModel.memoryEvents,
                        chatMode = chatMode,
                        onChatModeChange = viewModel::setChatMode,
                        hasImageModel = imageModels.isNotEmpty(),
                        onGetImageModel = { openModels(ModelsTab.Image, Routes.CHAT) },
                        images = imageActions
                    )
                }
            }
        }
    }
}
