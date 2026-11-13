package ai.impact.ui.image

import android.graphics.Bitmap
import android.os.Build
import ai.impact.AspectRatio
import ai.impact.GenerationState
import ai.impact.ImageModelState
import ai.impact.ImageSettings
import ai.impact.image.GeneratedImage
import ai.impact.image.GenerationPhase
import ai.impact.image.InstalledImageModel
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.impact.ui.components.SectionHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.roundToInt

private val PROMPT_IDEAS = listOf(
    "A cozy cabin in snowy woods at dusk, warm lights",
    "Portrait of a red fox wearing a scarf, soft light",
    "A floating island city above the clouds",
    "Cherry blossom street in Kyoto after rain",
    "A tiny robot watering plants on a windowsill",
    "Lighthouse on a cliff during a storm, dramatic sky"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageStudioScreen(
    modelState: ImageModelState,
    installed: List<InstalledImageModel>,
    selectedModelId: String?,
    generation: GenerationState,
    prompt: String,
    settings: ImageSettings,
    recent: List<GeneratedImage>,
    chatModelLoaded: Boolean,
    chatModelName: String?,
    messages: Flow<String>,
    decode: (GeneratedImage, Int) -> Bitmap?,
    onOpened: () -> Unit,
    onBack: () -> Unit,
    onPromptChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onUpdateSettings: ((ImageSettings) -> ImageSettings) -> Unit,
    onResetSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenImage: (GeneratedImage) -> Unit,
    onLoadModel: (id: String, ignoreLowMemory: Boolean) -> Unit,
    onFreeChatModelAndRetry: (id: String) -> Unit,
    onDismissModelError: () -> Unit,
    onShare: (GeneratedImage) -> Unit,
    onSave: (GeneratedImage) -> Unit,
    onChooseChatModel: () -> Unit
) {
    BackHandler(onBack = onBack)
    // Re-run once the installed list arrives from disk, so the model auto-loads on a cold start too.
    LaunchedEffect(installed.isNotEmpty()) { onOpened() }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.collect { snackbarHostState.showSnackbar(it) } }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    val isRunning = generation is GenerationState.Running
    val ready = modelState as? ImageModelState.Ready
    val selectedModel = installed.firstOrNull { it.id == selectedModelId }

    // The phone would otherwise sleep partway through a minute-long generation.
    val view = LocalView.current
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Imagine", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "On-device image generation",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenGallery) { Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Your images") }
                    IconButton(onClick = onOpenModels) { Icon(Icons.Outlined.ViewInAr, contentDescription = "Image models") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (installed.isNotEmpty()) {
                PromptBar(
                    value = prompt,
                    onValueChange = onPromptChange,
                    enabled = ready != null || isRunning,
                    isRunning = isRunning,
                    onGenerate = onGenerate,
                    onCancel = onCancel
                )
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                Snackbar(it, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.inverseSurface)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (installed.isEmpty()) {
            NoModelsState(Modifier.padding(padding), onOpenModels)
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            ModelPill(modelState, selectedModel, onOpenModels)
            AnimatedVisibility(
                visible = modelState is ImageModelState.Error,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                (modelState as? ImageModelState.Error)?.let { error ->
                    ModelErrorCard(
                        error = error,
                        chatModelLoaded = chatModelLoaded,
                        onTryAnyway = { error.modelId?.let { onLoadModel(it, true) } },
                        onFreeChat = { error.modelId?.let(onFreeChatModelAndRetry) },
                        onDismiss = onDismissModelError
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val (canvasW, canvasH) = when (generation) {
                is GenerationState.Running -> generation.request.width to generation.request.height
                is GenerationState.Done -> generation.bitmap.width to generation.bitmap.height
                else -> ready?.fixedSize ?: (settings.aspect.width to settings.aspect.height)
            }
            ImageCanvas(
                width = canvasW,
                height = canvasH,
                modelState = modelState,
                generation = generation,
                onOpen = { (generation as? GenerationState.Done)?.let { onOpenImage(it.image) } },
                onRetry = onGenerate
            )

            AnimatedVisibility(
                visible = generation is GenerationState.Done,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                (generation as? GenerationState.Done)?.let { done ->
                    ResultActions(
                        image = done.image,
                        onShare = { onShare(done.image) },
                        onSave = { onSave(done.image) },
                        onOpen = { onOpenImage(done.image) },
                        onLockSeed = { onUpdateSettings { it.copy(seed = done.image.seed) } },
                        seedLocked = settings.seed == done.image.seed
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OptionsRow(
                settings = settings,
                fixedSize = ready?.fixedSize,
                enabled = !isRunning,
                onAspect = { aspect -> onUpdateSettings { it.copy(aspect = aspect) } },
                onOpenSettings = { showSettings = true }
            )
            Spacer(Modifier.height(12.dp))
            EnhanceRow(
                enabled = settings.enhancePrompt,
                chatModelName = chatModelName,
                interactive = !isRunning,
                onToggle = { on -> onUpdateSettings { it.copy(enhancePrompt = on) } },
                onChooseChatModel = onChooseChatModel
            )

            if (recent.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("Recent", Modifier.weight(1f))
                    TextButton(onClick = onOpenGallery) { Text("See all") }
                }
                RecentStrip(recent.take(12), decode, onOpenImage)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onUpdate = onUpdateSettings,
            onReset = onResetSettings,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun NoModelsState(modifier: Modifier, onOpenModels: () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Create images on your phone", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Describe anything and an image model running entirely on this device will paint it. " +
                "Nothing is uploaded, and it works offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenModels, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Get an image model")
        }
    }
}

@Composable
private fun ModelPill(state: ImageModelState, selected: InstalledImageModel?, onClick: () -> Unit) {
    val (label, status, color) = when (state) {
        is ImageModelState.Ready -> Triple(state.name, "Ready", MaterialTheme.colorScheme.tertiary)
        is ImageModelState.Loading -> Triple(state.name, "Loading…", MaterialTheme.colorScheme.primary)
        is ImageModelState.Error -> Triple(selected?.name ?: "Image model", "Not loaded", MaterialTheme.colorScheme.error)
        ImageModelState.NotLoaded -> Triple(selected?.name ?: "Choose a model", "Not loaded", MaterialTheme.colorScheme.outline)
    }
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = if (state is ImageModelState.Loading) pulse.value else 1f }
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp))
            Text(
                " · $status",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ModelErrorCard(
    error: ImageModelState.Error,
    chatModelLoaded: Boolean,
    onTryAnyway: () -> Unit,
    onFreeChat: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                if (error.lowMemory && error.modelId != null) {
                    TextButton(onClick = onTryAnyway) { Text("Try anyway") }
                    if (chatModelLoaded) {
                        TextButton(onClick = onFreeChat) { Text("Unload chat model") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageCanvas(
    width: Int,
    height: Int,
    modelState: ImageModelState,
    generation: GenerationState,
    onOpen: () -> Unit,
    onRetry: () -> Unit
) {
    val ratio by animateFloatAsState(width.toFloat() / height, tween(280), label = "ratio")
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Portrait images are capped so the prompt bar and actions stay on screen.
        val maxCanvasHeight = maxWidth / ratio
        val widthCap = if (maxCanvasHeight > 520.dp) maxWidth * (520.dp / maxCanvasHeight) else maxWidth
        Box(
            Modifier
                .width(widthCap)
                .aspectRatio(ratio)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        ) {
            val stateKey = when (generation) {
                is GenerationState.Running -> "running"
                is GenerationState.Done -> "done:${generation.image.id}"
                is GenerationState.Failed -> "failed"
                GenerationState.Idle -> if (modelState is ImageModelState.Loading) "loading" else "idle"
            }
            AnimatedContent(
                targetState = stateKey,
                transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) },
                label = "canvas",
                modifier = Modifier.fillMaxSize()
            ) { key ->
                when {
                    key == "running" && generation is GenerationState.Running -> RunningCanvas(generation)
                    key.startsWith("done") && generation is GenerationState.Done -> Image(
                        bitmap = remember(generation.bitmap) { generation.bitmap.asImageBitmap() },
                        contentDescription = generation.image.prompt,
                        // The canvas has the image's exact aspect ratio, so Fit fills it with no cropping or bars.
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onOpen)
                    )
                    key == "failed" && generation is GenerationState.Failed -> CanvasMessage(
                        icon = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp)) },
                        title = "Couldn't create the image",
                        body = generation.message,
                        action = { FilledTonalButton(onClick = onRetry) { Text("Try again") } }
                    )
                    key == "loading" -> CanvasMessage(
                        icon = { CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp) },
                        title = "Loading ${(modelState as? ImageModelState.Loading)?.name ?: "model"}",
                        body = "The first load can take up to a minute."
                    )
                    else -> CanvasMessage(
                        icon = {
                            Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                        },
                        title = "Your image will appear here",
                        body = "$width × $height"
                    )
                }
            }
        }
    }
}

@Composable
private fun CanvasMessage(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
private fun RunningCanvas(state: GenerationState.Running) {
    val elapsed by produceState(0L, state.startedAt) {
        while (true) {
            value = System.currentTimeMillis() - state.startedAt
            delay(500)
        }
    }
    val progress by animateFloatAsState(state.progress, tween(400), label = "progress")
    val shimmer = rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "shimmerOffset"
    )
    Box(Modifier.fillMaxSize()) {
        val preview = state.preview
        if (preview != null) {
            // The preview is the latent at 1/8 scale: smooth it and soften it so it reads as "developing".
            Image(
                bitmap = remember(preview) { preview.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(6.dp) else Modifier)
            )
        }
        val base = MaterialTheme.colorScheme.surfaceContainer
        val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = if (preview == null) listOf(base, highlight, base)
                        else listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(shimmer.value * 800f, 0f),
                        end = androidx.compose.ui.geometry.Offset(shimmer.value * 800f + 600f, 600f)
                    )
                )
        )
        if (preview == null) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
            )
        }
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = when (val phase = state.phase) {
                        GenerationPhase.EnhancingPrompt -> "Polishing your prompt…"
                        GenerationPhase.EncodingPrompt -> "Reading your prompt…"
                        is GenerationPhase.Denoising -> if (phase.total == 1) "Painting…" else "Step ${phase.step + 1} of ${phase.total}"
                        GenerationPhase.Decoding -> "Adding final details…"
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(formatClock(elapsed), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    drawStopIndicator = {}
                )
            }
        }
    }
}

