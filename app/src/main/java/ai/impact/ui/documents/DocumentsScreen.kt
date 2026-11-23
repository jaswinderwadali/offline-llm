package ai.impact.ui.documents

import ai.impact.data.documents.DocumentEntity
import ai.impact.data.documents.DocumentKind
import ai.impact.data.documents.DocumentStatus
import ai.impact.data.documents.SearchHit
import ai.impact.models.ModelDownload
import ai.impact.models.formatBytes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The user's document library: get the search model, add files, see them index, and try a
 * search to see what chat would find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    documents: List<DocumentEntity>,
    progress: Map<Long, Float>,
    modelInstalled: Boolean,
    modelDownload: ModelDownload?,
    modelSizeBytes: Long,
    messages: Flow<String>,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onAdd: () -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSearch: suspend (String) -> List<SearchHit>,
    onBack: () -> Unit,
    /** False when it's a tab of the main navigation, which has no back arrow. */
    showBack: Boolean = true
) {
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (modelInstalled) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add document") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Add PDFs, Word files, Markdown or text. When you chat, TinyAI looks up the passages that " +
                        "match your question and shows which document and page they came from.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Read and searched on this phone. Your files are never uploaded.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (!modelInstalled) {
                item { SearchModelCard(modelDownload, modelSizeBytes, onDownloadModel, onCancelDownload) }
            }
            if (documents.isEmpty() && modelInstalled) {
                item { EmptyLibrary() }
            }
            items(documents, key = { it.id }) { doc ->
                DocumentRow(doc, progress[doc.id], onRetry = { onRetry(doc.id) }, onDelete = { pendingDelete = doc })
            }
            if (modelInstalled && documents.any { it.status == DocumentStatus.READY }) {
                item { TrySearch(onSearch) }
            }
        }
    }

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${doc.name}?") },
            text = { Text("Chat won't search it any more. The original file on your phone isn't touched.") },
            confirmButton = {
                TextButton(onClick = { onDelete(doc.id); pendingDelete = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SearchModelCard(download: ModelDownload?, sizeBytes: Long, onDownload: () -> Unit, onCancel: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Get the search model", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Searching documents needs a small model (${formatBytes(sizeBytes)}) that understands what passages mean. " +
                    "It's downloaded once and runs offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            when (download) {
                is ModelDownload.Running -> {
                    LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (download.waiting) "Waiting for a connection…"
                            else "${formatBytes(download.downloadedBytes)} of ${formatBytes(download.totalBytes)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                is ModelDownload.Failed -> {
                    Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDownload) { Text("Try again") }
                }
                null -> Button(onClick = onDownload) { Text("Download ${formatBytes(sizeBytes)}") }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("No documents yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add notes, manuals, papers or reports, then ask about them in chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DocumentRow(doc: DocumentEntity, progress: Float?, onRetry: () -> Unit, onDelete: () -> Unit) {
    val kind = DocumentKind.of(doc.name, doc.mimeType)
    val icon: ImageVector = when (kind) {
        DocumentKind.Pdf -> Icons.Outlined.PictureAsPdf
        DocumentKind.Word -> Icons.Outlined.Article
        else -> Icons.Outlined.Description
    }
    val failed = doc.status == DocumentStatus.FAILED
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (failed) Icons.Outlined.ErrorOutline else icon, contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle(doc, kind, progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (failed) {
                    IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, contentDescription = "Try again") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Remove") }
            }
            if (doc.status == DocumentStatus.INDEXING) {
                Spacer(Modifier.height(8.dp))
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
                }
            }
        }
    }
}

private fun subtitle(doc: DocumentEntity, kind: DocumentKind?, progress: Float?): String = when (doc.status) {
    DocumentStatus.FAILED -> doc.error ?: "This file couldn't be read."
    DocumentStatus.INDEXING -> when {
        progress == null -> "Waiting to be read…"
        progress <= 0f -> "Reading…"
        else -> "Indexing… ${(progress * 100).toInt()}%"
    }
    else -> listOfNotNull(
        kind?.label,
        doc.pageCount?.let { "$it page${if (it == 1) "" else "s"}" },
        "${doc.chunkCount} passage${if (doc.chunkCount == 1) "" else "s"}"
    ).joinToString(" · ")
}

/** Shows what chat would find for a question, so the user can see the library working. */
@Composable
private fun TrySearch(onSearch: suspend (String) -> List<SearchHit>) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run() {
        if (query.isBlank() || searching) return
        searching = true
        scope.launch {
            results = try { onSearch(query) } finally { searching = false }
        }
    }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Try a search", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask something your documents answer") },
            singleLine = true,
            trailingIcon = { IconButton(onClick = ::run, enabled = !searching) { Icon(Icons.Outlined.Search, contentDescription = "Search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() })
        )
        results?.let { hits ->
            if (hits.isEmpty()) {
                Text(
                    "Nothing close enough. Chat would answer this from general knowledge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            hits.forEach { hit ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(
                                hit.source.label, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text("${(hit.score * 100).toInt()}% match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        hit.heading?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                        Text(hit.text, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
