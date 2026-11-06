package io.hazaar.lapdog.flash

import android.os.SystemClock
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.update.FirmwareRelease
import io.hazaar.lapdog.update.TimerFirmware
import io.hazaar.lapdog.transport.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Updates the connected timer's firmware over USB (lapdog-android#3): takes the port
 * from the lap transport, writes the app image with [EspLoader], resets the timer and
 * waits for it to come back on the new version. Never while armed.
 *
 * Images come from the website (TimerFirmware.download, SHA-256 checked).
 */
object TimerFlasher {
    data class State(
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float? = null,
        val log: List<String> = emptyList(),
        val ok: Boolean? = null,
        /** The version being installed. */
        val target: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Flash size to tell the ROM: every board has at least 4 MB, and the app sits well inside it. */
    private const val FLASH_SIZE = 4 * 1024 * 1024
    private const val BACK_TIMEOUT_MS = 30_000L

    /**
     * Installs [version] for [board] on the connected timer: downloads its app image from the
     * website (or uses the copy kept from last time), then writes it. [board] is the timer's
     * own (S:board) or, for firmware too old to report it, the one the pilot picked.
     */
    fun install(board: String, release: FirmwareRelease) {
        if (_state.value.running) return
        val s = LapRepository.state.value
        if (s.armed) return fail("Disarm the timer first.")
        val reported = s.settings?.board.orEmpty()
        if (reported.isNotEmpty() && reported != board) return fail("That's a $board image; this timer is a $reported.")
        _state.value = State(running = true, stage = "Downloading v${release.version}", target = release.version)
        scope.launch {
            try {
                val file = TimerFirmware.download(board, release) { p -> _state.update { it.copy(progress = p) } }
                log("Downloaded v${release.version} for $board (SHA-256 checked)")
                run(file, board)
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun run(image: File, board: String) {
        val bytes = image.readBytes()
        val from = LapRepository.state.value.settings?.firmware.orEmpty()
        log("${bytes.size / 1024} KB, MD5 ${EspLoader.md5Hex(bytes).take(8)}…")
        log("Timer: $board, firmware ${from.ifEmpty { "?" }}")
        if (bytes.size < 64 * 1024 || bytes[0] != 0xE9.toByte()) return fail("That doesn't look like an ESP32 app image.")

        val t0 = SystemClock.elapsedRealtime()
        fun at() = "%.1f s".format((SystemClock.elapsedRealtime() - t0) / 1000.0)
        val r = UsbTransport.exclusive { port ->
            val loader = EspLoader(UsbLink(port))
            stage("Restarting the timer into its bootloader")
            loader.enterDownloadMode()
            stage("Talking to the bootloader")
            loader.sync()
            log("Bootloader answered (${at()})")
            val chip = loader.chipId()
            log("Chip id $chip")
            val want = if (board.startsWith("c3")) EspProtocol.CHIP_ID_ESP32C3 else EspProtocol.CHIP_ID_ESP32C5
            if (chip != null && chip != want) throw FlashException("the timer's chip ($chip) doesn't match the image ($want)")
            loader.attachFlash(FLASH_SIZE)
            var writeFrom = 0L
            loader.flash(bytes) { p ->
                when (p.stage) {
                    EspLoader.Stage.ERASE -> stage("Erasing")
                    EspLoader.Stage.WRITE -> {
                        if (writeFrom == 0L) { writeFrom = SystemClock.elapsedRealtime(); log("Erased (${at()})") }
                        _state.update { it.copy(stage = "Writing", progress = p.done / p.total.toFloat()) }
                    }
                    EspLoader.Stage.VERIFY -> { log("Written (${at()})"); stage("Checking") }
                    else -> Unit
                }
            }
            log("Verified (${at()})")
            stage("Restarting the timer")
            loader.hardReset()
        }
        r.exceptionOrNull()?.let { return fail(it.message ?: it.javaClass.simpleName) }

        // The transport reconnects by itself and reads "get".
        stage("Waiting for the timer")
        val back = withTimeoutOrNull(BACK_TIMEOUT_MS) {
            LapRepository.state.first { !it.usbConnected || it.settings == null }
            LapRepository.state.first { it.usbConnected && it.settings?.firmware?.isNotEmpty() == true }
        }
        if (back == null) return fail("Written and checked, but the timer didn't come back over USB in ${BACK_TIMEOUT_MS / 1000} s. Unplug it and plug it in again.")
        delay(500)
        val now = back.settings?.firmware.orEmpty()
        log("Back (${at()}): firmware $now")
        val target = _state.value.target
        if (target != null && now != target) return fail("The timer came back on $now, not $target.")
        _state.update { it.copy(running = false, stage = "Updated to v$now", progress = null, ok = true) }
    }

    private fun stage(s: String) {
        _state.update { it.copy(stage = s, progress = null) }
        log(s)
    }

    private fun log(line: String) = _state.update { it.copy(log = it.log + line) }

    private fun fail(why: String) {
        log("Failed: $why")
        _state.update { it.copy(running = false, stage = "Failed", progress = null, ok = false) }
    }
}
