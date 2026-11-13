package ai.impact.image

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

data class GenerationRequest(
    /** What the image model is given: the user's prompt, or the text model's enhanced version of it. */
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val guidance: Float,
    val width: Int,
    val height: Int,
    val seed: Long,
    /** The user's own words when [prompt] is an enhanced rewrite of them. */
    val originalPrompt: String? = null
)

/** Where a generation is; [preview] is a low-resolution look at the image forming. */
sealed interface GenerationPhase {
    /** The text model is rewriting the prompt (happens before the image model starts). */
    data object EnhancingPrompt : GenerationPhase
    data object EncodingPrompt : GenerationPhase
    data class Denoising(val step: Int, val total: Int, val preview: Bitmap?) : GenerationPhase
    data object Decoding : GenerationPhase
}

class UnsupportedModelException(message: String) : Exception(message)

/**
 * Text-to-image with a Stable Diffusion model exported to ONNX in the standard diffusers
 * layout (`text_encoder/`, `unet/`, `vae_decoder/`, `tokenizer/`), running on the CPU.
 *
 * Exports differ in tensor types (fp32 vs fp16, int32 vs int64 ids, scalar vs batched
 * timesteps) and in whether shapes are fixed, so the pipeline reads them from each session
 * instead of assuming one export.
 */
