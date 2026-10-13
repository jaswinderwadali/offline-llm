package ai.impact.ui.chat

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    persona: Persona,
    personas: List<Persona>,
    messages: List<ChatMessage>,
    modelState: ModelState,
    isGenerating: Boolean,
    userName: String,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onSelectPersona: (String) -> Unit,
    onBrowsePersonas: () -> Unit,
    onCreatePersona: () -> Unit,
    onPickModel: () -> Unit,
    onOpenModelCenter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMemory: () -> Unit,
    onRememberMessage: (String) -> Unit,
    memoryEvents: Flow<MemoryEntity>
) {
    var input by rememberSaveable { mutableStateOf("") }
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val isReady = modelState is ModelState.Ready
    val snackbarHostState = remember { SnackbarHostState() }

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

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(messages.lastOrNull()?.text?.length) {
        if (messages.lastOrNull()?.isStreaming == true) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showSwitcher = true }
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {
                        PersonaAvatar(persona, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    persona.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Switch personality",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            val isThinking = messages.lastOrNull()?.let {
                                it.isStreaming && ResponseParser.parse(it.text).let { p -> p.hasThinking && !p.isThinkingComplete }
                            } == true
                            StatusLine(modelState, isGenerating, isThinking)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "Chat history")
                    }
                    IconButton(onClick = onNewChat, enabled = messages.isNotEmpty()) {
                        Icon(Icons.Default.EditNote, contentDescription = "New chat")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
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
                        onStarter = { if (isReady) onSend(it) else input = it },
                        onPickModel = onPickModel,
                        onOpenModelCenter = onOpenModelCenter
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageBubble(message, persona, onRemember = onRememberMessage)
                        }
                    }
                }
            }

            if (!isReady && messages.isNotEmpty()) {
                ModelBanner(modelState, onPickModel)
            }

            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = isReady,
                isGenerating = isGenerating,
                placeholder = if (isReady) "Message ${persona.name}…" else "Load a model to start chatting",
                onSend = {
                    onSend(input)
                    input = ""
                },
                onStop = onStop
            )
        }
    }

    if (showSwitcher) {
        PersonaSwitcherSheet(
            personas = personas,
            selectedId = persona.id,
            onDismiss = { showSwitcher = false },
            onSelect = {
                onSelectPersona(it)
                showSwitcher = false
            },
            onBrowseAll = {
                showSwitcher = false
                onBrowsePersonas()
            },
            onCreate = {
                showSwitcher = false
                onCreatePersona()
            }
        )
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
    onStarter: (String) -> Unit,
    onPickModel: () -> Unit,
    onOpenModelCenter: () -> Unit
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

        if (modelState !is ModelState.Ready) {
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
                        else "TinyAI runs entirely on your phone. Download a model once (around 1 GB), then choose the file here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (modelState is ModelState.Error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Choose model file")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenModelCenter, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Get a model")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, persona: Persona, onRemember: (String) -> Unit) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val copy = { text: String ->
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    if (message.isUser) {
        var menuOpen by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box {
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                        // Your messages echo the icon's bubble: charcoal in light mode, cream in dark mode.
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        .padding(horizontal = 16.dp, vertical = 11.dp)
                ) {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
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
        val parsed = remember(message.text) { ResponseParser.parse(message.text) }
        val thinkingLive = message.isStreaming && parsed.hasThinking && !parsed.isThinkingComplete
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            PersonaAvatar(persona, size = 28.dp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                val showTyping = message.isStreaming && parsed.answer.isBlank() && !thinkingLive
                if (parsed.answer.isNotBlank() || showTyping || (!parsed.hasThinking && message.text.isNotBlank())) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp))
                            .background(
                                if (message.isError) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { if (parsed.answer.isNotBlank()) copy(parsed.answer) }
                            )
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) {
                        when {
                            showTyping -> TypingIndicator()
                            message.isError -> Text(
                                message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            else -> MarkdownText(parsed.answer.ifBlank { message.text })
                        }
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                enabled = enabled,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = RoundedCornerShape(26.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
            Spacer(Modifier.width(8.dp))
            val canSend = enabled && value.isNotBlank() && !isGenerating
            val active = isGenerating || canSend
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = active) { if (isGenerating) onStop() else onSend() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isGenerating) "Stop" else "Send",
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaSwitcherSheet(
    personas: List<Persona>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onBrowseAll: () -> Unit,
    onCreate: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Switch personality", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "See all",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBrowseAll)
                        .padding(8.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(76.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onCreate)
                    ) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Create", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                items(personas, key = { it.id }) { persona ->
                    val selected = persona.id == selectedId
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(76.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(persona.id) }
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            PersonaAvatar(persona, size = 64.dp)
                            if (selected) {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(persona.brush()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Current", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            persona.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
