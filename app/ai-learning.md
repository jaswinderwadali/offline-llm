To build the complete integration, we will use the Android File Picker (Storage Access Framework).This is the most reliable way to debug local AI models on Android because it completely bypasses Android 11+ storage permission nightmares. The user (you) just taps a button in the app, selects the 2GB Llama-3.2-3B-Instruct-Q4_K_M.gguf file from the Downloads folder, and Android securely passes a readable content:// URI directly to the C++ engine.Here are the three complete files to get your chatbot running.1.Update build.gradle.kts (App Level):Ensure you have the native wrapper and Jetpack Compose state management libraries installed. No changes to the AndroidManifest.xml are needed for this approach.Kotlindependencies {
// ... existing compose dependencies ...

    // Native C++ engine wrapper
    implementation("io.github.ljcamargo:llamacpp-kotlin:0.4.0")
    
    // Lifecycle and State management
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
}
2.Create ChatViewModel.kt:This ViewModel initializes the native LlamaHelper using the URI provided by the file picker, and listens to the native event flow to stream the text to the UI.Kotlinimport android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ljcamargo.llamacpp.LlamaHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(contentResolver: ContentResolver) : ViewModel() {

    private val _chatText = MutableStateFlow("Tap 'Select GGUF Model' to begin.")
    val chatText = _chatText.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    // Reactive stream that receives tokens directly from C++
    private val _llmFlow = MutableSharedFlow()

    private val llamaHelper = LlamaHelper(contentResolver, viewModelScope, _llmFlow)

    init {
        viewModelScope.launch(Dispatchers.Main) {
            _llmFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        _chatText.value += event.word
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        // Generation finished, wait for next user prompt
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        _chatText.value += "\n[Error: ${event.message}]"
                    }
                    else -> {}
                }
            }
        }
    }

    fun loadModel(uri: Uri) {
        _chatText.value = "Loading 2GB Model into RAM... (This takes a few seconds)"
        
        llamaHelper.load(
            path = uri.toString(),
            contextLength = 2048 // Keep at 2048 for mobile stability
        ) { id ->
            _isReady.value = true
            _chatText.value = "Model loaded natively! Ready to chat."
        }
    }

    fun sendMessage(userText: String) {
        if (!_isReady.value) return
        
        // Format specifically for Llama 3 models
        val prompt = "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nYou are an empathetic mental health assistant.<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$userText<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

        viewModelScope.launch(Dispatchers.IO) {
            _chatText.value = "" // Clear screen
            llamaHelper.send(prompt) // Triggers LLMEvent.Ongoing flow
        }
    }
}
3.Update MainActivity.kt:This Activity launches the Android File Picker. Once you select the file you pushed to your device, it securely grants read access and tells the ViewModel to load the model into memory.Kotlinimport android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        
        // Inject ContentResolver into the ViewModel so it can read the URI
        viewModel = ChatViewModel(contentResolver)

        setContent {
            ChatScreen(
                viewModel = viewModel,
                onSelectFile = { 
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*" // Filters are unreliable; let user pick the .gguf
                    }
                    modelPickerLauncher.launch(intent)
                }
            )
        }
    }
}

@Composable
fun ChatScreen(viewModel: ChatViewModel, onSelectFile: () -> Unit) {
val isReady by viewModel.isReady.collectAsStateWithLifecycle()
val chatText by viewModel.chatText.collectAsStateWithLifecycle()
var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Setup Button - Disappears once the model is loaded
        if (!isReady) {
            Button(
                onClick = onSelectFile,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text("1. Select GGUF Model File")
            }
        }

        // Output Console
        Text(
            text = chatText,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyLarge
        )

        // Input Row
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                enabled = isReady,
                placeholder = { Text("Write a message...") }
            )
            Button(
                onClick = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                enabled = isReady && inputText.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Send")
            }
        }
    }
}
Push the Q4_K_M.gguf file to your phone's Download folder using ADB or Android Studio's Device File Explorer. Build the app, tap the "Select GGUF Model" button, pick the file, and you will see the 2GB file stream straight into RAM.