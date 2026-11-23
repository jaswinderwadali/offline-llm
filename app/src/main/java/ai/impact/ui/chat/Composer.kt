package ai.impact.ui.chat

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import ai.impact.data.Persona
import ai.impact.data.documents.DocumentEntity
import ai.impact.data.documents.DocumentKind
import ai.impact.data.documents.DocumentSource
import ai.impact.data.skills.Skill
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The user's documents as the composer offers them; null when there's nothing to search. */
class DocumentsAttachment(
    val documents: List<DocumentEntity>,
    val enabled: Boolean,
    /** Documents unselected for this chat. */
    val excluded: Set<Long>,
    val onToggle: (Boolean) -> Unit,
    val onToggleDocument: (Long) -> Unit,
    val onManage: () -> Unit
) {
    fun isSelected(doc: DocumentEntity) = enabled && doc.id !in excluded
    val selectedCount: Int get() = if (enabled) documents.count { it.id !in excluded } else 0
}

private enum class ActionMode { Send, Mic, Stop }

/**
 * Where a message is written: a floating card with the text on top and a toolbar below for
 * skills and documents, and a send button that becomes a mic when there's nothing to send.
 */
@Composable
internal fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    persona: Persona,
    activeSkill: Skill?,
    placeholder: String,
    documents: DocumentsAttachment?,
    onOpenSkills: () -> Unit,
    onClearSkill: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: (() -> Unit)?,
    /** Where the send/mic button's centre is on screen, for voice mode to open from. */
    onActionButtonPlaced: (Offset) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    var showDocuments by remember { mutableStateOf(false) }
    val accent = Brush.linearGradient(listOf(Color(persona.colorStart), Color(persona.colorEnd)))
    val idleBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    // The last skill and documents shown, so their chips can animate away after they're gone.
    var lastSkill by remember { mutableStateOf(activeSkill) }
    if (activeSkill != null) lastSkill = activeSkill
    var lastDocuments by remember { mutableStateOf(documents) }
    if (documents != null) lastDocuments = documents

    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(if (focused) 10.dp else 3.dp, RoundedCornerShape(28.dp), clip = false)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // The border glows in the personality's colours while typing.
                    .border(if (focused) 1.5.dp else 1.dp, if (focused) accent else SolidColor(idleBorder), RoundedCornerShape(28.dp))
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            ) {
                AnimatedVisibility(
                    visible = activeSkill != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    lastSkill?.let { skill -> ActiveSkillChip(skill, onClear = onClearSkill, modifier = Modifier.padding(start = 12.dp, top = 10.dp)) }
                }
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .onFocusChanged { focused = it.isFocused },
                    enabled = enabled,
                    placeholder = {
                        AnimatedContent(placeholder, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }, label = "placeholder") {
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    )
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ToolPill(
                        icon = Icons.Outlined.Bolt,
                        label = activeSkill?.name ?: "Skills",
                        tint = activeSkill?.let { Color(it.colorStart) },
                        selected = activeSkill != null,
                        onClick = onOpenSkills
                    )
                    AnimatedVisibility(
                        visible = documents != null,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut()
                    ) {
                        lastDocuments?.let { docs ->
                            val count = docs.selectedCount
                            val total = docs.documents.size
                            ToolPill(
                                icon = Icons.Outlined.Description,
                                label = when {
                                    count == 0 -> "Docs off"
                                    count == total -> "$count doc${if (count == 1) "" else "s"}"
                                    else -> "$count of $total docs"
                                },
                                tint = if (count > 0) MaterialTheme.colorScheme.primary else null,
                                selected = count > 0,
                                onClick = { showDocuments = true }
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val hasInput = value.isNotBlank() || activeSkill?.emptyPrompt != null
                    val mode = when {
                        isGenerating -> ActionMode.Stop
                        !hasInput && onMic != null -> ActionMode.Mic
                        else -> ActionMode.Send
                    }
                    val active = mode != ActionMode.Send || (enabled && hasInput)
                    ActionButton(mode, active, accent, Modifier.onGloballyPositioned { onActionButtonPlaced(it.boundsInRoot().center) }) {
                        when (mode) {
                            ActionMode.Stop -> onStop()
                            ActionMode.Mic -> onMic?.invoke()
                            ActionMode.Send -> onSend()
                        }
                    }
                }
            }
        }
    }

    if (showDocuments && documents != null) {
        DocumentsSheet(documents, onDismiss = { showDocuments = false })
    }
}

/** A rounded toolbar button: icon and label, tinted when it's doing something. */
@Composable
private fun ToolPill(icon: ImageVector, label: String, tint: Color?, selected: Boolean, onClick: () -> Unit) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    val background by animateColorAsState(if (selected) color.copy(alpha = 0.14f) else Color.Transparent, label = "pill")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, if (selected) color.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp)
        )
    }
}

