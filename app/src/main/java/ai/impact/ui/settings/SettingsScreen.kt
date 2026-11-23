package ai.impact.ui.settings

import ai.impact.data.ReplyLength
import ai.impact.data.ThemeMode
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

// Each kind of setting has its own colour, so the list is easy to scan.
private val MemoryTint = Color(0xFF7C5CFF)
private val CheckInTint = Color(0xFFFF9F45)
private val PrivacyTint = Color(0xFF34C759)
private val SafetyTint = Color(0xFF4F9DFF)
private val AboutTint = Color(0xFF8A9BBF)

/**
 * Settings: only what isn't already a tab. Personalities and chats live on Home, models on
 * Models, and files on Documents.
 */
@Composable
fun SettingsScreen(
    userName: String,
    wordLimit: Int,
    themeMode: ThemeMode,
    appVersion: String,
    memoryCount: Int,
    useMemory: Boolean,
    conversationCount: Int,
    checkInsEnabled: Boolean,
    onUserNameChange: (String) -> Unit,
    onReplyLengthChange: (ReplyLength) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenMemory: () -> Unit,
    onCheckInsChange: (Boolean) -> Unit,
    onDeleteAllChats: () -> Unit,
    onResetApp: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var editingName by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var confirmDeleteChats by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        // Padded as a whole, so scrolled content never slides under the status bar.
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 16.dp)
            )
        }
        item { ProfileCard(userName, onClick = { editingName = true }) }

        item { Section("Conversations") }
        item {
            Group {
                ReplyLengthPicker(ReplyLength.nearest(wordLimit), onReplyLengthChange)
                RowDivider()
                SettingRow(
                    Icons.Outlined.Psychology, MemoryTint, "Memory",
                    when {
                        !useMemory -> "Off · companions don't use what they know about you"
                        memoryCount == 0 -> "What your companions learn about you"
                        else -> "$memoryCount thing${if (memoryCount == 1) "" else "s"} remembered about you"
                    },
                    onClick = onOpenMemory
                )
                RowDivider()
                CheckInsRow(checkInsEnabled, onCheckInsChange)
            }
        }

        item { Section("Appearance") }
        item { Group { ThemePicker(themeMode, onThemeModeChange) } }

        item { Section("Privacy & safety") }
        item {
            Group {
                SettingRow(Icons.Outlined.Lock, PrivacyTint, "Private by design", "Chats, memory and documents never leave this phone. No account, no tracking.")
                RowDivider()
                SettingRow(Icons.Outlined.Shield, SafetyTint, "Safety & disclaimer", "TinyAI is an AI, not a professional", onClick = { showSafety = true })
                RowDivider()
                SettingRow(
                    Icons.Outlined.Forum, MaterialTheme.colorScheme.error, "Delete all chats",
                    if (conversationCount == 0) "No saved chats" else "$conversationCount saved chat${if (conversationCount == 1) "" else "s"}",
                    onClick = if (conversationCount > 0) ({ confirmDeleteChats = true }) else null,
                    destructive = conversationCount > 0
                )
            }
        }

        item { Section("About") }
        item {
            Group {
                SettingRow(Icons.Outlined.Info, AboutTint, "Version", appVersion)
                RowDivider()
                SettingRow(
                    Icons.Outlined.DeleteSweep, MaterialTheme.colorScheme.error, "Reset app",
                    "Erase your name, personalities, chats, memory and images",
                    onClick = { confirmReset = true }, destructive = true
                )
            }
        }
        item {
            Text(
                "TinyAI runs entirely on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
            )
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

    if (showSafety) {
        AlertDialog(
            onDismissRequest = { showSafety = false },
            title = { Text("Safety & disclaimer") },
            text = {
                Text(
                    "TinyAI's personalities are AI role-play designed for support, motivation and conversation. " +
                        "They are not doctors, therapists, lawyers or financial advisors, and they can be wrong.\n\n" +
                        "If you are in crisis or thinking about harming yourself, contact your local emergency number or a crisis helpline immediately."
                )
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text("Got it") } }
        )
    }

    if (confirmDeleteChats) {
        AlertDialog(
            onDismissRequest = { confirmDeleteChats = false },
            title = { Text("Delete all chats?") },
            text = { Text("Every saved conversation is removed from this phone. Memory, personalities and documents stay.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteChats = false; onDeleteAllChats() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteChats = false }) { Text("Cancel") } }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset TinyAI?") },
            text = { Text("This removes your name, custom personalities, settings, chat history, memory and documents. Downloaded model files stay on your device.") },
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
private fun ProfileCard(userName: String, onClick: () -> Unit) {
    val name = userName.trim()
    val accent = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Text(
                    name.firstOrNull()?.uppercase() ?: "🙂",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name.ifEmpty { "Add your name" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Companions use it to talk to you",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Change your name", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** A rounded, tinted square behind a setting's icon. */
@Composable
private fun IconTile(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, tint)
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
        when {
            trailing != null -> { Spacer(Modifier.width(12.dp)); trailing() }
            onClick != null && !destructive -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CheckInsRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Re-checked on return from system settings, where the user may have allowed notifications.
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    SettingRow(
        Icons.Outlined.NotificationsActive, CheckInTint, "Check-ins",
        when {
            !enabled -> "Off"
            !allowed -> "Blocked in your phone's settings"
            else -> "A gentle nudge about what you were exploring"
        },
        onClick = { onChange(!enabled) },
        trailing = { Switch(checked = enabled, onCheckedChange = onChange) }
    )
}

/** System, light or dark, as three tiles that show what they pick. */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            val active = mode == selected
            val accent = MaterialTheme.colorScheme.primary
            val background by animateColorAsState(if (active) accent.copy(alpha = 0.12f) else Color.Transparent, label = "themeTile")
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(background)
                    .border(if (active) 2.dp else 1.dp, if (active) accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .selectable(selected = active, onClick = { onSelect(mode) }, role = Role.RadioButton)
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    when (mode) {
                        ThemeMode.System -> Icons.Outlined.BrightnessAuto
                        ThemeMode.Light -> Icons.Outlined.LightMode
                        ThemeMode.Dark -> Icons.Outlined.DarkMode
                    },
                    contentDescription = null,
                    tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Five named lengths instead of a free slider: each tile previews its length as lines of text,
 * and a tap saves immediately.
 */
@Composable
private fun ReplyLengthPicker(selected: ReplyLength, onSelect: (ReplyLength) -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.AutoMirrored.Outlined.ShortText, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text("Reply length", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("Up to ${selected.words} words", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.selectableGroup()) {
            ReplyLength.entries.forEach { length ->
                ReplyLengthTile(length, selected = length == selected, onClick = { onSelect(length) }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${selected.description} Takes effect from your next message.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReplyLengthTile(length: ReplyLength, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.outlineVariant, label = "tileBorder")
    val background by animateColorAsState(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent, label = "tileBackground")
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(14.dp))
            .background(background)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A tiny paragraph whose line count grows with the length.
        val lines = length.ordinal + 1
        Column(Modifier.height(30.dp), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
            repeat(lines) { line ->
                val last = line == lines - 1
                Box(
                    Modifier
                        .width(if (last && lines > 1) 16.dp else 26.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(length.label, style = MaterialTheme.typography.labelMedium, color = if (selected) accent else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${length.words}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
