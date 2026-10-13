package ai.impact.ui.personas

import ai.impact.data.Persona
import ai.impact.data.PersonaCategory
import ai.impact.data.personaColorChoices
import ai.impact.data.personaEmojiChoices
import ai.impact.ui.components.Chip
import ai.impact.ui.components.EmojiAvatar
import ai.impact.ui.components.PersonaAvatar
import ai.impact.ui.components.PersonaCard
import ai.impact.ui.components.PrimaryButton
import ai.impact.ui.components.SelectableDot
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
fun CreatePersonaCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 168.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text("Create your own", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Design a custom personality",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonaPickerScreen(
    personas: List<Persona>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (Persona) -> Unit,
    onDelete: (Persona) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Persona?>(null) }

    val categories = remember(personas) { personas.map { it.category }.distinct() }
    val visible = personas.filter { filter == null || it.category.name == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personalities") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Create") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    categories.forEach { category ->
                        FilterChip(
                            selected = filter == category.name,
                            onClick = { filter = if (filter == category.name) null else category.name },
                            label = { Text(category.label) }
                        )
                    }
                }
            }
            items(visible, key = { it.id }) { persona ->
                Box {
                    PersonaCard(
                        persona = persona,
                        selected = persona.id == selectedId,
                        onClick = { onSelect(persona.id) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (persona.isCustom) {
                        CustomPersonaMenu(
                            modifier = Modifier.align(Alignment.BottomEnd),
                            onEdit = { onEdit(persona) },
                            onDelete = { pendingDelete = persona }
                        )
                    }
                }
            }
            if (filter == null || filter == PersonaCategory.Custom.name) {
                item { CreatePersonaCard(onClick = onCreate) }
            }
        }
    }

    pendingDelete?.let { persona ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${persona.name}?") },
            text = { Text("This personality and its current conversation will be removed.") },
            confirmButton = {
                TextButton(onClick = { onDelete(persona); pendingDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CustomPersonaMenu(modifier: Modifier, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { expanded = false; onEdit() }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}

private val promptIdeas = listOf(
    "Tough love" to "Be direct and honest, even blunt. Push me hard and don't accept excuses.",
    "Gentle" to "Be soft, patient and reassuring. Go slowly and validate my feelings.",
    "Funny" to "Be witty and playful. Use humour and keep things light.",
    "Expert" to "Be precise and knowledgeable. Give detailed, well-structured answers.",
    "Short replies" to "Keep every reply to two or three sentences."
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonaEditorScreen(
    existing: Persona?,
    onBack: () -> Unit,
    onSave: (Persona) -> Unit
) {
    BackHandler(onBack = onBack)
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var tagline by rememberSaveable { mutableStateOf(existing?.tagline ?: "") }
    var behaviour by rememberSaveable { mutableStateOf(existing?.systemPrompt ?: "") }
    var starters by rememberSaveable { mutableStateOf(existing?.starters?.joinToString("\n") ?: "") }
    var emoji by rememberSaveable { mutableStateOf(existing?.emoji ?: personaEmojiChoices.first()) }
    var colorIndex by rememberSaveable {
        mutableStateOf(
            personaColorChoices.indexOfFirst { it.first == existing?.colorStart }.takeIf { it >= 0 } ?: 0
        )
    }
    val colors = personaColorChoices[colorIndex]
    val brush = Brush.linearGradient(listOf(Color(colors.first), Color(colors.second)))
    val canSave = name.isNotBlank() && behaviour.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New personality" else "Edit personality") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // Live preview
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        EmojiAvatar(emoji, brush, size = 64.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name.ifBlank { "Your persona" },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                tagline.ifBlank { "A short tagline" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                EditorLabel("Avatar")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    personaEmojiChoices.forEach { choice ->
                        val selected = choice == emoji
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable { emoji = choice },
                            contentAlignment = Alignment.Center
                        ) { Text(choice, style = MaterialTheme.typography.titleLarge) }
                    }
                }

                EditorLabel("Color")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    personaColorChoices.forEachIndexed { index, (start, end) ->
                        SelectableDot(
                            selected = index == colorIndex,
                            brush = Brush.linearGradient(listOf(Color(start), Color(end))),
                            onClick = { colorIndex = index }
                        )
                    }
                }

                EditorLabel("Name")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Grandma Rose, Coach Max") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                EditorLabel("Tagline")
                OutlinedTextField(
                    value = tagline,
                    onValueChange = { tagline = it.take(48) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Warm advice with a cup of tea") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )

                EditorLabel("Personality & behaviour")
                OutlinedTextField(
                    value = behaviour,
                    onValueChange = { behaviour = it.take(1200) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    placeholder = { Text("Describe who they are and how they should talk to you…") },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = { Text("${behaviour.length}/1200") }
                )
                Text(
                    "Quick add",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    promptIdeas.forEach { (label, text) ->
                        Chip(label, onClick = {
                            behaviour = (if (behaviour.isBlank()) text else behaviour.trimEnd() + " " + text).take(1200)
                        })
                    }
                }

                EditorLabel("Conversation starters (optional)")
                OutlinedTextField(
                    value = starters,
                    onValueChange = { starters = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("One per line") },
                    shape = RoundedCornerShape(16.dp),
                    minLines = 2
                )
                Spacer(Modifier.height(24.dp))
            }

            Surface(color = MaterialTheme.colorScheme.background) {
                PrimaryButton(
                    text = if (existing == null) "Create personality" else "Save changes",
                    enabled = canSave,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    onClick = {
                        onSave(
                            Persona(
                                id = existing?.id ?: "custom_${UUID.randomUUID()}",
                                name = name.trim(),
                                emoji = emoji,
                                tagline = tagline.trim().ifBlank { "Your custom companion" },
                                description = tagline.trim(),
                                systemPrompt = behaviour.trim(),
                                colorStart = colors.first,
                                colorEnd = colors.second,
                                category = PersonaCategory.Custom,
                                starters = starters.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4),
                                isCustom = true
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun EditorLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}
