package ai.impact.ui.models

import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Mic
import ai.impact.models.ModelDownload
import ai.impact.models.formatBytes
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The kinds of model the app runs, each with one active model at a time. */
enum class ModelSlotKind(val title: String, val purpose: String, val icon: ImageVector) {
    Chat("Chat", "Writes every reply. Bigger models answer better but load slower.", Icons.Outlined.ChatBubbleOutline),
    Image("Images", "Paints pictures from a description. Uses a lot of memory while loaded.", Icons.Outlined.Image),
    Search("Document search", "Finds the passages in your documents that answer a question.", Icons.Outlined.TravelExplore),
    Listen("Speech to text", "Hears what you say in voice mode.", Icons.Outlined.Mic),
    Speak("Text to speech", "Reads replies aloud in voice mode and when you tap Listen.", Icons.Outlined.RecordVoiceOver)
}

/** A model that can be downloaded from right inside a card. */
class CatalogOption(
    val id: String,
    val name: String,
    val description: String,
    val license: String,
    val sizeBytes: Long,
    val download: ModelDownload?,
    val onDownload: () -> Unit,
    val onCancel: () -> Unit
)

enum class SlotStatus { NotLoaded, Loading, Loaded, Error }

/** A model on this phone that can be made the active one. */
data class ManagedModel(val id: String, val name: String, val sizeBytes: Long, val active: Boolean)

/** Everything the manager shows and does for one kind of model. */
class ModelSlot(
    val kind: ModelSlotKind,
    val status: SlotStatus,
    /** The loaded (or loading) model's name, or the one that loads next. */
    val activeName: String?,
    val error: String? = null,
    val installed: List<ManagedModel>,
    /** About how much memory the loaded model takes; 0 when nothing is loaded. */
    val loadedBytes: Long,
    /** Loads the remembered model; null when there's nothing to load without choosing. */
    val onLoad: (() -> Unit)?,
    val onUse: (ManagedModel) -> Unit,
    val onUnload: () -> Unit,
    /** Something worth knowing, like "Loads by itself when you search". */
    val note: String? = null,
    val moreLabel: String? = null,
    val onMore: () -> Unit = {},
    /** False for the phone's built-in engines, which have nothing to unload. */
    val canUnload: Boolean = true,
    /** For a model that isn't downloaded yet: download it right here. */
    val download: ModelDownload? = null,
    val onDownload: (() -> Unit)? = null,
    val downloadSizeBytes: Long = 0,
    /** Models of this kind that can be downloaded here. */
    val catalog: List<CatalogOption> = emptyList(),
    /** Deletes a downloaded model; null when models of this kind can't be deleted here. */
    val onDelete: ((ManagedModel) -> Unit)? = null
)

/** A switch that may not fit in free memory, waiting for the user to decide. */
private class PendingSwap(val slot: ModelSlot, val model: ManagedModel, val needBytes: Long, val freeBytes: Long)

/**
 * One place to see every model the app uses and load, unload or swap each one, with how much
 * memory they take. Downloading and deleting stay on the Models screen it links to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    slots: List<ModelSlot>,
    deviceRamBytes: Long,
    onUnloadAll: () -> Unit,
    onBack: () -> Unit,
    /** False when it's a tab of the main navigation, which has no back arrow. */
    showBack: Boolean = true
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var freeBytes by remember { mutableLongStateOf(0L) }
    // Free memory changes as models load and unload, so keep it current while the page is open.
    LaunchedEffect(Unit) {
        val am = context.getSystemService(ActivityManager::class.java)
        while (true) {
            freeBytes = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
            delay(1_500)
        }
    }
    var pending by remember { mutableStateOf<PendingSwap?>(null) }
    val usedByModels = slots.sumOf { it.loadedBytes }

    fun use(slot: ModelSlot, model: ManagedModel) {
        // The model being replaced gives its memory back, so it counts as free.
        val available = freeBytes + slot.loadedBytes
        if (model.sizeBytes > available * 0.9 && slots.any { it !== slot && it.loadedBytes > 0 }) {
            pending = PendingSwap(slot, model, model.sizeBytes, available)
        } else {
            slot.onUse(model)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model manager") },
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { MemoryCard(usedByModels, freeBytes, deviceRamBytes, anyLoaded = slots.any { it.status == SlotStatus.Loaded }, onUnloadAll) }
            items(slots, key = { it.kind.name }) { slot -> SlotCard(slot, onUse = { use(slot, it) }) }
        }
    }

    pending?.let { swap ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Free up memory first?") },
            text = {
                Text(
                    "${swap.model.name} needs about ${formatBytes(swap.needBytes)}, but only about " +
                        "${formatBytes(swap.freeBytes)} is free. Unloading the other models gives it the best chance to load."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    slots.filter { it !== swap.slot && it.loadedBytes > 0 }.forEach { it.onUnload() }
                    swap.slot.onUse(swap.model)
                    pending = null
                }) { Text("Unload others and switch") }
            },
            dismissButton = {
                TextButton(onClick = { swap.slot.onUse(swap.model); pending = null }) { Text("Switch anyway") }
            }
        )
    }
}

