package ai.impact.ui.models

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import ai.impact.ImageModelState
import ai.impact.ModelState
import ai.impact.image.CatalogImageModel
import ai.impact.image.InstalledImageModel
import ai.impact.image.ModelImport
import ai.impact.image.ModelKind
import ai.impact.models.CatalogTextModel
import ai.impact.models.InstalledTextModel
import ai.impact.models.ModelDownload
import ai.impact.models.formatBytes
import ai.impact.ui.components.SectionHeader
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import java.util.Locale

enum class ModelsTab(val label: String) { Chat("Chat"), Image("Image") }

/** Everything the Chat tab shows and does. */
class ChatModelsUi(
    val state: ModelState,
    val installed: List<InstalledTextModel>,
    val catalog: List<CatalogTextModel>,
    val downloads: Map<String, ModelDownload>,
    val onDownload: (CatalogTextModel) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val onDismissDownloadError: (String) -> Unit,
    val onUse: (InstalledTextModel) -> Unit,
    val onUnload: () -> Unit,
    val onDelete: (InstalledTextModel) -> Unit,
    val onPickFile: () -> Unit
)

/** Everything the Image tab shows and does. */
class ImageModelsUi(
    val state: ImageModelState,
    val installed: List<InstalledImageModel>,
    val catalog: List<CatalogImageModel>,
    val downloads: Map<String, ModelDownload>,
    val import: ModelImport?,
    val selectedId: String?,
    val onDownload: (CatalogImageModel) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val onDismissDownloadError: (String) -> Unit,
    val onUse: (InstalledImageModel) -> Unit,
    val onUnload: () -> Unit,
    val onDelete: (InstalledImageModel) -> Unit,
    val onImport: () -> Unit,
    val onCancelImport: () -> Unit
)

/** A download or delete the user is being asked to confirm. */
private sealed interface Confirm {
    data class Download(val name: String, val sizeBytes: Long, val license: String?, val onConfirm: () -> Unit) : Confirm
    data class Delete(val name: String, val sizeBytes: Long, val note: String, val onConfirm: () -> Unit) : Confirm
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    tab: ModelsTab,
    onTabChange: (ModelsTab) -> Unit,
    deviceRamBytes: Long,
    chat: ChatModelsUi,
    image: ImageModelsUi,
    messages: Flow<String>,
    onOpenHub: (ModelKind) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(messages) { messages.collect { snackbarHostState.showSnackbar(it) } }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val ramGb = deviceRamBytes / (1024.0 * 1024 * 1024)

