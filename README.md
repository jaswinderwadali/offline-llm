# Impact AI - On-Device LLM Chatbot

Impact AI is a privacy-first, fully offline Android application that allows users to run Large Language Models (LLMs) locally on their mobile devices. It utilizes native C++ bindings for high-performance inference without requiring an internet connection.

## 🚀 Features

- **On-Device Inference**: Runs GGUF models locally. No data leaves your device.
- **Multimodal Support**: Capable of running both text-only (Llama 3, Qwen) and vision-capable models.
- **Dynamic Prompt Formatting**: Automatically detects the model type (Llama vs. ChatML/Qwen) based on the filename and applies the correct prompt template to prevent erratic behavior.
- **Modern UI/UX**:
    - Smooth Chat Bubble interface.
    - Real-time token streaming (the AI "types" as it thinks).
    - Auto-scrolling to keep the latest messages in view.
    - Edge-to-edge display support.
- **Customizable Settings**:
    - **Word Limit**: Use a slider to instruct the AI to keep answers concise (50-500 words).
    - **Model Center**: Integrated hub with direct links to download optimized mobile models from Hugging Face.
- **Performance Optimized**:
    - Heavy model loading and inference are isolated to background threads (`Dispatchers.IO`) to prevent UI freezing.
    - Utilizes CPU hardware acceleration (ARM NEON, i8mm, dotprod) via the `llamacpp-kotlin` library.

## 🛠 Tech Stack

- **Kotlin**: Primary language for Android development.
- **Jetpack Compose**: Modern toolkit for building native UI.
- **Coroutines & Flow**: For asynchronous operations and real-time streaming.
- **Llama.cpp (via llamacpp-kotlin)**: The native C++ engine powering the AI.
- **Material 3**: Latest Android design system.

## 📥 How to Use

1. **Download a Model**:
   - Go to the **Model Center** (Download icon at the top).
   - Click a link to Hugging Face and download a `.gguf` file (Recommended: Llama 3.2 3B or Qwen 2.5 1.5B).
   - Place the file in your device's `Downloads` folder.
2. **Load the Model**:
   - Tap **"Select GGUF Model File"** in the app.
   - Pick the downloaded file. The app will securely request read permissions and load the model into RAM.
3. **Chat**:
   - Type your message and hit send!
4. **Manage**:
   - Use the **Settings** (Gear icon) to adjust the word limit.
   - Use the **Clear Chat** (Trash icon) to wipe the current conversation.
   - Use **Change Model** (Refresh icon) to swap to a different file.

## ⚠️ Important Notes

- **RAM Requirements**: Large models (like 3B parameters) require significantly more RAM. If the app crashes during loading, try a smaller model (like 1.5B).
- **Battery & Heat**: Running AI locally is CPU-intensive. Your device may get warm during long conversations.
- **GPU Acceleration**: The current version utilizes high-performance CPU instructions. Native GPU acceleration for mobile is currently under development in the underlying wrapper.

---
*Created with ❤️ for private and secure mobile AI.*
