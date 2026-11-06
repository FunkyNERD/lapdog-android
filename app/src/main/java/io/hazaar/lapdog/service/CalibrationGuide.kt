package io.hazaar.lapdog.service

import io.hazaar.lapdog.protocol.Calibration.Event
import io.hazaar.lapdog.protocol.CalibrationScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Talks a pilot through calibrating the timer, so it can be done with goggles on and
 * the phone on the ground: floor (quad off), a countdown to take off, then the
 * timer's hands-free "cal auto" rounds, saying each pass and each change of gain.
 * The timer does the work; this sends the commands and says what's happening.
 * Over USB only (the app's only way to send commands).
 */
object CalibrationGuide {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var floorDone: CompletableDeferred<Event>? = null
    private var autoDone: CompletableDeferred<Event>? = null

    private val _active = MutableStateFlow(false)
    /** A guided calibration is running (LapService stays up for it). */
    val active: StateFlow<Boolean> = _active

    private val _line = MutableStateFlow<String?>(null)
    /** The last thing said, for the screen. */
    val line: StateFlow<String?> = _line

    private const val FLOOR_TIMEOUT_MS = 60_000L
    private const val AUTO_TIMEOUT_MS = 8 * 60_000L // 3 rounds of 90 s, with room

    @Synchronized
    fun start() {
        if (_active.value) return
        val state = LapRepository.state.value
        when {
            !state.usbConnected -> return say(CalibrationScript.notConnected, true)
            state.armed -> return say("Disarm the timer first.", true)
        }
        _active.value = true
        job = scope.launch {
            try {
                run()
            } finally {
                floorDone = null
                autoDone = null
                _active.value = false
            }
        }
    }

    private suspend fun run() {
        say(CalibrationScript.start, true)
        floorDone = CompletableDeferred()
        if (!command("cal floor")) return
        val floor = withTimeoutOrNull(FLOOR_TIMEOUT_MS) { floorDone?.await() }
        if (floor !is Event.FloorDone) return say(if (floor == null) "The floor measurement didn't finish." else lineOf(floor), true)

        say(CalibrationScript.takeOff, true)
        delay((CalibrationScript.COUNTDOWN_S - 10) * 1000L)
        say(CalibrationScript.tenSeconds, true)
        delay(10_000)

        autoDone = CompletableDeferred()
        if (!command("cal auto")) return
        if (withTimeoutOrNull(AUTO_TIMEOUT_MS) { autoDone?.await() } == null) {
            command("cal stop")
            say("Calibration timed out. You can land.", true)
        }
    }

    /** Sends [c]; on refusal says so and returns false. */
    private suspend fun command(c: String): Boolean = when (val r = LapRepository.send(c)) {
        LapRepository.Result.Ok -> true
        is LapRepository.Result.Failed -> {
            say("The timer refused to calibrate: ${r.reason}.", true)
            false
        }
    }

    @Synchronized
    fun cancel() {
        if (!_active.value) return
        job?.cancel()
        scope.launch { LapRepository.send("cal stop") }
        say(CalibrationScript.cancelled, true)
    }

    /** From LapRepository, for every calibration event the timer reports. */
    fun onEvent(e: Event) {
        if (!_active.value) return // a calibration run over the console: stay quiet
        lineOf(e).takeIf { it.isNotEmpty() }?.let { say(it, interrupt = e !is Event.Pass) }
        when (e) {
            is Event.FloorDone, is Event.Failed -> floorDone?.complete(e)
            else -> Unit
        }
        when (e) {
            is Event.AutoDone, is Event.Aborted, is Event.Failed -> autoDone?.complete(e)
            else -> Unit
        }
    }

    private fun lineOf(e: Event) = CalibrationScript.line(e) ?: ""

    private fun say(text: String, interrupt: Boolean = false) {
        _line.value = text
        Voice.say(text, interrupt)
    }
}