    fun openPage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // No browser installed.
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    title = { Text("Models") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                PrimaryTabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    ModelsTab.entries.forEach { entry ->
                        val ready = when (entry) {
                            ModelsTab.Chat -> chat.state is ModelState.Ready
                            ModelsTab.Image -> image.state is ImageModelState.Ready
                        }
                        Tab(
                            selected = tab == entry,
                            onClick = { onTabChange(entry) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.label)
                                    if (ready) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.tertiary)
                                        )
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    if (entry == ModelsTab.Chat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) {
                Snackbar(it, shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.inverseSurface)
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "modelsTab",
            modifier = Modifier.padding(padding)
        ) { current ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (current) {
                    ModelsTab.Chat -> chatTab(chat, ramGb, onOpenHub = { onOpenHub(ModelKind.Chat) }, ask = { confirm = it })
                    ModelsTab.Image -> imageTab(image, ramGb, onOpenHub = { onOpenHub(ModelKind.Image) }, ask = { confirm = it }, openPage = ::openPage)
                }
            }
        }
    }

    when (val c = confirm) {
        is Confirm.Download -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Download ${c.name}?") },
            text = {
                Text(
                    "This downloads ${formatBytes(c.sizeBytes)}, so Wi-Fi is best. It keeps going if you leave the app." +
                        (c.license?.let { "\n\nLicense: $it." } ?: "")
                )
            },
            confirmButton = { TextButton(onClick = { c.onConfirm(); confirm = null }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
        is Confirm.Delete -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("This frees ${formatBytes(c.sizeBytes)}. ${c.note}") },
            confirmButton = {
                TextButton(onClick = { c.onConfirm(); confirm = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
        null -> Unit
    }
}

// region Tabs

private fun LazyListScope.chatTab(ui: ChatModelsUi, ramGb: Double, onOpenHub: () -> Unit, ask: (Confirm) -> Unit) {
    val loadedUri = (ui.state as? ModelState.Ready)?.uri
    val loadingUri = (ui.state as? ModelState.Loading)?.uri
    item(key = "status") {
        val status = when (val s = ui.state) {
            is ModelState.Ready -> Status.Ready(s.name)
            is ModelState.Loading -> Status.Loading(s.name)
            is ModelState.Error -> Status.Failed(s.message)
            ModelState.NotLoaded -> Status.Idle("No chat model loaded", "Download one below, or load a .gguf file from your phone.")
        }
        StatusCard(Icons.Outlined.ChatBubbleOutline, "Chat model", status, ramGb, onUnload = ui.onUnload)
    }

    if (ui.installed.isNotEmpty()) {
        item(key = "chat-installed-header") { SectionHeader("On this device", Modifier.padding(top = 8.dp)) }
        items(ui.installed, key = { "chat-installed:${it.id}" }) { model ->
            val uri = Uri.fromFile(model.file).toString()
            InstalledRow(
                name = model.name,
                details = buildString {
                    append(formatBytes(model.sizeBytes))
                    model.sourceRepo?.let { append(" · From Hugging Face") }
                },
                selected = uri == loadedUri,
                loaded = uri == loadedUri,
                loading = uri == loadingUri,
                onUse = { ui.onUse(model) },
                onUnload = ui.onUnload,
                onDelete = {
                    ask(Confirm.Delete(model.name, model.sizeBytes, "You can download it again any time.") { ui.onDelete(model) })
                }
            )
        }
    }

    val installedIds = ui.installed.map { it.id }.toSet()
    customDownloads(ui.downloads.filterKeys { id -> ui.catalog.none { it.id == id } }, "chat", ui.onCancelDownload, ui.onDismissDownloadError)

    val available = ui.catalog.filterNot { it.id in installedIds }
    if (available.isNotEmpty()) {
        item(key = "chat-catalog-header") { SectionHeader("Download", Modifier.padding(top = 8.dp)) }
        items(available, key = { "chat-catalog:${it.id}" }) { model ->
            CatalogCard(
                name = model.name,
                description = model.description,
                recommended = model.recommended,
                tags = listOf(formatBytes(model.sizeBytes), "GGUF"),
                minRamGb = model.minRamGb,
                ramGb = ramGb,
                download = ui.downloads[model.id],
                onDownload = { ask(Confirm.Download(model.name, model.sizeBytes, null) { ui.onDownload(model) }) },
                onCancel = { ui.onCancelDownload(model.id) },
                onDismissError = { ui.onDismissDownloadError(model.id) },
                onDetails = null
            )
        }
    }

    item(key = "chat-hub-header") { SectionHeader("Hugging Face", Modifier.padding(top = 8.dp)) }
    item(key = "chat-hub") {
        HubCard("Search thousands of small GGUF chat models, or paste a link to any repo.", onOpenHub)
    }
    item(key = "chat-file-header") { SectionHeader("Load from device", Modifier.padding(top = 8.dp)) }
    item(key = "chat-file") {
        ActionCard(
            title = "Choose a .gguf file",
            body = "Load a model you already have on your phone, e.g. in Downloads. It's used where it is, without copying.",
            buttonLabel = "Choose file",
            icon = Icons.Default.FolderOpen,
            onClick = ui.onPickFile
        )
    }
}

private fun LazyListScope.imageTab(
    ui: ImageModelsUi,
    ramGb: Double,
    onOpenHub: () -> Unit,
    ask: (Confirm) -> Unit,
    openPage: (String) -> Unit
) {
    val loadedId = (ui.state as? ImageModelState.Ready)?.id
    val loadingId = (ui.state as? ImageModelState.Loading)?.id
    item(key = "image-status") {
        val status = when (val s = ui.state) {
            is ImageModelState.Ready -> Status.Ready(s.name)
            is ImageModelState.Loading -> Status.Loading(s.name)
            is ImageModelState.Error -> Status.Failed(s.message)
            ImageModelState.NotLoaded -> Status.Idle(
                "No image model loaded",
                if (ui.installed.isEmpty()) "Download one below to start creating images." else "It loads when you create an image."
            )
        }
        StatusCard(Icons.Outlined.Image, "Image model", status, ramGb, onUnload = ui.onUnload)
    }

    if (ui.installed.isNotEmpty()) {
        item(key = "image-installed-header") { SectionHeader("On this device", Modifier.padding(top = 8.dp)) }
        items(ui.installed, key = { "image-installed:${it.id}" }) { model ->
            InstalledRow(
                name = model.name,
                details = buildString {
                    append(formatBytes(model.sizeBytes))
                    append(" · ")
                    append(if (model.defaults.steps == 1) "1 step" else "${model.defaults.steps} steps")
                    if (model.imported) append(" · Imported")
                    model.sourceRepo?.let { append(" · From Hugging Face") }
                },
                selected = model.id == ui.selectedId,
                loaded = model.id == loadedId,
                loading = model.id == loadingId,
                onUse = { ui.onUse(model) },
                onUnload = ui.onUnload,
                onDelete = {
                    ask(Confirm.Delete(model.name, model.sizeBytes, "Images you made with it are kept.") { ui.onDelete(model) })
                }
            )
        }
    }

    val installedIds = ui.installed.map { it.id }.toSet()
    customDownloads(ui.downloads.filterKeys { id -> ui.catalog.none { it.id == id } }, "image", ui.onCancelDownload, ui.onDismissDownloadError)

    val available = ui.catalog.filterNot { it.id in installedIds }
    if (available.isNotEmpty()) {
        item(key = "image-catalog-header") { SectionHeader("Download", Modifier.padding(top = 8.dp)) }
        items(available, key = { "image-catalog:${it.id}" }) { model ->
            CatalogCard(
                name = model.name,
                description = model.description,
                recommended = model.recommended,
                tags = listOf(formatBytes(model.sizeBytes), model.style),
                minRamGb = model.minRamGb,
                ramGb = ramGb,
                download = ui.downloads[model.id],
                onDownload = { ask(Confirm.Download(model.name, model.sizeBytes, model.license) { ui.onDownload(model) }) },
                onCancel = { ui.onCancelDownload(model.id) },
                onDismissError = { ui.onDismissDownloadError(model.id) },
                onDetails = { openPage(model.pageUrl) }
            )
        }
    }

    item(key = "image-hub-header") { SectionHeader("Hugging Face", Modifier.padding(top = 8.dp)) }
    item(key = "image-hub") {
        HubCard("Search community models, or paste a link to any Stable Diffusion ONNX repo.", onOpenHub)
    }
    item(key = "image-import-header") { SectionHeader("Load from device", Modifier.padding(top = 8.dp)) }
    item(key = "image-import") { ImportCard(ui.import, ui.onImport, ui.onCancelImport) }
}

private fun LazyListScope.customDownloads(
    downloads: Map<String, ModelDownload>,
    prefix: String,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    if (downloads.isEmpty()) return
    item(key = "$prefix-downloads-header") { SectionHeader("Downloading", Modifier.padding(top = 8.dp)) }
    items(downloads.toList(), key = { "$prefix-download:${it.first}" }) { (id, download) ->
        Card {
            Column(Modifier.padding(16.dp)) {
                Text(download.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("From Hugging Face", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                DownloadState(download, onCancel = { onCancel(id) }, onRetry = null, onDismiss = { onDismiss(id) })
            }
        }
    }
}

// endregion

// region Building blocks

private sealed interface Status {
    data class Ready(val name: String) : Status
    data class Loading(val name: String) : Status
    data class Failed(val message: String) : Status
    data class Idle(val title: String, val body: String) : Status
}

@Composable
private fun StatusCard(icon: ImageVector, label: String, status: Status, ramGb: Double, onUnload: () -> Unit) {
    val accent = when (status) {
        is Status.Ready -> MaterialTheme.colorScheme.tertiary
        is Status.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = MaterialTheme.shapes.large, color = accent.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                when (status) {
                    is Status.Loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent)
                    is Status.Failed -> Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = accent)
                    else -> Icon(icon, contentDescription = null, tint = accent)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
                when (status) {
                    is Status.Ready -> {
                        Text(status.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Loaded · runs on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Status.Loading -> {
                        Text(status.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Status.Failed -> Text(status.message, style = MaterialTheme.typography.bodyMedium)
                    is Status.Idle -> {
                        Text(status.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            status.body + String.format(Locale.US, " This phone has %.0f GB of memory.", ramGb),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (status is Status.Ready) TextButton(onClick = onUnload) { Text("Unload") }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, border: BorderStroke? = null, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = border ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        content = content
    )
}

@Composable
private fun InstalledRow(
    name: String,
    details: String,
    selected: Boolean,
    loaded: Boolean,
    loading: Boolean,
    onUse: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (loaded) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.CheckCircle, contentDescription = "Loaded", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    details + when {
                        loaded -> " · Loaded"
                        loading -> " · Loading…"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                loading -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                loaded -> TextButton(onClick = onUnload) { Text("Unload") }
                else -> FilledTonalButton(onClick = onUse) { Text("Use") }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete $name", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCard(
    name: String,
    description: String,
    recommended: Boolean,
    tags: List<String>,
    minRamGb: Int,
    ramGb: Double,
    download: ModelDownload?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onDetails: (() -> Unit)?
) {
    val tooLittleRam = ramGb + 0.5 < minRamGb
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (recommended) Tag("Recommended", MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { Tag(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                Tag("$minRamGb GB+ RAM", if (tooLittleRam) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tooLittleRam && download == null) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("This phone may not have enough memory to run it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (download != null) {
                DownloadState(download, onCancel = onCancel, onRetry = onDownload, onDismiss = onDismissError)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onDownload, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                    if (onDetails != null) {
                        TextButton(onClick = onDetails) {
                            Text("Details")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadState(download: ModelDownload, onCancel: () -> Unit, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    when (download) {
        is ModelDownload.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (download.waiting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                } else {
                    LinearProgressIndicator(
                        progress = { download.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        drawStopIndicator = {}
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (download.waiting) "Waiting to start…"
                    else "${formatBytes(download.downloadedBytes)} of ${formatBytes(download.totalBytes)} · ${(download.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel download") }
        }
        is ModelDownload.Failed -> Column {
            Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun HubCard(body: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.TravelExplore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Find on Hugging Face", style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionCard(title: String, body: String, buttonLabel: String, icon: ImageVector, onClick: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun ImportCard(import: ModelImport?, onImport: () -> Unit, onCancel: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Import a model folder", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Any Stable Diffusion 1.x or 2.x model exported to ONNX. Pick the folder that contains:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(
                    "text_encoder/model.onnx\nunet/model.onnx\nvae_decoder/model.onnx\ntokenizer/vocab.json, merges.txt",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            if (import != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { import.fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            drawStopIndicator = {}
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Copying ${import.name} · ${formatBytes(import.copiedBytes)} of ${formatBytes(import.totalBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel import") }
                }
            } else {
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose folder")
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

// endregion
