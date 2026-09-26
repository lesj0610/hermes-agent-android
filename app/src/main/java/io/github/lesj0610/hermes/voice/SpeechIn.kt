package io.github.lesj0610.hermes.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The phone's own speech recognizer, kept on the device.
 *
 * Which one is chosen among the recognition services the phone carries
 * ([service], listed by [recognizers]) — Google's everywhere, Samsung's on a
 * Samsung — or left to the device: its on-device recognizer once that has
 * confirmed the language is installed, its default recognizer otherwise. Every
 * request asks for offline recognition only ([RecognizerIntent.EXTRA_PREFER_OFFLINE]);
 * a recognizer without the language on the device fails rather than sending
 * audio away, and says so.
 *
 * Every session gets an id, carried by each event. [start] and [cancel] retire
 * the previous session, and a session opened again after a failure retires the
 * recognizer that failed: only the recognizer a session currently owns reaches
 * the draft, the conversation or the level meter, so a late callback from one
 * already stopped or replaced is dropped here. A session ends once: a second
 * closing callback from the same recognizer is dropped too.
 *
 * A recognizer is not opened within [SETTLE_MILLIS] of closing the last one, and
 * one that fails before it is ready is opened again, once: a recognition service
 * still closing one session drops the next.
 *
 * Main thread only: SpeechRecognizer requires it, and its callbacks arrive there.
 */
class SpeechIn(private val context: Context) {

    sealed interface Event {
        data object Ready : Event
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data object EndOfSpeech : Event
        /** Nothing was heard, or nothing matched: a normal outcome, not an error. */
        data object Silence : Event
        data class Failed(val fault: VoiceFault, val code: Int) : Event
    }

    /** Whether recognition can run on the device for a language. */
    enum class OnDevice { Unknown, Unavailable, Downloadable, Installed }

    private var recognizer: SpeechRecognizer? = null
    private var session = 0L
    private val onDevice = HashMap<String, OnDevice>()
    private val main = Handler(Looper.getMainLooper())

    /** When a recognizer was last closed, on the uptime clock. */
    private var closedAt: Long? = null

    /** The session waiting for the last recognizer to finish closing; see [open]. */
    private var waiting: Waiting? = null

    /** The session [stop] was asked for. */
    private var stopped = 0L

    private class Waiting(val id: Long, val listener: (Long, Event) -> Unit, val go: Runnable)

    /** The chosen recognition service, a flattened component; empty leaves it to the device. */
    var service = ""

    private val _level = MutableStateFlow(0f)

    /** Input level while listening, 0…1, for a meter. */
    val level: StateFlow<Float> = _level.asStateFlow()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val listening: Boolean get() = recognizer != null || waiting != null

