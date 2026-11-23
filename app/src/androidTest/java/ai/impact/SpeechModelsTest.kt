package ai.impact

import ai.impact.speech.MmsSpeaker
import ai.impact.speech.SpeechCatalog
import ai.impact.speech.WhisperListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the downloaded speech models on the phone: the voice reads a sentence aloud into memory,
 * and Whisper transcribes it back. Skipped unless both models were downloaded in the app.
 */
@RunWith(AndroidJUnit4::class)
class SpeechModelsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun modelDir(id: String) = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "speech_models/$id")

    private fun installed(id: String): File? {
        val dir = modelDir(id)
        val model = SpeechCatalog.find(id) ?: return null
        return dir.takeIf { d -> File(d, ".complete").isFile && model.files.all { File(d, it.path).length() == it.size } }
    }

    @Test
    fun voiceAndWhisperRoundTrip() {
        val voiceDir = installed("mms-tts-eng")
        val whisperDir = installed("whisper-tiny-en") ?: installed("whisper-base-en")
        assumeTrue("Download the MMS voice and a Whisper model in the app first", voiceDir != null && whisperDir != null)

        val speaker = MmsSpeaker(voiceDir!!)
        val synth = MmsSpeaker::class.java.getDeclaredMethod("synthesize", String::class.java).apply { isAccessible = true }
        speaker.load()
        val started = System.currentTimeMillis()
        val wave = synth.invoke(speaker, "The emergency radio code is blue heron.") as FloatArray
        val synthMs = System.currentTimeMillis() - started
        assertTrue("voice produced ${wave.size} samples", wave.size > 16_000)

        val listener = WhisperListener(context, whisperDir!!)
        val t0 = System.currentTimeMillis()
        val text = listener.transcribe(wave)
        val transcribeMs = System.currentTimeMillis() - t0
        android.util.Log.i("SpeechModelsTest", "synth=${synthMs}ms transcribe=${transcribeMs}ms text=\"$text\"")
        assertTrue("Whisper heard \"$text\"", text.contains("emergency", ignoreCase = true) && text.contains("radio", ignoreCase = true))
        listener.release()
        speaker.release()
    }
}
