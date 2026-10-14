package io.hazaar.lapdog.protocol

/** 5.8 GHz FPV bands - the same table as lapdog-firmware's include/channels.h. */
object Channels {
    data class Band(val id: Char, val name: String, val mhz: List<Int>)

    val bands = listOf(
        Band('R', "Raceband", listOf(5658, 5695, 5732, 5769, 5806, 5843, 5880, 5917)),
        Band('F', "Fatshark", listOf(5740, 5760, 5780, 5800, 5820, 5840, 5860, 5880)),
        Band('E', "Boscam E", listOf(5705, 5685, 5665, 5645, 5885, 5905, 5925, 5945)),
        Band('A', "Boscam A", listOf(5865, 5845, 5825, 5805, 5785, 5765, 5745, 5725)),
        Band('B', "Boscam B", listOf(5733, 5752, 5771, 5790, 5809, 5828, 5847, 5866)),
        Band('L', "Lowband", listOf(5362, 5399, 5436, 5473, 5510, 5547, 5584, 5621)),
    )

    /** "R4" -> 5769, null if unknown. */
    fun freqOf(label: String): Int? {
        if (label.length != 2) return null
        val band = bands.firstOrNull { it.id == label[0].uppercaseChar() } ?: return null
        val ch = label[1].digitToIntOrNull()?.takeIf { it in 1..8 } ?: return null
        return band.mhz[ch - 1]
    }

    /** 5769 -> "R4" (first band in table order), null for a non-standard frequency. */
    fun labelOf(mhz: Int): String? {
        for (b in bands) {
            val i = b.mhz.indexOf(mhz)
            if (i >= 0) return "${b.id}${i + 1}"
        }
        return null
    }
}
