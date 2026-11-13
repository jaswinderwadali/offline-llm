package ai.impact.image

/** Generation defaults a model is tuned for. */
data class ImageModelDefaults(
    val steps: Int,
    val maxSteps: Int,
    /** Classifier-free guidance scale; 0 means the model is distilled and runs without it. */
    val guidance: Float,
    /** SD 2.x tokenizers (e.g. SD-Turbo) pad prompts with "!" instead of end-of-text. */
    val padWithExclamation: Boolean = false,
    val negativePrompt: String = ""
) {
    val usesGuidance: Boolean get() = guidance > 1f
}

/** A model that can be downloaded from the in-app catalog. */
data class CatalogImageModel(
    val id: String,
    val name: String,
    val description: String,
    val style: String,
    val sizeBytes: Long,
    /** Device RAM below which the model is likely to be killed by the system while loading. */
    val minRamGb: Int,
    val license: String,
    val pageUrl: String,
    val baseUrl: String,
    /** Files relative to [baseUrl] with their sizes, kept in the standard diffusers ONNX layout. */
    val files: List<Pair<String, Long>>,
    val defaults: ImageModelDefaults,
    val recommended: Boolean = false
)

/** A model that is on the device and ready to load. */
data class InstalledImageModel(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val imported: Boolean,
    val defaults: ImageModelDefaults,
    /** Hugging Face repo it was downloaded from, for models not in the catalog. */
    val sourceRepo: String? = null
)

object ImageModelCatalog {
    private const val MB = 1024L * 1024L

    val models = listOf(
        CatalogImageModel(
            id = "anything-v5-int8",
            name = "Anything V5 (int8)",
            description = "Stable Diffusion 1.5 model quantised for phone CPUs. Best balance of size, speed and quality.",
            style = "Illustration & anime",
            sizeBytes = 1_185 * MB,
            minRamGb = 6,
            license = "CreativeML OpenRAIL-M",
            pageUrl = "https://huggingface.co/latentdivergence/anything-v5-int8-onnx",
            baseUrl = "https://huggingface.co/latentdivergence/anything-v5-int8-onnx/resolve/main/",
            files = listOf(
                "unet/model.onnx" to 2 * MB,
                "unet/model_int8.onnx.data" to 861 * MB,
                "text_encoder/model.onnx" to 1 * MB,
                "text_encoder/model_int8.onnx.data" to 123 * MB,
                "vae_decoder/model.onnx" to 198 * MB,
                "tokenizer/vocab.json" to 1 * MB,
                "tokenizer/merges.txt" to 1 * MB
            ),
            defaults = ImageModelDefaults(
                steps = 20,
                maxSteps = 40,
                guidance = 7f,
                negativePrompt = "lowres, bad anatomy, bad hands, blurry, worst quality, low quality, watermark, text"
            ),
            recommended = true
        ),
        CatalogImageModel(
            id = "sd-turbo",
            name = "SD-Turbo",
            description = "Stability AI's distilled model. Makes an image in a single step. Needs a high-end phone.",
            style = "Photo & general",
            sizeBytes = 2_520 * MB,
            minRamGb = 8,
            license = "Stability AI Community License",
            pageUrl = "https://huggingface.co/v6t2b9/local-image-generator-models",
            baseUrl = "https://huggingface.co/v6t2b9/local-image-generator-models/resolve/main/sd-turbo/",
            files = listOf(
                "unet/model.onnx" to 1_730 * MB,
                "text_encoder/model.onnx" to 681 * MB,
                "vae_decoder/model.onnx" to 99 * MB,
                "tokenizer/vocab.json" to 1 * MB,
                "tokenizer/merges.txt" to 1 * MB
            ),
            defaults = ImageModelDefaults(steps = 1, maxSteps = 4, guidance = 0f, padWithExclamation = true)
        )
    )

    fun find(id: String): CatalogImageModel? = models.firstOrNull { it.id == id }

    /** Best guess for a folder the user imported, based on its name. */
    fun guessDefaults(name: String): ImageModelDefaults {
        val lower = name.lowercase()
        return when {
            "turbo" in lower || "lightning" in lower ->
                ImageModelDefaults(steps = 1, maxSteps = 8, guidance = 0f, padWithExclamation = "sd-turbo" in lower || "sd_turbo" in lower)
            "v2" in lower || "2.1" in lower || "2-1" in lower ->
                ImageModelDefaults(steps = 20, maxSteps = 50, guidance = 7.5f, padWithExclamation = true)
            else -> ImageModelDefaults(steps = 20, maxSteps = 50, guidance = 7.5f)
        }
    }
}
