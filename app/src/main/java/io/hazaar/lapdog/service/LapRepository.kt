package io.hazaar.lapdog.service

import android.os.SystemClock
import io.hazaar.lapdog.protocol.LapFrame
import io.hazaar.lapdog.protocol.TimerLine
import io.hazaar.lapdog.state.TimerModel
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.state.Via
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared between LapService (transports in) and the UI (state out, commands to the
 * timer). Commands only go over USB: BLE is one-way advert bursts.
 */
object LapRepository {
    private val model = TimerModel()
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Live signal (dB above baseline), newest last, while "rssi on". */
    private val _rssi = MutableStateFlow<List<Float>>(emptyList())
    val rssi: StateFlow<List<Float>> = _rssi

    /** Lines for UsbTransport to write. */
    internal val outgoing = Channel<String>(Channel.UNLIMITED)
    private val replies = MutableSharedFlow<TimerLine.Reply>(extraBufferCapacity = 16)
    private val commandLock = Mutex()

    private const val RSSI_SAMPLES = 200 // ~10 s at the timer's 20 Hz stream
    private const val REPLY_TIMEOUT_MS = 1500L

    fun now() = SystemClock.elapsedRealtime()

    fun setRunning(running: Boolean) { _running.value = running }

    /** The app is on screen. Off screen and disarmed, LapService stops. */
    private val _appVisible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _appVisible

    fun setAppVisible(visible: Boolean) { _appVisible.value = visible }

    @Synchronized
    fun onUsb(connected: Boolean) {
        model.onUsb(connected)
        publish()
        if (!connected) {
            while (outgoing.tryReceive().isSuccess) Unit // drop anything queued for the old port
            _rssi.value = emptyList()
        }
    }

    @Synchronized
    fun onFrame(frame: LapFrame, via: Via) {
        if (model.onFrame(frame, via, now())) publish()
    }

    /** New state out, finished heats saved. */
    private fun publish() {
        _state.value = model.state
        model.takeFinished().forEach(HeatStore::save)
    }

    fun onLine(line: TimerLine) {
        when (line) {
            is TimerLine.Frame -> onFrame(line.frame, Via.USB)
            is TimerLine.Setting -> synchronized(this) {
                model.onSetting(line.key, line.value)
                publish()
            }
            is TimerLine.Rssi -> _rssi.value = (_rssi.value + line.db).takeLast(RSSI_SAMPLES)
            is TimerLine.Reply -> replies.tryEmit(line)
        }
    }

    sealed interface Result {
        data object Ok : Result
        data class Failed(val command: String, val reason: String) : Result
    }

    /** Sends commands one at a time, waiting for each OK; stops at the first failure. */
    suspend fun send(vararg commands: String): Result = send(commands.toList())

    suspend fun send(commands: List<String>): Result = commandLock.withLock {
        for (c in commands) {
            if (!_state.value.usbConnected) return Result.Failed(c, "timer not connected over USB")
            outgoing.send(c)
            val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) { replies.first() }
                ?: return Result.Failed(c, "no reply")
            if (!reply.ok) return Result.Failed(c, reply.message.ifEmpty { "rejected" })
        }
        Result.Ok
    }

    /** Re-reads the timer's settings ("get"). */
    suspend fun refresh() = send("get")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Sends without waiting, e.g. from a dispose callback; still serialised with [send]. */
    fun fire(vararg commands: String) {
        scope.launch { send(commands.toList()) }
    }

    /** Like [send], from a non-suspending caller; [done] gets the result. */
    fun launch(commands: List<String>, done: (Result) -> Unit = {}) {
        scope.launch { done(send(commands)) }
    }
}
