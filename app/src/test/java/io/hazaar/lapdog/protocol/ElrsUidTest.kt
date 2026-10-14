package io.hazaar.lapdog.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ElrsUidTest {
    @Test
    fun matchesVrxcMspVector() {
        // vrxc-msp test/test_uid: "lapdog" -> 54:0f:ea:11:90:75 (multicast bit cleared)
        assertEquals("54:0f:ea:11:90:75", ElrsUid.format(ElrsUid.fromBindPhrase("lapdog")))
    }
}
