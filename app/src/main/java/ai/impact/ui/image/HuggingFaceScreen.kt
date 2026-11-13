package ai.impact.ui.image

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import ai.impact.HubResolveState
import ai.impact.HubSearchState
import ai.impact.image.GgufFile
import ai.impact.image.GgufResolution
import ai.impact.image.HubCandidate
import ai.impact.image.HubModel
import ai.impact.image.HubResolution
import ai.impact.image.ModelKind
import ai.impact.image.RepoRef
import ai.impact.models.formatBytes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import java.util.Locale

private data class QuickSearch(val label: String, val query: String)

private val QUICK_SEARCHES = mapOf(
    ModelKind.Image to listOf(
        QuickSearch("Phone-friendly", "int8"),
        QuickSearch("Turbo", "sd-turbo"),
        QuickSearch("SD 1.5", "stable-diffusion-v1-5"),
        QuickSearch("Realistic", "realistic"),
        QuickSearch("Anime", "anime"),
        QuickSearch("DreamShaper", "dreamshaper")
    ),
    ModelKind.Chat to listOf(
        QuickSearch("Instruct", "instruct"),
        QuickSearch("Qwen", "qwen"),
        QuickSearch("Llama", "llama"),
        QuickSearch("Gemma", "gemma"),
        QuickSearch("Phi", "phi"),
        QuickSearch("SmolLM", "smollm")
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceScreen(
    kind: ModelKind,
    search: HubSearchState,
    resolve: HubResolveState?,
    installedIds: Set<String>,
    downloadingIds: Set<String>,
    messages: Flow<String>,
    fitsInMemory: (Long) -> Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenModel: (HubModel) -> Unit,
    onRetryResolve: (RepoRef) -> Unit,
    onDismissResolve: () -> Unit,
    onDownloadImage: (HubCandidate) -> Unit,
    onDownloadChat: (GgufFile) -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.collect { snackbarHostState.showSnackbar(it) } }

    var text by rememberSaveable(kind) { mutableStateOf("") }
    fun submit(query: String) {
        focus.clearFocus()
        onSearch(query)
    }
    fun openRepoPage(repo: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/$repo")))
        } catch (e: ActivityNotFoundException) {
            // No browser installed.
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (kind == ModelKind.Chat) "Chat models" else "Image models")
                        Text(
                            if (kind == ModelKind.Chat) "Hugging Face · GGUF, up to 4B parameters" else "Hugging Face · Stable Diffusion ONNX",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                Snackbar(it, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.inverseSurface)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Search, or paste a model link") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) submit(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                items(QUICK_SEARCHES.getValue(kind)) { quick ->
                    FilterChip(
                        selected = search.query == quick.query,
                        onClick = {
                            text = ""
                            submit(quick.query)
                        },
                        label = { Text(quick.label) }
                    )
                }
            }
            Box(Modifier.height(3.dp).fillMaxWidth()) {
                if (search.loading) LinearProgressIndicator(Modifier.fillMaxSize())
            }

            when {
                search.error != null && search.results.isEmpty() -> CenteredMessage(
                    title = "Couldn't search Hugging Face",
                    body = search.error,
                    action = { OutlinedButton(onClick = { onSearch(search.query) }) { Text("Try again") } }
                )
                search.loaded && search.results.isEmpty() && !search.loading -> CenteredMessage(
                    title = if (kind == ModelKind.Chat) "No small GGUF models found" else "No ONNX image models found",
                    body = if (kind == ModelKind.Chat) {
                        "Try another word, or paste a link to a model repo. Adding \"gguf\" to a model's name often finds a version that runs here."
                    } else {
                        "Try another word, or paste a link to a model repo. Most models on Hugging Face are not converted to ONNX; " +
                            "adding \"onnx\" to a model's name often finds a version that is."
                    }
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Tip: paste any huggingface.co link, e.g. owner/model or a link to a folder inside a repo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(search.results, key = { it.id }) { model -> ResultRow(model) { onOpenModel(model) } }
                }
            }
        }
    }

    if (resolve != null) {
        ResolveSheet(
            state = resolve,
            installedIds = installedIds,
            downloadingIds = downloadingIds,
            fitsInMemory = fitsInMemory,
            onDownloadImage = onDownloadImage,
            onDownloadChat = onDownloadChat,
            onRetry = onRetryResolve,
            onOpenPage = ::openRepoPage,
            onDismiss = onDismissResolve
        )
    }
}

