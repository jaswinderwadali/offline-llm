package ai.impact.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import ai.impact.data.documents.DocumentEntity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import ai.impact.VoiceState
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import ai.impact.data.documents.DocumentSource
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TextButton
import ai.impact.ChatMessage
import ai.impact.ModelState
import ai.impact.data.Persona
import ai.impact.data.ResponseParser
import ai.impact.data.memory.MemoryEntity
import kotlinx.coroutines.flow.Flow
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import ai.impact.ui.components.Chip
import ai.impact.ui.components.PersonaAvatar
import ai.impact.ui.components.brush
import ai.impact.ui.theme.calmBackground
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import ai.impact.SkillEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import ai.impact.data.skills.BuiltInSkills
import ai.impact.data.skills.ScheduledNotification
import ai.impact.data.skills.Skill
import ai.impact.data.skills.SkillAction
import ai.impact.data.skills.SkillRouter
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Bolt
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    persona: Persona,
    personas: List<Persona>,
    messages: List<ChatMessage>,
    modelState: ModelState,
    isGenerating: Boolean,
    userName: String,
    /** Text, the skill to use (or null for plain chat), and whether that skill was picked automatically. */
    onSend: (String, Skill?, Boolean) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onSelectPersona: (String) -> Unit,
    onBrowsePersonas: () -> Unit,
    onCreatePersona: () -> Unit,
    onPickModel: () -> Unit,
    onOpenModelCenter: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Back to the home tab; the chat opens full screen on top of the main navigation. */
    onBack: (() -> Unit)? = null,
    onOpenHistory: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenImageStudio: () -> Unit,
    onRememberMessage: (String) -> Unit,
    memoryEvents: Flow<MemoryEntity>,
    hasImageModel: Boolean,
    onGetImageModel: () -> Unit,
    images: ChatImageActions,
    enabledSkillIds: Set<String>,
    onToggleSkill: (String, Boolean) -> Unit,
    skillEffects: Flow<SkillEffect>,
    scheduledNotifications: List<ScheduledNotification>,
    onCancelNotification: (Int) -> Unit,
    autoSkills: Boolean,
    onAutoSkillsChange: (Boolean) -> Unit,
    draft: String? = null,
    documentsAvailable: Boolean = false,
    useDocuments: Boolean = true,
    onUseDocumentsChange: (Boolean) -> Unit = {},
    onOpenDocuments: () -> Unit = {},
    /** The documents chat can search, for the composer's documents sheet. */
    documents: List<DocumentEntity> = emptyList(),
    excludedDocuments: Set<Long> = emptySet(),
    onToggleDocument: (Long) -> Unit = {},
    /** Hands-free voice mode; null when it's off. */
    voice: VoiceState? = null,
    onStartVoice: () -> Unit = {},
    onVoiceTap: () -> Unit = {},
    onStopVoice: () -> Unit = {},
    /** The reply being read aloud, and the button that reads one. */
    speakingMessageId: Long? = null,
    onReadAloud: (ChatMessage) -> Unit = {},
    onDraftConsumed: () -> Unit = {}
) {
    if (onBack != null) BackHandler(onBack = onBack)
    var input by rememberSaveable { mutableStateOf("") }
    // A check-in's follow-up prompt, ready for the user to send or edit.
    LaunchedEffect(draft) {
        if (draft != null) {
            input = draft
            onDraftConsumed()
        }
    }
    // The sheet opened from the input field (or the title); null when it's closed.
    var showSkills by rememberSaveable { mutableStateOf(false) }
    var activeSkillId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val isReady = modelState is ModelState.Ready
    val activeSkill = BuiltInSkills.byId(activeSkillId)
    // Agentic personas always pick skills on their own; others follow the "Use skills automatically" switch.
    val routeSkills = autoSkills || persona.agentic
    // Tool skills such as QR codes, reminders and images work with no chat model at all.
    val canSend = when {
        activeSkill != null -> isReady || !activeSkill.needsModel
        // With automatic skills, typing stays open so "make a QR code for…" works without a model.
        else -> isReady || (routeSkills && BuiltInSkills.all.any { it.id in enabledSkillIds && !it.needsModel })
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Only while the chat is on screen: Android blocks opening other apps from the background,
    // so a draft that finishes while the user is away just waits in the chat with its Send button.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(skillEffects, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            skillEffects.collect { effect ->
            when (effect) {
                is SkillEffect.AddToCalendar -> context.openCalendar(effect.title, effect.triggerAt)
                is SkillEffect.SendToApp -> context.sendToApp(effect.target, effect.text, effect.phone)
            }
            }
        }
    }

    // Notifications need permission on Android 13+; ask the first time the skill is used, then send.
    var pendingSend by remember { mutableStateOf<Pair<String, Skill>?>(null) }
    // Event handlers below are cached by Compose, so they read these through State: a plain value
    // captured at composition would be stale (that once sent every skill message as plain chat).
    val currentOnSend by rememberUpdatedState(onSend)
    val currentOnToggleSkill by rememberUpdatedState(onToggleSkill)
    val currentEnabledSkillIds by rememberUpdatedState(enabledSkillIds)
    val currentRouteSkills by rememberUpdatedState(routeSkills)
    val currentIsReady by rememberUpdatedState(isReady)
    val currentHasImageModel by rememberUpdatedState(hasImageModel)
    val currentOnGetImageModel by rememberUpdatedState(onGetImageModel)
    val scope = rememberCoroutineScope()
    var pendingAuto by remember { mutableStateOf(false) }
    val currentOnStartVoice by rememberUpdatedState(onStartVoice)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) currentOnStartVoice()
        else scope.launch { snackbarHostState.showSnackbar("Voice mode needs microphone access.") }
    }
    fun startVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onStartVoice()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingSend?.let { (text, skill) -> currentOnSend(text, skill, pendingAuto) }
        pendingSend = null
    }
    fun submit() {
        val text = input
        // Read the skill from state at the moment of sending, never from a captured value.
        val picked = BuiltInSkills.byId(activeSkillId)
        // Nothing picked: use an enabled skill if the message clearly asks for one.
        val detected = if (picked == null && currentRouteSkills) {
            SkillRouter.detect(text, currentEnabledSkillIds)
        } else null
        val skill = picked ?: detected
        if (skill?.action == SkillAction.Image && !currentHasImageModel) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    "Creating images needs an image model on this phone.",
                    actionLabel = "Get one",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) currentOnGetImageModel()
            }
            return
        }
        if (!currentIsReady && (skill == null || skill.needsModel)) {
            // Typing is allowed without a model so skills can still run; plain chat can't.
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (skill == null) "No chat model loaded. Load one to chat, or ask for something like a QR code or a reminder."
                    else "${skill.name} needs a chat model. Load one from Settings."
                )
            }
            return
        }
        input = ""
        activeSkillId = null
        val auto = detected != null
        val needsPermission = skill?.action == SkillAction.Notification &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingSend = text to skill!!
            pendingAuto = auto
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            currentOnSend(text, skill, auto)
        }
    }
    fun useSkill(skill: Skill) {
        if (skill.id !in currentEnabledSkillIds) currentOnToggleSkill(skill.id, true)
        activeSkillId = skill.id
        if (input.startsWith("/")) input = ""
        showSkills = false
    }

    LaunchedEffect(memoryEvents) {
        memoryEvents.collect { memory ->
            val result = snackbarHostState.showSnackbar(
                message = "Remembered: ${memory.content}",
                actionLabel = "View",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onOpenMemory()
        }
    }

    // Stay pinned to the bottom while a reply types itself out, unless the user scrolls up to read.
    var followBottom by remember { mutableStateOf(true) }
    val listDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listDragged) { if (listDragged) followBottom = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (_, canScrollDown) -> if (!canScrollDown) followBottom = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null || last.index != info.totalItemsCount - 1) 0
            else last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
        }.collect { overflow ->
            if (followBottom && overflow > 0 && !listDragged) listState.scrollBy(overflow.toFloat())
        }
    }
    LaunchedEffect(messages.size) {
        followBottom = true
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // The top bar slides away while scrolling up through the chat and returns on scrolling down.
    val topBarScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var micCenter by remember { mutableStateOf<Offset?>(null) }
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .calmBackground(accent = Color(persona.colorStart))
            .nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = 76.dp).navigationBarsPadding()) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary
                )
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        PersonaAvatar(persona, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                persona.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val isThinking = messages.lastOrNull()?.let {
                                it.isStreaming && ResponseParser.parse(it.text).let { p -> p.hasThinking && !p.isThinkingComplete }
                            } == true
                            StatusLine(modelState, isGenerating, isThinking)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat, enabled = messages.isNotEmpty()) {
                        Icon(Icons.Default.EditNote, contentDescription = "New chat")
                    }
                },
                scrollBehavior = topBarScroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState(
                        persona = persona,
                        userName = userName,
                        modelState = modelState,
                        needsChatModel = activeSkill?.needsModel != false,
                        onStarter = { if (isReady && activeSkill == null) onSend(it, null, false) else input = it },
                        onPickModel = onPickModel,
                        onOpenModelCenter = onOpenModelCenter,
                        skills = BuiltInSkills.all.filter { it.id in enabledSkillIds },
                        onUseSkill = ::useSkill
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        itemsIndexed(
                            messages,
                            key = { _, message -> message.id },
                            // Lets the list reuse the right layout for each kind of row while scrolling.
                            contentType = { _, message ->
                                when {
                                    message.isImage -> "image"
                                    message.isUser -> "user"
                                    else -> "assistant"
                                }
                            }
                        ) { index, message ->
                            // New messages fade and slide in; the list eases when items move.
                            Box(Modifier.animateItem(fadeInSpec = tween(250), placementSpec = spring(stiffness = Spring.StiffnessMediumLow))) {
                            val toolSkill = message.skill?.takeIf { it.isTool && !message.isUser }
                            if (message.isImage) {
                                val request = messages.getOrNull(index - 1)?.takeIf { it.isUser }?.text
                                ImageMessageBubble(message, request, persona, images)
                            } else if (toolSkill != null) {
                                SkillReplyCard(message, toolSkill, persona, scheduledNotifications, onCancelNotification)
                            } else {
                                MessageBubble(
                                    message, persona, onRemember = onRememberMessage,
                                    speaking = speakingMessageId == message.id,
                                    onReadAloud = { onReadAloud(message) },
                                    // Undo for an automatic pick: send the same words as plain chat.
                                    onReplyAsChat = if (isReady && !isGenerating) {
                                        { text -> onSend(text, null, false) }
                                    } else null
                                )
                            }
                            }
                        }
                    }
                }
            }

            if (!isReady && messages.isNotEmpty()) {
                ModelBanner(modelState, onPickModel)
            }

            // Typing "/" first lists skills to pick by name.
            val slashMatches = if (activeSkill == null && input.startsWith("/") && '\n' !in input) {
                matchingSkills(input.drop(1), enabledSkillIds)
            } else emptyList()
            AnimatedVisibility(
                visible = slashMatches.isNotEmpty(),
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                SkillSuggestions(slashMatches, onPick = ::useSkill)
            }
            Composer(
                value = input,
                onValueChange = { input = it },
                enabled = canSend,
                isGenerating = isGenerating,
                persona = persona,
                activeSkill = activeSkill,
                documents = if (documentsAvailable) DocumentsAttachment(documents, useDocuments, excludedDocuments, onUseDocumentsChange, onToggleDocument, onOpenDocuments) else null,
                onOpenSkills = { showSkills = true },
                onClearSkill = { activeSkillId = null },
                placeholder = when {
                    activeSkill != null && !canSend -> "${activeSkill.name} needs a chat model"
                    activeSkill != null -> activeSkill.placeholder
                    isReady -> "Message ${persona.name}…"
                    routeSkills && canSend -> "Load a model to chat, or ask for a QR code, reminder…"
                    else -> "Load a model, or pick a skill"
                },
                onSend = ::submit,
                onStop = onStop,
                onMic = ::startVoice,
                onActionButtonPlaced = { micCenter = it }
            )
        }
    }

    if (showSkills) {
        SkillsSheet(
            enabledSkillIds = enabledSkillIds,
            activeSkillId = activeSkill?.id,
            skillsAvailable = true,
            autoSkills = autoSkills,
            onAutoSkillsChange = onAutoSkillsChange,
            onUseSkill = ::useSkill,
            onToggleSkill = { skill, on ->
                onToggleSkill(skill.id, on)
                if (!on && activeSkillId == skill.id) activeSkillId = null
            },
            onDismiss = { showSkills = false }
        )
    }

    VoiceReveal(voice, micCenter) { state, reveal, active ->
        VoiceModeOverlay(state, persona, onTap = onVoiceTap, onEnd = onStopVoice, reveal = reveal, active = active)
    }
}

