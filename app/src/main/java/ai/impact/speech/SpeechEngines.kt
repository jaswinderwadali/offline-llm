package ai.impact.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Something went wrong listening or speaking; the message is shown to the user as is. */
class SpeechException(message: String) : Exception(message)

/** Turns what the user says into text. */
interface Listener {
    /**
     * Listens until the user stops talking and returns what they said, or "" when they said
     * nothing. [onLevel] gets loudness from 0 to 1; [onPartial] gets text as it's recognised.
     */
    suspend fun listen(onLevel: (Float) -> Unit, onPartial: (String) -> Unit): String

    /** Stops listening early; what's been heard so far is still returned. */
    fun finish()
    fun release()
}

/** Reads text aloud. */
interface Speaker {
    /** Returns once everything has been spoken, or as soon as [stop] is called. */
    suspend fun speak(text: String)
    fun stop()
    fun release()
}

// region The phone's own engines

/**
 * The phone's on-device recognizer. Only the offline one is used: TinyAI promises nothing leaves
 * the phone, and the ordinary recognizer may send audio to a server.
 */
class SystemListener(private val context: Context) : Listener {

    companion object {
        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var finishRequested = false

    /** One stretch of speech the recognizer heard, and when the speaker went quiet. */
    private class Segment(val text: String, val heardSpeech: Boolean, val quietSince: Long)

    /**
     * Listens until the user has been quiet for [SpeechPause.MILLIS]. Recognizers often stop at
     * the first short pause whatever they're asked, so a stop that comes sooner starts a new
     * stretch, and the words are joined into one message.
     */
    override suspend fun listen(onLevel: (Float) -> Unit, onPartial: (String) -> Unit): String = withContext(Dispatchers.Main) {
        if (!isAvailable(context)) {
            throw SpeechException("This phone has no offline speech recognizer. Download Whisper in the Models tab.")
        }
        finishRequested = false
        val parts = ArrayList<String>()
        fun joined(extra: String = "") = (parts + extra).filter { it.isNotBlank() }.joinToString(" ")
        var segment = listenOnce(onLevel, { onPartial(joined(it)) }, startWithinMillis = null)
        if (segment.text.isNotBlank()) parts.add(segment.text)
        while (parts.isNotEmpty() && !finishRequested) {
            // How much of the pause is left since the user went quiet.
            val left = SpeechPause.MILLIS - (System.currentTimeMillis() - segment.quietSince)
            if (left <= 0) break
            segment = listenOnce(onLevel, { onPartial(joined(it)) }, startWithinMillis = left)
            if (!segment.heardSpeech) break
            if (segment.text.isNotBlank()) parts.add(segment.text)
        }
        joined()
    }

    /**
     * One recognizer session. With [startWithinMillis], it gives up (returning nothing heard) if
     * the user doesn't start speaking again within that time.
     */
    private suspend fun listenOnce(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        startWithinMillis: Long?
    ): Segment {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Asked for, though many recognizers decide for themselves; the loop above covers that.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SpeechPause.MILLIS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SpeechPause.MILLIS)
        return suspendCancellableCoroutine { cont ->
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = r
            var partial = ""
            var began = false
            var quietSince = System.currentTimeMillis()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            fun done() {
                handler.removeCallbacksAndMessages(null)
                r.destroy()
                if (recognizer === r) recognizer = null
            }
            fun finishWith(segment: Segment) {
                done()
                if (cont.isActive) cont.resume(segment)
            }
            // A continuation that never starts ends the turn.
            if (startWithinMillis != null) {
                handler.postDelayed({ if (!began) finishWith(Segment("", false, quietSince)) }, startWithinMillis)
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() { began = true }
                // About -2 dB in silence to 10 dB when speaking.
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    quietSince = System.currentTimeMillis()
                    onLevel(0f)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        if (it.isNotBlank()) began = true
                        partial = it
                        onPartial(it)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial
                    finishWith(Segment(text.trim(), began || text.isNotBlank(), quietSince))
                }
                override fun onError(error: Int) {
                    if (!cont.isActive) { done(); return }
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            finishWith(Segment(partial.trim(), began && partial.isNotBlank(), quietSince))
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            done(); cont.resumeWithException(SpeechException("TinyAI needs microphone access to listen."))
                        }
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            done()
                            // Ask the phone to fetch its offline speech pack for this language.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context).triggerModelDownload(intent) }
                            }
                            cont.resumeWithException(SpeechException("Your phone is downloading its offline speech pack. Try again in a minute, or use Whisper."))
                        }
                        else -> {
                            done(); cont.resumeWithException(SpeechException("The phone's speech recognizer stopped (error $error). Try again, or use Whisper."))
                        }
                    }
                }
            })
            cont.invokeOnCancellation { handler.removeCallbacksAndMessages(null); runCatching { r.cancel(); r.destroy() } }
            r.startListening(intent)
        }
    }

    override fun finish() {
        finishRequested = true
        recognizer?.stopListening()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}

