package io.github.lesj0610.hermes.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Speech in and out, both on the device.
 *
 * The gateway has no audio surface — no transcription route, no realtime
 * socket, no audio parts on the run request. Anything spoken therefore has to
 * become text before it is sent and back into speech after it returns, and
 * Android does both without a server, which is what keeps this inside the "no
 * extra backend" constraint.
 *
 * The consequence is that voice mode is half duplex: the microphone is closed
 * while the agent is speaking. A model that can be interrupted mid-sentence
 * needs a duplex audio stream, and there is nothing to stream to.
 */
enum class VoiceState { Idle, Listening, Speaking }

class VoiceController(private val context: Context) {

    private val _state = MutableStateFlow(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /**
     * True while a spoken conversation is running: each reply is read aloud and
     * the microphone reopens when it finishes. Distinct from a single dictation,
     * which fills the input box and stops.
     */
    private val _conversing = MutableStateFlow(false)
    val conversing: StateFlow<Boolean> = _conversing.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Set by the caller so a spoken turn can be sent without going through the UI. */
    var onTranscript: ((String) -> Unit)? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Dictate one utterance. [locale] follows the app's language so Korean is
     * transcribed as Korean rather than as whatever the device happens to
     * default to.
     */
    fun listen(locale: Locale = Locale.getDefault()) {
        if (!available) return
        stopListening()

        val engine = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = VoiceState.Listening
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                _state.value = VoiceState.Idle
                if (text.isNotBlank()) onTranscript?.invoke(text)
            }

            override fun onError(error: Int) {
                // A no-match or timeout ends the turn rather than retrying:
                // reopening the microphone on silence is how a voice mode ends
                // up listening to an empty room indefinitely.
                _state.value = VoiceState.Idle
                _conversing.value = false
            }

            override fun onEndOfSpeech() { _state.value = VoiceState.Idle }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        engine.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            },
        )
    }

    fun stopListening() {
        recognizer?.run {
            stopListening()
            destroy()
        }
        recognizer = null
        if (_state.value == VoiceState.Listening) _state.value = VoiceState.Idle
    }

    /** Read [text] aloud, then reopen the microphone if a conversation is running. */
    fun speak(text: String, locale: Locale = Locale.getDefault()) {
        if (text.isBlank()) return
        ensureTts(locale) { engine ->
            _state.value = VoiceState.Speaking
            engine.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        _state.value = VoiceState.Idle
                        if (_conversing.value) listen(locale)
                    }

                    @Deprecated("Required by the abstract class")
                    override fun onError(utteranceId: String?) {
                        _state.value = VoiceState.Idle
                        _conversing.value = false
                    }
                },
            )
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes")
        }
    }

    fun startConversation(locale: Locale = Locale.getDefault()) {
        _conversing.value = true
        listen(locale)
    }

    fun stopConversation() {
        _conversing.value = false
        stopListening()
        tts?.stop()
        _state.value = VoiceState.Idle
    }

    /**
     * The engine initialises asynchronously, so the first utterance has to wait
     * for it rather than being dropped.
     */
    private fun ensureTts(locale: Locale, block: (TextToSpeech) -> Unit) {
        val existing = tts
        if (existing != null && ttsReady) {
            block(existing)
            return
        }
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            val engine = tts ?: return@TextToSpeech
            if (!ttsReady) return@TextToSpeech
            engine.language = locale
            block(engine)
        }
    }

    fun release() {
        stopConversation()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
