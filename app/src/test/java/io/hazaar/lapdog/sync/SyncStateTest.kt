package io.hazaar.lapdog.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncStateTest {
    @Test
    fun firstSyncTakesTheWebsitesNumbers() {
        val s = SyncState(token = "t").numbered(Counters(race = 18, epoch = 0), listOf(100, 200))
        assertEquals(mapOf(100L to 18L, 200L to 19L), s.assigned)
        assertEquals(20, s.nextRace)
        assertEquals(0L, s.epoch)
    }

    @Test
    fun sameEpochKeepsWhicheverIsHigher() {
        val ahead = SyncState(token = "t", epoch = 2, nextRace = 30).numbered(Counters(25, 2), listOf(1))
        assertEquals(30L, ahead.assigned[1])
        // Another device (a LapDog LCD) used numbers since: carry on after them.
        val behind = SyncState(token = "t", epoch = 2, nextRace = 30).numbered(Counters(41, 2), listOf(1))
        assertEquals(41L, behind.assigned[1])
    }

    @Test
    fun aRetryReusesItsNumber() {
        val s = SyncState(token = "t", epoch = 0, nextRace = 5, assigned = mapOf(1L to 3L))
            .numbered(Counters(4, 0), listOf(1, 2))
        assertEquals(mapOf(1L to 3L, 2L to 5L), s.assigned)
    }

    @Test
    fun aResetOnTheWebsiteRenumbersWhatHasntGoneUp() {
        val s = SyncState(token = "t", epoch = 0, nextRace = 50, assigned = mapOf(1L to 48L), uploaded = mapOf(9L to 47L))
            .numbered(Counters(0, 1), listOf(1))
        assertEquals(mapOf(1L to 0L), s.assigned)
        assertEquals(mapOf(9L to 47L), s.uploaded)
        assertEquals(1L, s.epoch)
    }

    @Test
    fun uploadedMovesOutOfAssigned() {
        val s = SyncState(token = "t", assigned = mapOf(1L to 3L, 2L to 4L)).markUploaded(1)
        assertEquals(mapOf(2L to 4L), s.assigned)
        assertEquals(mapOf(1L to 3L), s.uploaded)
    }

    @Test
    fun unpairingForgetsAllButTheAutoSwitch() {
        val s = SyncState(token = "t", deviceName = "d", auto = false, epoch = 1, nextRace = 9, uploaded = mapOf(1L to 2L)).unpaired()
        assertEquals(SyncState(auto = false), s)
        assertFalse(s.paired)
    }

    @Test
    fun roundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val s = SyncState(token = "elrs_x", epoch = 0, nextRace = 3, assigned = mapOf(1_760_000_000_000L to 2L))
        assertEquals(s, json.decodeFromString<SyncState>(json.encodeToString(SyncState.serializer(), s)))
    }
}