@Composable
private fun ResultRow(model: HubModel, onClick: () -> Unit) {
    val unsupported = model.unsupportedReason != null
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (unsupported) 0.6f else 1f)
        ) {
            Text(
                model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                model.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stat(Icons.Default.FileDownload, compactNumber(model.downloads))
                Spacer(Modifier.width(12.dp))
                Stat(Icons.Default.Favorite, compactNumber(model.likes))
                if (unsupported) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (model.unsupportedReason!!.contains("too big")) model.unsupportedReason
                        else "${model.unsupportedReason} · likely not supported",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenteredMessage(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveSheet(
    state: HubResolveState,
    installedIds: Set<String>,
    downloadingIds: Set<String>,
    fitsInMemory: (Long) -> Boolean,
    onDownloadImage: (HubCandidate) -> Unit,
    onDownloadChat: (GgufFile) -> Unit,
    onRetry: (RepoRef) -> Unit,
    onOpenPage: (String) -> Unit,
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
                .heightIn(min = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            val repo = when (state) {
                is HubResolveState.Checking -> state.repo
                is HubResolveState.ImageResult -> when (val r = state.resolution) {
                    is HubResolution.Found -> r.repo
                    is HubResolution.Unsupported -> r.repo
                }
                is HubResolveState.ChatResult -> when (val r = state.resolution) {
                    is GgufResolution.Found -> r.repo
                    is GgufResolution.Unsupported -> r.repo
                }
                is HubResolveState.Failed -> state.ref.repo
            }
            Text(repo.substringAfter('/'), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(repo.substringBefore('/'), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            when (state) {
                is HubResolveState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking the repo's files…", style = MaterialTheme.typography.bodyMedium)
                }
                is HubResolveState.Failed -> {
                    Notice(Icons.Default.ErrorOutline, state.message, MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { onRetry(state.ref) }) { Text("Try again") }
                }
                is HubResolveState.ImageResult -> when (val r = state.resolution) {
                    is HubResolution.Unsupported -> Notice(Icons.Default.WarningAmber, r.reason, MaterialTheme.colorScheme.error)
                    is HubResolution.Found -> {
                        Text(
                            if (r.candidates.size == 1) "Ready to download" else "This repo has ${r.candidates.size} models",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        r.candidates.forEach { candidate ->
                            OptionRow(
                                title = candidate.name,
                                subtitle = "${formatBytes(candidate.sizeBytes)} · ${candidate.files.size} files" +
                                    (candidate.root.takeIf { it.isNotEmpty() }?.let { " · /${it.trimEnd('/')}" } ?: ""),
                                badge = null,
                                installed = candidate.id in installedIds,
                                downloading = candidate.id in downloadingIds,
                                fits = fitsInMemory(candidate.sizeBytes),
                                onDownload = { onDownloadImage(candidate) }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Footnote("Only the files needed to run it on-device are downloaded.")
                    }
                }
                is HubResolveState.ChatResult -> when (val r = state.resolution) {
                    is GgufResolution.Unsupported -> Notice(Icons.Default.WarningAmber, r.reason, MaterialTheme.colorScheme.error)
                    is GgufResolution.Found -> {
                        // Q4_K_M is the usual sweet spot of size and quality; fall back to the biggest file that fits.
                        val fitting = r.files.filter { fitsInMemory(it.size) }
                        val recommended = fitting.firstOrNull { it.quant == "Q4_K_M" } ?: fitting.lastOrNull()
                        Text(
                            if (r.files.size == 1) "Ready to download" else "Choose a size (${r.files.size} versions)",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Smaller files are faster and use less memory; bigger ones answer better.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        r.files.forEach { file ->
                            OptionRow(
                                title = file.quant ?: file.name,
                                subtitle = "${formatBytes(file.size)} · ${file.fileName}",
                                badge = if (file == recommended) "Recommended" else null,
                                installed = file.id in installedIds,
                                downloading = file.id in downloadingIds,
                                fits = fitsInMemory(file.size),
                                onDownload = { onDownloadChat(file) }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            Footnote("Check the model's license on its page before using it commercially.")
            TextButton(onClick = { onOpenPage(repo) }) {
                Text("Open on Hugging Face")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    badge: String?,
    installed: Boolean,
    downloading: Boolean,
    fits: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (badge != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (badge != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                when {
                    installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    }
                    downloading -> Text("Downloading…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    else -> Button(onClick = onDownload, enabled = fits, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
                    }
                }
            }
            if (!fits && !installed) {
                Spacer(Modifier.height(8.dp))
                Notice(Icons.Default.WarningAmber, "Too large for this phone's memory.", MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun compactNumber(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}
