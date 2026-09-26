package io.github.lesj0610.hermes.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The phone's own text-to-speech engine, fed a sentence at a time.
 *
 * Speech is grouped by a token — one per reply — so the engine's callbacks can
 * be matched to what is still wanted: [begin] starts a new group and silences
 * the last one, and a callback that arrives for an older group is ignored. That
 * is what stops a cancelled reply from finishing its sentence, or reporting
 * itself done and reopening the microphone over the next one.
 *
 * All public methods and callbacks are on the main thread; the engine's own
 * callbacks arrive on a binder thread and are posted over.
 *
 * Which engine and voice speak is the system's (or the one chosen in settings),
 * and some voices are synthesised over the network, so nothing here assumes
 * speech is offline or instant.
 */
class SpeechOut(private val context: Context) {

    /** What the engine reported about itself, for the settings page. */
    data class Info(
        val ready: Boolean = false,
        /** Installed engines as (package, label). */
        val engines: List<Pair<String, String>> = emptyList(),
        val defaultEngine: String = "",
        /** Why the configured language cannot be spoken, when the engine said so. */
        val fault: VoiceFault? = null,
    )

    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info.asStateFlow()

    /** The engine has spoken every sentence of [token] after [finish]. */
    var onDrained: ((token: Long) -> Unit)? = null

    /** Speech for [token] cannot happen; the reason is for the user. */
    var onFailed: ((token: Long, fault: VoiceFault) -> Unit)? = null

    /** The first sound of [token] — a latency mark. */
    var onFirstAudio: ((token: Long) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    private var tts: TextToSpeech? = null
    private var engineState = EngineState.Off
    private var engineFault: VoiceFault? = null

    private var engine = ""
    private var locale: Locale = Locale.getDefault()
    private var rate = 1f
    private var pitch = 1f

    private var token = NONE
    /** The current group has drained, failed or been cancelled: nothing is being spoken. */
    private var groupDone = true
    /** An engine chosen while a group was playing, applied once it is over. */
    private var pendingEngine: String? = null
    private var sequence = 0
    private var outstanding = 0
    private var finished = false
    private var heard = false
    private val waiting = ArrayList<String>()
    private var focus: AudioFocusRequest? = null

    private enum class EngineState { Off, Starting, Ready, Failed }

    /**
     * Rate, pitch and language apply to the next sentence. A different engine
     * applies once nothing is being spoken: shutting the current one down
     * mid-reply would strand the sentences it was given — a stopped engine
     * never reports them done — and the reply would never drain.
     */
    fun configure(locale: Locale, rate: Float, pitch: Float, engine: String) {
        this.rate = rate
        this.pitch = pitch
        val relocalised = locale != this.locale
        this.locale = locale
        pendingEngine = engine.takeIf { it != this.engine }
        if (pendingEngine != null && groupDone) {
            applyPendingEngine()
            return
        }
        tts?.takeIf { engineState == EngineState.Ready }?.let { current ->
            current.setSpeechRate(rate)
            current.setPitch(pitch)
            if (relocalised) {
                val spoken = applyLanguage(current)
                if (!spoken) engineState = EngineState.Failed
                _info.value = _info.value.copy(ready = spoken, fault = engineFault)
            }
        }
    }

    /** Starts a new group of speech and silences whatever was playing. */
    fun begin(token: Long) {
        silence()
        applyPendingEngine()
        // A failure is not permanent — voice data can be installed, another
        // engine chosen — so each new reply gives the engine another start.
        if (engineState == EngineState.Failed) shutdownEngine()
        this.token = token
        groupDone = false
        sequence = 0
        outstanding = 0
        finished = false
        heard = false
    }

    fun enqueue(token: Long, sentence: String) {
        if (token != this.token || sentence.isBlank()) return
        when (engineState) {
            EngineState.Ready -> speakNow(sentence)
            EngineState.Failed -> fail(engineFault ?: VoiceFault.SpeechEngine)
            else -> {
                waiting += sentence
                ensureEngine()
            }
        }
    }

    /** No more sentences for [token]: report it drained once they are spoken. */
    fun finish(token: Long) {
        if (token != this.token) return
        finished = true
        drainedIfDone()
    }

    /** Stops speaking and forgets the current group. */
    fun cancel() {
        silence()
        token = NONE
        groupDone = true
        applyPendingEngine()
    }

    fun release() {
        cancel()
        shutdownEngine()
    }

    /** Starts the engine ahead of need, so settings can report on it. */
    fun warmUp() = ensureEngine()

    private fun silence() {
        waiting.clear()
        if (outstanding > 0 || engineState == EngineState.Ready) tts?.stop()
        outstanding = 0
        finished = false
        abandonFocus()
    }

    private fun speakNow(sentence: String) {
        val engine = tts ?: return
        requestFocus()
        val id = "$token:${sequence++}"
        outstanding++
        val result = engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            outstanding--
            fail(VoiceFault.Speech)
        }
    }

