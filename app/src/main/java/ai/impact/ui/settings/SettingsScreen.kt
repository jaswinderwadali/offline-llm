package ai.impact.ui.settings

import ai.impact.ModelState
import ai.impact.data.Persona
import ai.impact.data.ThemeMode
import ai.impact.ui.components.PersonaAvatar
import ai.impact.ui.components.SectionHeader
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    persona: Persona,
    userName: String,
    wordLimit: Int,
    themeMode: ThemeMode,
    modelState: ModelState,
    appVersion: String,
    onBack: () -> Unit,
    onUserNameChange: (String) -> Unit,
    onWordLimitChange: (Int) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenPersonas: () -> Unit,
    onCreatePersona: () -> Unit,
    onPickModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onOpenModelCenter: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMemory: () -> Unit,
    conversationCount: Int,
    memoryCount: Int,
    onResetApp: () -> Unit
) {
    BackHandler(onBack = onBack)
    var editingName by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var sliderValue by remember(wordLimit) { mutableFloatStateOf(wordLimit.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { SectionHeader("Personality") }
            item {
                SettingsGroup {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PersonaAvatar(persona, size = 52.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Current personality", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(persona.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                persona.tagline,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Divider()
                    SettingsRow(Icons.Default.Person, "Switch personality", "Choose from all personalities", onClick = onOpenPersonas)
                    Divider()
                    SettingsRow(Icons.Default.AddCircleOutline, "Create custom personality", "Design your own companion", onClick = onCreatePersona)
                }
            }

            item { SectionHeader("Your data", Modifier.padding(top = 12.dp)) }
            item {
                SettingsGroup {
                    SettingsRow(
                        Icons.Default.History,
                        "Chat history",
                        if (conversationCount == 0) "No saved conversations" else "$conversationCount saved conversation${if (conversationCount == 1) "" else "s"}",
                        onClick = onOpenHistory
                    )
                    Divider()
                    SettingsRow(
                        Icons.Default.AutoAwesome,
                        "Memory",
                        if (memoryCount == 0) "What your companions know about you" else "$memoryCount thing${if (memoryCount == 1) "" else "s"} remembered about you",
                        onClick = onOpenMemory
                    )
                }
            }

            item { SectionHeader("Profile", Modifier.padding(top = 12.dp)) }
            item {
                SettingsGroup {
                    SettingsRow(
                        Icons.Default.Person,
                        "Your name",
                        userName.ifBlank { "Not set" },
                        onClick = { editingName = true }
                    )
                }
            }

            item { SectionHeader("Responses", Modifier.padding(top = 12.dp)) }
            item {
                SettingsGroup {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reply length", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(
                                "${sliderValue.toInt()} words",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = { onWordLimitChange(sliderValue.toInt()) },
                            valueRange = 50f..500f,
                            steps = 8
                        )
                        Text(
                            "Shorter replies are faster on most phones.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { SectionHeader("AI model", Modifier.padding(top = 12.dp)) }
            item {
                SettingsGroup {
                    val (modelTitle, modelSubtitle) = when (modelState) {
                        is ModelState.Ready -> modelState.name to "Loaded and running on-device"
                        is ModelState.Loading -> modelState.name to "Loading…"
                        is ModelState.Error -> "Model error" to modelState.message
                        ModelState.NotLoaded -> "No model loaded" to "Choose a .gguf file to start chatting"
                    }
                    SettingsRow(Icons.Default.Memory, modelTitle, modelSubtitle, showChevron = false)
                    Divider()
                    SettingsRow(Icons.Default.FolderOpen, "Choose model file", "Load a .gguf model from your device", onClick = onPickModel)
                    Divider()
                    SettingsRow(Icons.Default.CloudDownload, "Model center", "Download recommended models", onClick = onOpenModelCenter)
                    if (modelState is ModelState.Ready) {
                        Divider()
                        SettingsRow(Icons.Default.Eject, "Unload model", "Free up memory", onClick = onUnloadModel)
                    }
                }
            }

            item { SectionHeader("Appearance", Modifier.padding(top = 12.dp)) }
            item {
                SettingsGroup {
                    SingleChoiceSegmentedButtonRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                            ) { Text(mode.label) }
                        }
                    }
                }
            }

            item { SectionHeader("About", Modifier.padding(top = 12.dp)) }
            item {
                SettingsGroup {
                    SettingsRow(Icons.Default.Lock, "Privacy", "Chats never leave your device. No account, no tracking.", showChevron = false)
                    Divider()
                    SettingsRow(Icons.Default.Shield, "Safety & disclaimer", "TinyAI is an AI, not a professional", onClick = { showAbout = true })
                    Divider()
                    SettingsRow(Icons.Default.Info, "Version", appVersion, showChevron = false)
                    Divider()
                    SettingsRow(Icons.Default.DeleteSweep, "Reset app", "Delete settings, personalities, history and memory", onClick = { confirmReset = true }, destructive = true)
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (editingName) {
        var draft by remember { mutableStateOf(userName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your name") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(30) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Your first name") }
                )
            },
            confirmButton = { TextButton(onClick = { onUserNameChange(draft); editingName = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("Safety & disclaimer") },
            text = {
                Text(
                    "TinyAI's personalities are AI role-play designed for support, motivation and conversation. " +
                        "They are not doctors, therapists, lawyers or financial advisors, and they can be wrong.\n\n" +
                        "If you are in crisis or thinking about harming yourself, contact your local emergency number or a crisis helpline immediately."
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Got it") } }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset TinyAI?") },
            text = { Text("This removes your name, custom personalities, settings, chat history and memory. Downloaded model files stay on your device.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onResetApp() }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column { content() }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    destructive: Boolean = false
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) { content() }
    } else {
        content()
    }
}
