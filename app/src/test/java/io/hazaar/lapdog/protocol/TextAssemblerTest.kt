package io.hazaar.lapdog.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextAssemblerTest {
    private fun part(seq: Int, part: Int, parts: Int, text: String, slot: Int = LapFrame.SLOT_EVENT) =
        LapFrame.Text(seq, 0, slot, part, parts, text)

    @Test
    fun singlePartIsImmediate() {
        assertEquals("GO!", TextAssembler().add(part(1, 0, 1, "GO!", slot = 5)))
    }

    @Test
    fun joinsPartsInAnyOrder() {
        val a = TextAssembler()
        assertNull(a.add(part(12, 2, 3, "d 7")))
        assertNull(a.add(part(10, 0, 3, "Thursday Backyard N")))
        assertEquals("Thursday Backyard Night Round 7", a.add(part(11, 1, 3, "ight Roun")))
    }

    @Test
    fun newerTextReplacesAnIncompleteOne() {
        val a = TextAssembler()
        assertNull(a.add(part(10, 0, 2, "Old ")))
        assertNull(a.add(part(20, 0, 2, "New ")))
        assertNull(a.add(part(11, 1, 2, "event"))) // belongs to the dropped text
        assertEquals("New event", a.add(part(21, 1, 2, "event")))
    }

    @Test
    fun seqWrapsAround() {
        val a = TextAssembler()
        assertNull(a.add(part(255, 0, 2, "ab")))
        assertEquals("abcd", a.add(part(0, 1, 2, "cd")))
    }
}
