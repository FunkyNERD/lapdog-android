package io.hazaar.lapdog.sync

import kotlinx.serialization.Serializable

/** The website's log numbering, from `/pair` and `/device`. */
@Serializable
data class Counters(val race: Long = 0, val epoch: Long = 0)

/**
 * What the phone remembers about website sync, saved as files/sync.json. Heats are
 * keyed by [io.hazaar.lapdog.state.HeatRecord.id].
 */
@Serializable
data class SyncState(
    /** The device token from pairing; null = not paired. */
    val token: String? = null,
    val deviceName: String = "",
    val pilotName: String = "",
    /** Upload each heat as soon as it's finished (when there's a network). */
    val auto: Boolean = true,
    /** The website's numbering epoch the numbers below were given out under. */
    val epoch: Long? = null,
    /** The next race number this phone will give a heat. */
    val nextRace: Long = 0,
    /** Heats given a number but not uploaded yet: a retry reuses it. */
    val assigned: Map<Long, Long> = emptyMap(),
    /** Heats the website has (or refused for good), and the number each went up as. */
    val uploaded: Map<Long, Long> = emptyMap(),
    /** Wall clock (ms) of the last sync that finished without an error. */
    val lastSyncAt: Long? = null,
) {
    val paired get() = token != null

    /**
     * Gives every heat in [pending] (oldest first) a race number, carrying on from the
     * website's [server] numbering the way LapDog LCD does (device-api.md, "counters"):
     * the same epoch keeps whichever is higher; a new epoch (a reset on the website, or
     * the first sync) starts from the website's and renumbers what hasn't gone up yet.
     * Website race numbers are shared by all of a pilot's devices, so the phone can't use
     * its own ids - the website moves its counter past every number it's sent.
     */
    fun numbered(server: Counters, pending: List<Long>): SyncState {
        val sameEpoch = epoch == server.epoch
        var next = if (sameEpoch) maxOf(nextRace, server.race) else server.race
        val kept = if (sameEpoch) assigned.filterKeys { it in pending } else emptyMap()
        val ids = kept.toMutableMap()
        for (id in pending) if (id !in ids) ids[id] = next++
        return copy(epoch = server.epoch, nextRace = next, assigned = ids)
    }

    fun markUploaded(heatId: Long): SyncState {
        val n = assigned[heatId] ?: return this
        return copy(assigned = assigned - heatId, uploaded = uploaded + (heatId to n))
    }

    /**
     * Forget the pairing and what went up. Pairing again (maybe with another pilot's
     * account) sends everything again; the website drops what it already has.
     */
    fun unpaired() = SyncState(auto = auto)
}
