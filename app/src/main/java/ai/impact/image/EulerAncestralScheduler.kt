package ai.impact.image

import java.util.Random
import kotlin.math.sqrt

/**
 * Euler ancestral sampler for epsilon-predicting Stable Diffusion models (SD 1.x, SD 2.x and
 * SD-Turbo), matching diffusers' EulerAncestralDiscreteScheduler with "trailing" timestep
 * spacing and a scaled-linear beta schedule. Trailing spacing always starts at the final
 * training timestep, which is what lets turbo models produce an image in a single step.
 */
class EulerAncestralScheduler(
    val steps: Int,
    trainSteps: Int = 1000,
    betaStart: Double = 0.00085,
    betaEnd: Double = 0.012
) {
    /** UNet timestep for each step, from noisiest to cleanest. */
    val timesteps: IntArray
    /** Noise level for each step, plus a trailing 0 for the final clean image. */
    val sigmas: DoubleArray

    init {
        require(steps in 1..trainSteps) { "steps must be between 1 and $trainSteps" }
        val sqrtStart = sqrt(betaStart)
        val sqrtEnd = sqrt(betaEnd)
        val allSigmas = DoubleArray(trainSteps)
        var alphaCumprod = 1.0
        for (t in 0 until trainSteps) {
            val beta = (sqrtStart + (sqrtEnd - sqrtStart) * t / (trainSteps - 1)).let { it * it }
            alphaCumprod *= 1.0 - beta
            allSigmas[t] = sqrt((1.0 - alphaCumprod) / alphaCumprod)
        }
        val ratio = trainSteps.toDouble() / steps
        timesteps = IntArray(steps) { i -> (Math.round(trainSteps - i * ratio) - 1).toInt().coerceIn(0, trainSteps - 1) }
        sigmas = DoubleArray(steps + 1) { i -> if (i < steps) allSigmas[timesteps[i]] else 0.0 }
    }

    /** Standard deviation of the starting noise. */
    val initNoiseSigma: Float get() = sqrt(sigmas[0] * sigmas[0] + 1.0).toFloat()

    /** Factor applied to the latents before they go into the UNet at [step]. */
    fun inputScale(step: Int): Float = (1.0 / sqrt(sigmas[step] * sigmas[step] + 1.0)).toFloat()

    /**
     * Advances [latents] in place using the UNet's noise prediction [noise], and returns the
     * model's current estimate of the clean image (useful for live previews).
     */
    fun step(noise: FloatArray, step: Int, latents: FloatArray, random: Random): FloatArray {
        val sigma = sigmas[step]
        val sigmaTo = sigmas[step + 1]
        val sigmaUp = if (sigmaTo == 0.0) 0.0 else sqrt(sigmaTo * sigmaTo * (sigma * sigma - sigmaTo * sigmaTo) / (sigma * sigma))
        val sigmaDown = sqrt((sigmaTo * sigmaTo - sigmaUp * sigmaUp).coerceAtLeast(0.0))
        val dt = (sigmaDown - sigma).toFloat()
        val up = sigmaUp.toFloat()
        val s = sigma.toFloat()

        val denoised = FloatArray(latents.size)
        for (i in latents.indices) {
            denoised[i] = latents[i] - s * noise[i]
            // derivative (x - denoised) / sigma is exactly the predicted noise.
            latents[i] = latents[i] + noise[i] * dt + (if (up > 0f) random.nextGaussian().toFloat() * up else 0f)
        }
        return denoised
    }
}