@Composable
private fun MemoryCard(usedByModels: Long, freeBytes: Long, totalBytes: Long, anyLoaded: Boolean, onUnloadAll: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Memory", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (anyLoaded) OutlinedButton(onClick = onUnloadAll) { Text("Unload all") }
            }
            val fraction = if (totalBytes > 0) (usedByModels.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                buildString {
                    append(if (usedByModels > 0) "Models use about ${formatBytes(usedByModels)}" else "No models loaded")
                    if (freeBytes > 0) append(" · ${formatBytes(freeBytes)} free of ${formatBytes(totalBytes)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SlotCard(slot: ModelSlot, onUse: (ManagedModel) -> Unit) {
    val busy = slot.status == SlotStatus.Loading
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(slot.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(slot.kind.title, style = MaterialTheme.typography.titleMedium)
                    Text(slot.kind.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            StatusLine(slot)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    slot.status == SlotStatus.Loaded && slot.canUnload -> OutlinedButton(onClick = slot.onUnload) { Text("Unload") }
                    !busy && slot.onLoad != null -> Button(onClick = slot.onLoad) { Text("Load") }
                }
            }

            if (slot.installed.isEmpty()) {
                NotDownloaded(slot)
            } else {
                Text(
                    if (slot.installed.size > 1) "On this phone · tap Use to switch" else "On this phone",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                slot.installed.forEach { model ->
                    InstalledModelRow(
                        model, enabled = !busy, onUse = { onUse(model) },
                        onDelete = slot.onDelete?.takeIf { model.sizeBytes > 0 && !model.active }?.let { delete -> { delete(model) } }
                    )
                }
            }
            if (slot.catalog.isNotEmpty()) {
                Text("Download", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                slot.catalog.forEach { CatalogRow(it) }
            }

            slot.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            slot.moreLabel?.let { TextButton(onClick = slot.onMore) { Text(it) } }
        }
    }
}

@Composable
private fun StatusLine(slot: ModelSlot) {
    val (color, text) = when (slot.status) {
        SlotStatus.Loaded -> MaterialTheme.colorScheme.tertiary to
            if (slot.loadedBytes > 0) "Loaded · ${slot.activeName ?: ""} · about ${formatBytes(slot.loadedBytes)}"
            else "Ready · ${slot.activeName ?: ""}"
        SlotStatus.Loading -> MaterialTheme.colorScheme.primary to "Loading ${slot.activeName ?: "model"}…"
        SlotStatus.Error -> MaterialTheme.colorScheme.error to (slot.error ?: "Couldn't load the model.")
        SlotStatus.NotLoaded -> MaterialTheme.colorScheme.outline to
            (slot.activeName?.let { "Not loaded · $it is ready to load" } ?: "Not loaded")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (slot.status == SlotStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InstalledModelRow(model: ManagedModel, enabled: Boolean, onUse: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (model.active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (model.active) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (model.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (model.sizeBytes > 0) formatBytes(model.sizeBytes) else "Built into your phone · no download",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (model.active) {
            Text("In use", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete ${model.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onUse, enabled = enabled) { Text("Use") }
        }
    }
}

@Composable
private fun NotDownloaded(slot: ModelSlot) {
    when (val download = slot.download) {
        is ModelDownload.Running -> {
            LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                if (download.waiting) "Waiting for a connection…"
                else "Downloading · ${formatBytes(download.downloadedBytes)} of ${formatBytes(download.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is ModelDownload.Failed -> {
            Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            slot.onDownload?.let { Button(onClick = it) { Text("Try again") } }
        }
        null -> {
            Text("Nothing downloaded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            slot.onDownload?.let { Button(onClick = it) { Text("Download ${formatBytes(slot.downloadSizeBytes)}") } }
        }
    }
}

@Composable
private fun CatalogRow(option: CatalogOption) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(option.name, style = MaterialTheme.typography.bodyMedium)
                Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            when (val d = option.download) {
                is ModelDownload.Running -> TextButton(onClick = option.onCancel) { Text("Cancel") }
                else -> Button(onClick = option.onDownload) { Text(if (d is ModelDownload.Failed) "Retry" else formatBytes(option.sizeBytes)) }
            }
        }
        // The license matters: some voices may only be used non-commercially.
        Text(
            "License: ${option.license}",
            style = MaterialTheme.typography.labelSmall,
            color = if ("non-commercial" in option.license) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (val d = option.download) {
            is ModelDownload.Running -> {
                LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (d.waiting) "Waiting for a connection…" else "${formatBytes(d.downloadedBytes)} of ${formatBytes(d.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is ModelDownload.Failed -> Text(d.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
    }
}