@Composable
private fun ResultActions(
    image: GeneratedImage,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
    onLockSeed: () -> Unit,
    seedLocked: Boolean
) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShare, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save")
            }
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.OpenInFull, contentDescription = "View full screen", modifier = Modifier.size(18.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${image.width} × ${image.height} · ${image.steps} ${if (image.steps == 1) "step" else "steps"} · " +
                    formatDuration(image.durationMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onLockSeed, enabled = !seedLocked) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (seedLocked) "Seed kept" else "Keep seed ${image.seed}", style = MaterialTheme.typography.labelMedium)
            }
        }
        image.enhancedPrompt?.let { EnhancedPromptCard(it) }
    }
}

@Composable
private fun EnhancedPromptCard(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .animateContentSize()
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.AutoFixHigh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Enhanced prompt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy enhanced prompt", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** The on/off switch for letting the chat model polish prompts, with what it'll use (or what's missing). */
@Composable
private fun EnhanceRow(
    enabled: Boolean,
    chatModelName: String?,
    interactive: Boolean,
    onToggle: (Boolean) -> Unit,
    onChooseChatModel: () -> Unit
) {
    val missingModel = enabled && chatModelName == null
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (enabled && !missingModel) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Enhance prompt with AI", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            missingModel -> "Needs a chat model loaded"
                            enabled -> "$chatModelName adds detail before painting"
                            else -> "Your chat model turns short ideas into detailed prompts"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missingModel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = interactive)
            }
            if (missingModel) {
                TextButton(onClick = onChooseChatModel, modifier = Modifier.padding(start = 36.dp)) {
                    Text("Choose a chat model file")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsRow(
    settings: ImageSettings,
    fixedSize: Pair<Int, Int>?,
    enabled: Boolean,
    onAspect: (AspectRatio) -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (fixedSize == null) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                AspectRatio.entries.forEachIndexed { index, aspect ->
                    SegmentedButton(
                        selected = settings.aspect == aspect,
                        onClick = { onAspect(aspect) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, AspectRatio.entries.size),
                        icon = {}
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AspectGlyph(aspect)
                            Spacer(Modifier.width(6.dp))
                            Text(aspect.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        } else {
            Text(
                "${fixedSize.first} × ${fixedSize.second} (fixed by this model)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onOpenSettings,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(40.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("${settings.steps}")
        }
    }
}

/** A tiny outlined rectangle in the aspect's proportions. */
@Composable
private fun AspectGlyph(aspect: AspectRatio) {
    val longSide = 14.dp
    val w = if (aspect.width >= aspect.height) longSide else longSide * aspect.width / aspect.height
    val h = if (aspect.height >= aspect.width) longSide else longSide * aspect.height / aspect.width
    Box(Modifier.size(longSide), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(w, h)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun RecentStrip(images: List<GeneratedImage>, decode: (GeneratedImage, Int) -> Bitmap?, onOpen: (GeneratedImage) -> Unit) {
    val sizePx = with(LocalDensity.current) { 88.dp.roundToPx() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(images, key = { it.id }) { image ->
            Thumbnail(
                image = image,
                sizePx = sizePx,
                decode = decode,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpen(image) }
            )
        }
    }
}

@Composable
private fun PromptBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isRunning: Boolean,
    onGenerate: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            AnimatedVisibility(visible = value.isBlank() && enabled && !isRunning) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                ) {
                    items(PROMPT_IDEAS) { idea ->
                        Surface(
                            onClick = { onValueChange(idea) },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                idea,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                    enabled = enabled && !isRunning,
                    placeholder = { Text("Describe an image…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 4,
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
                val canGenerate = enabled && value.isNotBlank() && !isRunning
                val active = isRunning || canGenerate
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
                            else Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                        )
                        .clickable(enabled = active) { if (isRunning) onCancel() else onGenerate() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isRunning) Icons.Default.Stop else Icons.Default.AutoAwesome,
                        contentDescription = if (isRunning) "Cancel" else "Generate image",
                        tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: ImageSettings,
    onUpdate: ((ImageSettings) -> ImageSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Generation settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Reset") }
            }
            Spacer(Modifier.height(16.dp))

            LabeledValue("Steps", "${settings.steps}")
            Slider(
                value = settings.steps.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(steps = v.roundToInt().coerceIn(1, it.maxSteps)) } },
                valueRange = 1f..settings.maxSteps.toFloat(),
                steps = (settings.maxSteps - 2).coerceAtLeast(0)
            )
            Hint("More steps add detail but take longer. Each step takes a few seconds on a phone.")

            if (settings.usesGuidance) {
                Spacer(Modifier.height(20.dp))
                LabeledValue("Prompt strength", String.format(Locale.US, "%.1f", settings.guidance))
                Slider(
                    value = settings.guidance,
                    onValueChange = { v -> onUpdate { it.copy(guidance = (v * 2).roundToInt() / 2f) } },
                    valueRange = 1.5f..14f
                )
                Hint("Higher sticks closer to your words; lower gives the model more freedom.")

                Spacer(Modifier.height(20.dp))
                Text("Avoid", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.negativePrompt,
                    onValueChange = { v -> onUpdate { it.copy(negativePrompt = v) } },
                    placeholder = { Text("Things to keep out, e.g. blurry, text, watermark") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 4
                )
            } else {
                Spacer(Modifier.height(12.dp))
                Hint("This model is distilled for speed, so it ignores prompt strength and \"avoid\" terms.")
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Random seed", style = MaterialTheme.typography.titleSmall)
                    Hint("Turn off to repeat an image exactly, or to tweak a prompt while keeping its look.")
                }
                Switch(
                    checked = settings.seed == null,
                    onCheckedChange = { random ->
                        onUpdate { it.copy(seed = if (random) null else (kotlin.random.Random.nextLong() and 0xFFFFFFFFL)) }
                    }
                )
            }
            settings.seed?.let { seed ->
                Spacer(Modifier.height(8.dp))
                var text by remember(seed) { mutableStateOf(seed.toString()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        val digits = v.filter(Char::isDigit).take(10)
                        text = digits
                        digits.toLongOrNull()?.let { parsed -> onUpdate { it.copy(seed = parsed) } }
                    },
                    label = { Text("Seed") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        IconButton(onClick = { onUpdate { it.copy(seed = kotlin.random.Random.nextLong() and 0xFFFFFFFFL) } }) {
                            Icon(Icons.Default.Casino, contentDescription = "New seed")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
