package ai.impact

import ai.impact.image.AuxResolution
import ai.impact.image.HubFile
import ai.impact.image.HubParsing
import ai.impact.image.RepoRef
import ai.impact.models.AuxFiles
import ai.impact.models.AuxKind
import ai.impact.models.AuxLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuxModelsTest {

    @Test
    fun picksTheSmallestSearchModelFirst() {
        val paths = setOf("onnx/model.onnx", "onnx/model_quantized.onnx", "onnx/model_fp16.onnx", "vocab.txt", "config.json")
        val variants = AuxLayout.variants(AuxKind.Search, paths)
        assertEquals(listOf("onnx/model_quantized.onnx", "onnx/model.onnx", "onnx/model_fp16.onnx"), variants.map { it.files.first().first })
        assertEquals(listOf("onnx/model_quantized.onnx" to AuxFiles.MODEL, "vocab.txt" to AuxFiles.VOCAB_TXT), variants.first().files)
    }

    @Test
    fun searchModelNeedsAWordPieceVocab() {
        // XLM-R style models ship a SentencePiece tokenizer instead of vocab.txt.
        assertTrue(AuxLayout.variants(AuxKind.Search, setOf("onnx/model_quantized.onnx", "tokenizer.json")).isEmpty())
    }

    @Test
    fun whisperPairsEncoderAndDecoderOfTheSameVersionAndSkipsMergedDecoders() {
        val paths = setOf(
            "onnx/encoder_model_quantized.onnx", "onnx/decoder_model_quantized.onnx",
            "onnx/encoder_model.onnx", "onnx/decoder_model_merged.onnx",
            "vocab.json", "generation_config.json"
        )
        val variants = AuxLayout.variants(AuxKind.Listen, paths)
        // Only the quantised pair: the full-precision encoder has no plain decoder to go with it.
        assertEquals(1, variants.size)
        assertEquals(
            listOf(
                "onnx/encoder_model_quantized.onnx" to AuxFiles.ENCODER,
                "onnx/decoder_model_quantized.onnx" to AuxFiles.DECODER,
                "vocab.json" to AuxFiles.VOCAB_JSON,
                "generation_config.json" to AuxFiles.GENERATION
            ),
            variants.single().files
        )
    }

    @Test
    fun importFindsFilesInNestedFoldersAndIgnoresEmptyOnes() {
        val folder = mapOf(
            "mms-tts-hin/onnx/model_quantized.onnx" to 38_000_000L,
            "mms-tts-hin/onnx/model.onnx" to 0L,
            "mms-tts-hin/vocab.json" to 907L,
            "mms-tts-hin/config.json" to 1_656L
        )
        val plan = AuxLayout.importPlan(AuxKind.Speak, folder)
        assertEquals("mms-tts-hin/onnx/model_quantized.onnx" to AuxFiles.MODEL, plan!!.first())
        assertNull(AuxLayout.importPlan(AuxKind.Listen, folder))
    }

    @Test
    fun importAcceptsAFolderAlreadyInTheSavedLayout() {
        val folder = mapOf("encoder.onnx" to 1L, "decoder.onnx" to 1L, "vocab.json" to 1L, "generation_config.json" to 1L)
        assertEquals(AuxKind.Listen.requiredFiles, AuxLayout.importPlan(AuxKind.Listen, folder)!!.map { it.second })
    }

    @Test
    fun configRulesOutModelsTheEnginesCantRun() {
        assertNotNull(AuxLayout.configProblem(AuxKind.Listen, JSONObject().put("model_type", "moonshine"), null))
        assertNull(AuxLayout.configProblem(AuxKind.Listen, JSONObject().put("model_type", "whisper"), null))
        assertNotNull(AuxLayout.configProblem(AuxKind.Search, JSONObject().put("model_type", "xlm-roberta"), null))
        assertNotNull(AuxLayout.configProblem(AuxKind.Search, JSONObject().put("model_type", "bert"), JSONObject().put("do_lower_case", false)))
        assertNull(AuxLayout.configProblem(AuxKind.Search, JSONObject().put("model_type", "bert"), JSONObject().put("do_lower_case", true)))
        assertNotNull(AuxLayout.configProblem(AuxKind.Speak, JSONObject().put("model_type", "vits"), JSONObject().put("is_uroman", true)))
        assertNull(AuxLayout.configProblem(AuxKind.Speak, JSONObject().put("model_type", "vits"), JSONObject().put("is_uroman", false)))
    }

    @Test
    fun resolvesARepoIntoDownloadOptionsWithItsSizes() {
        val files = listOf(
            HubFile("onnx/model_quantized.onnx", 38_368_171), HubFile("onnx/model.onnx", 145_000_000),
            HubFile("vocab.json", 907), HubFile("config.json", 1_656), HubFile("README.md", 10)
        )
        val result = HubParsing.auxOptions(AuxKind.Speak, RepoRef("Xenova/mms-tts-hin"), files, configProblem = null)
        result as AuxResolution.Found
        assertEquals(2, result.options.size)
        val smallest = result.options.first()
        assertEquals(38_368_171L + 907 + 1_656, smallest.sizeBytes)
        assertTrue(smallest.id.startsWith("hf-speak-"))
        // Saved under the names the voice engine reads, whatever they're called in the repo.
        assertEquals(setOf(AuxFiles.MODEL, AuxFiles.VOCAB_JSON, AuxFiles.CONFIG), smallest.files.map { it.third }.toSet())
        assertEquals("onnx/model_quantized.onnx", smallest.files.first().first)
    }

    @Test
    fun explainsWhyARepoWithoutOnnxCantBeUsed() {
        val files = listOf(HubFile("model.safetensors", 1), HubFile("vocab.txt", 1))
        val result = HubParsing.auxOptions(AuxKind.Search, RepoRef("someone/model"), files, configProblem = null)
        assertTrue((result as AuxResolution.Unsupported).reason.contains("ONNX"))
    }
}
