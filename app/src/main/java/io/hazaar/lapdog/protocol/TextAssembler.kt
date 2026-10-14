package io.hazaar.lapdog.protocol

/**
 * Rebuilds chunked TEXT frames. A text goes out as `parts` frames with
 * consecutive seqs, so all parts of one text share `seq - part`. A part from a
 * newer text replaces whatever was pending for that slot; a late part of an older
 * one is dropped (seqs compare modulo 256).
 */
class TextAssembler {
    private class Pending(val firstSeq: Int, val parts: Array<String?>)

    private val pending = HashMap<Int, Pending>()

    /** Returns the slot's complete text once its last missing part arrives, else null. */
    fun add(f: LapFrame.Text): String? {
        if (f.parts == 1) {
            pending.remove(f.slot)
            return f.text
        }
        val firstSeq = (f.seq - f.part) and 0xFF
        var p = pending[f.slot]
        if (p != null && p.firstSeq != firstSeq && ((firstSeq - p.firstSeq) and 0xFF) >= 128) return null // older text
        if (p == null || p.firstSeq != firstSeq || p.parts.size != f.parts) {
            p = Pending(firstSeq, arrayOfNulls(f.parts))
            pending[f.slot] = p
        }
        p.parts[f.part] = f.text
        if (p.parts.any { it == null }) return null
        pending.remove(f.slot)
        return p.parts.joinToString("")
    }
}
