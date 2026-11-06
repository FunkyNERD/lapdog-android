package io.hazaar.lapdog.state

/**
 * Fixing a heat's laps afterwards, as timing systems let you:
 * - [split]: a missed pass made one lap of two (or three): share its time equally.
 * - [mergeWithPrevious] / [mergeWithNext]: a false read split one lap in two: drop
 *   the pass between them and add the times.
 * The total time never changes. Later laps (and the ignored passes after them) are
 * renumbered; the timer's own laps are kept on the first edit for [restore].
 * Indexes are into [HeatRecord.laps].
 */
object LapEdits {
    fun canMergePrevious(h: HeatRecord, i: Int) = i in 1..h.laps.lastIndex
    fun canMergeNext(h: HeatRecord, i: Int) = i in 0 until h.laps.lastIndex

    fun split(h: HeatRecord, i: Int, parts: Int = 2): HeatRecord {
        require(i in h.laps.indices && parts >= 2)
        val lap = h.laps[i]
        val each = lap.ms / parts
        val pieces = (0 until parts).map { k ->
            LapEntry(lap.lap + k, if (k == parts - 1) lap.ms - each * (parts - 1) else each, split = true)
        }
        val shift = parts - 1
        val laps = h.laps.take(i) + pieces + h.laps.drop(i + 1).map { it.copy(lap = it.lap + shift) }
        val passes = h.passes.map { if (it.afterLap >= lap.lap) it.copy(afterLap = it.afterLap + shift) else it }
        return h.edit(laps, passes)
    }

    fun mergeWithPrevious(h: HeatRecord, i: Int): HeatRecord {
        require(canMergePrevious(h, i))
        return merge(h, i - 1)
    }

    fun mergeWithNext(h: HeatRecord, i: Int): HeatRecord {
        require(canMergeNext(h, i))
        return merge(h, i)
    }

    /** Laps [i] and [i] + 1 become one, numbered as the first. */
    private fun merge(h: HeatRecord, i: Int): HeatRecord {
        val a = h.laps[i]
        val b = h.laps[i + 1]
        // Part estimate, whole estimate (Restore undoes a split exactly).
        val one = LapEntry(a.lap, a.ms + b.ms, split = a.split || b.split)
        val shift = b.lap - a.lap
        val laps = h.laps.take(i) + one + h.laps.drop(i + 2).map { it.copy(lap = it.lap - shift) }
        // Passes between the two now fall inside the merged lap: keep them after it.
        val passes = h.passes.map { if (it.afterLap >= b.lap) it.copy(afterLap = it.afterLap - shift) else it }
        return h.edit(laps, passes)
    }

    fun restore(h: HeatRecord): HeatRecord =
        if (!h.edited) h else h.copy(laps = h.originalLaps!!, passes = h.originalPasses ?: h.passes, originalLaps = null, originalPasses = null)

    private fun HeatRecord.edit(laps: List<LapEntry>, passes: List<IgnoredPass>) = copy(
        laps = laps,
        passes = passes,
        originalLaps = originalLaps ?: this.laps,
        originalPasses = originalPasses ?: this.passes,
    )
}
