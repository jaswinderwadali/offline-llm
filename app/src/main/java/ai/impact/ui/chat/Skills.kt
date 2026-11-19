package ai.impact.ui.chat

import ai.impact.ChatMessage
import ai.impact.data.Persona
import ai.impact.data.skills.BuiltInSkills
import ai.impact.data.skills.QrCodes
import ai.impact.data.skills.ScheduledNotification
import ai.impact.data.skills.ShareTarget
import ai.impact.data.skills.Skill
import ai.impact.data.skills.SkillAction
import ai.impact.ui.components.EmojiAvatar
import ai.impact.ui.components.PersonaAvatar
import ai.impact.ui.components.brush
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

fun Skill.brush(): Brush = Brush.linearGradient(listOf(Color(colorStart), Color(colorEnd)))

@Composable
fun SkillAvatar(skill: Skill, size: androidx.compose.ui.unit.Dp = 40.dp, modifier: Modifier = Modifier) {
    EmojiAvatar(skill.emoji, skill.brush(), size, modifier)
}

/** Which half of the input field's sheet is showing. */
enum class ComposerSheetTab { Personality, Skills }

/**
 * Opened from inside the input field: switch who you're talking to, or pick and manage the
 * skills that can handle your next message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalitySkillsSheet(
    initialTab: ComposerSheetTab,
    personas: List<Persona>,
    selectedPersonaId: String,
    enabledSkillIds: Set<String>,
    activeSkillId: String?,
    skillsAvailable: Boolean,
    autoSkills: Boolean,
    onAutoSkillsChange: (Boolean) -> Unit,
    onSelectPersona: (String) -> Unit,
    onBrowsePersonas: () -> Unit,
    onCreatePersona: () -> Unit,
    onUseSkill: (Skill) -> Unit,
    onToggleSkill: (Skill, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        PrimaryTabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            ComposerSheetTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = { Text(entry.name) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            when (tab) {
                ComposerSheetTab.Personality -> PersonalityTab(personas, selectedPersonaId, onSelectPersona, onBrowsePersonas, onCreatePersona)
                ComposerSheetTab.Skills -> SkillsTab(
                    enabledSkillIds, activeSkillId, skillsAvailable, autoSkills, onAutoSkillsChange, onUseSkill, onToggleSkill
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonalityTab(
    personas: List<Persona>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onBrowseAll: () -> Unit,
    onCreate: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Who do you want to talk to?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onBrowseAll) { Text("Manage") }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 4
    ) {
        PersonaTile(
            label = "Create",
            selected = false,
            onClick = onCreate,
            avatar = {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        )
        personas.forEach { persona ->
            val selected = persona.id == selectedId
            PersonaTile(
                label = persona.name,
                selected = selected,
                onClick = { onSelect(persona.id) },
                avatar = {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        PersonaAvatar(persona, size = 60.dp)
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
                }
            )
        }
        // Keeps the last row's tiles aligned to the grid instead of spreading out.
        repeat((4 - (personas.size + 1) % 4) % 4) { Spacer(Modifier.width(76.dp)) }
    }
}

@Composable
private fun PersonaTile(label: String, selected: Boolean, onClick: () -> Unit, avatar: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        avatar()
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SkillsTab(
    enabledIds: Set<String>,
    activeSkillId: String?,
    available: Boolean,
    autoSkills: Boolean,
    onAutoSkillsChange: (Boolean) -> Unit,
    onUse: (Skill) -> Unit,
    onToggle: (Skill, Boolean) -> Unit
) {
    // The headline setting: skills that are on get used without being picked.
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (autoSkills) 0.10f else 0.05f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        Row(
            Modifier
                .clickable(role = Role.Switch) { onAutoSkillsChange(!autoSkills) }
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Use skills automatically", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (autoSkills) "Just ask: \"make a QR code for…\", \"remind me at 6pm to…\", \"write a tweet about…\". Skills that are on are used when your message clearly asks for one."
                    else "Skills run only when you pick one with ⚡ or type /.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = autoSkills, onCheckedChange = onAutoSkillsChange)
        }
    }
    Text(
        if (available) "Or tap a skill to use it for your next message. Switch a skill off and it's never used on its own."
        else "Skills work in Chat mode. Switch back to Chat to use them.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)
    )
    val (on, off) = BuiltInSkills.all.partition { it.id in enabledIds }
    if (on.isNotEmpty()) {
        SkillSectionHeader("Your skills")
        on.forEach { SkillRow(it, true, it.id == activeSkillId, available, onUse, onToggle) }
    }
    if (off.isNotEmpty()) {
        SkillSectionHeader(if (on.isEmpty()) "Skills" else "More skills")
        off.forEach { SkillRow(it, false, it.id == activeSkillId, available, onUse, onToggle) }
    }
}

@Composable
private fun SkillSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SkillRow(
    skill: Skill,
    enabled: Boolean,
    active: Boolean,
    available: Boolean,
    onUse: (Skill) -> Unit,
    onToggle: (Skill, Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = available) { onUse(skill) }
            .background(if (active) Color(skill.colorStart).copy(alpha = 0.10f) else Color.Transparent)
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkillAvatar(skill, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val badge = when {
                    active -> "In use"
                    skill.share != null -> "Opens ${skill.share.label}"
                    skill.action == SkillAction.Image -> "Uses image model"
                    skill.usesInternet -> "Online"
                    !skill.needsModel -> "No model needed"
                    else -> null
                }
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) Color(skill.colorStart) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = enabled, onCheckedChange = { onToggle(skill, it) })
    }
}

/** The skill the next message goes to, shown inside the input field. */
@Composable
internal fun ActiveSkillChip(skill: Skill, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(skill.colorStart).copy(alpha = 0.16f))
            .padding(start = 4.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkillAvatar(skill, size = 22.dp)
        Spacer(Modifier.width(6.dp))
        Text(skill.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Box(
            Modifier
                .padding(start = 2.dp)
                .size(26.dp)
                .clip(CircleShape)
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Stop using ${skill.name}", modifier = Modifier.size(16.dp))
        }
    }
}

