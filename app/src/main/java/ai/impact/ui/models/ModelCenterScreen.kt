package ai.impact.ui.models

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import ai.impact.ui.components.SectionHeader
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class ModelInfo(
    val name: String,
    val description: String,
    val size: String,
    val url: String,
    val recommended: Boolean = false
)

val availableModels = listOf(
    ModelInfo(
        "Qwen 2.5 1.5B (Q4_K_M)",
        "Excellent balance of quality and speed for most mobile devices.",
        "~1.1 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
        recommended = true
    ),
    ModelInfo(
        "Llama 3.2 3B Instruct (Q4_K_M)",
        "Meta's highly capable small model. Smarter, needs 6 GB+ RAM.",
        "~2.0 GB",
        "https://huggingface.co/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf?download=true",
        recommended = true
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (Q2_K) - Ultra Fast",
        "Smallest size, lowest RAM usage. Good for older devices.",
        "~0.7 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q2_k.gguf?download=true"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (Q3_K_M)",
        "Very efficient with decent intelligence.",
        "~0.9 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q3_k_m.gguf?download=true"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (Q4_0)",
        "Standard quantization, reliable performance.",
        "~1.1 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_0.gguf?download=true"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (Q5_0)",
        "Good quality with moderate resource usage.",
        "~1.3 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q5_0.gguf?download=true"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (Q5_K_M)",
        "Higher quality, slightly more RAM usage.",
        "~1.3 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q5_k_m.gguf?download=true"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (Q6_K)",
        "Near-perfect quality, requires more processing power.",
        "~1.5 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q6_k.gguf?download=true"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (Q8_0)",
        "Maximum quality, highest RAM usage.",
        "~1.9 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q8_0.gguf?download=true"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B (FP16)",
        "Uncompressed format. Very slow on most phones.",
        "~3.5 GB",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-fp16.gguf?download=true"
    )
)

private const val EXPLORE_URL =
    "https://huggingface.co/models?pipeline_tag=text-generation&num_parameters=min:0,max:6B&library=gguf&sort=trending"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCenterScreen(onBack: () -> Unit, onPickModel: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser found to open the link", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model center") },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("How it works", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Step(1, "Tap a model below to download the .gguf file")
                        Step(2, "Come back and tap \"Choose model file\"")
                        Step(3, "Pick the file from Downloads. It's remembered for next time")
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose model file")
                        }
                    }
                }
            }
            item { SectionHeader("Recommended", Modifier.padding(top = 8.dp)) }
            items(availableModels.filter { it.recommended }) { ModelRow(it, onClick = { open(it.url) }) }
            item { SectionHeader("More options", Modifier.padding(top = 8.dp)) }
            items(availableModels.filterNot { it.recommended }) { ModelRow(it, onClick = { open(it.url) }) }
            item {
                Surface(
                    onClick = { open(EXPLORE_URL) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Explore more models", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Browse Hugging Face for other GGUF models under 6B parameters.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModelRow(model: ModelInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(model.size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
