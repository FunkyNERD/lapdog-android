package io.hazaar.lapdog.transport

/**
 * A link to the timer. Each runs until cancelled and feeds LapRepository; every
 * transport delivers the same LapDog frames to the same model.
 */
interface Transport {
    val name: String
    suspend fun run()
}
