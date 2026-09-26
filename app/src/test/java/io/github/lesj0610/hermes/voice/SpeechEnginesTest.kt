package io.github.lesj0610.hermes.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Locale

/**
 * The engine wrappers, driven through Robolectric's shadows: the platform's
 * callbacks are fired by hand, late and out of order, the way a real engine
 * delivers them when a reply is cancelled or an engine is swapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechOutTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val drained = mutableListOf<Long>()
    private var failure: Pair<Long, VoiceFault>? = null

    @Before
    fun setUp() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREA)
    }

    private fun speaker() = SpeechOut(context).apply {
        configure(Locale.KOREA, 1f, 1f, "")
        onDrained = { drained += it }
        onFailed = { token, fault -> failure = token to fault }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun engine(): TextToSpeech = ShadowTextToSpeech.getLastTextToSpeechInstance()
    private fun start(tts: TextToSpeech = engine(), status: Int = TextToSpeech.SUCCESS) {
        shadowOf(tts).onInitListener.onInit(status)
        idle()
    }

    // The shadow completes each utterance by itself once it is spoken, so a
    // group drains as soon as the engine has played it.
    @Test
    fun `sentences wait for the engine, play in order, and drain once all are done`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "첫 문장.")
        out.enqueue(1, "둘째 문장.")
        out.finish(1)
        idle()
        assertEquals(emptyList<Long>(), drained)
        start()
        assertEquals(listOf("첫 문장.", "둘째 문장."), shadowOf(engine()).spokenTextList)
        assertEquals(listOf(1L), drained)
    }

    @Test
    fun `a group still being written does not drain when its sentences finish`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "첫 문장.")
        start()
        assertEquals(emptyList<Long>(), drained)
        out.finish(1)
        idle()
        assertEquals(listOf(1L), drained)
    }

    @Test
    fun `an engine that fails to start says so`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "안녕하세요.")
        start(status = TextToSpeech.ERROR)
        assertEquals(1L to VoiceFault.SpeechEngine, failure)
        assertEquals(VoiceFault.SpeechEngine, out.info.value.fault)
    }

    @Test
    fun `a language the engine cannot speak is reported, not read in another voice`() {
        ShadowTextToSpeech.reset()
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "안녕하세요.")
        start()
        assertEquals(1L, failure?.first)
        assertTrue(failure?.second == VoiceFault.SpeechLanguage || failure?.second == VoiceFault.SpeechData)
        assertEquals(emptyList<String>(), shadowOf(engine()).spokenTextList)
    }

    @Test
    fun `cancelling while the engine starts leaves nothing to play`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "안녕하세요.")
        out.finish(1)
        out.cancel()
        start()
        assertEquals(emptyList<String>(), shadowOf(engine()).spokenTextList)
        assertEquals(emptyList<Long>(), drained)
        assertNull(failure)
    }

    @Test
    fun `a late callback from a cancelled reply is ignored`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "이전 답.")
        start()
        out.begin(2)
        out.enqueue(2, "새 답.")
        drained.clear()

        // Reply 1 was cut off before it finished; its completion lands late,
        // while reply 2 is still being written.
        val progress = shadowOf(engine()).utteranceProgressListener
        progress.onDone("1:0")
        progress.onDone("1:5")
        idle()
        assertEquals(emptyList<Long>(), drained)
        assertNull(failure)

        out.finish(2)
        idle()
        assertEquals(listOf(2L), drained)
    }

    @Test
    fun `an engine error for a cancelled reply does not fail the next one`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "이전 답.")
        start()
        out.begin(2)
        shadowOf(engine()).utteranceProgressListener.onError("1:0", TextToSpeech.ERROR_SYNTHESIS)
        idle()
        assertNull(failure)
    }

    @Test
    fun `an engine replaced while starting cannot report its replacement ready`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "첫 엔진.")
        val first = engine()

        out.configure(Locale.KOREA, 1f, 1f, "com.example.tts")
        out.begin(2)
        out.enqueue(2, "둘째 엔진.")
        val second = engine()

        start(first)
        assertEquals(emptyList<String>(), shadowOf(second).spokenTextList)
        start(second)
        assertEquals(listOf("둘째 엔진."), shadowOf(second).spokenTextList)
    }

    @Test
    fun `an engine chosen while sentences wait for the engine applies to the next reply`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "첫 엔진.")
        out.finish(1)
        val first = engine()
        out.configure(Locale.KOREA, 1f, 1f, "com.example.tts")

        start(first)
        assertEquals(listOf("첫 엔진."), shadowOf(first).spokenTextList)
        assertEquals(listOf(1L), drained)
        assertTrue(!shadowOf(first).isShutdown)

        out.begin(2)
        out.enqueue(2, "둘째 엔진.")
        assertTrue(shadowOf(first).isShutdown)
        assertTrue(engine() !== first)
    }

    @Test
    fun `an engine chosen while a reply plays does not strand it`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "첫 문장.")
        start()
        val first = engine()
        out.configure(Locale.KOREA, 1f, 1f, "com.example.tts")
        out.enqueue(1, "둘째 문장.")
        out.finish(1)
        idle()
        assertEquals(listOf("첫 문장.", "둘째 문장."), shadowOf(first).spokenTextList)
        assertEquals(listOf(1L), drained)
        assertTrue(!shadowOf(first).isShutdown)
    }

    @Test
    fun `an engine chosen after the last sentence applies at once`() {
        val out = speaker()
        out.begin(1)
        out.enqueue(1, "끝.")
        out.finish(1)
        start()
        assertEquals(listOf(1L), drained)
        val first = engine()
        out.configure(Locale.KOREA, 1f, 1f, "com.example.tts")
        assertTrue(shadowOf(first).isShutdown)
    }

    @Test
    fun `an empty reply drains at once, asynchronously`() {
        val out = speaker()
        out.begin(3)
        out.finish(3)
        assertEquals(emptyList<Long>(), drained)
        idle()
        assertEquals(listOf(3L), drained)
    }

    /** An installed package; with [speaks], one that offers a text-to-speech service. */
    private fun installEngine(pkg: String, label: String, speaks: Boolean) {
        val packages = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply {
            packageName = pkg
            nonLocalizedLabel = label
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        packages.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = app })
        if (!speaks) return
        val component = ComponentName(pkg, "$pkg.SpeechService")
        packages.addOrUpdateService(
            ServiceInfo().apply {
                packageName = pkg
                name = component.className
                nonLocalizedLabel = label
                applicationInfo = app
                exported = true
            },
        )
        packages.addIntentFilterForService(
            component,
            IntentFilter(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
    }

    // A maker's engine that serves only the apps its maker allows is left out
    // of the engines an app is shown, and the system reads with another.
    @Test
    fun `a default engine that turns the app away is named, with the one that reads instead`() {
        installEngine("com.maker.tts", "제조사 TTS", speaks = false)
        installEngine("com.open.tts", "공개 TTS", speaks = true)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.maker.tts")
        val out = speaker()
        out.warmUp()
        start()
        assertEquals(listOf("com.open.tts" to "공개 TTS"), out.info.value.engines)
        assertEquals("제조사 TTS", out.info.value.withheldDefault)
        assertEquals("공개 TTS", out.info.value.standIn)
    }

    @Test
    fun `a default engine the app may use, or one since removed, is not called withheld`() {
        installEngine("com.open.tts", "공개 TTS", speaks = true)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.open.tts")
        val out = speaker()
        out.warmUp()
        start()
        assertEquals("", out.info.value.withheldDefault)
        assertEquals("", out.info.value.standIn)

        Settings.Secure.putString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH, "com.gone.tts")
        val again = speaker()
        again.warmUp()
        start()
        assertEquals("", again.info.value.withheldDefault)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechInTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val events = mutableListOf<Pair<Long, SpeechIn.Event>>()

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.reset()
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun latest() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    private fun newest(): SpeechRecognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()

    // The platform recognizer hands its commands to the main looper; they run
    // before the (simulated) service can answer.
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // Past the pause a closed recognizer is given before the next one opens.
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun onDevice(recognizer: SpeechRecognizer): Boolean = ReflectionHelpers.getField(recognizer, "mOnDevice")

    private fun confirmOnDevice(input: SpeechIn) {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        input.refreshOnDevice(Locale.KOREA) {}
        idle()
        latest().triggerSupportResult(
            RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("ko-KR")).build(),
        )
        idle()
        assertEquals(SpeechIn.OnDevice.Installed, input.onDeviceStatus(Locale.KOREA))
    }

    @Test
    fun `partial results come first, then the final one`() {
        val input = SpeechIn(context)
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        latest().triggerOnPartialResults(results("안녕"))
        latest().triggerOnResults(results("안녕하세요"))
        assertEquals(
            listOf(session to SpeechIn.Event.Partial("안녕"), session to SpeechIn.Event.Final("안녕하세요")),
            events,
        )
        assertTrue(!input.listening)
    }

    @Test
    fun `a cancelled session's late result never arrives`() {
        val input = SpeechIn(context)
        input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        val stale = latest()
        input.cancel()
        idle()
        stale.triggerOnResults(results("늦은 결과"))
        assertEquals(emptyList<Pair<Long, SpeechIn.Event>>(), events)
    }

    @Test
    fun `silence is an outcome, other errors carry their cause`() {
        val input = SpeechIn(context)
        input.start(Locale.KOREA) { _, event -> events += 0L to event }
        idle()
        latest().triggerOnError(7)
        input.start(Locale.KOREA) { _, event -> events += 0L to event }
        settle()
        latest().triggerOnError(9)
        input.start(Locale.KOREA) { _, event -> events += 0L to event }
        settle()
        latest().triggerOnError(2)
        assertEquals(
            listOf(
                SpeechIn.Event.Silence,
                SpeechIn.Event.Failed(VoiceFault.Permission, 9),
                SpeechIn.Event.Failed(VoiceFault.Network, 2),
            ),
            events.map { it.second },
        )
    }

    @Test
    fun `on-device recognition is used only once confirmed, and falls back once`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val input = SpeechIn(context)
        var status: SpeechIn.OnDevice? = null
        input.refreshOnDevice(Locale.KOREA) { status = it }
        idle()
        latest().triggerSupportResult(
            RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("ko-KR")).build(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SpeechIn.OnDevice.Installed, status)

        // Left to the device, a confirmed on-device recognizer is what runs.
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        settle()
        val local = newest()
        assertTrue(onDevice(local))
        // The on-device recognizer turns out not to serve the language.
        shadowOf(local).triggerOnError(13)
        settle()
        assertEquals(emptyList<Pair<Long, SpeechIn.Event>>(), events)
        assertEquals(SpeechIn.OnDevice.Unavailable, input.onDeviceStatus(Locale.KOREA))
        assertTrue(!onDevice(newest()))

        latest().triggerOnResults(results("기본 인식기"))
        assertEquals(listOf(session to SpeechIn.Event.Final("기본 인식기")), events)
    }

    // Seen on a phone: the conversation listened again the moment silence
    // ended a session, the service was still closing it, and the new session
    // was dropped ("Connection to speech recognition service lost").
    @Test
    fun `listening again right after a session waits for the recognizer to close`() {
        val input = SpeechIn(context)
        input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        val first = newest()
        shadowOf(first).triggerOnResults(results(""))
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        assertTrue(input.listening)
        assertSame(first, newest())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertSame(first, newest())

        settle()
        assertNotSame(first, newest())
        latest().triggerOnResults(results("다시 듣기"))
        assertEquals(SpeechIn.Event.Final("다시 듣기"), events.last().second)
        assertEquals(session, events.last().first)
    }

    @Test
    fun `a session waiting to open is heard as silence when stopped, and never opens`() {
        val input = SpeechIn(context)
        input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        val first = newest()
        shadowOf(first).triggerOnResults(results(""))
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        input.stop()
        assertEquals(session to SpeechIn.Event.Silence, events.last())
        assertTrue(!input.listening)
        settle()
        assertSame(first, newest())
    }

    @Test
    fun `a session waiting to open that is cancelled never opens`() {
        val input = SpeechIn(context)
        input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        val first = newest()
        shadowOf(first).triggerOnResults(results(""))
        input.start(Locale.KOREA) { id, event -> events += id to event }
        input.cancel()
        assertTrue(!input.listening)
        settle()
        assertSame(first, newest())
        assertEquals(1, events.size)
    }

    @Test
    fun `a recognizer that fails before it is ready opens again, once, and the caller sees no failure`() {
        val input = SpeechIn(context)
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        val first = newest()
        shadowOf(first).triggerOnError(11)
        assertEquals(emptyList<Pair<Long, SpeechIn.Event>>(), events)
        assertTrue(input.listening)
        settle()
        val second = newest()
        assertNotSame(first, second)
        shadowOf(second).triggerOnReadyForSpeech(Bundle())
        shadowOf(second).triggerOnResults(results("다시 연 인식기"))
        assertEquals(
            listOf(session to SpeechIn.Event.Ready, session to SpeechIn.Event.Final("다시 연 인식기")),
            events,
        )
    }

    @Test
    fun `a recognizer that fails to start twice says so`() {
        val input = SpeechIn(context)
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        latest().triggerOnError(11)
        settle()
        latest().triggerOnError(11)
        settle()
        assertEquals(listOf(session to SpeechIn.Event.Failed(VoiceFault.Server, 11)), events)
        assertTrue(!input.listening)
    }

    @Test
    fun `an on-device recognizer that will not start hands over without forgetting the language`() {
        val input = SpeechIn(context)
        confirmOnDevice(input)
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        settle()
        assertTrue(onDevice(newest()))
        latest().triggerOnError(11)
        settle()
        assertTrue(onDevice(newest()))
        latest().triggerOnError(5)
        settle()
        assertTrue(!onDevice(newest()))
        assertEquals(SpeechIn.OnDevice.Installed, input.onDeviceStatus(Locale.KOREA))
        latest().triggerOnResults(results("기본 인식기"))
        assertEquals(listOf(session to SpeechIn.Event.Final("기본 인식기")), events)
    }

    @Test
    fun `a recognizer stopped before it was ready is not opened again`() {
        val input = SpeechIn(context)
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        val first = newest()
        input.stop()
        shadowOf(first).triggerOnError(5)
        settle()
        assertSame(first, newest())
        assertEquals(listOf(session to SpeechIn.Event.Failed(VoiceFault.Recognizer, 5)), events)
    }

    @Test
    fun `a session ends once, even when the recognizer follows its result with an error`() {
        val input = SpeechIn(context)
        val session = input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        latest().triggerOnResults(results(""))
        latest().triggerOnError(7)
        assertEquals(listOf(session to SpeechIn.Event.Silence), events)
    }

    private fun installRecognizer(pkg: String, label: String): ComponentName {
        val packages = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply { packageName = pkg }
        packages.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = app })
        val component = ComponentName(pkg, "$pkg.RecognizerService")
        packages.addOrUpdateService(
            ServiceInfo().apply {
                packageName = pkg
                name = component.className
                nonLocalizedLabel = label
                applicationInfo = app
                exported = true
            },
        )
        packages.addIntentFilterForService(component, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        return component
    }

    private fun serviceOf(recognizer: SpeechRecognizer): ComponentName? =
        ReflectionHelpers.getField(recognizer, "mServiceComponent")

    @Test
    fun `the recognizers installed on the device are listed, by name`() {
        val samsung = installRecognizer("com.samsung.recognizer", "Samsung 음성 인식")
        val google = installRecognizer("com.google.recognizer", "Google 음성 인식")
        assertEquals(
            listOf(google.flattenToString() to "Google 음성 인식", samsung.flattenToString() to "Samsung 음성 인식"),
            SpeechIn(context).recognizers(),
        )
    }

    @Test
    fun `a chosen recognizer is used, and asked to stay offline`() {
        val chosen = installRecognizer("com.samsung.recognizer", "Samsung 음성 인식")
        val input = SpeechIn(context)
        input.service = chosen.flattenToString()
        input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        assertEquals(chosen, serviceOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
        assertTrue(latest().lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    @Test
    fun `a chosen recognizer since removed leaves it to the device, still offline`() {
        val input = SpeechIn(context)
        input.service = "com.gone.recognizer/com.gone.recognizer.RecognizerService"
        input.start(Locale.KOREA) { id, event -> events += id to event }
        idle()
        assertEquals(null, serviceOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
        assertTrue(latest().lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    @Test(expected = IllegalStateException::class)
    fun `the recognizer refuses to start off the main thread`() {
        val input = SpeechIn(context)
        var thrown: Throwable? = null
        val worker = Thread { thrown = runCatching { input.start(Locale.KOREA) { _, _ -> } }.exceptionOrNull() }
        worker.start()
        worker.join()
        throw thrown ?: AssertionError("started off the main thread")
    }
}