/** Shown above the input while the message starts with "/": pick a skill by name. */
@Composable
internal fun SkillSuggestions(skills: List<Skill>, onPick: (Skill) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            skills.forEach { skill ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(skill) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkillAvatar(skill, size = 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Skills matching what's typed after "/", enabled ones first. */
internal fun matchingSkills(query: String, enabledIds: Set<String>): List<Skill> {
    val q = query.trim().lowercase()
    return BuiltInSkills.all
        .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.id.contains(q) }
        .sortedBy { if (it.id in enabledIds) 0 else 1 }
        .take(6)
}

/**
 * Small label above a user message saying which skill it went to. An [auto] pick says so, and
 * offers [onReplyAsChat] in case the guess was wrong.
 */
@Composable
internal fun SkillLabel(
    skill: Skill,
    modifier: Modifier = Modifier,
    auto: Boolean = false,
    onReplyAsChat: (() -> Unit)? = null
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (auto) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                tint = Color(skill.colorStart),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(skill.emoji, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(4.dp))
        Text(
            if (auto) "Used ${skill.name}" else skill.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onReplyAsChat != null) {
            Text(
                " · ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Reply as chat instead",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onReplyAsChat)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }
    }
}

/** A reply produced by a tool skill: a QR code, a calendar reminder or a scheduled notification. */
@Composable
internal fun SkillReplyCard(
    message: ChatMessage,
    skill: Skill,
    persona: Persona,
    scheduled: List<ScheduledNotification>,
    onCancelNotification: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PersonaAvatar(persona, size = 28.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            when (skill.action) {
                SkillAction.QrCode -> QrCodeCard(message.text)
                SkillAction.Reminder -> ReminderCard(message, skill)
                else -> NotificationCard(message, skill, scheduled, onCancelNotification)
            }
        }
    }
}

@Composable
private fun QrCodeCard(content: String) {
    val context = LocalContext.current
    val bitmap = remember(content) { runCatching { QrCodes.render(content, 512).asImageBitmap() }.getOrNull() }
    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap == null) {
            Text("Couldn't make a QR code from this text.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Image(
            bitmap = bitmap,
            contentDescription = "QR code for $content",
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .size(216.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(10.dp))
        Text(
            content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardAction("Share", Icons.Default.Share) {
                try {
                    context.startActivity(QrCodes.shareIntent(context, content))
                } catch (e: Exception) {
                    Toast.makeText(context, "Couldn't share the QR code", Toast.LENGTH_SHORT).show()
                }
            }
            CardAction("Copy text", Icons.Default.ContentCopy) { context.copyText(content, "Copied") }
        }
    }
}

