package io.github.lesj0610.hermes.voice

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.lesj0610.hermes.R
import io.github.lesj0610.hermes.data.ChatState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class VoiceState { Idle, Listening, Speaking }

/**
 * One step of dictation, for the composer to apply through [DictationDraft].
 * [session] changes with every new dictation, so the composer knows when to
 * start a new span rather than replace the last one.
 */
data class DictationUpdate(val session: Long, val text: String, val kind: Kind) {
    enum class Kind { Partial, Final, Ended }
}

/**
 * Speech in and out, on the phone's own engines.
 *
 * Three uses share one recognizer and one speaker:
 *
 *  - dictation fills the composer, partial results first, and never sends;
 *  - a spoken conversation listens, sends, and reads the reply aloud as it
 *    streams, then listens again ([ConversationLoop] decides when);
 *  - reading aloud speaks one finished reply, or — with auto-read on — every
 *    reply as it streams.
 *
 * Replies are read from [ChatState] as it changes ([ReplyFollower]), cleaned
 * and cut into sentences ([SpeechTextStream]) and queued on the engine
 * ([SpeechOut]). Main thread throughout.
 */
class VoiceController(
    private val context: Context,
    /** Sends a spoken turn; the outcome comes back through [onTurnSent] or [onTurnRefused]. */
    private val sendTurn: (String) -> Unit,
) {
    private val input = SpeechIn(context)
    private val output = SpeechOut(context)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _conversing = MutableStateFlow(false)
    val conversing: StateFlow<Boolean> = _conversing.asStateFlow()

    private val _dictation = MutableStateFlow<DictationUpdate?>(null)
    val dictation: StateFlow<DictationUpdate?> = _dictation.asStateFlow()

    private val _notice = MutableStateFlow<VoiceFault?>(null)

    /** Why the last voice action stopped, until the UI has shown it. */
    val notice: StateFlow<VoiceFault?> = _notice.asStateFlow()

    private val _reading = MutableStateFlow<String?>(null)

    /** The key of the message being read aloud, if one is. */
    val reading: StateFlow<String?> = _reading.asStateFlow()

    val level: StateFlow<Float> get() = input.level
    val speech: StateFlow<SpeechOut.Info> get() = output.info

    val available: Boolean get() = input.available

    private var locale: Locale = Locale.getDefault()
    private var codeNote: String? = null

    // ── speech groups ─────────────────────────────────────────────────────

    private enum class Purpose { Conversation, AutoRead, Message }

    private var nextToken = 0L
    private var speechToken = NONE
    private var purpose: Purpose? = null
    private var stream: SpeechTextStream? = null

    // ── conversation ──────────────────────────────────────────────────────

    private val port = object : ConversationLoop.Port {
        override fun listen(epoch: Long) {
            input.start(locale) { _, event -> onConversationHeard(epoch, event) }
            refresh()
        }

        override fun stopListening() {
            input.cancel()
            refresh()
        }

        override fun send(text: String) {
            trace.sent()
            sendTurn(text)
        }

        override fun speak(turn: Long, text: String) {
            ensureConversationSpeech(turn)
            feed(text)
        }

        override fun finishSpeaking(turn: Long) {
            ensureConversationSpeech(turn)
            finishSpeech()
        }

        override fun cancelSpeaking() {
            if (purpose == Purpose.Conversation) cancelSpeech()
        }

        override fun notice(fault: VoiceFault) {
            _notice.value = fault
        }
    }

    private val loop = ConversationLoop(port)
    private var conversationTurn = 0L
    private var conversationFollower: ReplyFollower? = null

    // ── reading ───────────────────────────────────────────────────────────

    /** Speak every reply as it streams, outside a conversation. */
    var autoRead = false
        set(value) {
            if (value && !field) turnSeen = lastState?.startedTurn ?: turnSeen
            field = value
            if (!value && purpose == Purpose.AutoRead) cancelSpeech()
        }

    private var autoFollower: ReplyFollower? = null
    private var turnSeen = 0L
    private var lastState: ChatState? = null

    private val trace = LatencyTrace()

    init {
        output.onDrained = { token -> onSpeechDrained(token) }
        output.onFailed = { token, fault -> onSpeechFailed(token, fault) }
        output.onFirstAudio = { token -> if (token == speechToken && purpose == Purpose.Conversation) trace.firstAudio() }
    }

    fun configure(locale: Locale, rate: Float, pitch: Float, engine: String, recognizer: String) {
        if (locale != this.locale || codeNote == null) {
            this.locale = locale
            codeNote = localized(locale).getString(R.string.voice_code_omitted)
        }
        input.service = recognizer
        output.configure(locale, rate, pitch, engine)
    }

    // ── dictation ─────────────────────────────────────────────────────────

    /** Starts dictating, or — while dictating — stops and keeps what was heard. */
    fun dictate() {
        if (loop.active) return
        if (input.listening) {
            input.stop()
            return
        }
        input.start(locale) { session, event -> onDictationHeard(session, event) }
        refresh()
    }

    /** The user edited the draft mid-dictation: discard the rest. */
    fun cancelDictation() {
        if (loop.active || !input.listening) return
        val session = _dictation.value?.session
        input.cancel()
        if (session != null) _dictation.value = DictationUpdate(session, "", DictationUpdate.Kind.Ended)
        refresh()
    }

    /** The composer has applied a finished dictation step. */
    fun consumeDictation() {
        _dictation.value = null
    }

    private fun onDictationHeard(session: Long, event: SpeechIn.Event) {
        when (event) {
            SpeechIn.Event.Ready -> Unit
            is SpeechIn.Event.Partial ->
                _dictation.value = DictationUpdate(session, event.text, DictationUpdate.Kind.Partial)
            is SpeechIn.Event.Final ->
                _dictation.value = DictationUpdate(session, event.text, DictationUpdate.Kind.Final)
            SpeechIn.Event.Silence -> {
                _dictation.value = DictationUpdate(session, "", DictationUpdate.Kind.Ended)
                _notice.value = VoiceFault.NoSpeech
            }
            is SpeechIn.Event.Failed -> {
                _dictation.value = DictationUpdate(session, "", DictationUpdate.Kind.Ended)
                _notice.value = event.fault
            }
            SpeechIn.Event.EndOfSpeech -> Unit
        }
        refresh()
    }

    // ── conversation ──────────────────────────────────────────────────────

    fun toggleConversation() {
        if (loop.active) stopConversation() else startConversation()
    }

    fun startConversation() {
        if (loop.active) return
        if (input.listening) input.cancel()
        cancelSpeech()
        autoFollower = null
        loop.start()
        refresh()
    }

    fun stopConversation() {
        loop.stop()
        conversationFollower = null
        refresh()
    }

    /** Stop pressed during a spoken turn: silence it, listen again when it ends. */
    fun interruptReply() {
        if (loop.active) loop.interruptReply() else if (purpose == Purpose.AutoRead) cancelSpeech()
        refresh()
    }

    fun onTurnSent(turn: Long) {
        conversationFollower = ReplyFollower(turn)
        loop.onSent(turn)
        lastState?.let(::onChatState)
        refresh()
    }

    fun onTurnRefused() {
        loop.onSendFailed()
        refresh()
    }

    private fun onConversationHeard(epoch: Long, event: SpeechIn.Event) {
        when (event) {
            SpeechIn.Event.Ready, is SpeechIn.Event.Partial -> Unit
            SpeechIn.Event.EndOfSpeech -> trace.endOfSpeech()
            is SpeechIn.Event.Final -> {
                trace.recognized()
                loop.onFinal(epoch, event.text)
            }
            SpeechIn.Event.Silence -> loop.onSilence(epoch)
            is SpeechIn.Event.Failed -> loop.onRecognitionFailed(epoch, event.fault)
        }
        refresh()
    }

    private fun ensureConversationSpeech(turn: Long) {
        if (purpose == Purpose.Conversation && conversationTurn == turn) return
        conversationTurn = turn
        beginSpeech(Purpose.Conversation)
    }

    // ── following the transcript ──────────────────────────────────────────

    /** Called on every change to the chat; hands new reply text to whoever is reading it. */
    fun onChatState(state: ChatState) {
        lastState = state
        conversationFollower?.let { follower ->
            val update = follower.read(state)
            if (update.text.isNotEmpty()) trace.firstDelta()
            loop.onReply(follower.turn, update.text, update.ended)
            if (update.ended) conversationFollower = null
        }

        if (!autoRead || loop.active) {
            turnSeen = maxOf(turnSeen, state.startedTurn)
        } else {
            if (state.startedTurn > turnSeen) {
                turnSeen = state.startedTurn
                autoFollower = ReplyFollower(state.startedTurn)
                beginSpeech(Purpose.AutoRead)
            }
            autoFollower?.let { follower ->
                val update = follower.read(state)
                if (purpose == Purpose.AutoRead) {
                    if (update.text.isNotEmpty()) feed(update.text)
                    if (update.ended) finishSpeech()
                }
                if (update.ended) autoFollower = null
            }
        }
        refresh()
    }

    // ── reading aloud ─────────────────────────────────────────────────────

    /** Reads one reply; the same key again stops it. */
    fun readAloud(key: String, text: String) {
        if (loop.active) return
        if (_reading.value == key) {
            stopReading()
            return
        }
        autoFollower = null
        beginSpeech(Purpose.Message)
        _reading.value = key
        feed(text)
        finishSpeech()
        refresh()
    }

    fun stopReading() {
        if (purpose == Purpose.Message) cancelSpeech()
        refresh()
    }

    /** A sample, for the settings page. */
    fun preview() = readAloud(PREVIEW_KEY, localized(locale).getString(R.string.settings_speech_sample))

    fun consumeNotice() {
        _notice.value = null
    }

    // ── settings support ──────────────────────────────────────────────────

    fun onDeviceStatus(): SpeechIn.OnDevice = input.onDeviceStatus(locale)

    fun refreshOnDevice(done: (SpeechIn.OnDevice) -> Unit) = input.refreshOnDevice(locale, done)

    /** The recognition services installed on the device; see [SpeechIn.recognizers]. */
    fun recognizers(): List<Pair<String, String>> = input.recognizers()

    fun downloadOnDevice() = input.downloadOnDevice(locale)

    fun warmUpSpeech() = output.warmUp()

    fun release() {
        loop.stop()
        input.release()
        output.release()
        main.removeCallbacks(tick)
    }

    // ── the speech pipeline ───────────────────────────────────────────────

    private fun beginSpeech(purpose: Purpose) {
        if (this.purpose == Purpose.Message) _reading.value = null
        val token = ++nextToken
        speechToken = token
        this.purpose = purpose
        stream = SpeechTextStream(codeNote)
        output.begin(token)
        main.removeCallbacks(tick)
    }

    private fun feed(text: String) {
        val current = stream ?: return
        enqueue(current.append(text, SystemClock.elapsedRealtime()))
        scheduleTick()
    }

    private fun finishSpeech() {
        val current = stream ?: return
        enqueue(current.finish())
        stream = null
        main.removeCallbacks(tick)
        output.finish(speechToken)
    }

    private fun enqueue(sentences: List<String>) {
        sentences.forEach { output.enqueue(speechToken, it) }
    }

    private fun cancelSpeech() {
        output.cancel()
        stream = null
        speechToken = NONE
        if (purpose == Purpose.Message) _reading.value = null
        purpose = null
        main.removeCallbacks(tick)
    }

    private val tick = Runnable {
        stream?.let { current ->
            enqueue(current.onTick(SystemClock.elapsedRealtime()))
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        if (stream?.hasPending == true) main.postDelayed(tick, TICK_MILLIS)
    }

    private fun onSpeechDrained(token: Long) {
        if (token != speechToken) return
        val finished = purpose
        speechToken = NONE
        purpose = null
        when (finished) {
            Purpose.Conversation -> loop.onSpoken(conversationTurn)
            Purpose.Message -> _reading.value = null
            Purpose.AutoRead, null -> Unit
        }
        refresh()
    }

    private fun onSpeechFailed(token: Long, fault: VoiceFault) {
        if (token != speechToken) return
        val failed = purpose
        cancelSpeech()
        if (failed == Purpose.Conversation) loop.onSpeechFailed(conversationTurn, fault) else _notice.value = fault
        refresh()
    }

    private fun refresh() {
        _conversing.value = loop.active
        _state.value = when {
            input.listening -> VoiceState.Listening
            speechToken != NONE -> VoiceState.Speaking
            else -> VoiceState.Idle
        }
    }

    private fun localized(locale: Locale): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })

    private companion object {
        const val NONE = -1L
        const val TICK_MILLIS = 250L
        const val PREVIEW_KEY = "voice-preview"
    }
}

