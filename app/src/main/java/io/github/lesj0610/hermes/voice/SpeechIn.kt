package io.github.lesj0610.hermes.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
 * the previous session, and its late callbacks — a result from a recognizer
 * that was already stopped — are dropped here rather than reaching the draft or
 * the conversation.
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

    /** The chosen recognition service, a flattened component; empty leaves it to the device. */
    var service = ""

    private val _level = MutableStateFlow(0f)

    /** Input level while listening, 0…1, for a meter. */
    val level: StateFlow<Float> = _level.asStateFlow()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val listening: Boolean get() = recognizer != null

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
        begin(id, locale, source, listener)
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
        recognizer?.stopListening()
    }

    /** Stops and discards: no result follows. */
    fun cancel() {
        checkMain()
        session++
        recognizer?.let { engine ->
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
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
    }

    private fun begin(id: Long, locale: Locale, source: Source, listener: (Long, Event) -> Unit) {
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
        var heardAnything = false

        fun deliver(event: Event) {
            if (id == session) listener(id, event)
        }

        fun retire() {
            if (recognizer === engine) recognizer = null
            runCatching { engine.destroy() }
            _level.value = 0f
        }

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                heardAnything = true
                deliver(Event.Ready)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Roughly -2…10 dB from the platform recognizer; a meter only.
                if (id == session) _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onEndOfSpeech() {
                _level.value = 0f
                deliver(Event.EndOfSpeech)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                heardAnything = true
                firstResult(partialResults)?.let { deliver(Event.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                if (id != session) return retire()
                val text = firstResult(results)
                retire()
                deliver(if (text == null) Event.Silence else Event.Final(text))
            }

            override fun onError(error: Int) {
                if (id != session) return retire()
                retire()
                // An on-device recognizer that turns out not to serve this
                // language falls back to the device's default one, once,
                // without the caller seeing a failure — still offline only.
                if (source == Source.OnDevice && !heardAnything && error in ON_DEVICE_FALLBACK) {
                    onDevice[locale.language] = OnDevice.Unavailable
                    begin(id, locale, Source.Default, listener)
                    return
                }
                deliver(eventFor(error))
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
                    done(status)
                }

                override fun onError(error: Int) {
                    onDevice[locale.language] = OnDevice.Unknown
                    probe.destroy()
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

        val ON_DEVICE_FALLBACK = setOf(
            ERROR_CLIENT, ERROR_SERVER_DISCONNECTED, ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE,
        )

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