class StableDiffusionPipeline private constructor(
    private val env: OrtEnvironment,
    private val textEncoder: OrtSession,
    private val unet: OrtSession,
    private val vaeDecoder: OrtSession,
    private val tokenizer: ClipTokenizer,
    private val vaeScale: Float
) : Closeable {

    companion object {
        private const val TAG = "SDPipeline"
        const val DEFAULT_SIZE = 512
        private const val LATENT_CHANNELS = 4

        val REQUIRED_FILES = listOf(
            "text_encoder/model.onnx",
            "unet/model.onnx",
            "vae_decoder/model.onnx",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt"
        )

        fun missingFiles(dir: File): List<String> = REQUIRED_FILES.filterNot { File(dir, it).isFile }

        // Linear map from SD latent channels to RGB, close enough for a live preview.
        private val PREVIEW_FACTORS = arrayOf(
            floatArrayOf(0.3512f, 0.2297f, 0.3227f),
            floatArrayOf(0.3250f, 0.4974f, 0.2350f),
            floatArrayOf(-0.2829f, 0.1762f, 0.2721f),
            floatArrayOf(-0.2120f, -0.2616f, -0.7177f)
        )

        fun load(dir: File, defaults: ImageModelDefaults): StableDiffusionPipeline {
            val missing = missingFiles(dir)
            if (missing.isNotEmpty()) {
                throw UnsupportedModelException("This model folder is missing ${missing.joinToString()}.")
            }
            val env = OrtEnvironment.getEnvironment()
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            fun open(path: String): OrtSession {
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                return options.use { env.createSession(File(dir, path).absolutePath, it) }
            }
            val opened = mutableListOf<OrtSession>()
            try {
                val text = open("text_encoder/model.onnx").also(opened::add)
                val unet = open("unet/model.onnx").also(opened::add)
                val vae = open("vae_decoder/model.onnx").also(opened::add)
                val tokenizer = ClipTokenizer.load(File(dir, "tokenizer"), defaults.padWithExclamation)
                val scale = readVaeScale(File(dir, "vae_decoder/config.json"))
                return StableDiffusionPipeline(env, text, unet, vae, tokenizer, scale).also { it.validate() }
            } catch (e: Throwable) {
                opened.forEach { runCatching { it.close() } }
                throw e
            }
        }

        private fun readVaeScale(config: File): Float = try {
            if (config.isFile) JSONObject(config.readText()).optDouble("scaling_factor", 0.18215).toFloat() else 0.18215f
        } catch (e: Exception) {
            0.18215f
        }
    }

    private val unetInputs: Map<String, NodeInfo> = unet.inputInfo
    private val sampleName = unetInputs.keys.firstOrNull { it == "sample" } ?: unetInputs.keys.first()
    private val timestepName = unetInputs.keys.firstOrNull { it == "timestep" || it == "t" }
        ?: unetInputs.keys.elementAtOrNull(1) ?: "timestep"
    private val hiddenName = unetInputs.keys.firstOrNull { it == "encoder_hidden_states" }
        ?: unetInputs.keys.elementAtOrNull(2) ?: "encoder_hidden_states"

    private fun tensorInfo(info: NodeInfo?): TensorInfo =
        info?.info as? TensorInfo ?: throw UnsupportedModelException("Unexpected model input ${info?.name}.")

    private val sampleInfo = tensorInfo(unetInputs[sampleName])
    private val timestepInfo = tensorInfo(unetInputs[timestepName])
    private val hiddenInfo = tensorInfo(unetInputs[hiddenName])

    /** Whether the UNet accepts a batch of 2 so guidance runs in one pass. */
    private val batchedGuidance = sampleInfo.shape.firstOrNull()?.let { it <= 0 || it == 2L } == true

    /** Image size the model was exported for, or null if any multiple of 64 works. */
    val fixedSize: Pair<Int, Int>? = sampleInfo.shape.let { shape ->
        val h = shape.getOrNull(2) ?: -1
        val w = shape.getOrNull(3) ?: -1
        if (h > 0 && w > 0) (w * 8).toInt() to (h * 8).toInt() else null
    }

    @Volatile
    private var runOptions: OrtSession.RunOptions? = null
    @Volatile
    private var cancelled = false

    private fun validate() {
        val extra = unetInputs.keys - setOf(sampleName, timestepName, hiddenName)
        if (extra.isNotEmpty()) {
            throw UnsupportedModelException(
                "This model needs extra inputs (${extra.joinToString()}), so it isn't a Stable Diffusion 1.x/2.x model. " +
                    "SDXL and LCM models aren't supported yet."
            )
        }
        if (sampleInfo.shape.getOrNull(1)?.let { it > 0 && it != LATENT_CHANNELS.toLong() } == true) {
            throw UnsupportedModelException("This model's UNet doesn't take 4-channel latents, so it isn't supported.")
        }
    }

    /** Aborts the running generation; [generate] then throws [CancellationException]. */
    fun cancel() {
        cancelled = true
        runOptions?.let { runCatching { it.setTerminate(true) } }
    }

    fun generate(request: GenerationRequest, onPhase: (GenerationPhase) -> Unit): Bitmap {
        cancelled = false
        val options = OrtSession.RunOptions()
        runOptions = options
        try {
            return run(request, options, onPhase)
        } catch (e: OrtException) {
            if (cancelled) throw CancellationException("Generation cancelled")
            throw e
        } finally {
            runOptions = null
            options.close()
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("Generation cancelled")
    }

    private fun run(request: GenerationRequest, options: OrtSession.RunOptions, onPhase: (GenerationPhase) -> Unit): Bitmap {
        val (width, height) = fixedSize ?: (request.width to request.height)
        val latentW = width / 8
        val latentH = height / 8
        val latentSize = LATENT_CHANNELS * latentW * latentH
        val guided = request.guidance > 1f
        val random = Random(request.seed)

        onPhase(GenerationPhase.EncodingPrompt)
        val cond = encode(request.prompt, options)
        val uncond = if (guided) encode(request.negativePrompt, options) else null
        checkCancelled()

        val scheduler = EulerAncestralScheduler(request.steps)
        val latents = FloatArray(latentSize) { random.nextGaussian().toFloat() * scheduler.initNoiseSigma }

        for (step in 0 until scheduler.steps) {
            onPhase(GenerationPhase.Denoising(step, scheduler.steps, null))
            val scale = scheduler.inputScale(step)
            val input = FloatArray(latentSize) { latents[it] * scale }
            val timestep = scheduler.timesteps[step]

            val noise = if (uncond == null) {
                predictNoise(input, 1, latentW, latentH, timestep, cond.values, cond.shape, options)
            } else if (batchedGuidance) {
                val both = predictNoise(
                    input + input, 2, latentW, latentH, timestep, uncond.values + cond.values,
                    longArrayOf(2, cond.shape[1], cond.shape[2]), options
                )
                guide(both.copyOfRange(0, latentSize), both.copyOfRange(latentSize, latentSize * 2), request.guidance)
            } else {
                val noUncond = predictNoise(input, 1, latentW, latentH, timestep, uncond.values, uncond.shape, options)
                checkCancelled()
                val noCond = predictNoise(input, 1, latentW, latentH, timestep, cond.values, cond.shape, options)
                guide(noUncond, noCond, request.guidance)
            }
            checkCancelled()
            val denoised = scheduler.step(noise, step, latents, random)
            if (step < scheduler.steps - 1) {
                onPhase(GenerationPhase.Denoising(step + 1, scheduler.steps, previewOf(denoised, latentW, latentH)))
            }
        }

        onPhase(GenerationPhase.Decoding)
        val image = decode(latents, latentW, latentH, options)
        checkCancelled()
        return image
    }

    private fun guide(uncond: FloatArray, cond: FloatArray, scale: Float): FloatArray =
        FloatArray(cond.size) { uncond[it] + scale * (cond[it] - uncond[it]) }

    private class Embedding(val values: FloatArray, val shape: LongArray)

    private fun encode(text: String, options: OrtSession.RunOptions): Embedding {
        val ids = tokenizer.encode(text)
        val inputName = textEncoder.inputInfo.keys.first()
        val type = tensorInfo(textEncoder.inputInfo[inputName]).type
        val shape = longArrayOf(1, ids.size.toLong())
        val tensor = when (type) {
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(ids), shape)
            else -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape)
        }
        tensor.use {
            textEncoder.run(mapOf(inputName to it), options).use { result ->
                val output = (result.get("last_hidden_state").orElse(null) ?: result.get(0)) as OnnxTensor
                return Embedding(readFloats(output), output.info.shape)
            }
        }
    }

    private fun predictNoise(
        sample: FloatArray,
        batch: Int,
        latentW: Int,
        latentH: Int,
        timestep: Int,
        hidden: FloatArray,
        hiddenShape: LongArray,
        options: OrtSession.RunOptions
    ): FloatArray {
        val sampleShape = longArrayOf(batch.toLong(), LATENT_CHANNELS.toLong(), latentH.toLong(), latentW.toLong())
        val timestepShape = if (timestepInfo.shape.isEmpty()) longArrayOf() else longArrayOf(batch.toLong())
        val timestepCount = if (timestepShape.isEmpty()) 1 else batch

        val inputs = linkedMapOf(
            sampleName to floatTensor(sample, sampleShape, sampleInfo.type),
            timestepName to timestepTensor(timestep, timestepCount, timestepShape),
            hiddenName to floatTensor(hidden, hiddenShape, hiddenInfo.type)
        )
        try {
            unet.run(inputs, options).use { result -> return readFloats(result.get(0) as OnnxTensor) }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun decode(latents: FloatArray, latentW: Int, latentH: Int, options: OrtSession.RunOptions): Bitmap {
        val inputName = vaeDecoder.inputInfo.keys.first()
        val type = tensorInfo(vaeDecoder.inputInfo[inputName]).type
        val scaled = FloatArray(latents.size) { latents[it] / vaeScale }
        floatTensor(scaled, longArrayOf(1, LATENT_CHANNELS.toLong(), latentH.toLong(), latentW.toLong()), type).use { tensor ->
            vaeDecoder.run(mapOf(inputName to tensor), options).use { result ->
                val output = result.get(0) as OnnxTensor
                val shape = output.info.shape
                val h = shape[2].toInt()
                val w = shape[3].toInt()
                return toBitmap(readFloats(output), w, h)
            }
        }
    }

    private fun toBitmap(chw: FloatArray, width: Int, height: Int): Bitmap {
        val plane = width * height
        val pixels = IntArray(plane) { i ->
            val r = channel(chw[i])
            val g = channel(chw[plane + i])
            val b = channel(chw[2 * plane + i])
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun channel(v: Float): Int = ((v / 2f + 0.5f).coerceIn(0f, 1f) * 255f).roundToInt()

    private fun previewOf(latents: FloatArray, width: Int, height: Int): Bitmap? = try {
        val plane = width * height
        val pixels = IntArray(plane) { i ->
            var r = 0f
            var g = 0f
            var b = 0f
            for (c in 0 until LATENT_CHANNELS) {
                val v = latents[c * plane + i]
                r += v * PREVIEW_FACTORS[c][0]
                g += v * PREVIEW_FACTORS[c][1]
                b += v * PREVIEW_FACTORS[c][2]
            }
            (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        Log.w(TAG, "Preview failed", e)
        null
    }

    private fun floatTensor(data: FloatArray, shape: LongArray, type: OnnxJavaType): OnnxTensor = when (type) {
        OnnxJavaType.FLOAT16 -> {
            val buffer = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
            val shorts = buffer.asShortBuffer()
            for (v in data) shorts.put(Half.fromFloat(v))
            OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.FLOAT16)
        }
        OnnxJavaType.FLOAT -> OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        else -> throw UnsupportedModelException("Model input type $type isn't supported.")
    }

    private fun timestepTensor(timestep: Int, count: Int, shape: LongArray): OnnxTensor = when (timestepInfo.type) {
        OnnxJavaType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(count) { timestep.toLong() }), shape)
        OnnxJavaType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(IntArray(count) { timestep }), shape)
        else -> floatTensor(FloatArray(count) { timestep.toFloat() }, shape, timestepInfo.type)
    }

    private fun readFloats(tensor: OnnxTensor): FloatArray = when (tensor.info.type) {
        OnnxJavaType.FLOAT16 -> {
            val shorts = tensor.shortBuffer
            FloatArray(shorts.remaining()) { Half.toFloat(shorts.get(it)) }
        }
        else -> {
            val floats = tensor.floatBuffer
            FloatArray(floats.remaining()).also { floats.get(it) }
        }
    }

    override fun close() {
        listOf(textEncoder, unet, vaeDecoder).forEach { runCatching { it.close() } }
    }
}