/** How long the user has to be quiet before voice mode takes it they've finished. */
object SpeechPause {
    const val MILLIS = 3_000
}

/** The phone's own voice, restricted to voices that work offline. */
class SystemSpeaker(context: Context) : Speaker {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = HashMap<String, CompletableDeferred<Unit>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { synchronized(pending) { pending.remove(utteranceId) }?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { synchronized(pending) { pending.remove(utteranceId) }?.complete(Unit) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { synchronized(pending) { pending.remove(utteranceId) }?.complete(Unit) }
        })
    }

    private var configured = false

    private fun configure() {
        if (configured) return
        configured = true
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val locale = Locale.getDefault()
        // The best installed voice for the user's language that doesn't need the internet.
        val voice = runCatching {
            tts.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
                ?.firstOrNull()
        }.getOrNull()
        if (voice != null) tts.voice = voice else tts.language = locale
    }

    suspend fun isUsable(): Boolean = ready.await()

    override suspend fun speak(text: String) {
        if (!ready.await()) throw SpeechException("The phone's voice isn't available. Download a voice in the Models tab.")
        configure()
        val chunks = SpeakableText.chunks(text)
        val last = CompletableDeferred<Unit>()
        chunks.forEachIndexed { i, chunk ->
            val id = "tinyai-${System.nanoTime()}-$i"
            if (i == chunks.lastIndex) synchronized(pending) { pending[id] = last }
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false") }
            tts.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
        }
        if (chunks.isEmpty()) return
        try {
            last.await()
        } finally {
            if (!last.isCompleted) tts.stop()
        }
    }

    override fun stop() {
        tts.stop()
        synchronized(pending) {
            pending.values.forEach { it.complete(Unit) }
            pending.clear()
        }
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}

// endregion

// region Downloaded models

/** Records from the microphone until the user stops talking. */
class MicRecorder(private val context: Context) {

    @Volatile private var finishRequested = false

    fun finish() {
        finishRequested = true
    }

    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit): FloatArray? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SpeechException("TinyAI needs microphone access to listen.")
        }
        finishRequested = false
        val rate = WhisperFeatures.SAMPLE_RATE
        val frame = rate * 30 / 1000
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuffer, frame * 4 * 4)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw SpeechException("The microphone is busy. Close other apps using it and try again.")
        }
        val detector = SpeechDetector()
        val buffer = FloatArray(frame)
        val audio = ArrayList<FloatArray>()
        // Keep a little audio from before speech was detected, so the first word isn't clipped.
        val preRoll = ArrayDeque<FloatArray>()
        try {
            record.startRecording()
            while (true) {
                coroutineContext.ensureActive()
                val read = record.read(buffer, 0, frame, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                val chunk = buffer.copyOf(read)
                val level = detector.rms(chunk)
                onLevel((level * 8).toFloat().coerceIn(0f, 1f))
                val state = if (finishRequested) SpeechDetector.State.Done else detector.feed(level)
                when (state) {
                    SpeechDetector.State.Waiting -> {
                        preRoll.addLast(chunk)
                        if (preRoll.size > 10) preRoll.removeFirst()
                    }
                    SpeechDetector.State.Speaking -> {
                        if (preRoll.isNotEmpty()) { audio.addAll(preRoll); preRoll.clear() }
                        audio.add(chunk)
                    }
                    SpeechDetector.State.Done -> {
                        audio.add(chunk)
                        break
                    }
                    SpeechDetector.State.NoSpeech -> return@withContext null
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            onLevel(0f)
        }
        val total = audio.sumOf { it.size }
        FloatArray(total).also { out -> var at = 0; audio.forEach { it.copyInto(out, at); at += it.size } }
    }
}

/** Whisper running on the phone: records, then transcribes the whole utterance. */
class WhisperListener(private val context: Context, private val dir: File) : Listener {

    private val env = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private lateinit var vocab: WhisperVocab
    private var startId = 0
    private var noTimestampsId = 0
    private var endId = 0
    private var suppress = IntArray(0)
    private var beginSuppress = IntArray(0)
    private val mic = MicRecorder(context)

    val isLoaded: Boolean get() = encoder != null

    @Synchronized
    fun load() {
        if (encoder != null) return
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        options.use {
            encoder = env.createSession(File(dir, SpeechCatalog.ENCODER).absolutePath, it)
            decoder = env.createSession(File(dir, SpeechCatalog.DECODER).absolutePath, it)
        }
        vocab = WhisperVocab.fromJson(File(dir, SpeechCatalog.VOCAB).readText())
        val gen = JSONObject(File(dir, SpeechCatalog.GENERATION).readText())
        startId = gen.getInt("decoder_start_token_id")
        noTimestampsId = gen.getInt("no_timestamps_token_id")
        // A single id in most configs, a list in some.
        endId = when (val eos = gen.opt("eos_token_id")) {
            is org.json.JSONArray -> eos.getInt(0)
            is Number -> eos.toInt()
            else -> 50256
        }
        suppress = gen.optJSONArray("suppress_tokens")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
        beginSuppress = gen.optJSONArray("begin_suppress_tokens")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
    }

    override suspend fun listen(onLevel: (Float) -> Unit, onPartial: (String) -> Unit): String {
        withContext(Dispatchers.Default) { load() }
        val audio = mic.record(onLevel) ?: return ""
        onPartial("…")
        return withContext(Dispatchers.Default) { transcribe(audio) }
    }

    /** The text Whisper hears in [audio] (16 kHz mono, up to 30 seconds). */
    @Synchronized
    fun transcribe(audio: FloatArray): String {
        load()
        val features = WhisperFeatures.compute(audio)
        val hidden = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features), longArrayOf(1, WhisperFeatures.N_MELS.toLong(), WhisperFeatures.N_FRAMES.toLong())
        ).use { input ->
            encoder!!.run(mapOf("input_features" to input)).use { out ->
                val tensor = out.get(0) as OnnxTensor
                val shape = tensor.info.shape
                val data = FloatArray(tensor.floatBuffer.remaining()).also { tensor.floatBuffer.get(it) }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            }
        }
        val tokens = arrayListOf(startId, noTimestampsId)
        hidden.use {
            for (step in 0 until 120) {
                val ids = LongArray(tokens.size) { tokens[it].toLong() }
                val next = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                    decoder!!.run(mapOf("input_ids" to input, "encoder_hidden_states" to hidden)).use { out ->
                        val logits = (out.get(0) as OnnxTensor).floatBuffer
                        val vocabSize = logits.remaining() / ids.size
                        val offset = (ids.size - 1) * vocabSize
                        val row = FloatArray(vocabSize).also { logits.position(offset); logits.get(it) }
                        for (s in suppress) if (s < vocabSize) row[s] = Float.NEGATIVE_INFINITY
                        if (step == 0) for (s in beginSuppress) if (s < vocabSize) row[s] = Float.NEGATIVE_INFINITY
                        // Timestamps are off, so never pick one.
                        for (s in noTimestampsId + 1 until vocabSize) row[s] = Float.NEGATIVE_INFINITY
                        row.indices.maxBy { row[it] }
                    }
                }
                if (next == endId) break
                tokens.add(next)
            }
        }
        return vocab.decode(tokens.drop(2))
    }

    override fun finish() = mic.finish()

    @Synchronized
    override fun release() {
        encoder?.close(); encoder = null
        decoder?.close(); decoder = null
    }
}