/** Send, talk or stop: the icon morphs between them, and stop gently pulses while replying. */
@Composable
private fun ActionButton(mode: ActionMode, active: Boolean, accent: Brush, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "stop").animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse"
    )
    val pressScale by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0.92f, spring(dampingRatio = 0.5f), label = "press")
    Box(
        modifier
            .size(44.dp)
            .scale(if (mode == ActionMode.Stop) pulse else pressScale)
            .clip(CircleShape)
            .background(if (active) accent else SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest))
            .clickable(enabled = active, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            mode,
            transitionSpec = { (scaleIn(initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut()) },
            label = "action"
        ) { m ->
            Icon(
                when (m) {
                    ActionMode.Stop -> Icons.Default.Stop
                    ActionMode.Mic -> Icons.Default.Mic
                    ActionMode.Send -> Icons.AutoMirrored.Filled.Send
                },
                contentDescription = when (m) {
                    ActionMode.Stop -> "Stop"
                    ActionMode.Mic -> "Talk hands-free"
                    ActionMode.Send -> "Send"
                },
                tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Each kind of file gets its own colour and icon, here and under replies. */
internal fun documentStyle(name: String, mimeType: String? = null): Pair<Color, ImageVector> = when (DocumentKind.of(name, mimeType)) {
    DocumentKind.Pdf -> Color(0xFFE5484D) to Icons.Outlined.PictureAsPdf
    DocumentKind.Word -> Color(0xFF4F9DFF) to Icons.Outlined.Article
    DocumentKind.Markdown -> Color(0xFF8B5CF6) to Icons.Outlined.TextSnippet
    else -> Color(0xFF8A9BBF) to Icons.Outlined.Description
}

@Composable
private fun FileTile(name: String, mimeType: String?, size: androidx.compose.ui.unit.Dp) {
    val (color, icon) = documentStyle(name, mimeType)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.58f))
    }
}

/** The documents chat searches, and whether this chat uses them. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DocumentsSheet(attachment: DocumentsAttachment, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Your documents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Chat finds the passages that answer your question and shows where they came from.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Surface(
                    onClick = { attachment.onToggle(!attachment.enabled) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use in this chat", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (val n = attachment.selectedCount) {
                                    0 -> "This chat ignores your documents"
                                    attachment.documents.size -> "All documents · tap one to leave it out"
                                    else -> "$n of ${attachment.documents.size} selected · tap to change"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = attachment.selectedCount > 0, onCheckedChange = attachment.onToggle)
                    }
                }
            }
            items(attachment.documents, key = { it.id }) { doc ->
                DocumentRow(doc, selected = attachment.isSelected(doc), onClick = { attachment.onToggleDocument(doc.id) }, modifier = Modifier.animateItem())
            }
            item {
                Surface(
                    onClick = { onDismiss(); attachment.onManage() },
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Add or manage documents", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Where a reply's facts came from: a card per document and page, easing in once it's done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceCards(sources: List<DocumentSource>) {
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "From your documents",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.forEach { source ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            FileTile(source.documentName, null, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                source.documentName,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp)
                            )
                            val pages = when {
                                source.pageStart == null -> null
                                source.pageEnd == null || source.pageEnd == source.pageStart -> "p. ${source.pageStart}"
                                else -> "pp. ${source.pageStart}–${source.pageEnd}"
                            }
                            if (pages != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    pages,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A document in the sheet: tap to select or unselect it for this chat. */
@Composable
private fun DocumentRow(doc: DocumentEntity, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val background by animateColorAsState(
        if (selected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer, label = "docRow"
    )
    val border by animateColorAsState(
        if (selected) accent.copy(alpha = 0.45f) else Color.Transparent, label = "docBorder"
    )
    val contentAlpha by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0.55f, label = "docAlpha")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = background,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = modifier
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp).alpha(contentAlpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileTile(doc.name, doc.mimeType, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        doc.pageCount?.let { "$it page${if (it == 1) "" else "s"}" },
                        "${doc.chunkCount} passage${if (doc.chunkCount == 1) "" else "s"}"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SelectionMark(selected)
        }
    }
}

/** A filled tick when selected, an empty ring when not, with a little pop between them. */
@Composable
private fun SelectionMark(selected: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    AnimatedContent(
        selected,
        transitionSpec = { (scaleIn(initialScale = 0.5f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut()) },
        label = "mark"
    ) { on ->
        if (on) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
            }
        } else {
            Box(
                Modifier.size(26.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) { }
        }
    }
}

