package io.hazaar.lapdog.transport

import io.hazaar.lapdog.protocol.LapFrame
import kotlinx.coroutines.flow.Flow

/** A source of LapDog frames. Every transport feeds the same parser and the same UI. */
interface Transport {
    val name: String
    fun frames(): Flow<LapFrame>
}
