package ai.impact

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ChatViewModel

    // Standard Android 11+ File Picker Launcher
    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                // Request persistent read access so the native engine doesn't lose the file
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.loadModel(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Inject ContentResolver into the ViewModel so it can read the URI
        viewModel = ChatViewModel(contentResolver)

        setContent {
            var currentScreen by remember { mutableStateOf("chat") }

            if (currentScreen == "models") {
                ModelCenterScreen(onBack = { currentScreen = "chat" })
            } else {
                ChatScreen(
                    viewModel = viewModel,
                    onSelectFile = { 
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*" // Filters are unreliable; let user pick the .gguf
                        }
                        modelPickerLauncher.launch(intent)
                    },
                    onOpenModelCenter = { currentScreen = "models" }
                )
            }
        }
    }
}

data class ModelInfo(
    val name: String,
    val description: String,
    val url: String
)

val availableModels = listOf(
    ModelInfo(
        "Llama 3.2 3B Instruct",
        "Meta's highly capable small model. Balanced for performance and speed.",
        "https://huggingface.co/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/tree/main"
    ),
    ModelInfo(
        "Qwen 2.5 1.5B Instruct",
        "Alibaba's efficient model. Excellent for mobile devices with lower RAM.",
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/tree/main"
    ),
    ModelInfo(
        "Explore more models",
        "Browse the Hugging Face library for other GGUF models suitable for mobile (under 6B parameters).",
        "https://huggingface.co/models?pipeline_tag=text-generation&num_parameters=min:0,max:6B&library=gguf&sort=trending"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Download Center") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Select a model to view on Hugging Face. Download the .GGUF version to use in this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(availableModels) { model ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.url))
                        context.startActivity(intent)
                    },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(model.description, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel, 
    onSelectFile: () -> Unit,
    onOpenModelCenter: () -> Unit
) {
    val isReady by viewModel.isReady.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chatText by viewModel.chatText.collectAsStateWithLifecycle()
    val wordLimit by viewModel.wordLimit.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive or current message grows
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(messages.lastOrNull()?.text) {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Impact AI")
                        if (isReady) {
                            Text(
                                text = "Limit: $wordLimit words",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenModelCenter) {
                        Icon(Icons.Default.Download, contentDescription = "Download Models")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    if (isReady) {
                        IconButton(onClick = { viewModel.clearChat() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear Chat")
                        }
                        IconButton(onClick = { viewModel.resetModel() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Change Model")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (showSettings) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Word Limit: $wordLimit", style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = wordLimit.toFloat(),
                            onValueChange = { viewModel.setWordLimit(it.toInt()) },
                            valueRange = 50f..500f,
                            steps = 9
                        )
                        Text("Limits how many words the AI should aim for.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Setup Button - Disappears once the model is loaded
            if (!isReady) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = onSelectFile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text("1. Select GGUF Model File")
                    }
                    Text(chatText, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Chat Messages
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    ChatBubble(message)
                }
            }

            // Input Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    enabled = isReady,
                    placeholder = { Text("Write a message...") },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                IconButton(
                    onClick = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    enabled = isReady && inputText.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (isReady && inputText.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (message.isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val shape = if (message.isUser) {
        RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = shape,
            tonalElevation = 2.dp
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