@Composable
private fun ReminderCard(message: ChatMessage, skill: Skill) {
    val context = LocalContext.current
    val at = message.skillArg?.toLongOrNull() ?: return
    Column(Modifier.padding(16.dp)) {
        CardHeader(skill, "Reminder")
        Spacer(Modifier.height(10.dp))
        Text(message.text, style = MaterialTheme.typography.titleMedium)
        Text(formatWhen(context, at), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            "Save it in your calendar app to get the alert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        CardAction("Add to calendar", Icons.Outlined.CalendarMonth) { context.openCalendar(message.text, at) }
    }
}

@Composable
private fun NotificationCard(
    message: ChatMessage,
    skill: Skill,
    scheduled: List<ScheduledNotification>,
    onCancel: (Int) -> Unit
) {
    val context = LocalContext.current
    val parts = message.skillArg?.split(';') ?: return
    val at = parts.getOrNull(0)?.toLongOrNull() ?: return
    val id = parts.getOrNull(1)?.toIntOrNull()
    val pending = id != null && scheduled.any { it.id == id }
    val status = when {
        pending -> "Scheduled"
        at <= System.currentTimeMillis() -> "Delivered"
        else -> "Cancelled"
    }
    Column(Modifier.padding(16.dp)) {
        CardHeader(skill, "Notification · $status")
        Spacer(Modifier.height(10.dp))
        Text(message.text, style = MaterialTheme.typography.titleMedium)
        Text(formatWhen(context, at), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pending) {
            val reminders = (context.applicationContext as ai.impact.ImpactApp).reminders
            if (!reminders.notificationsEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Notifications are off for TinyAI, so this won't show.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                CardAction("Turn on", Icons.AutoMirrored.Filled.OpenInNew) { context.openNotificationSettings() }
            } else {
                Spacer(Modifier.height(4.dp))
                CardAction("Cancel", Icons.Default.Close) { onCancel(id!!) }
            }
        }
    }
}

@Composable
private fun CardHeader(skill: Skill, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SkillAvatar(skill, size = 26.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CardAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/**
 * Actions under a finished reply from a skill: send a drafted post to its app, or open the
 * Wikipedia article an answer came from.
 */
@Composable
internal fun SkillReplyActions(message: ChatMessage, skill: Skill, answer: String) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        skill.share?.let { target ->
            // The main action, so it's a filled button in the app's own color.
            androidx.compose.material3.Button(
                onClick = { context.sendToApp(target, answer, message.skillArg) },
                shape = RoundedCornerShape(50),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(skill.colorStart),
                    contentColor = Color.White
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (message.skillArg != null && target == ShareTarget.WhatsApp) "Send on WhatsApp" else "Send to ${target.label}")
            }
            AssistChip(
                onClick = { context.copyText(answer, "Copied") },
                label = { Text("Copy") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(50)
            )
        }
        if (skill.action == SkillAction.Wikipedia) {
            val (title, url) = message.skillArg?.split('\n')?.let { it.getOrNull(0) to it.getOrNull(1) } ?: return@Row
            if (title == null || url == null) return@Row
            AssistChip(
                onClick = { context.openUrl(url) },
                label = { Text("Wikipedia · $title", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Text(skill.emoji) },
                trailingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp)) },
                shape = RoundedCornerShape(50),
                modifier = Modifier.widthIn(max = 280.dp)
            )
        }
    }
}

/** "Today, 6:00 PM", "Tomorrow, 9:00 AM" or "Mon, Nov 24, 9:00 AM", in the phone's 12/24-hour style. */
internal fun formatWhen(context: Context, at: Long): String {
    val time = DateFormat.getTimeFormat(context).format(at)
    fun startOfDay(millis: Long) = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 12) // noon, so a daylight-saving shift can't change the day count
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = Math.round((startOfDay(at) - startOfDay(System.currentTimeMillis())) / 86_400_000.0).toInt()
    val day = when (days) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> DateUtils.formatDateTime(
            context, at,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        )
    }
    return "$day, $time"
}

internal fun Context.openCalendar(title: String, at: Long) {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, at + 30 * 60_000L)
        .putExtra(CalendarContract.Events.HAS_ALARM, true)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No calendar app found to save the reminder", Toast.LENGTH_LONG).show()
    }
}

private fun Context.copyText(text: String, toast: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TinyAI", text))
    // Android 13+ shows its own confirmation when something is copied.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.openNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Open Settings to turn on notifications", Toast.LENGTH_LONG).show()
    }
}

