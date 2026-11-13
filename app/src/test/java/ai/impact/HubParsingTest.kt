package ai.impact

import ai.impact.image.GgufResolution
import ai.impact.image.HubFile
import ai.impact.image.HubParsing
import ai.impact.image.HubResolution
import ai.impact.image.RepoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubParsingTest {

    @Test
    fun parsesRepoNamesAndLinks() {
        assertEquals(RepoRef("owner/model"), HubParsing.parseRepoReference("owner/model"))
        assertEquals(RepoRef("owner/model"), HubParsing.parseRepoReference("https://huggingface.co/owner/model"))
        assertEquals(RepoRef("owner/model"), HubParsing.parseRepoReference("huggingface.co/owner/model/?x=1#readme"))
        assertEquals(
            RepoRef("v6t2b9/local-image-generator-models", "main", "sd-turbo/"),
            HubParsing.parseRepoReference("https://huggingface.co/v6t2b9/local-image-generator-models/tree/main/sd-turbo")
        )
        // A link to a file inside a component folder points at the model root.
        assertEquals(
            RepoRef("v6t2b9/local-image-generator-models", "main", "sd-turbo/"),
            HubParsing.parseRepoReference("https://huggingface.co/v6t2b9/local-image-generator-models/blob/main/sd-turbo/unet/model.onnx")
        )
        assertEquals(RepoRef("a/b", "v1.0", ""), HubParsing.parseRepoReference("https://hf.co/a/b/tree/v1.0"))
    }

    @Test
    fun rejectsThingsThatAreNotModelRepos() {
        assertNull(HubParsing.parseRepoReference("anime"))
        assertNull(HubParsing.parseRepoReference("stable diffusion/onnx"))
        assertNull(HubParsing.parseRepoReference("a/b/c"))
        assertNull(HubParsing.parseRepoReference("https://huggingface.co/datasets/a/b"))
        assertNull(HubParsing.parseRepoReference("https://huggingface.co/spaces/a/b"))
    }

    @Test
    fun findsModelInSubfolderAndSkipsUnneededFiles() {
        val files = listOf(
            HubFile("README.md", 2_000),
            HubFile("runtime/ort-1.27.0/ort.wasm", 24_000_000),
            HubFile("sd-turbo/text_encoder/model.onnx", 681_342_985),
            HubFile("sd-turbo/tokenizer/vocab.json", 1_059_962),
            HubFile("sd-turbo/tokenizer/merges.txt", 525_000),
            HubFile("sd-turbo/unet/model.onnx", 1_733_131_719),
            HubFile("sd-turbo/unet/diffusion_pytorch_model.safetensors", 3_400_000_000),
            HubFile("sd-turbo/vae_decoder/model.onnx", 99_126_105),
            HubFile("sd-turbo/LICENSE.md", 11_900)
        )
        val result = HubParsing.findCandidates(RepoRef("v6t2b9/local-image-generator-models"), files)
        assertTrue(result is HubResolution.Found)
        val candidate = (result as HubResolution.Found).candidates.single()
        assertEquals("sd-turbo/", candidate.root)
        assertEquals(
            setOf("text_encoder/model.onnx", "tokenizer/vocab.json", "tokenizer/merges.txt", "unet/model.onnx", "vae_decoder/model.onnx"),
            candidate.files.map { it.path }.toSet()
        )
        assertEquals("local-image-generator-models · sd-turbo", candidate.name)
    }

    @Test
    fun keepsExternalWeightFiles() {
        val files = listOf(
            HubFile("text_encoder/model.onnx", 419_223),
            HubFile("text_encoder/model_int8.onnx.data", 123_424_512),
            HubFile("tokenizer/vocab.json", 1_109_372),
            HubFile("tokenizer/merges.txt", 574_000),
            HubFile("tokenizer/special_tokens_map.json", 574),
            HubFile("unet/model.onnx", 1_891_087),
            HubFile("unet/model_int8.onnx.data", 860_852_480),
            HubFile("vae_decoder/model.onnx", 198_078_197),
            HubFile("vae_encoder/model.onnx", 136_756_675)
        )
        val found = HubParsing.findCandidates(RepoRef("latentdivergence/anything-v5-int8-onnx"), files) as HubResolution.Found
        val paths = found.candidates.single().files.map { it.path }
        assertTrue("unet/model_int8.onnx.data" in paths)
        assertTrue("text_encoder/model_int8.onnx.data" in paths)
        assertTrue("tokenizer/special_tokens_map.json" in paths)
        // The VAE encoder is only for image-to-image, so it isn't downloaded.
        assertTrue(paths.none { it.startsWith("vae_encoder/") })
    }

    @Test
    fun explainsWhyRepoCantBeUsed() {
        val sdxl = listOf(
            HubFile("text_encoder/model.onnx", 1), HubFile("text_encoder_2/model.onnx", 1),
            HubFile("tokenizer/vocab.json", 1), HubFile("tokenizer/merges.txt", 1),
            HubFile("unet/model.onnx", 1), HubFile("vae_decoder/model.onnx", 1)
        )
        assertReason(sdxl, "SDXL")
        assertReason(listOf(HubFile("qwen2.5-1.5b-instruct-q4_k_m.gguf", 1)), "GGUF")
        assertReason(listOf(HubFile("unet/diffusion_pytorch_model.safetensors", 1)), "isn't converted to ONNX")
        assertReason(listOf(HubFile("unet/model.onnx", 1), HubFile("tokenizer/vocab.json", 1)), "incomplete")
    }

    @Test
    fun flagsLikelyUnsupportedNames() {
        assertEquals("SDXL", HubParsing.unsupportedReason("stabilityai/sdxl-turbo"))
        assertEquals("SDXL", HubParsing.unsupportedReason("stabilityai/stable-diffusion-xl-base-1.0"))
        assertEquals("LCM", HubParsing.unsupportedReason("latentdivergence/lcm-dreamshaper-int8-onnx"))
        assertEquals("Inpainting", HubParsing.unsupportedReason("latentdivergence/realvis6-inpaint-int8-onnx"))
        assertNull(HubParsing.unsupportedReason("latentdivergence/dreamshaper-8-int8-onnx"))
        assertNull(HubParsing.unsupportedReason("v6t2b9/local-image-generator-models"))
    }

    @Test
    fun listsLoadableGgufFilesSmallestFirst() {
        val files = listOf(
            HubFile("Qwen3-4B-Q4_K_M.gguf", 2_497_281_312),
            HubFile("Qwen3-4B-Q2_K.gguf", 1_669_500_192),
            HubFile("Qwen3-4B-UD-Q4_K_XL.gguf", 2_546_341_152),
            HubFile("mmproj-F16.gguf", 800_000_000),
            HubFile("big/Qwen3-4B-BF16-00001-of-00002.gguf", 4_000_000_000),
            HubFile("README.md", 1_000)
        )
        val found = HubParsing.ggufFiles(RepoRef("unsloth/Qwen3-4B-GGUF"), files) as GgufResolution.Found
        assertEquals(listOf("Q2_K", "Q4_K_M", "UD-Q4_K_XL"), found.files.map { it.quant })
    }

    @Test
    fun readsQuantFromLowercaseNames() {
        val found = HubParsing.ggufFiles(
            RepoRef("Qwen/Qwen2.5-1.5B-Instruct-GGUF"),
            listOf(HubFile("qwen2.5-1.5b-instruct-q4_k_m.gguf", 1), HubFile("qwen2.5-1.5b-instruct-fp16.gguf", 2))
        ) as GgufResolution.Found
        assertEquals(listOf("Q4_K_M", "FP16"), found.files.map { it.quant })
    }

    @Test
    fun explainsUnusableChatRepos() {
        val split = HubParsing.ggufFiles(RepoRef("a/b"), listOf(HubFile("m-00001-of-00002.gguf", 1), HubFile("m-00002-of-00002.gguf", 1)))
        assertTrue((split as GgufResolution.Unsupported).reason.contains("split"))
        val image = HubParsing.ggufFiles(RepoRef("a/b"), listOf(HubFile("unet/model.onnx", 1)))
        assertTrue((image as GgufResolution.Unsupported).reason.contains("image model"))
        val raw = HubParsing.ggufFiles(RepoRef("a/b"), listOf(HubFile("model.safetensors", 1)))
        assertTrue((raw as GgufResolution.Unsupported).reason.contains("GGUF"))
    }

    @Test
    fun readsParameterCountFromNames() {
        assertEquals(1.5, HubParsing.parameterBillions("Qwen/Qwen2.5-1.5B-Instruct-GGUF")!!, 0.0)
        assertEquals(27.0, HubParsing.parameterBillions("x/Qwen3.8-27B-Uncensored-GGUF")!!, 0.0)
        assertEquals(35.0, HubParsing.parameterBillions("empero-ai/Qwen3.8-35B-A3B-Distill-GGUF")!!, 0.0)
        assertNull(HubParsing.parameterBillions("TheBloke/tinyllama-chat-GGUF"))
        assertNull(HubParsing.chatUnsupportedReason("Qwen/Qwen2.5-3B-Instruct-GGUF"))
        assertTrue(HubParsing.chatUnsupportedReason("Qwen/Qwen2.5-7B-Instruct-GGUF")!!.contains("too big"))
    }

    private fun assertReason(files: List<HubFile>, expected: String) {
        val result = HubParsing.findCandidates(RepoRef("a/b"), files)
        assertTrue("expected Unsupported for $files", result is HubResolution.Unsupported)
        val reason = (result as HubResolution.Unsupported).reason
        assertTrue("\"$reason\" should mention $expected", reason.contains(expected))
    }
}
