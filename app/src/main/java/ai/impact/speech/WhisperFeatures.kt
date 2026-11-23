package ai.impact.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper's input: an 80-band log-mel spectrogram of 30 seconds of 16 kHz audio, computed exactly
 * like Hugging Face's WhisperFeatureExtractor (see WhisperFeaturesTest), since the model only
 * understands features made the way it was trained.
 */
object WhisperFeatures {

    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MELS = 80
    const val N_SAMPLES = 30 * SAMPLE_RATE
    const val N_FRAMES = N_SAMPLES / HOP
    private const val N_BINS = N_FFT / 2 + 1

    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }
    private val cosTable = Array(N_BINS) { k -> DoubleArray(N_FFT) { n -> cos(2 * PI * k * n / N_FFT) } }
    private val sinTable = Array(N_BINS) { k -> DoubleArray(N_FFT) { n -> sin(2 * PI * k * n / N_FFT) } }
    private val filters: Array<DoubleArray> = melFilters()

    /**
     * The model's input_features, [N_MELS] rows of [N_FRAMES] values, flattened row by row.
     * Audio longer than 30 seconds is cut; shorter is padded with silence.
     */
    fun compute(audio: FloatArray): FloatArray {
        val samples = FloatArray(N_SAMPLES)
        audio.copyInto(samples, 0, 0, min(audio.size, N_SAMPLES))
        // Frames past the audio see only padding: their spectrum is zero, so they're skipped.
        val lastAudioFrame = min(N_FRAMES - 1, (min(audio.size, N_SAMPLES) + N_FFT / 2) / HOP + 1)

        val logMel = Array(N_MELS) { DoubleArray(N_FRAMES) }
        val frame = DoubleArray(N_FFT)
        val power = DoubleArray(N_BINS)
        val pad = N_FFT / 2
        for (t in 0 until N_FRAMES) {
            if (t <= lastAudioFrame) {
                // Centered frames with reflect padding at the edges, like librosa and torch.stft.
                for (n in 0 until N_FFT) {
                    frame[n] = reflect(samples, t * HOP + n - pad) * window[n]
                }
                for (k in 0 until N_BINS) {
                    var re = 0.0
                    var im = 0.0
                    val c = cosTable[k]
                    val s = sinTable[k]
                    for (n in 0 until N_FFT) {
                        re += frame[n] * c[n]
                        im -= frame[n] * s[n]
                    }
                    power[k] = re * re + im * im
                }
            } else {
                // Silence: every band is at the floor, log10(1e-10).
                for (m in 0 until N_MELS) logMel[m][t] = -10.0
                continue
            }
            for (m in 0 until N_MELS) {
                var sum = 0.0
                val f = filters[m]
                for (k in 0 until N_BINS) sum += f[k] * power[k]
                logMel[m][t] = log10(max(sum, 1e-10))
            }
        }
        var top = Double.NEGATIVE_INFINITY
        for (row in logMel) for (v in row) if (v > top) top = v
        val floor = top - 8.0
        val out = FloatArray(N_MELS * N_FRAMES)
        for (m in 0 until N_MELS) {
            for (t in 0 until N_FRAMES) {
                out[m * N_FRAMES + t] = ((max(logMel[m][t], floor) + 4.0) / 4.0).toFloat()
            }
        }
        return out
    }

    private fun reflect(x: FloatArray, i: Int): Double {
        val n = x.size
        val j = when {
            i < 0 -> -i
            i >= n -> 2 * (n - 1) - i
            else -> i
        }
        return x[j.coerceIn(0, n - 1)].toDouble()
    }

    // Slaney-style mel scale and normalisation, as Whisper's preprocessing uses.
    private fun hzToMel(hz: Double): Double {
        val fMin = 0.0
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = (minLogHz - fMin) / fSp
        val logStep = ln(6.4) / 27.0
        return if (hz < minLogHz) (hz - fMin) / fSp else minLogMel + ln(hz / minLogHz) / logStep
    }

    private fun melToHz(mel: Double): Double {
        val fSp = 200.0 / 3
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0
        return if (mel < minLogMel) fSp * mel else minLogHz * kotlin.math.exp(logStep * (mel - minLogMel))
    }

    private fun melFilters(): Array<DoubleArray> {
        val fftFreqs = DoubleArray(N_BINS) { it * (SAMPLE_RATE / 2.0) / (N_BINS - 1) }
        val melMin = hzToMel(0.0)
        val melMax = hzToMel(8000.0)
        val melPoints = DoubleArray(N_MELS + 2) { melMin + it * (melMax - melMin) / (N_MELS + 1) }
        val hz = DoubleArray(N_MELS + 2) { melToHz(melPoints[it]) }
        return Array(N_MELS) { m ->
            val lower = hz[m]
            val center = hz[m + 1]
            val upper = hz[m + 2]
            val norm = 2.0 / (upper - lower)
            DoubleArray(N_BINS) { k ->
                val f = fftFreqs[k]
                val down = (f - lower) / (center - lower)
                val up = (upper - f) / (upper - center)
                max(0.0, min(down, up)) * norm
            }
        }
    }
}
