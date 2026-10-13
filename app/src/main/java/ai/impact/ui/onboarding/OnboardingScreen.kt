package ai.impact.ui.onboarding

import ai.impact.data.BuiltInPersonas
import ai.impact.data.Persona
import ai.impact.ui.components.PageIndicator
import ai.impact.ui.components.PersonaAvatar
import ai.impact.ui.components.PersonaCard
import ai.impact.ui.components.PrimaryButton
import ai.impact.ui.personas.CreatePersonaCard
import ai.impact.ui.personas.PersonaEditorScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val STEP_COUNT = 4

@Composable
fun OnboardingScreen(
    personas: List<Persona>,
    initialPersonaId: String,
    onSaveCustomPersona: (Persona) -> Unit,
    onFinish: (name: String, personaId: String) -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf(initialPersonaId) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var accepted by rememberSaveable { mutableStateOf(false) }

    if (showEditor) {
        PersonaEditorScreen(
            existing = null,
            onBack = { showEditor = false },
            onSave = { persona ->
                onSaveCustomPersona(persona)
                selectedId = persona.id
                showEditor = false
            }
        )
        return
    }

    BackHandler(enabled = step > 0) { step-- }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: back + progress
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    IconButton(onClick = { step-- }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Spacer(Modifier.weight(1f))
                PageIndicator(count = STEP_COUNT, current = step)
                Spacer(Modifier.weight(1f))
                if (step == 1) {
                    TextButton(onClick = { name = ""; step++ }) { Text("Skip") }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "onboarding"
            ) { page ->
                when (page) {
                    0 -> WelcomeStep(onNext = { step = 1 })
                    1 -> NameStep(name = name, onNameChange = { name = it.take(30) }, onNext = { step = 2 })
                    2 -> PersonaStep(
                        personas = personas,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        onCreateCustom = { showEditor = true },
                        onNext = { step = 3 }
                    )
                    else -> SafetyStep(
                        persona = personas.firstOrNull { it.id == selectedId } ?: BuiltInPersonas.all.first(),
                        name = name,
                        accepted = accepted,
                        onAcceptedChange = { accepted = it },
                        onFinish = { onFinish(name, selectedId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        PersonaCluster()
        Spacer(Modifier.height(36.dp))
        Text(
            "One AI.\nEvery personality you need.",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "A coach when you need a push, a listener when you need to talk, a friend when you just want to chat.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        FeatureRow(Icons.Default.SwapHoriz, "12+ personalities", "Switch anytime or create your own")
        FeatureRow(Icons.Default.Lock, "Completely private", "Every conversation stays on your phone")
        FeatureRow(Icons.Default.WifiOff, "Works offline", "AI runs on your device, no account needed")
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Get started", onClick = onNext)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PersonaCluster() {
    val featured = listOf("therapist", "life_coach", "friend", "mindfulness", "creative")
        .mapNotNull { BuiltInPersonas.byId(it) }
    Box(modifier = Modifier.size(width = 280.dp, height = 190.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), Color.Transparent)
                    )
                )
        )
        val offsets = listOf((-96).dp to (-40).dp, (96).dp to (-46).dp, 0.dp to 0.dp, (-70).dp to 58.dp, (74).dp to 54.dp)
        val sizes = listOf(64.dp, 60.dp, 104.dp, 56.dp, 58.dp)
        featured.forEachIndexed { index, persona ->
            val (x, y) = offsets[index]
            Surface(
                modifier = Modifier.offset(x, y),
                shape = CircleShape,
                shadowElevation = 8.dp,
                color = Color.Transparent
            ) {
                PersonaAvatar(persona, size = sizes[index])
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NameStep(name: String, onNameChange: (String) -> Unit, onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("👋", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text("What should we call you?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your companion will use your name to make conversations feel more personal. It never leaves your device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Your first name") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() })
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continue", onClick = onNext, enabled = name.isNotBlank())
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PersonaStep(
    personas: List<Persona>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onCreateCustom: () -> Unit,
    onNext: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp, start = 4.dp, end = 4.dp)) {
                    Text("Choose your companion", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pick who you'd like to talk to first. You can switch personalities or create your own anytime in Settings.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(personas, key = { it.id }) { persona ->
                PersonaCard(persona = persona, selected = persona.id == selectedId, onClick = { onSelect(persona.id) })
            }
            item {
                CreatePersonaCard(onClick = onCreateCustom)
            }
        }
        val selected = personas.firstOrNull { it.id == selectedId }
        Surface(color = MaterialTheme.colorScheme.background) {
            PrimaryButton(
                text = if (selected != null) "Continue with ${selected.name}" else "Select a personality",
                onClick = onNext,
                enabled = selected != null,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun SafetyStep(
    persona: Persona,
    name: String,
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        PersonaAvatar(persona, size = 88.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            if (name.isNotBlank()) "You're all set, ${name.trim()}!" else "You're all set!",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${persona.name} is ready to chat. Before you start, a quick note:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("TinyAI is an AI companion", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Personalities like Therapist or Fitness Coach are AI role-play for support and self-reflection. " +
                        "They are not licensed professionals and can make mistakes. Don't rely on them for medical, legal or financial decisions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "If you're in crisis or thinking about harming yourself, please contact your local emergency number or a crisis helpline right away.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            onClick = { onAcceptedChange(!accepted) },
            shape = MaterialTheme.shapes.medium,
            color = Color.Transparent
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Checkbox(checked = accepted, onCheckedChange = onAcceptedChange)
                Text("I understand and I'm 13 or older", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Start chatting", onClick = onFinish, enabled = accepted)
        Spacer(Modifier.height(16.dp))
    }
}
