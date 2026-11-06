package io.hazaar.lapdog.flash

import android.content.Context
import android.os.SystemClock
import io.hazaar.lapdog.service.LapRepository
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
 * For now (the spike) images come from the app's files folder, put there with
 * `adb push <board>-firmware.bin /sdcard/Android/data/io.hazaar.lapdog/files/`.
 */
object TimerFlasher {
    data class State(
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float? = null,
        val log: List<String> = emptyList(),
        val ok: Boolean? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Flash size to tell the ROM: every board has at least 4 MB, and the app sits well inside it. */
    private const val FLASH_SIZE = 4 * 1024 * 1024
    private const val BACK_TIMEOUT_MS = 30_000L

    fun images(context: Context): List<File> =
        context.getExternalFilesDir(null)?.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedBy { it.name }.orEmpty()

    fun start(image: File) {
        if (_state.value.running) return
        val s = LapRepository.state.value
        if (s.armed) return fail("Disarm the timer first.")
        _state.value = State(running = true, stage = "Starting")
        scope.launch {
            try {
                run(image)
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun run(image: File) {
        val bytes = image.readBytes()
        val board = LapRepository.state.value.settings?.board.orEmpty()
        val from = LapRepository.state.value.settings?.firmware.orEmpty()
        log("${image.name}: ${bytes.size / 1024} KB, MD5 ${EspLoader.md5Hex(bytes).take(8)}…")
        log("Timer: ${board.ifEmpty { "board unknown" }}, firmware ${from.ifEmpty { "?" }}")
        if (board.isNotEmpty() && !image.name.startsWith(board)) return fail("That image isn't for a $board.")
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
        log("Back (${at()}): firmware ${back.settings?.firmware}")
        _state.update { it.copy(running = false, stage = "Done", progress = null, ok = true) }
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
