package io.hazaar.lapdog.service

import io.hazaar.lapdog.protocol.LapFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory session state shared between LapService and the UI. */
object LapRepository {
    data class State(
        val running: Boolean = false,
        val armed: Boolean = false,
        val laps: List<Long> = emptyList(), // lap times, ms, newest last
        val lastHeard: String? = null,      // transport that delivered the last frame
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setRunning(running: Boolean) = _state.update { it.copy(running = running) }

    fun clear() = _state.update { it.copy(laps = emptyList()) }

    fun onFrame(frame: LapFrame, transport: String) = _state.update {
        when (frame.type) {
            LapFrame.Type.LAP -> it.copy(laps = it.laps + frame.value, lastHeard = transport)
            LapFrame.Type.ARMED -> it.copy(armed = true, laps = emptyList(), lastHeard = transport)
            LapFrame.Type.DISARMED -> it.copy(armed = false, lastHeard = transport)
            LapFrame.Type.HEARTBEAT -> it.copy(armed = true, lastHeard = transport)
        }
    }
}
