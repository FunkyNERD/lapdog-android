package io.hazaar.lapdog.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewer("0.3.1", "0.3.0"))
        assertTrue(isNewer("0.10.0", "0.9.9"))
        assertTrue(isNewer("v1.0.0", "0.9.0"))
        assertFalse(isNewer("0.3.0", "0.3.0"))
        assertFalse(isNewer("0.2.9", "0.3.0"))
    }

    @Test
    fun ignoresSuffixesAndShortVersions() {
        assertFalse(isNewer("0.3.0", "0.3.0-beta.1")) // same release
        assertTrue(isNewer("0.4", "0.3.9"))
        assertFalse(isNewer("garbage", "0.1.0"))
    }
}
