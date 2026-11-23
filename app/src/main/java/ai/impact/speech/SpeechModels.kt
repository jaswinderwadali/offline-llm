package ai.impact.speech

import ai.impact.models.DownloadFile
import ai.impact.models.DownloadSpec
import ai.impact.models.ModelDownload
import ai.impact.models.ModelDownloader
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

enum class SpeechKind { Listen, Speak }

/** A speech model TinyAI can download. Files are pinned to a commit so they never change underneath. */
data class CatalogSpeechModel(
    val id: String,
    val name: String,
    val kind: SpeechKind,
    val description: String,
    val license: String,
    val files: List<DownloadFile>
) {
    val sizeBytes: Long get() = files.sumOf { it.size }

    fun toDownloadSpec() = DownloadSpec(
        id, name, files,
        JSONObject().put("name", name).put("kind", kind.name)
    )
}

object SpeechCatalog {
    const val ENCODER = "encoder.onnx"
    const val DECODER = "decoder.onnx"
    const val VOCAB = "vocab.json"
    const val GENERATION = "generation_config.json"
    const val VOICE = "model.onnx"
    const val CONFIG = "config.json"

    private fun whisper(id: String, name: String, repo: String, sha: String, description: String, license: String, encoder: Long, decoder: Long, generation: Long): CatalogSpeechModel {
        val base = "https://huggingface.co/$repo/resolve/$sha"
        return CatalogSpeechModel(
            id, name, SpeechKind.Listen, description, license,
            listOf(
                DownloadFile("$base/onnx/encoder_model_quantized.onnx", ENCODER, encoder),
                DownloadFile("$base/onnx/decoder_model_quantized.onnx", DECODER, decoder),
                DownloadFile("$base/vocab.json", VOCAB, 999_186),
                DownloadFile("$base/generation_config.json", GENERATION, generation)
            )
        )
    }

    val models: List<CatalogSpeechModel> = listOf(
        whisper(
            "whisper-tiny-en", "Whisper Tiny (English)", "onnx-community/whisper-tiny.en",
            "794207fec2761281fe63a23a94b7e9f53a4efbc1",
            "Fast and light. Good for short, clear requests.", "Apache-2.0",
            encoder = 10_124_993, decoder = 30_460_688, generation = 1_646
        ),
        whisper(
            "whisper-base-en", "Whisper Base (English)", "onnx-community/whisper-base.en",
            "51eefc0af78b103839eda9e7e4f4186acc6517fe",
            "Hears names and longer sentences more accurately, a little slower.", "MIT",
            encoder = 23_201_320, decoder = 53_309_666, generation = 1_556
        ),
        CatalogSpeechModel(
            "mms-tts-eng", "MMS Voice (English)", SpeechKind.Speak,
            "Meta's open voice. Fully offline, but sounds more robotic than your phone's voice.",
            "CC BY-NC 4.0 (non-commercial use only)",
            "https://huggingface.co/Xenova/mms-tts-eng/resolve/3f8955a2adbd6487ce57420620d4916de22b9dac".let { base ->
                listOf(
                    DownloadFile("$base/onnx/model_quantized.onnx", VOICE, 38_361_643),
                    DownloadFile("$base/vocab.json", VOCAB, 413),
                    DownloadFile("$base/config.json", CONFIG, 1_656)
                )
            }
        )
    )

    fun find(id: String): CatalogSpeechModel? = models.firstOrNull { it.id == id }
}

data class InstalledSpeechModel(val id: String, val name: String, val kind: SpeechKind, val dir: File, val sizeBytes: Long)

/** Downloaded speech models, kept apart from chat and image models. */
class SpeechModelStore(context: Context, scope: CoroutineScope) {

    private val downloader = ModelDownloader(
        context = context,
        scope = scope,
        prefsName = "speech_models",
        relativeRoot = "speech_models",
        validate = { dir ->
            val model = SpeechCatalog.find(dir.name.removePrefix(".download-"))
            val missing = model?.files?.firstOrNull { File(dir, it.path).length() != it.size }
            if (missing != null) "${model.name} didn't download completely. Try again." else null
        },
        onInstalled = { refresh() }
    )

    private val _installed = MutableStateFlow<List<InstalledSpeechModel>>(emptyList())
    val installed: StateFlow<List<InstalledSpeechModel>> = _installed.asStateFlow()
    val downloads: StateFlow<Map<String, ModelDownload>> = downloader.downloads

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun refresh() {
        _installed.value = SpeechCatalog.models.mapNotNull { model ->
            val dir = downloader.dirFor(model.id)
            val complete = File(dir, ModelDownloader.COMPLETE).isFile && model.files.all { File(dir, it.path).length() == it.size }
            if (complete) InstalledSpeechModel(model.id, model.name, model.kind, dir, model.sizeBytes) else null
        }
    }

    fun find(id: String): InstalledSpeechModel? = _installed.value.firstOrNull { it.id == id }

    fun startDownload(model: CatalogSpeechModel): String? = downloader.start(model.toDownloadSpec())
    fun cancelDownload(id: String) = downloader.cancel(id)
    fun dismissError(id: String) = downloader.dismiss(id)

    fun delete(id: String) {
        downloader.cancel(id)
        try {
            downloader.dirFor(id).deleteRecursively()
        } catch (e: Exception) {
            Log.w("SpeechModelStore", "Couldn't delete $id", e)
        }
        refresh()
    }
}
