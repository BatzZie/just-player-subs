package autosubs.core

/**
 * Piecewise sync: different offsets for different parts of a video, for subtitles made for another cut
 * (e.g. a "previously on" recap or an extra scene in the WEB version but not in the BluRay).
 *
 * Works on a complete reference (MKV index): best offset per 3-minute window, windows with the same offset
 * merged into segments, and each boundary placed where the reference lines switch from one offset to the next.
 */
object SegmentSync {

    /** From video time [fromMs] on, display = subtitle time * scale + [offsetMs]. */
    class Segment(@JvmField val fromMs: Long, @JvmField val offsetMs: Long) {
        override fun toString() = "${fromMs / 1000}s:${offsetMs}ms"
    }

    class Result(@JvmField val segments: List<Segment>, @JvmField val matched: Int, @JvmField val references: Int) {
        fun fit(): Double = if (references == 0) 0.0 else matched.toDouble() / references

        /** Offset for a video position. */
        fun offsetAt(positionMs: Long): Long {
            var o = segments[0].offsetMs
            for (s in segments) if (s.fromMs <= positionMs) o = s.offsetMs else break
            return o
        }

        override fun toString() = "segments=$segments fit=${"%.2f".format(fit())}"
    }

    private const val WINDOW_MS = 180_000L
    private const val STEP_MS = 60_000L
    private const val MAX_OFFSET_MS = 120_000L
    private const val OFFSET_STEP_MS = 100L
    private const val MATCH_MS = 350L
    private const val SAME_MS = 400L

    /**
     * @return segments, or null if a single offset explains the reference just as well (or nothing fits)
     */
    @JvmStatic
    fun estimate(refStartsMs: List<Long>, subStartsMs: LongArray, scale: Double): Result? {
        val refs = refStartsMs.sorted()
        if (refs.size < 40 || subStartsMs.isEmpty()) return null
        val subs = DoubleArray(subStartsMs.size) { subStartsMs[it] * scale }.also { it.sort() }

        // 1. Best offset per window
        class Win(val center: Long, val offset: Long)
        val windows = ArrayList<Win>()
        var start = refs.first()
        while (start < refs.last()) {
            val inWin = refs.filter { it >= start && it < start + WINDOW_MS }
            if (inWin.size >= 8) {
                var best = 0L
                var bestCount = -1
                var second = 0
                val counts = IntArray((2 * MAX_OFFSET_MS / OFFSET_STEP_MS + 1).toInt())
                for (k in counts.indices) {
                    val d = k * OFFSET_STEP_MS - MAX_OFFSET_MS
                    counts[k] = inWin.count { matches(subs, it - d) }
                    if (counts[k] > bestCount) {
                        bestCount = counts[k]
                        best = d
                    }
                }
                for (k in counts.indices) {
                    val d = k * OFFSET_STEP_MS - MAX_OFFSET_MS
                    if (Math.abs(d - best) > 1500) second = maxOf(second, counts[k])
                }
                if (bestCount >= maxOf(5, (inWin.size * 0.4).toInt()) && bestCount >= second * 1.6) {
                    windows.add(Win(start + WINDOW_MS / 2, refine(inWin, subs, best)))
                }
            }
            start += STEP_MS
        }
        CoreLog.i("SegmentSync: ${windows.size} windows " + windows.joinToString(" ") { "${it.center / 60_000}m:${it.offset}" })
        if (windows.size < 2) return null

        // 2. Merge neighbouring windows with the same offset (a lone deviating window is ignored)
        class Group(val offsets: MutableList<Long>, var firstCenter: Long, var lastCenter: Long)
        val groups = ArrayList<Group>()
        for (w in windows) {
            val g = groups.lastOrNull()
            if (g != null && Math.abs(median(g.offsets) - w.offset) <= SAME_MS) {
                g.offsets.add(w.offset)
                g.lastCenter = w.center
            } else {
                groups.add(Group(mutableListOf(w.offset), w.center, w.center))
            }
        }
        val solid = groups.filter { it.offsets.size >= 2 }.toMutableList()
        // Merge again after dropping lone windows
        val merged = ArrayList<Group>()
        for (g in solid) {
            val last = merged.lastOrNull()
            if (last != null && Math.abs(median(last.offsets) - median(g.offsets)) <= SAME_MS) {
                last.offsets.addAll(g.offsets)
                last.lastCenter = g.lastCenter
            } else merged.add(g)
        }
        if (merged.size < 2) return null

        // 3. Boundaries: where the reference lines switch from one offset to the next
        val segments = ArrayList<Segment>()
        segments.add(Segment(Long.MIN_VALUE, median(merged[0].offsets)))
        for (i in 1 until merged.size) {
            val a = median(merged[i - 1].offsets)
            val b = median(merged[i].offsets)
            val from = merged[i - 1].lastCenter - WINDOW_MS / 2
            val to = merged[i].firstCenter + WINDOW_MS / 2
            val zone = refs.filter { it in from..to }
            var bestSplit = (merged[i - 1].lastCenter + merged[i].firstCenter) / 2
            var bestScore = -1
            // Try a split before each reference line in the zone
            for (j in 0..zone.size) {
                val score = zone.subList(0, j).count { matches(subs, it - a) } + zone.subList(j, zone.size).count { matches(subs, it - b) }
                if (score > bestScore) {
                    bestScore = score
                    bestSplit = if (j < zone.size) zone[j] - 500 else to
                }
            }
            segments.add(Segment(bestSplit, b))
        }

        // 4. Only worth it if it clearly beats one offset for the whole video
        val pieceMatched = refs.count { r -> matches(subs, r - offsetAt(segments, r)) }
        var single = 0
        var k = -MAX_OFFSET_MS
        while (k <= MAX_OFFSET_MS) {
            single = maxOf(single, refs.count { matches(subs, it - k) })
            k += OFFSET_STEP_MS
        }
        CoreLog.i("SegmentSync: ${segments.size} segments $segments matched=$pieceMatched single=$single refs=${refs.size}")
        if (pieceMatched < single + maxOf(10, refs.size / 10)) return null
        return Result(segments, pieceMatched, refs.size)
    }

    private fun offsetAt(segments: List<Segment>, t: Long): Long {
        var o = segments[0].offsetMs
        for (s in segments) if (s.fromMs <= t) o = s.offsetMs else break
        return o
    }

    private fun matches(subs: DoubleArray, t: Double): Boolean {
        var lo = 0
        var hi = subs.size
        val v = t - MATCH_MS
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (subs[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo < subs.size && subs[lo] <= t + MATCH_MS
    }

    private fun matches(subs: DoubleArray, t: Long) = matches(subs, t.toDouble())

    /** Median of the nearest differences around a coarse offset. */
    private fun refine(refs: List<Long>, subs: DoubleArray, coarse: Long): Long {
        val diffs = ArrayList<Double>()
        for (r in refs) {
            var best = Double.NaN
            for (s in subs) {
                val d = r - s
                if (Math.abs(d - coarse) <= MATCH_MS && (best.isNaN() || Math.abs(d - coarse) < Math.abs(best - coarse))) best = d
            }
            if (!best.isNaN()) diffs.add(best)
        }
        if (diffs.isEmpty()) return coarse
        diffs.sort()
        return Math.round(diffs[diffs.size / 2])
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
}
