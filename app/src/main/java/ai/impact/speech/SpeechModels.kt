package ai.impact.speech

import ai.impact.models.AuxCatalogModel
import ai.impact.models.AuxFiles
import ai.impact.models.AuxKind
import ai.impact.models.DownloadFile

/** Suggested speech models. Files are pinned to a commit and sizes are checked after download. */
object SpeechCatalog {
    const val ENCODER = AuxFiles.ENCODER
    const val DECODER = AuxFiles.DECODER
    const val VOCAB = AuxFiles.VOCAB_JSON
    const val GENERATION = AuxFiles.GENERATION
    const val VOICE = AuxFiles.MODEL
    const val CONFIG = AuxFiles.CONFIG

    private const val MMS_LICENSE = "CC BY-NC 4.0 (non-commercial use only)"

    private fun whisper(
        id: String, name: String, repo: String, sha: String, description: String, license: String,
        encoder: Long, decoder: Long, vocab: Long, generation: Long, recommended: Boolean = false
    ): AuxCatalogModel {
        val base = "https://huggingface.co/$repo/resolve/$sha"
        return AuxCatalogModel(
            id, name, AuxKind.Listen, description, license, repo,
            listOf(
                DownloadFile("$base/onnx/encoder_model_quantized.onnx", ENCODER, encoder),
                DownloadFile("$base/onnx/decoder_model_quantized.onnx", DECODER, decoder),
                DownloadFile("$base/vocab.json", VOCAB, vocab),
                DownloadFile("$base/generation_config.json", GENERATION, generation)
            ),
            recommended
        )
    }

    private fun mms(id: String, name: String, repo: String, sha: String, description: String, model: Long, vocab: Long, config: Long): AuxCatalogModel {
        val base = "https://huggingface.co/$repo/resolve/$sha"
        return AuxCatalogModel(
            id, name, AuxKind.Speak, description, MMS_LICENSE, repo,
            listOf(
                DownloadFile("$base/onnx/model_quantized.onnx", VOICE, model),
                DownloadFile("$base/vocab.json", VOCAB, vocab),
                DownloadFile("$base/config.json", CONFIG, config)
            )
        )
    }

    val models: List<AuxCatalogModel> = listOf(
        whisper(
            "whisper-tiny-en", "Whisper Tiny (English)", "onnx-community/whisper-tiny.en",
            "794207fec2761281fe63a23a94b7e9f53a4efbc1",
            "Fast and light. Good for short, clear requests.", "Apache-2.0",
            encoder = 10_124_993, decoder = 30_460_688, vocab = 999_186, generation = 1_646, recommended = true
        ),
        whisper(
            "whisper-base-en", "Whisper Base (English)", "onnx-community/whisper-base.en",
            "51eefc0af78b103839eda9e7e4f4186acc6517fe",
            "Hears names and longer sentences more accurately, a little slower.", "MIT",
            encoder = 23_201_320, decoder = 53_309_666, vocab = 999_186, generation = 1_556
        ),
        whisper(
            "whisper-small-en", "Whisper Small (English)", "onnx-community/whisper-small.en",
            "482fb8ba081b6e906f92efe103622316b2a0cc69",
            "The most accurate here. Noticeably slower; best on newer phones.", "MIT",
            encoder = 92_326_170, decoder = 155_988_577, vocab = 999_186, generation = 1_956
        ),
        whisper(
            "whisper-tiny", "Whisper Tiny (99 languages)", "onnx-community/whisper-tiny",
            "ff4177021cc41f7db950912b73ea4fdf7d01d8e7",
            "Understands your phone's language, including Hindi, Spanish and French.", "MIT",
            encoder = 10_124_990, decoder = 30_461_071, vocab = 1_036_584, generation = 3_772
        ),
        whisper(
            "whisper-base", "Whisper Base (99 languages)", "onnx-community/whisper-base",
            "27a07b4b52b3a4c5886134bb4320e93e1a4bbc7c",
            "Multilingual and more accurate than Tiny, a little slower.", "Apache-2.0",
            encoder = 23_201_314, decoder = 53_310_178, vocab = 1_036_584, generation = 3_832
        ),
        AuxCatalogModel(
            "mms-tts-eng", "MMS Voice (English)", AuxKind.Speak,
            "Meta's open voice. Fully offline, but sounds more robotic than your phone's voice.",
            MMS_LICENSE, "Xenova/mms-tts-eng",
            "https://huggingface.co/Xenova/mms-tts-eng/resolve/3f8955a2adbd6487ce57420620d4916de22b9dac".let { base ->
                listOf(
                    DownloadFile("$base/onnx/model_quantized.onnx", VOICE, 38_361_643),
                    DownloadFile("$base/vocab.json", VOCAB, 413),
                    DownloadFile("$base/config.json", CONFIG, 1_656)
                )
            },
            recommended = true
        ),
        mms(
            "mms-tts-hin", "MMS Voice (Hindi)", "Xenova/mms-tts-hin", "3fc18cc9d5ce10023fb0d22e8339e318fdcae9d9",
            "Reads Hindi written in Devanagari.", model = 38_368_171, vocab = 907, config = 1_656
        ),
        mms(
            "mms-tts-spa", "MMS Voice (Spanish)", "Xenova/mms-tts-spa", "03f05a694412894ff930e9418fa05f52d66d6ad3",
            "Reads Spanish replies aloud, offline.", model = 38_362_987, vocab = 497, config = 1_656
        ),
        mms(
            "mms-tts-fra", "MMS Voice (French)", "Xenova/mms-tts-fra", "c7aa283bf5b599d3c804e4accab07f6394a2b4ae",
            "Reads French replies aloud, offline.", model = 38_362_795, vocab = 493, config = 1_656
        ),
        mms(
            "mms-tts-deu", "MMS Voice (German)", "Xenova/mms-tts-deu", "b95ad64a0715453237dc47ed873235a0024b4275",
            "Reads German replies aloud, offline.", model = 38_362_988, vocab = 496, config = 1_656
        )
    )

    fun find(id: String): AuxCatalogModel? = models.firstOrNull { it.id == id }
}