    /** The recognition services installed on the device, as (flattened component, label), by label. */
    fun recognizers(): List<Pair<String, String>> {
        val packages = context.packageManager
        return runCatching {
            packages.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { info ->
                ComponentName(info.serviceInfo.packageName, info.serviceInfo.name).flattenToString() to
                    info.loadLabel(packages).toString()
            }
        }.getOrDefault(emptyList()).sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    /** Starts a session; answers the id its events carry. */
    fun start(locale: Locale, listener: (session: Long, event: Event) -> Unit): Long {
        checkMain()
        cancel()
        val id = ++session
        val source = chosen()?.let(Source::Service)
            ?: if (onDeviceStatus(locale) == OnDevice.Installed) Source.OnDevice else Source.Default
        open(id, locale, source, listener, retried = false)
        return id
    }

    /** [service], while it is still installed; otherwise the device decides. */
    private fun chosen(): ComponentName? {
        val component = service.takeIf { it.isNotBlank() }?.let(ComponentName::unflattenFromString) ?: return null
        return component.takeIf { wanted -> recognizers().any { it.first == wanted.flattenToString() } }
    }

    private sealed interface Source {
        /** The device's on-device recognizer, confirmed to have the language. */
        data object OnDevice : Source

        /** The device's default recognizer. */
        data object Default : Source

        data class Service(val component: ComponentName) : Source
    }

    /** Stops listening; what was heard so far still comes back as a result. */
    fun stop() {
        checkMain()
        stopped = session
        recognizer?.stopListening()
        // A session still waiting to open has heard nothing.
        waiting?.let { held ->
            main.removeCallbacks(held.go)
            waiting = null
            if (held.id == session) held.listener(held.id, Event.Silence)
        }
    }

    /** Stops and discards: no result follows. */
    fun cancel() {
        checkMain()
        session++
        waiting?.let { main.removeCallbacks(it.go) }
        waiting = null
        recognizer?.let { engine ->
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
            closedAt = SystemClock.uptimeMillis()
        }
        recognizer = null
        _level.value = 0f
    }

    fun release() = cancel()

    /** What was last learned about on-device recognition for [locale]. */
    fun onDeviceStatus(locale: Locale): OnDevice = onDevice[locale.language] ?: OnDevice.Unknown

    /**
     * Asks the on-device recognizer what it can do for [locale]. Only Android 13
     * reports installed languages; before that the answer stays Unknown and
     * on-device recognition is not offered.
     */
    fun refreshOnDevice(locale: Locale, done: (OnDevice) -> Unit) {
        checkMain()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                OnDevice.Unknown
            } else {
                OnDevice.Unavailable
            }
            onDevice[locale.language] = status
            done(status)
            return
        }
        checkSupport(locale, done)
    }

    /** Asks the system to fetch the on-device model for [locale]. Android 13+. */
    fun downloadOnDevice(locale: Locale) {
        checkMain()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
        val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        runCatching { probe.triggerModelDownload(intent(locale)) }
        probe.destroy()
        closedAt = SystemClock.uptimeMillis()
    }

    /**
     * Begins [id] on [source] once the recognizer closed last has had
     * [SETTLE_MILLIS] to go. A recognition service still closing one session
     * drops the next one it is asked to open ("Connection to speech recognition
     * service lost"), and a conversation listening again the moment silence ends
     * a session lands in exactly that gap; on a phone the service was seen closing
     * within 40 ms of the next open.
     */
    private fun open(id: Long, locale: Locale, source: Source, listener: (Long, Event) -> Unit, retried: Boolean) {
        val wait = closedAt?.let { it + SETTLE_MILLIS - SystemClock.uptimeMillis() } ?: 0L
        if (wait <= 0L) {
            begin(id, locale, source, listener, retried)
            return
        }
        val go = Runnable {
            waiting = null
            if (id == session) begin(id, locale, source, listener, retried)
        }
        waiting = Waiting(id, listener, go)
        main.postDelayed(go, wait)
    }

    private fun begin(id: Long, locale: Locale, source: Source, listener: (Long, Event) -> Unit, retried: Boolean) {
        val engine = runCatching {
            when {
                source is Source.Service -> SpeechRecognizer.createSpeechRecognizer(context, source.component)
                source == Source.OnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()
        if (engine == null) {
            listener(id, Event.Failed(VoiceFault.NoRecognizer, -1))
            return
        }
        recognizer = engine
        var started = false
        var ended = false

        // Whether this recognizer is still the one session [id] listens to. A
        // session that ended, or was opened again after this one failed, leaves
        // it behind, and nothing it reports afterwards reaches the caller or
        // the meter.
        fun owned() = recognizer === engine && id == session

        fun retire() {
            if (recognizer === engine) {
                recognizer = null
                closedAt = SystemClock.uptimeMillis()
                _level.value = 0f
            }
            runCatching { engine.destroy() }
        }

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!owned()) return
                started = true
                listener(id, Event.Ready)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Roughly -2…10 dB from the platform recognizer; a meter only.
                if (owned()) _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onEndOfSpeech() {
                if (!owned()) return
                _level.value = 0f
                listener(id, Event.EndOfSpeech)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!owned()) return
                started = true
                firstResult(partialResults)?.let { listener(id, Event.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                if (ended) return
                ended = true
                val current = owned()
                retire()
                if (!current) return
                val text = firstResult(results)
                listener(id, if (text == null) Event.Silence else Event.Final(text))
            }

            override fun onError(error: Int) {
                // Some recognizers follow an empty result with "no speech".
                if (ended) return
                ended = true
                val current = owned()
                retire()
                if (!current) return
                if (!started && stopped != id) {
                    // It never got going, most often because the service was
                    // still closing the session before: once more, the same
                    // recognizer, without the caller seeing a failure.
                    if (error in NOT_STARTED && !retried) {
                        open(id, locale, source, listener, retried = true)
                        return
                    }
                    // The on-device recognizer does not serve this language after
                    // all, or will not start: the device's default one takes over,
                    // still offline only. Only a language answer is remembered.
                    if (source == Source.OnDevice && (error in LANGUAGE_MISSING || error in NOT_STARTED)) {
                        if (error in LANGUAGE_MISSING) onDevice[locale.language] = OnDevice.Unavailable
                        open(id, locale, Source.Default, listener, retried)
                        return
                    }
                }
                listener(id, eventFor(error))
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        engine.startListening(intent(locale))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkSupport(locale: Locale, done: (OnDevice) -> Unit) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onDevice[locale.language] = OnDevice.Unavailable
            done(OnDevice.Unavailable)
            return
        }
        val probe = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        probe.checkRecognitionSupport(
            intent(locale),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val status = when {
                        support.installedOnDeviceLanguages.any { it.sameLanguage(locale) } -> OnDevice.Installed
                        (support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages)
                            .any { it.sameLanguage(locale) } -> OnDevice.Downloadable
                        else -> OnDevice.Unavailable
                    }
                    onDevice[locale.language] = status
                    probe.destroy()
                    closedAt = SystemClock.uptimeMillis()
                    done(status)
                }

                override fun onError(error: Int) {
                    onDevice[locale.language] = OnDevice.Unknown
                    probe.destroy()
                    closedAt = SystemClock.uptimeMillis()
                    done(OnDevice.Unknown)
                }
            },
        )
    }

    private fun intent(locale: Locale) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Offline engines only, despite the name: audio stays on the phone.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechRecognizer is main-thread only" }
    }

    private companion object {
        /** How long a closed recognizer is given before the next one opens. */
        const val SETTLE_MILLIS = 400L

        // Codes by number: several were only named in later API levels.
        const val ERROR_NETWORK_TIMEOUT = 1
        const val ERROR_NETWORK = 2
        const val ERROR_AUDIO = 3
        const val ERROR_SERVER = 4
        const val ERROR_CLIENT = 5
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        /** A recognizer failing with these before it was ready never got going. */
        val NOT_STARTED = setOf(ERROR_CLIENT, ERROR_RECOGNIZER_BUSY, ERROR_SERVER_DISCONNECTED)

        val LANGUAGE_MISSING = setOf(ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE)

        fun eventFor(error: Int): Event = when (error) {
            ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> Event.Silence
            ERROR_NETWORK_TIMEOUT, ERROR_NETWORK -> Event.Failed(VoiceFault.Network, error)
            ERROR_AUDIO -> Event.Failed(VoiceFault.Audio, error)
            ERROR_SERVER, ERROR_SERVER_DISCONNECTED, ERROR_TOO_MANY_REQUESTS -> Event.Failed(VoiceFault.Server, error)
            ERROR_RECOGNIZER_BUSY -> Event.Failed(VoiceFault.Busy, error)
            ERROR_INSUFFICIENT_PERMISSIONS -> Event.Failed(VoiceFault.Permission, error)
            ERROR_LANGUAGE_NOT_SUPPORTED -> Event.Failed(VoiceFault.LanguageUnsupported, error)
            ERROR_LANGUAGE_UNAVAILABLE -> Event.Failed(VoiceFault.LanguageUnavailable, error)
            else -> Event.Failed(VoiceFault.Recognizer, error)
        }

        fun firstResult(bundle: Bundle?): String? = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        fun String.sameLanguage(locale: Locale): Boolean = Locale.forLanguageTag(this).language == locale.language
    }
}