@Composable
private fun StatusLine(modelState: ModelState, isGenerating: Boolean, isThinking: Boolean) {
    val (text, color) = when {
        isThinking -> "thinking…" to MaterialTheme.colorScheme.primary
        isGenerating -> "typing…" to MaterialTheme.colorScheme.primary
        modelState is ModelState.Ready -> "Online · on-device" to MaterialTheme.colorScheme.tertiary
        modelState is ModelState.Loading -> "Waking up…" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Offline · no model" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    persona: Persona,
    userName: String,
    modelState: ModelState,
    needsChatModel: Boolean,
    onStarter: (String) -> Unit,
    onPickModel: () -> Unit,
    onOpenModelCenter: () -> Unit,
    skills: List<Skill>,
    onUseSkill: (Skill) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        PersonaAvatar(persona, size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            if (userName.isNotBlank()) "Hi ${userName.trim()}, I'm ${persona.name}" else "Hi, I'm ${persona.name}",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            persona.description.ifBlank { persona.tagline },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        if (needsChatModel && modelState !is ModelState.Ready) {
            ModelSetupCard(modelState, onPickModel, onOpenModelCenter)
            Spacer(Modifier.height(24.dp))
        }

        if (persona.starters.isNotEmpty()) {
            Text(
                "Try asking",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                persona.starters.forEach { starter -> Chip(starter, onClick = { onStarter(starter) }) }
            }
        }
        if (skills.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Or use a skill",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                skills.forEach { skill -> Chip("${skill.emoji}  ${skill.name}", onClick = { onUseSkill(skill) }) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModelSetupCard(modelState: ModelState, onPickModel: () -> Unit, onOpenModelCenter: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            when (modelState) {
                is ModelState.Loading -> {
                    Text("Loading ${modelState.name}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This can take a few seconds the first time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                }
                else -> {
                    Text("Set up your on-device AI", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (modelState is ModelState.Error) modelState.message
                        else "TinyAI runs entirely on your phone. Download a model once (around 1 GB) and it's ready offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (modelState is ModelState.Error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onOpenModelCenter, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Get a model")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Choose a .gguf file")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelBanner(modelState: ModelState, onPickModel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        onClick = onPickModel,
        enabled = modelState !is ModelState.Loading
    ) {
        Text(
            when (modelState) {
                is ModelState.Loading -> "Loading ${modelState.name}…"
                is ModelState.Error -> modelState.message
                else -> "No model loaded. Tap to choose one."
            },
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * Gives a message that just arrived a soft spring entrance: it rises a little and grows from
 * its own corner. Messages restored from history (or scrolled back into view) appear as is.
 */
@Composable
private fun Modifier.messageEntrance(createdAt: Long, fromEnd: Boolean): Modifier {
    val fresh = remember { System.currentTimeMillis() - createdAt < 1_500 }
    val progress = remember { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (fresh) progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 18.dp.toPx()
        val scale = 0.94f + 0.06f * p
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 1f)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    persona: Persona,
    onRemember: (String) -> Unit,
    onReplyAsChat: ((String) -> Unit)? = null,
    speaking: Boolean = false,
    onReadAloud: () -> Unit = {}
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val copy = { text: String ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    if (message.isUser) {
        var menuOpen by remember { mutableStateOf(false) }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalAlignment = Alignment.End
        ) {
            message.skill?.let { skill ->
                SkillLabel(
                    skill,
                    auto = message.isAutoSkill,
                    onReplyAsChat = onReplyAsChat?.takeIf { message.isAutoSkill }?.let { send -> { send(message.text) } },
                    modifier = Modifier.padding(end = 4.dp, bottom = 6.dp)
                )
            }
            Box(Modifier.messageEntrance(message.createdAt, fromEnd = true)) {
                val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 8.dp, bottomStart = 22.dp)
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(shape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            )
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), shape)
                        .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; copy(message.text) }
                    )
                    DropdownMenuItem(
                        text = { Text("Remember this") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        onClick = { menuOpen = false; onRemember(message.text) }
                    )
                }
            }
        }
    } else {
        // Replies sit on the page itself, Claude-style: no bubble, full width, easy to read.
        val parsed = remember(message.text) { ResponseParser.parse(message.text) }
        val thinkingLive = message.isStreaming && parsed.hasThinking && !parsed.isThinkingComplete
        val answer = parsed.answer.ifBlank { if (parsed.hasThinking) "" else message.text }
        val reveal = rememberSmoothReveal(message.id, answer, message.isStreaming)
        val shown = answer.take(reveal.visible)
        // Still typing out, either because tokens are arriving or the reveal is catching up.
        val settling = message.isStreaming || !reveal.isIdle || reveal.visible < answer.length

        Column(
            Modifier
                .fillMaxWidth()
                .messageEntrance(message.createdAt, fromEnd = false)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { if (answer.isNotBlank()) copy(answer) }
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (parsed.hasThinking) {
                ThinkingBlock(
                    messageId = message.id,
                    thinking = parsed.thinking.orEmpty(),
                    isLive = thinkingLive,
                    isComplete = parsed.isThinkingComplete,
                    startedAt = message.createdAt,
                    thinkingMillis = message.thinkingMillis
                )
            }
            when {
                message.isError -> ErrorNote(message.text)
                shown.isNotEmpty() -> MarkdownText(
                    shown,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    fadeChars = reveal.fade
                )
            }
            AnimatedVisibility(
                visible = message.isStreaming && !thinkingLive,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(320))
            ) {
                StreamingSpark(Modifier.padding(top = 2.dp))
            }
            if (message.sources.isNotEmpty() && !message.isError && !settling) SourceCards(message.sources)
            AnimatedVisibility(
                visible = !settling && !message.isError && answer.isNotBlank(),
                enter = fadeIn(tween(300, delayMillis = 80)) + slideInVertically(tween(300, delayMillis = 80)) { it / 3 },
                exit = fadeOut(tween(150))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CopyReplyButton(onClick = { copy(answer) })
                    ReadAloudButton(speaking, onReadAloud)
                }
            }
            val skill = message.skill
            if (skill != null && !settling && !message.isError && answer.isNotBlank()) {
                SkillReplyActions(message, skill, answer)
            }
        }
    }
}

@Composable
private fun ErrorNote(text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 1.dp).size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

/** A slowly turning, breathing spark shown while a reply is being written. */
@Composable
private fun StreamingSpark(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spark")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "sparkRotation"
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sparkBreath"
    )
    Icon(
        Icons.Default.AutoAwesome,
        contentDescription = "Writing",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(22.dp)
            .graphicsLayer {
                rotationZ = rotation
                val scale = 0.8f + 0.25f * breath
                scaleX = scale
                scaleY = scale
                alpha = 0.55f + 0.45f * breath
            }
    )
}

@Composable
private fun CopyReplyButton(onClick: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }
    IconButton(onClick = { onClick(); copied = true }, modifier = Modifier.size(34.dp)) {
        Icon(
            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = "Copy reply",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Reads a reply aloud, or stops it. */
@Composable
private fun ReadAloudButton(speaking: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (speaking) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (speaking) "Stop" else "Listen",
                style = MaterialTheme.typography.labelMedium,
                color = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