/**
 * Where the time goes between the end of speech and the first sound of the
 * reply, logged per spoken turn so it can be measured on a device
 * (`adb logcat -s HermesVoice`) rather than guessed.
 */
private class LatencyTrace {
    private var endOfSpeech = 0L
    private var recognized = 0L
    private var sent = 0L
    private var firstDelta = 0L

    fun endOfSpeech() {
        endOfSpeech = now()
        recognized = 0L
        sent = 0L
        firstDelta = 0L
    }

    fun recognized() {
        recognized = now()
        if (endOfSpeech == 0L) endOfSpeech = recognized
    }

    fun sent() {
        sent = now()
    }

    fun firstDelta() {
        if (firstDelta == 0L && sent != 0L) firstDelta = now()
    }

    fun firstAudio() {
        if (endOfSpeech == 0L || sent == 0L) return
        val audio = now()
        Log.i(
            TAG,
            "spoken turn: recognized +${recognized - endOfSpeech}ms, sent +${sent - endOfSpeech}ms, " +
                "first delta +${if (firstDelta == 0L) -1 else firstDelta - endOfSpeech}ms, " +
                "first audio +${audio - endOfSpeech}ms",
        )
        endOfSpeech = 0L
        sent = 0L
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val TAG = "HermesVoice"
    }
}