/** MMS-TTS running on the phone. Each sentence group plays while the next one is made. */
class MmsSpeaker(private val dir: File) : Speaker {

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private lateinit var tokenizer: MmsTokenizer
    private var sampleRate = 16_000
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    val isLoaded: Boolean get() = session != null

    @Synchronized
    fun load() {
        if (session != null) return
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        session = options.use { env.createSession(File(dir, SpeechCatalog.VOICE).absolutePath, it) }
        tokenizer = MmsTokenizer.fromJson(File(dir, SpeechCatalog.VOCAB).readText())
        sampleRate = runCatching { JSONObject(File(dir, SpeechCatalog.CONFIG).readText()).optInt("sampling_rate", 16_000) }.getOrDefault(16_000)
    }

    private fun synthesize(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        if (ids.size < 3) return FloatArray(0)
        val shape = longArrayOf(1, ids.size.toLong())
        return OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                session!!.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { out ->
                    val wave = (out.get(0) as OnnxTensor).floatBuffer
                    FloatArray(wave.remaining()).also { wave.get(it) }
                }
            }
        }
    }

    override suspend fun speak(text: String) = withContext(Dispatchers.Default) {
        load()
        stopped = false
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(sampleRate * 4)
            .build()
        track = audioTrack
        var written = 0
        try {
            audioTrack.play()
            for (chunk in SpeakableText.chunks(text)) {
                if (stopped) break
                coroutineContext.ensureActive()
                val wave = synthesize(chunk)
                // A short pause between sentence groups sounds more natural.
                val padded = wave + FloatArray(sampleRate / 6)
                // Blocking writes pace synthesis to playback, so the next group is made while this one plays.
                var at = 0
                while (at < padded.size && !stopped) {
                    val n = audioTrack.write(padded, at, padded.size - at, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    at += n
                }
                written += at
            }
            // Let the last samples play out.
            while (!stopped && audioTrack.playbackHeadPosition < written) {
                coroutineContext.ensureActive()
                kotlinx.coroutines.delay(50)
            }
        } finally {
            runCatching { audioTrack.pause(); audioTrack.flush(); audioTrack.release() }
            if (track === audioTrack) track = null
        }
    }

    override fun stop() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    @Synchronized
    override fun release() {
        stop()
        session?.close()
        session = null
    }
}

// endregion
