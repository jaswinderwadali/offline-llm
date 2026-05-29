package ai.impact

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.nehuatl.llamacpp.LlamaHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

class ChatViewModel(contentResolver: ContentResolver) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _chatText = MutableStateFlow("Tap 'Select GGUF Model' to begin.")
    val chatText = _chatText.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _wordLimit = MutableStateFlow(200)
    val wordLimit = _wordLimit.asStateFlow()

    private var isLlamaModel = false // Detection for prompt formatting

    // Reactive stream that receives tokens directly from C++
    private val _llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(extraBufferCapacity = 64)

    // Using a background scope for LlamaHelper to prevent UI freezes
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val llamaHelper = LlamaHelper(contentResolver, backgroundScope, _llmFlow)

    init {
        Log.d(TAG, "Initializing ChatViewModel")
        viewModelScope.launch(Dispatchers.Default) {
            _llmFlow.collect { event ->
                Log.d(TAG, "Received LLMEvent: ${event::class.simpleName}")
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        _messages.update { current ->
                            if (current.isEmpty() || current.last().isUser) {
                                current + ChatMessage(event.word, false)
                            } else {
                                val last = current.last()
                                val updatedMessages = current.toMutableList()
                                updatedMessages[current.size - 1] = last.copy(text = last.text + event.word)
                                updatedMessages
                            }
                        }
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        Log.d(TAG, "Inference Done")
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        Log.e(TAG, "LLM Error: ${event.message}")
                        _messages.update { it + ChatMessage("[Error: ${event.message}]", false) }
                    }
                    else -> {
                        Log.d(TAG, "Other LLMEvent received: $event")
                    }
                }
            }
        }
    }

    fun loadModel(uri: Uri) {
        Log.d(TAG, "Loading model from URI: $uri")
        val path = uri.toString().lowercase()
        isLlamaModel = path.contains("llama")
        Log.d(TAG, "Detected model type: ${if (isLlamaModel) "Llama" else "Qwen/ChatML"}")

        _chatText.value = "Loading 2GB Model into RAM... (This takes a few seconds)"
        _isReady.value = false // Ensure we're in loading state
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // n_gpu_layers is not supported in the standard load() call of this library version
                llamaHelper.load(
                    path = uri.toString(),
                    contextLength = 2048,
                    loaded = {
                        Log.d(TAG, "Model loaded successfully")
                        _isReady.value = true
                        _chatText.value = "Model loaded! Ready to chat."
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                _chatText.value = "Load Error: ${e.message}"
            }
        }
    }

    fun sendMessage(userText: String) {
        Log.d(TAG, "sendMessage: $userText (isReady: ${_isReady.value})")
        if (!_isReady.value) return
        
        // Add user message to list
        _messages.update { it + ChatMessage(userText, true) }
        
        val limitInstruction = " Please keep your answer under ${_wordLimit.value} words."
        
        // Use correct format based on detected model
        val prompt = if (isLlamaModel) {
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nYou are an empathetic mental health assistant.$limitInstruction<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$userText<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        } else {
            "<|im_start|>system\nYou are an empathetic mental health assistant.$limitInstruction<|im_end|>\n<|im_start|>user\n$userText<|im_end|>\n<|im_start|>assistant\n"
        }

        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Launching prediction on IO thread")
            try {
                // Ensure we are subscribed before predicting
                Log.d(TAG, "Calling llamaHelper.predict(prompt)")
                llamaHelper.predict(prompt) 
                Log.d(TAG, "predict() call finished execution")
            } catch (e: Exception) {
                Log.e(TAG, "Error during predict()", e)
                _messages.update { it + ChatMessage("[Prediction Error: ${e.message}]", false) }
            }
        }
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    fun resetModel() {
        _isReady.value = false
        _messages.value = emptyList()
        _chatText.value = "Tap 'Select GGUF Model' to begin."
    }

    fun setWordLimit(limit: Int) {
        _wordLimit.value = limit
    }

    override fun onCleared() {
        super.onCleared()
        backgroundScope.cancel()
    }
}
