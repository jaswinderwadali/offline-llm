package ai.impact.ui.home

import ai.impact.ModelState
import ai.impact.data.Persona
import ai.impact.data.history.ConversationSummary
import ai.impact.ui.components.PersonaAvatar
import ai.impact.ui.components.brush
import ai.impact.ui.history.ChatCard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/**
 * The first tab: personalities as a stories rail, most recently used first, and recent
 * conversations as cards.
 */
@Composable
fun HomeScreen(
    userName: String,
    personas: List<Persona>,
    selectedPersonaId: String,
    conversations: List<ConversationSummary>,
    modelState: ModelState,
    documentCount: Int,
    onOpenPersona: (Persona) -> Unit,
    onCreatePersona: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onRenameConversation: (Long, String) -> Unit,
    onSeeAllHistory: () -> Unit,
    onOpenModels: () -> Unit,
    onCreateImage: () -> Unit,
    onOpenDocuments: () -> Unit
) {
    val byId = remember(personas) { personas.associateBy { it.id } }
    val selected = byId[selectedPersonaId] ?: personas.first()
    val orderedPersonas = remember(personas, conversations) { byLastUse(personas, conversations) }
    // A ring like an unseen story: someone you've talked to in the last day.
    val recentlyActive = remember(conversations) {
        val since = System.currentTimeMillis() - 24 * 60 * 60_000L
        conversations.filter { it.updatedAt >= since }.map { it.personaId }.toSet()
    }

    LazyColumn(
        // Padded as a whole, so scrolled content never slides under the status bar.
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { Header(userName, modelState, onOpenModels) }
        item {
            StoriesRail(
                personas = orderedPersonas,
                selectedId = selected.id,
                active = recentlyActive,
                onOpen = onOpenPersona,
                onCreate = onCreatePersona
            )
        }
        item {
            // About 20dp from the circles above and from "Recent chats" below.
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAction(Icons.Outlined.Palette, "Create an image", "On this phone", Modifier.weight(1f), onCreateImage)
                QuickAction(
                    Icons.Outlined.Description, "Your documents",
                    if (documentCount == 0) "Add files to ask about" else "$documentCount ready to search",
                    Modifier.weight(1f), onOpenDocuments
                )
            }
        }
        item {
            Row(
                // The See all button is taller than the title, which already adds space above it.
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (conversations.isNotEmpty()) TextButton(onClick = onSeeAllHistory) { Text("See all") }
            }
        }
        if (conversations.isEmpty()) {
            item { EmptyChats() }
        } else {
            items(conversations.take(10), key = { it.id }) { conversation ->
                ChatCard(
                    conversation = conversation,
                    persona = byId[conversation.personaId],
                    onOpen = { onOpenConversation(conversation.id) },
                    onRename = { onRenameConversation(conversation.id, it) },
                    onDelete = { onDeleteConversation(conversation.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp).animateItem()
                )
            }
        }
    }
}

@Composable
private fun Header(userName: String, modelState: ModelState, onOpenModels: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (userName.isNotBlank()) "$greeting, ${userName.trim()}" else greeting,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text("Who would you like to talk to?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ModelPill(modelState, onOpenModels)
    }
}

@Composable
private fun ModelPill(state: ModelState, onClick: () -> Unit) {
    val (color, label) = when (state) {
        is ModelState.Ready -> MaterialTheme.colorScheme.tertiary to "Ready"
        is ModelState.Loading -> MaterialTheme.colorScheme.primary to "Loading…"
        is ModelState.Error -> MaterialTheme.colorScheme.error to "Model error"
        ModelState.NotLoaded -> MaterialTheme.colorScheme.outline to "No model"
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun StoriesRail(
    personas: List<Persona>,
    selectedId: String,
    active: Set<String>,
    onOpen: (Persona) -> Unit,
    onCreate: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(personas, key = { it.id }) { persona ->
            Story(persona, selected = persona.id == selectedId, ringed = persona.id in active, onClick = { onOpen(persona) })
        }
        // Making a new one comes after everyone you already talk to.
        item(key = "create") { CreateStory(onCreate) }
    }
}

private val StorySize = 74.dp
/** Wider than the circle, so a name gets room on either side of it. */
private val StoryWidth = 86.dp

@Composable
private fun Story(persona: Persona, selected: Boolean, ringed: Boolean, onClick: () -> Unit) {
    val ring: Brush = when {
        ringed || selected -> Brush.sweepGradient(listOf(Color(persona.colorStart), Color(persona.colorEnd), Color(0xFFFFB547), Color(persona.colorStart)))
        else -> Brush.linearGradient(listOf(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.outlineVariant))
    }
    Column(
        Modifier.width(StoryWidth).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Chat with ${persona.name}", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(StorySize).border(if (selected) 3.dp else 2.dp, ring, CircleShape).padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            PersonaAvatar(persona, size = StorySize - 10.dp)
        }
        Spacer(Modifier.height(6.dp))
        // Two short lines fit names like "Mindfulness Guide" whole instead of cutting them off.
        Text(
            persona.name,
            style = MaterialTheme.typography.labelMedium.copy(lineHeight = 15.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CreateStory(onClick: () -> Unit) {
    Column(
        Modifier.width(StoryWidth).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Create a personality", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(StorySize).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).padding(5.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Create",
            style = MaterialTheme.typography.labelMedium.copy(lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyChats() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("💬", fontSize = 40.sp)
        Text("No chats yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap a personality above to start your first conversation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Always first in the rail, so the everyday helper is one tap away. */
const val PINNED_PERSONA_ID = "assistant"

/**
 * Personalities like stories: the Assistant pinned first, then the one chatted with most recently,
 * and so on. Ones never chatted with keep their usual order after those.
 */
fun byLastUse(personas: List<Persona>, conversations: List<ConversationSummary>): List<Persona> {
    val lastUsed = HashMap<String, Long>()
    conversations.forEach { c -> if (c.updatedAt > (lastUsed[c.personaId] ?: Long.MIN_VALUE)) lastUsed[c.personaId] = c.updatedAt }
    // sortedByDescending is stable, so unused personalities stay in their original order.
    val (pinned, rest) = personas.partition { it.id == PINNED_PERSONA_ID }
    return pinned + rest.sortedByDescending { lastUsed[it.id] ?: Long.MIN_VALUE }
}