    private fun drainedIfDone() {
        if (!finished || outstanding > 0 || waiting.isNotEmpty()) return
        val done = token
        finished = false
        groupDone = true
        abandonFocus()
        // Posted, never called in place: the listener reacting to "drained" may
        // start listening, and doing that inside finish() would re-enter the
        // conversation loop mid-step.
        main.post { if (done == token) onDrained?.invoke(done) }
    }

    private fun fail(fault: VoiceFault) {
        val failed = token
        silence()
        token = NONE
        groupDone = true
        main.post { onFailed?.invoke(failed, fault) }
    }

    private fun ensureEngine() {
        if (engineState == EngineState.Starting || engineState == EngineState.Ready) return
        engineState = EngineState.Starting
        engineFault = null
        // Bound to the instance it was made for: an engine replaced while it was
        // still starting must not report the replacement ready.
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            main.post { if (created != null && created === tts) onEngineReady(status) }
        }
        created = if (engine.isBlank()) TextToSpeech(context, listener) else TextToSpeech(context, listener, engine)
        tts = created
    }

    private fun onEngineReady(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            engineState = EngineState.Failed
            engineFault = VoiceFault.SpeechEngine
            _info.value = Info(fault = VoiceFault.SpeechEngine)
            if (waiting.isNotEmpty()) fail(VoiceFault.SpeechEngine)
            return
        }
        engine.setAudioAttributes(ATTRIBUTES)
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        engine.setOnUtteranceProgressListener(progress)
        val spoken = applyLanguage(engine)
        _info.value = Info(
            ready = spoken,
            engines = runCatching { engine.engines.map { it.name to it.label } }.getOrDefault(emptyList()),
            defaultEngine = runCatching { engine.defaultEngine.orEmpty() }.getOrDefault(""),
            fault = engineFault,
        )
        if (!spoken) {
            engineState = EngineState.Failed
            if (waiting.isNotEmpty()) fail(engineFault ?: VoiceFault.SpeechLanguage)
            return
        }
        engineState = EngineState.Ready
        val queued = waiting.toList()
        waiting.clear()
        queued.forEach(::speakNow)
        drainedIfDone()
    }

    /** Sets the language, recording why when the engine cannot speak it. */
    private fun applyLanguage(engine: TextToSpeech): Boolean {
        engineFault = when (engine.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> VoiceFault.SpeechData
            TextToSpeech.LANG_NOT_SUPPORTED -> VoiceFault.SpeechLanguage
            else -> null
        }
        return engineFault == null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post {
                val group = groupOf(utteranceId)
                if (group == token && !heard) {
                    heard = true
                    onFirstAudio?.invoke(group)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            main.post {
                if (groupOf(utteranceId) != token) return@post
                outstanding = (outstanding - 1).coerceAtLeast(0)
                drainedIfDone()
            }
        }

        @Deprecated("Required by the abstract class")
        override fun onError(utteranceId: String?) {
            main.post { if (groupOf(utteranceId) == token) fail(VoiceFault.Speech) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { if (groupOf(utteranceId) == token) fail(VoiceFault.Speech) }
        }
    }

    private fun groupOf(utteranceId: String?): Long =
        utteranceId?.substringBefore(':')?.toLongOrNull() ?: NONE

    private fun requestFocus() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .build()
        audio?.requestAudioFocus(request)
        focus = request
    }

    private fun abandonFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun applyPendingEngine() {
        val next = pendingEngine ?: return
        pendingEngine = null
        engine = next
        shutdownEngine()
    }

    private fun shutdownEngine() {
        tts?.let { engine ->
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
        tts = null
        engineState = EngineState.Off
    }

    private companion object {
        const val NONE = -1L

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
